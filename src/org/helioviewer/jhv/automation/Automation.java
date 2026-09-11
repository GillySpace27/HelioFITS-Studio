package org.helioviewer.jhv.automation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.HdrGain;
import org.helioviewer.jhv.layers.AbstractLayer;
import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.opengl.GLImage;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Parameter animation: the tracks, the parameter registry, and the once-per-frame applier.
 *
 * <p>Every knob in the application is a constant otherwise. A track makes one of them a function
 * of data time, evaluated at the playhead at the top of every frame, so the parameter moves as the
 * movie plays and the recorded file contains exactly what the screen showed.
 *
 * <h2>Where this is applied, and why there</h2>
 * {@link org.helioviewer.jhv.opengl.GLRenderer#display} calls {@link #apply} after
 * {@code Layers.prerender()} and before {@code createMapView(...)}: the geometry parameters are
 * read inside that call and by the warp mesh in the same call, so applying later would leave them
 * one frame stale, which is the class of bug that is invisible until someone steps through a
 * recording.
 *
 * <p>At the render entry point rather than on a Player time listener, because a frame is also
 * drawn when nothing about time changed (a camera move, a resize, a window uncover). On a time
 * listener the parameters between two time changes would be whatever they were last left at,
 * which is right by accident rather than by construction. Applying every frame is idempotent and
 * costs a few dozen double interpolations.
 *
 * <p>And, decisively, because the export is grabbed from inside that same method:
 * ExportMovie.handleMovieExport() is the last thing GLRenderer.display does. There is no second
 * code path to keep in step, so the pixels encoded are the pixels the tracks produced, from one
 * evaluation. In this application the picture is the claim, and a parameter animation evaluated on
 * a different clock for export would produce a movie that is a different picture from the one on
 * screen, with no way to say which of the two is honest.
 *
 * <h2>The registry</h2>
 * A track names its parameter with a stable string and never holds a reference to what it drives:
 *
 * <pre>
 *   display.warpLambda    display.warpOuterRadius   display.diskScale
 *   hdr.gain              hdr.knee                  hdr.inRange
 *   layer:&lt;layerId&gt;/opacity   ... /blend /sharpen /enhanced /upsilonLow /upsilonHigh
 *                             ... /brightOffset /brightScale
 *   grid:&lt;layerId&gt;/alpha      grid:&lt;layerId&gt;/labelAlpha   grid:&lt;layerId&gt;/lineScale
 * </pre>
 *
 * <p>Resolution happens per frame, which is what makes the asynchronous restore a non-problem:
 * a key naming a layer that has not loaded yet (or never will) simply does nothing that frame and
 * costs a lookup, and the track is still written back out on the next save rather than being
 * quietly dropped. Nothing has to be ordered and nothing has to be retried.
 *
 * <p>The registry's contract is that the registered setter is the <em>cheap</em> one: no
 * properties-file write, no DisplayController.display(), no fan-out to the rest of the selection.
 * That is where every trap in docs/parameter-animation-spec.md section 8 is neutralised once:
 * HdrGain's aim* variants instead of its set* (which each rewrite the whole properties file),
 * Display.applyDiskScale instead of setDiskScale (which writes Settings and requests a render),
 * GridLayer's aim* variants instead of its set* (which each ask for another frame from inside a
 * frame), and GLImage's own setters instead of the filter panels' consumers, which fan every edit
 * out to the whole selection through Layers.applyToSelected.
 */
public final class Automation {

    /** A resolved parameter: how to read it now, how to write it cheaply, and what to call it in the UI. */
    public record Param(String label, DoubleSupplier getter, DoubleConsumer setter) {}

    // Sorted, so two tracks reaching the same underlying state resolve the same way every frame
    // rather than by hash iteration order.
    private static final Map<String, Track> tracks = new TreeMap<>();

    public static Collection<Track> getTracks() {
        return List.copyOf(tracks.values());
    }

    @Nullable
    public static Track get(String paramKey) {
        return tracks.get(paramKey);
    }

    public static void put(Track track) {
        tracks.put(track.paramKey, track);
        handedOver();
    }

    public static void remove(String paramKey) {
        tracks.remove(paramKey);
        handedOver();
    }

    public static void clear() {
        tracks.clear();
        latched = null;
        handedOver();
    }

    // Who is in charge of some parameter just changed, so the sliders' readouts have to be re-greyed.
    // Called straight rather than through a listener: this class already reaches the render side
    // (Display, GLImage, GridLayer), there is exactly one thing that wants to know, and a registry
    // of live sliders would need pruning as layer panels come and go. With no windows up -- a
    // headless check -- the walk finds nothing and costs nothing.
    private static void handedOver() {
        org.helioviewer.jhv.gui.component.JHVSlider.refreshAll();
    }

    // -- the touch latch ---------------------------------------------------------------------

    // While a hand is on the parameter's own slider the applier stops writing that key, so the
    // hand and the curve are not fighting over one number thirty times a second. One field rather
    // than a set because there is one mouse; a second slider grabbed mid-drag would be a Swing bug.
    @Nullable
    private static String latched;

    public static void setLatched(@Nullable String paramKey) {
        latched = paramKey;
    }

    @Nullable
    public static String getLatched() {
        return latched;
    }

    /** Whether a curve, rather than the last thing a hand did, is deciding this parameter now. */
    public static boolean isDriving(String paramKey) {
        Track track = tracks.get(paramKey);
        return track != null && track.isDriving();
    }

    /**
     * Takes the wheel from a curve, or hands it back. Returns false when nothing is animating it.
     *
     * <p>Handing it back needs no restoring: the applier writes the curve's value at the playhead
     * on the very next frame, so the parameter snaps to where the curve says it should be.
     */
    public static boolean setSuspended(String paramKey, boolean suspended) {
        Track track = tracks.get(paramKey);
        if (track == null)
            return false;
        track.setSuspended(suspended);
        handedOver();
        return true;
    }

    // -- editing -----------------------------------------------------------------------------

    /**
     * Starts animating a parameter, with one key at {@code time} holding what it is now.
     *
     * <p>Returns null, and changes nothing, when the parameter is already animated or when nothing
     * answers to the key: a menu item that quietly replaced a curve someone had built would be
     * worse than one that does nothing.
     */
    @Nullable
    public static Track arm(String paramKey, long time) {
        if (tracks.containsKey(paramKey))
            return null;
        Param param = resolve(paramKey);
        if (param == null)
            return null;
        Track track = new Track(paramKey);
        track.put(new Track.Key(time, param.getter().getAsDouble(), Track.Interp.LINEAR));
        tracks.put(paramKey, track);
        handedOver();
        return track;
    }

    /**
     * Writes the parameter's value at {@code time} as a key, replacing any key already there.
     *
     * <p>The value is read from the registry's getter, never from the slider that triggered this:
     * the slider's position is in ticks and the mapping from ticks to the parameter can change
     * under a track (the Crop slider's radius depends on the layer stack), while the parameter is
     * the thing the track is a curve of. False when nothing is animating this parameter.
     */
    public static boolean writeKey(String paramKey, long time) {
        Track track = tracks.get(paramKey);
        // A suspended or un-ticked track is one the person has taken the wheel from, and a hand on
        // the wheel must not be filing edits to the schedule it is overriding. Without this the
        // override is not an override: every correcting drag would quietly rewrite the curve it
        // was standing in for, and the curve you came back to would not be the curve you left.
        if (track == null || track.isSuspended() || !track.isEnabled())
            return false;
        Param param = resolve(paramKey);
        if (param == null)
            return false;
        track.put(new Track.Key(time, param.getter().getAsDouble(), Track.Interp.LINEAR));
        return true;
    }

    /** Writes every enabled track's value at {@code time}. Silently skips keys nothing answers to. */
    public static void apply(long time) {
        if (tracks.isEmpty()) // the common case: one map check per frame
            return;
        for (Track track : tracks.values()) {
            if (!track.isDriving() || track.paramKey.equals(latched))
                continue;
            Param param = resolve(track.paramKey);
            if (param == null)
                continue;
            double v = track.valueAt(time);
            if (!Double.isNaN(v))
                param.setter().accept(v);
        }
    }

    // -- the registry ------------------------------------------------------------------------

    @Nullable
    public static Param resolve(String paramKey) {
        return switch (paramKey) {
            case "display.warpLambda" -> new Param("Warp lambda", Display::getWarpLambda, Display::setWarpLambda);
            case "display.warpOuterRadius" -> new Param("Warp crop", Display::getWarpOuterRadius, Display::setWarpOuterRadius);
            case "display.diskScale" -> new Param("Disk scale", Display::getDiskScale, Display::applyDiskScale);
            case "hdr.gain" -> new Param("HDR gain", Automation::hdrGain, v -> HdrGain.aimSetting(String.valueOf(v)));
            case "hdr.knee" -> new Param("HDR knee", HdrGain::knee, HdrGain::aimKnee);
            case "hdr.inRange" -> new Param("HDR in-range", HdrGain::inRange, HdrGain::aimInRange);
            default -> resolveLayer(paramKey);
        };
    }

    // "auto" is the screen's headroom rather than a number; a track created off it starts from
    // what the screen is actually giving, which is the value the user is looking at.
    private static double hdrGain() {
        try {
            return Double.parseDouble(HdrGain.setting());
        } catch (NumberFormatException e) {
            return HdrGain.current(false);
        }
    }

    @Nullable
    private static Param resolveLayer(String paramKey) {
        int slash = paramKey.indexOf('/');
        int colon = paramKey.indexOf(':');
        if (colon < 0 || slash < colon)
            return null;
        String kind = paramKey.substring(0, colon);
        String id = paramKey.substring(colon + 1, slash);
        String name = paramKey.substring(slash + 1);

        AbstractLayer layer = findLayer(id);
        if (layer == null)
            return null;

        if ("layer".equals(kind) && layer instanceof ImageLayer image) {
            GLImage gl = image.getGLImage(); // null on the placeholder layer, so never dereferenced blind
            if (gl == null)
                return null;
            String label = image.getName() + ' ' + name;
            // Written straight onto this layer's GLImage. Never through the filter panels'
            // consumers: those go through Layers.applyToSelected, which would apply the edit to
            // every selected layer rather than the one the track is bound to.
            return switch (name) {
                case "opacity" -> new Param(label, gl::getOpacity, gl::setOpacity);
                case "blend" -> new Param(label, gl::getBlend, gl::setBlend);
                case "sharpen" -> new Param(label, gl::getSharpen, gl::setSharpen);
                case "enhanced" -> new Param(label, gl::getEnhanced, gl::setEnhanced);
                // Upsilon only reaches the shader while RHEF is the active filter; on any other
                // filter this track draws a curve that does nothing.
                case "upsilonLow" -> new Param(label, gl::getUpsilonLow, v -> gl.setUpsilon(v, gl.getUpsilonHigh()));
                case "upsilonHigh" -> new Param(label, gl::getUpsilonHigh, v -> gl.setUpsilon(gl.getUpsilonLow(), v));
                // Levels and Contrast are two panels over one pair of numbers. The pair is the
                // parameter; there is deliberately no separate "contrast" key to fight with it.
                case "brightOffset" -> new Param(label, gl::getBrightOffset, v -> gl.setBrightness(v, gl.getBrightScale()));
                case "brightScale" -> new Param(label, gl::getBrightScale, v -> gl.setBrightness(gl.getBrightOffset(), v));
                default -> null;
            };
        }
        if ("grid".equals(kind) && layer instanceof GridLayer grid) {
            String label = "Grid " + name;
            return switch (name) {
                case "alpha" -> new Param(label, grid::getGridAlpha, grid::aimGridAlpha);
                case "labelAlpha" -> new Param(label, grid::getLabelAlpha, grid::aimLabelAlpha);
                case "lineScale" -> new Param(label, grid::getGridLineScale, grid::aimGridLineScale);
                default -> null;
            };
        }
        return null;
    }

    @Nullable
    private static AbstractLayer findLayer(String id) {
        for (var layer : Layers.getLayers())
            if (layer instanceof AbstractLayer al && id.equals(al.getId()))
                return al;
        return null;
    }

    /** The label a lane shows, falling back to the raw key while the layer it names has not loaded. */
    public static String labelFor(String paramKey) {
        Param param = resolve(paramKey);
        return param == null ? paramKey : param.label();
    }

    // -- session -----------------------------------------------------------------------------

    public static JSONObject toJson() {
        JSONArray ja = new JSONArray();
        for (Track track : tracks.values())
            ja.put(track.toJson());
        return new JSONObject().put("tracks", ja);
    }

    /**
     * Replaces the current tracks with the session's.
     *
     * <p>Read and written unconditionally, outside the Timelines plugin gate: State.load only
     * reads the "timelines" array when EVEPlugin is active, while saveTimelineState writes it
     * from an empty list when the plugin was never installed, so a track stored as a timeline
     * layer would survive one session with the panel off and be written away to nothing by the
     * next autosave. Automation is a property of the picture, not of the panel that edits it.
     */
    public static List<Track> fromJson(@Nullable JSONObject jo) {
        tracks.clear();
        ArrayList<Track> loaded = new ArrayList<>();
        if (jo == null)
            return loaded;
        JSONArray ja = jo.optJSONArray("tracks");
        if (ja == null)
            return loaded;
        for (Object o : ja) {
            if (!(o instanceof JSONObject jt))
                continue;
            Track track = Track.fromJson(jt);
            if (track != null) {
                tracks.put(track.paramKey, track);
                loaded.add(track);
            }
        }
        return loaded;
    }

    private Automation() {}
}
