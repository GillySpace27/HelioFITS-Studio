package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.DisplaySettings;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.identify.CometSight;
import org.helioviewer.jhv.identify.CometStyle;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.IdentifyGlow;
import org.helioviewer.jhv.identify.IdentifySettings;
import org.helioviewer.jhv.identify.SkyScreen;
import org.helioviewer.jhv.layers.grid.GridMath;
import org.helioviewer.jhv.layers.selector.CometLayerOptions;
import org.helioviewer.jhv.layers.selector.LayerOptions;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.view.View;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A comet in the scene (comet3d plan, revision 2, section 2.6): its heliocentric path from JPL
 * Horizons ({@link CometEphemeris}), and in every visible image layer that shows it a hollow ring
 * where that layer's own spacecraft saw it at that layer's own time ({@link CometSight}). Rings
 * from different spacecraft sit in different places: that is parallax, not an error.
 *
 * <p>The rings are drawn in the float pass, so they are in exported movies and, in a layered
 * export, isolated in this layer's own pass, never in an image layer's data channel. The path is
 * fetched off the GL thread, only while the layer is enabled, and cached on disk.
 *
 * <p>In every 3D mode (section 3) the layer also draws the comet's orbit and a dot where it is at
 * the display time: its true position in 3D, so the dot sits on a ring only when the comet is in
 * that image's plane of sky. The orbit is rotated with one matrix at the display time, as
 * PlanetMarkers.buildOrbits rotates the planets', and always follows solar rotation, since the
 * rings must stay on the images.
 */
public final class CometLayer extends AbstractLayer {

    static {
        LayerOptions.register(CometLayer.class, l -> new CometLayerOptions((CometLayer) l));
    }

    static final double RING_RADIUS_PX = 7; // judgement
    static final double RING_WIDTH_PX = 1.5;
    static final int RING_POINTS = 24;
    /** Fetches started, for CometLayerCheck. */
    static final AtomicInteger FETCHES = new AtomicInteger();
    private static final long DAY = 86_400_000L;
    /** Hermite sub-chords per sample interval of the orbit line (plan section 3: chord error under 1e-3 Rsun). */
    static final int SUB_CHORDS = 8;
    static final double PAST_ALPHA = 0.8, FUTURE_ALPHA = 0.35; // judgement; the planets' orbits use 0.5
    static final int DEFAULT_SPAN_DAYS = 30;
    public static final int MAX_SPAN_DAYS = 365;
    /** The longest orbit window, whole days: three years (estimate), unless the movie itself is longer. */
    static final long MAX_WINDOW = 1096 * DAY;
    private static final double DOT_FRACTION = 0.012; // of the camera width, as GridLayer's planets
    private static final double HALO = 2.5; // the lit dot's halo, times the dot (judgement)
    private static final int HALO_ALPHA = 90;

    private final Comets.Comet comet;
    private int[] rgb;
    private boolean showRings = true;
    private boolean lightTime = true;
    private boolean showOrbit = true;
    private boolean showDot = true;
    private int orbitSpanDays = DEFAULT_SPAN_DAYS;

    private volatile CometEphemeris path;
    private volatile CometEphemeris soho;
    private volatile String fetchStatus = "";
    private volatile List<String> observerLines = List.of();
    private long fetchedStart = Long.MIN_VALUE, fetchedEnd = Long.MIN_VALUE, fetchedSohoStart = Long.MIN_VALUE, fetchedSohoEnd = Long.MIN_VALUE;
    private int generation;
    @Nullable
    private Future<?> load;

    private final GLSLLine line = new GLSLLine(true);
    private final BufVertex buf = new BufVertex(RING_POINTS * 8);
    private final GLSLLine orbitLine = new GLSLLine(false);
    private final BufVertex orbitBuf = new BufVertex(4096);
    private final GLSLShape dot = new GLSLShape(false);
    private final BufVertex dotBuf = new BufVertex(2);
    @Nullable
    private CometEphemeris builtPath;
    private long builtFrame = Long.MIN_VALUE;
    private int[] builtRgb = new int[0];

    public CometLayer(Comets.Comet _comet, int[] _rgb) {
        comet = _comet;
        rgb = _rgb.clone();
        setEnabled(true);
    }

    /** State.json2Object's constructor. A missing designation throws, which State logs and skips; damaged fields keep defaults. */
    public CometLayer(JSONObject jo) throws JSONException {
        String designation = jo.getString("designation");
        if (designation.isBlank())
            throw new JSONException("comet layer without a designation");
        comet = new Comets.Comet(designation, jo.optString("name", ""), jo.optDouble("perihelionAu", Double.NaN), Math.max(0, jo.optLong("perihelion", 0)));
        int[] c = CometStyle.fromHex(jo.optString("color", null));
        rgb = c == null ? CometStyle.palette(0) : c;
        showRings = jo.optBoolean("showRings", true);
        lightTime = jo.optBoolean("lightTime", true);
        showOrbit = jo.optBoolean("showOrbit", true);
        showDot = jo.optBoolean("showDot", true);
        orbitSpanDays = Math.clamp(jo.optInt("orbitSpanDays", DEFAULT_SPAN_DAYS), 1, MAX_SPAN_DAYS);
        setEnabled(true);
    }

    /** Whether {@code comet} has a layer that is switched on. */
    public static boolean isShown(Comets.Comet comet) {
        for (Layer l : Layers.getLayers())
            if (l instanceof CometLayer cl && cl.designation().equals(comet.designation()))
                return cl.isEnabled();
        return false;
    }

    /** On: as {@link #show}. Off: switch its layer off and keep it, with its settings, in the scene list. */
    public static void setShown(Comets.Comet comet, boolean on) {
        if (on) {
            show(comet);
            return;
        }
        for (Layer l : Layers.getLayers())
            if (l instanceof CometLayer cl && cl.designation().equals(comet.designation())) {
                cl.setEnabled(false);
                Layers.fireLayerUpdated(cl);
                DisplayController.display();
            }
    }

    /** Show {@code comet} in the scene: enable its layer if there is one, else add one in the next free colour. */
    public static void show(Comets.Comet comet) {
        List<int[]> inUse = new ArrayList<>();
        for (Layer l : Layers.getLayers())
            if (l instanceof CometLayer cl) {
                if (cl.designation().equals(comet.designation())) {
                    cl.setEnabled(true);
                    Layers.fireLayerUpdated(cl);
                    DisplayController.display();
                    return;
                }
                inUse.add(cl.rgb());
            }
        Layers.add(new CometLayer(comet, CometStyle.next(inUse)));
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("designation", comet.designation());
        jo.put("name", comet.name() == null ? "" : comet.name());
        if (Double.isFinite(comet.perihelionAu())) // JSON has no NaN
            jo.put("perihelionAu", comet.perihelionAu());
        jo.put("perihelion", comet.perihelion());
        jo.put("color", CometStyle.hex(rgb));
        jo.put("showRings", showRings);
        jo.put("lightTime", lightTime);
        jo.put("showOrbit", showOrbit);
        jo.put("showDot", showDot);
        jo.put("orbitSpanDays", orbitSpanDays);
    }

    @Override
    public Kind kind() {
        return Kind.SCENE;
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public String getName() {
        return comet.label();
    }

    public String designation() {
        return comet.designation();
    }

    public Comets.Comet comet() {
        return comet;
    }

    public int[] rgb() {
        return rgb.clone();
    }

    public void setRgb(int[] _rgb) {
        rgb = _rgb.clone();
        DisplayController.display();
    }

    public boolean showRings() {
        return showRings;
    }

    public void setShowRings(boolean b) {
        showRings = b;
        DisplayController.display();
    }

    public boolean lightTime() {
        return lightTime;
    }

    public void setLightTime(boolean b) {
        lightTime = b;
        DisplayController.display();
    }

    public boolean showOrbit() {
        return showOrbit;
    }

    public void setShowOrbit(boolean b) {
        showOrbit = b;
        DisplayController.display();
    }

    public boolean showDot() {
        return showDot;
    }

    public void setShowDot(boolean b) {
        showDot = b;
        DisplayController.display();
    }

    public int orbitSpanDays() {
        return orbitSpanDays;
    }

    /** Days of orbit drawn each side of perihelion (or of the movie, for a comet with none); the path is fetched again for it. */
    public void setOrbitSpanDays(int days) {
        orbitSpanDays = Math.clamp(days, 1, MAX_SPAN_DAYS);
        DisplayController.display();
    }

    // Fetching

    /** The span the path is fetched for: the movie's; CometEphemeris adds its own pad. */
    static long[] fetchWindow(long movieStart, long movieEnd) {
        return new long[]{Math.min(movieStart, movieEnd), Math.max(movieStart, movieEnd)};
    }

    /**
     * The span the orbit is drawn over: the movie and {@code spanDays} each side of perihelion, or
     * the movie widened by {@code spanDays} each side for a comet with no perihelion
     * ({@code perihelion <= 0}); without a movie the caller passes the display time as both ends.
     * Whole UTC days, and at most {@link #MAX_WINDOW}, trimmed on the side away from the movie.
     */
    static long[] orbitWindow(long movieStart, long movieEnd, long perihelion, int spanDays) {
        long a = Math.min(movieStart, movieEnd), b = Math.max(movieStart, movieEnd), span = spanDays * DAY;
        long s = perihelion > 0 ? Math.min(a, perihelion - span) : a - span;
        long e = perihelion > 0 ? Math.max(b, perihelion + span) : b + span;
        s = Math.floorDiv(s, DAY) * DAY;
        e = -Math.floorDiv(-e, DAY) * DAY;
        if (e - s > MAX_WINDOW) {
            if (perihelion > b) // the far side is after the movie
                e = Math.max(-Math.floorDiv(-b, DAY) * DAY, s + MAX_WINDOW);
            else
                s = Math.min(Math.floorDiv(a, DAY) * DAY, e - MAX_WINDOW);
        }
        return new long[]{s, e};
    }

    @Override
    public void prerender() {
        if (!isEnabled())
            return; // no network for a layer that is off
        boolean movie = Player.isAvailable();
        long t = Player.getTime().milli;
        long[] w = movie ? fetchWindow(Player.getStartTime(), Player.getEndTime()) : fetchWindow(t - DAY, t + DAY);
        long[] o = !showOrbit ? w : movie ? orbitWindow(Player.getStartTime(), Player.getEndTime(), comet.perihelion(), orbitSpanDays)
                : orbitWindow(t, t, comet.perihelion(), orbitSpanDays);
        ensureFetch(Math.min(o[0], w[0]), Math.max(o[1], w[1]), w[0], w[1], false);
    }

    /** Ask Horizons again (Refresh), ignoring the cache. */
    public void refresh() {
        long s = fetchedStart, e = fetchedEnd, ss = fetchedSohoStart, se = fetchedSohoEnd;
        if (s == Long.MIN_VALUE)
            return;
        fetchedStart = fetchedEnd = Long.MIN_VALUE;
        ensureFetch(s, e, ss, se, true);
    }

    /** The comet over [start, end] (the orbit window) and SOHO over [sohoStart, sohoEnd] (the movie, for the rings). */
    void ensureFetch(long start, long end, long sohoStart, long sohoEnd, boolean refresh) {
        if (start == fetchedStart && end == fetchedEnd && sohoStart == fetchedSohoStart && sohoEnd == fetchedSohoEnd)
            return;
        cancel();
        fetchedStart = start;
        fetchedEnd = end;
        fetchedSohoStart = sohoStart;
        fetchedSohoEnd = sohoEnd;
        FETCHES.incrementAndGet();
        int mine = ++generation;
        fetchStatus = "Fetching the path of " + comet.label() + " from JPL Horizons...";
        load = Task.submitBackground("comet-layer", () -> new CometEphemeris[]{
                CometEphemeris.fetch(comet, start, end, refresh),
                CometEphemeris.fetchBody("-21", "SOHO", sohoStart, sohoEnd, refresh)}, got -> {
            if (mine != generation)
                return; // the window moved on
            path = got[0];
            soho = got[1];
            fetchStatus = "";
            DisplayController.display();
        }, (logContext, t) -> {
            if (mine != generation)
                return;
            fetchStatus = "No path for " + comet.label() + ": " + (t.getMessage() == null ? t.toString() : t.getMessage());
            DisplayController.display();
        });
    }

    private void cancel() {
        generation++;
        if (load != null)
            load.cancel(true);
        load = null;
    }

    /** Lines for the options panel: the fetch, the orbit solution, and per layer where its observer came from. */
    public String status() {
        List<String> out = new ArrayList<>();
        if (!fetchStatus.isEmpty())
            out.add(fetchStatus);
        CometEphemeris p = path;
        if (p != null) {
            out.add(p.sampleTimes().length + " positions from JPL Horizons" + (p.sourceTag().isEmpty() ? "" : " " + p.sourceTag()) + '.');
            if (!p.note().isEmpty())
                out.add(p.note() + '.');
        }
        out.addAll(observerLines);
        return String.join("\n", out);
    }

    // Rings

    /** Ring radius in pixels: scaled for the display and for an export's capture scale. */
    static double ringRadiusPx(double pixelScale, double captureScale) {
        return RING_RADIUS_PX * pixelScale * captureScale;
    }

    static double ringWidthPx(double pixelScale, double captureScale) {
        return RING_WIDTH_PX * pixelScale * captureScale;
    }

    /**
     * The rings' colour under Identify's Boldness of the highlights, as Identify's own rings take
     * it: saturation scaled, opacity scaled (already opaque, so only less than 1 shows).
     */
    static byte[] ringColour(int[] rgb, double opacity, double saturation) {
        int[] c = IdentifyGlow.saturate(rgb, saturation);
        return new byte[]{(byte) c[0], (byte) c[1], (byte) c[2], (byte) Math.clamp(Math.round(255 * opacity), 0, 255)};
    }

    /** How this frame draws the rings: colours, line widths in pixels, the glyph's scale and whether it is drawn. */
    record RingStyle(byte[] colour, byte[] shadow, double widthPx, double shadowWidthPx, double glyphScale, boolean glyph) {}

    /**
     * The rings' style from the settings: Identify's Boldness of the highlights, as its own rings
     * take it, and its comet glyph. Everything scales with the display and an export's capture scale.
     */
    static RingStyle ringStyle(int[] rgb, double pixelScale, double captureScale) {
        double opacity = IdentifySettings.boldOpacity(), wm = IdentifySettings.boldWidth();
        double scale = pixelScale * captureScale;
        double width = ringWidthPx(pixelScale, captureScale) * wm;
        return new RingStyle(ringColour(rgb, opacity, IdentifySettings.boldSaturation()), ringShadow(IdentifySettings.boldShadow(), opacity),
                width, width + 3 * wm * scale, scale, IdentifySettings.cometGlyph());
    }

    /** The dark outline under the rings for Boldness's shadow, as Identify draws it; alpha 0 = none. */
    static byte[] ringShadow(double shadow, double opacity) {
        return new byte[]{0, 0, 0, (byte) (int) Math.min(255, 200 * shadow * Math.min(1, opacity))};
    }

    private record Row(Identifier.Target target, String observatory) {}

    /** The observatory of the image layer named {@code name}, or "" (no such layer, or not FITS). */
    static String observatory(String name) {
        for (ImageLayer layer : Layers.getImageLayers()) {
            View.ImageData data = layer.getImageData();
            if (data != null && layer.getName().equals(name))
                return data.metaData() instanceof FitsMetaData f ? f.getObservatory() : "";
        }
        return "";
    }

    // For Identify (CometMarks): the same answers the drawing uses

    /**
     * Where this comet's ring is in {@code t}'s image, as {@link #renderFloat} places it (that
     * image's observer, metadata and time, the time mode, light time), or null where it draws none.
     */
    @Nullable
    public Vec3 ringDirection(Identifier.Target t) {
        CometEphemeris p = path, s = soho;
        if (p == null || !showRings)
            return null;
        CometSight.Look look = CometSight.look(p::world, t, observatory(t.name()), s == null ? null : s::world, lightTime, DisplaySettings.getTimeMode());
        return look.drawn() ? look.sight().dir() : null;
    }

    /** Where the dot is drawn at {@code time} (geometric, world frame at that time), or null. */
    @Nullable
    public Vec3 positionAt(JHVTime time) {
        CometEphemeris p = path;
        return p == null ? null : p.world(time.milli, time);
    }

    /** The ring's radius on the canvas, pixels. */
    public static double ringRadiusOnScreen() {
        return ringRadiusPx(Display.pixelScale[1], Display.captureScale);
    }

    /** The dot's drawn radius in solar radii, for a camera {@code cameraWidth} wide. */
    public static double dotRadius(double cameraWidth) {
        return DOT_FRACTION * cameraWidth / 2;
    }

    /** A signature of what decides this comet's marks, for Identify's frame cache. */
    public String identifySignature() {
        return comet.designation() + ',' + isEnabled() + ',' + showRings + ',' + showDot + ',' + lightTime + ','
                + System.identityHashCode(path) + ',' + System.identityHashCode(soho) + ',' + CometStyle.hex(rgb);
    }

    /** The image layers visible in {@code vp}, each as the identifier's target, with its observatory. */
    private static List<Row> targets(Viewport vp) {
        List<Row> out = new ArrayList<>();
        for (ImageLayer layer : Layers.getImageLayers()) {
            View.ImageData data = layer.getImageData();
            if (data == null || !layer.isVisible(vp.idx))
                continue;
            MetaData m = data.metaData();
            Identifier.Target t = new Identifier.Target(layer.getName(), m.getViewpoint(), m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius());
            out.add(new Row(t, m instanceof FitsMetaData f ? f.getObservatory() : ""));
        }
        return out;
    }

    @Override
    public void renderFloat(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        CometEphemeris p = path, s = soho;
        if (p == null)
            return;
        CometEphemeris.WorldPath sohoPath = s == null ? null : s::world;
        DisplaySettings.TimeMode mode = DisplaySettings.getTimeMode();
        double radius = ringRadiusPx(Display.pixelScale[1], Display.captureScale);
        List<String> lines = new ArrayList<>();
        List<double[]> centres = new ArrayList<>();
        for (Row row : targets(vp)) {
            Identifier.Target t = row.target();
            CometSight.Look look = CometSight.look(p::world, t, row.observatory(), sohoPath, lightTime, mode);
            lines.add(t.name() + ": " + look.source().text + (look.drawn() ? "" : look.sight() == null ? "; no position" : "; no ring (" + look.qualifier().text + ')'));
            if (!showRings || !look.drawn())
                continue;
            double[] c = SkyScreen.place(mv, vp, t.observer(), look.sight().dir());
            if (c == null)
                continue;
            centres.add(c);
        }
        if (vp.idx == 0)
            observerLines = List.copyOf(lines);
        if (centres.isEmpty() || vp.height <= 0)
            return;

        RingStyle style = ringStyle(rgb, Display.pixelScale[1], Display.captureScale);

        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        GL.glDisable(GL.DEPTH_TEST);
        try {
            if (style.shadow()[3] != 0) { // under the colour, wider, so a ring stands out on a bright corona
                buf.clear();
                for (double[] c : centres)
                    putMarker(buf, c, vp, radius, style.glyphScale(), style.glyph(), style.shadow());
                line.upload(buf);
                line.renderLine(vp, style.shadowWidthPx() / vp.height);
            }
            buf.clear();
            for (double[] c : centres)
                putMarker(buf, c, vp, radius, style.glyphScale(), style.glyph(), style.colour());
            line.upload(buf);
            line.renderLine(vp, style.widthPx() / vp.height);
        } finally {
            GL.glEnable(GL.DEPTH_TEST);
            Transform.popView();
            Transform.popProjection();
        }
    }

    // Orbit and dot

    /**
     * The times the orbit line is drawn at: every sample, {@link #SUB_CHORDS} Hermite sub-chords
     * between each pair, and {@code now} inserted where it falls inside, so the line splits there.
     */
    static long[] orbitTimes(long[] samples, long now) {
        if (samples.length < 2)
            return samples.clone();
        long[] out = new long[SUB_CHORDS * (samples.length - 1) + 2];
        int n = 0;
        for (int i = 0; i + 1 < samples.length; i++)
            for (int k = 0; k < SUB_CHORDS; k++) {
                long t = samples[i] + Math.round((samples[i + 1] - samples[i]) * (double) k / SUB_CHORDS);
                if (n > 0 && out[n - 1] < now && now < t)
                    out[n++] = now;
                out[n++] = t;
            }
        long last = samples[samples.length - 1];
        if (out[n - 1] < now && now < last)
            out[n++] = now;
        out[n++] = last;
        return Arrays.copyOf(out, n);
    }

    /** The orbit as lines, past brighter than future, broken where the path has no position (NaN). */
    static void putOrbit(BufVertex b, long[] times, double[] xyz, long now, int[] rgb) {
        byte[] past = {(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) Math.round(255 * PAST_ALPHA)};
        byte[] future = {(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) Math.round(255 * FUTURE_ALPHA)};
        boolean open = false;
        for (int i = 0; i < times.length; i++) {
            float x = (float) xyz[3 * i], y = (float) xyz[3 * i + 1], z = (float) xyz[3 * i + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                if (open)
                    b.endLine();
                open = false;
                continue;
            }
            byte[] col = times[i] <= now ? past : future;
            if (open)
                b.putVertex(x, y, z, 1, col);
            else
                b.startLine(x, y, z, 1, col);
            open = true;
            if (times[i] == now && i + 1 < times.length) { // split: the future starts at the same point
                b.endLine();
                b.startLine(x, y, z, 1, future);
            }
        }
        if (open)
            b.endLine();
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx] || (!showOrbit && !showDot))
            return;
        CometEphemeris p = path;
        if (p == null)
            return;
        JHVTime now = mv.viewpoint().time;
        if (showOrbit) {
            if (p != builtPath || now.milli != builtFrame || !Arrays.equals(rgb, builtRgb)) {
                long[] times = orbitTimes(p.sampleTimes(), now.milli);
                double[] xyz = p.worldAll(times, now); // one rotation, at the display time
                if (xyz != null)
                    putOrbit(orbitBuf, times, xyz, now.milli, rgb);
                orbitLine.uploadAndClear(orbitBuf);
                builtPath = p;
                builtFrame = now.milli;
                builtRgb = rgb.clone();
            }
            orbitLine.renderLine(vp, GridMath.LINEWIDTH);
        }
        double cameraWidth = mv.cameraWidth(vp);
        Vec3 w = showDot && cameraWidth > 0 ? p.world(now.milli, now) : null; // geometric: where it is, not where it was seen
        if (w == null)
            return;
        if (CometStyle.lit(comet.designation()) && !ExportMovie.isRecording()) // Identify's highlight, never in a recording
            dotBuf.putVertex((float) w.x, (float) w.y, (float) w.z, (float) (HALO * DOT_FRACTION * cameraWidth),
                    new byte[]{(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) HALO_ALPHA});
        dotBuf.putVertex((float) w.x, (float) w.y, (float) w.z, (float) (DOT_FRACTION * cameraWidth),
                new byte[]{(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) 255});
        dot.uploadAndClear(dotBuf);
        // A position, not a surface: depth-tested it would vanish behind the Sun's sphere exactly when that is worth seeing (GridLayer's planets)
        GL.glDisable(GL.DEPTH_TEST);
        try {
            dot.renderPoints(ViewportMath.getPixelFactor(vp, cameraWidth));
        } finally {
            GL.glEnable(GL.DEPTH_TEST);
        }
    }

    /** A closed ring of {@link #RING_POINTS} around (x, y), viewport GL pixels. */
    /**
     * One image's marker: the ring, and inside it the comet glyph when Identify's "Comet glyph on
     * comets" is on. {@code c} is the page point (y down) SkyScreen.place gives. Identify draws the
     * glyph only with its own rings, and a comet shown in the 3D scene is drawn here instead, so
     * turning on "Show in 3D scene" used to take the glyph away (Gilly, 2026-11-22).
     *
     * @return the strokes put: 1, or 4 with the glyph (the ring is the head; three tails leave it)
     */
    static int putMarker(BufVertex b, double[] c, Viewport vp, double radius, double scale, boolean glyph, byte[] col) {
        putRing(b, c[0] - vp.x, vp.height - (c[1] - vp.yAWT), radius, col);
        if (!glyph)
            return 1;
        int strokes = 1;
        for (List<double[]> stroke : IdentifyGlow.cometGlyphStrokes(c, scale, radius)) {
            for (int i = 0; i < stroke.size(); i++) {
                double[] p = stroke.get(i);
                float px = (float) (p[0] - vp.x), py = (float) (vp.height - (p[1] - vp.yAWT));
                if (i == 0)
                    b.startLine(px, py, 0, 1, col);
                else
                    b.putVertex(px, py, 0, 1, col);
            }
            b.endLine();
            strokes++;
        }
        return strokes;
    }

    static void putRing(BufVertex b, double x, double y, double radius, byte[] col) {
        for (int i = 0; i <= RING_POINTS; i++) {
            double a = 2 * Math.PI * i / RING_POINTS;
            float px = (float) (x + radius * Math.cos(a)), py = (float) (y + radius * Math.sin(a));
            if (i == 0)
                b.startLine(px, py, 0, 1, col);
            else
                b.putVertex(px, py, 0, 1, col);
        }
        b.endLine();
    }

    @Override
    public void init() {
        line.init();
        orbitLine.init();
        dot.init();
    }

    @Override
    public void dispose() {
        line.dispose();
        orbitLine.dispose();
        dot.dispose();
    }

    @Override
    public void remove() {
        cancel();
        dispose();
    }

}
