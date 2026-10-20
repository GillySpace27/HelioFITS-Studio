package org.helioviewer.jhv.display;

import java.util.List;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.view.View;
import org.json.JSONObject;

/**
 * Two image layers compared in place (HS-11): blink between them at a set rate, a vertical swipe
 * divider across every viewport with layer A on one side and layer B on the other, or A minus B.
 *
 * <p>Neither mode touches either layer. Blink and swipe are decided at draw time: ImageLayer.render
 * asks {@link #hides} before it draws and {@link #swipeSide}/{@link #swipeX} while it binds, so the
 * layers' own enabled flags, their settings and the session's dirty state never change while a
 * comparison runs ("Selecting layers must never change the imagery"). Off is the motion switch: no
 * timer runs and nothing is hidden. The blink phase is never saved.
 *
 * <p>Difference draws A minus B in the data's own units, each stretch inverted first, in place of A,
 * and keeps B off screen, both at draw time like blink. Whether it can be drawn, and how, is
 * {@link CompareDifference}'s verdict on the frames on screen; when it refuses, both layers draw as
 * usual and the palette says why.
 *
 * <p>State lives in one final holder rather than in static fields of its own (guards.py ratchet).
 */
public final class Compare {

    public enum Mode {Off, Blink, Swipe, Difference}

    public static final double MIN_RATE = 0.5;
    public static final double MAX_RATE = 8;
    public static final double DEFAULT_RATE = 2; // Hz, full A-B cycles per second (estimate, register HS-11)

    /** {@link #swipeSide} codes; the shader reads the same numbers (imageCommon.frag, swipeSide). */
    public static final int SIDE_NONE = 0;
    public static final int SIDE_KEEP_LEFT = 1;
    public static final int SIDE_KEEP_RIGHT = 2;

    private static final class State {
        @Nullable ImageLayer a;
        @Nullable ImageLayer b;
        Mode mode = Mode.Off;
        double rate = DEFAULT_RATE;
        double split = 0.5; // fraction of each viewport's width, from its left edge
        boolean aLeft = true;
        boolean showingB; // blink phase: transient, never persisted
        @Nullable Timer timer;
    }

    private static final State S = new State();

    private Compare() {}

    public static synchronized void setLayers(@Nullable ImageLayer a, @Nullable ImageLayer b) {
        S.a = a;
        S.b = b;
        changed();
    }

    @Nullable
    public static synchronized ImageLayer getLayerA() {
        return S.a;
    }

    @Nullable
    public static synchronized ImageLayer getLayerB() {
        return S.b;
    }

    public static synchronized Mode getMode() {
        return S.mode;
    }

    public static synchronized void setMode(Mode mode) {
        S.mode = mode;
        S.showingB = false;
        syncTimer();
        changed();
    }

    public static synchronized double getRate() {
        return S.rate;
    }

    /** Clamped to [{@link #MIN_RATE}, {@link #MAX_RATE}] Hz. */
    public static synchronized void setRate(double hz) {
        S.rate = clampRate(hz);
        if (S.timer != null)
            S.timer.setDelay(halfPeriodMillis(S.rate));
    }

    public static synchronized double getSplit() {
        return S.split;
    }

    public static synchronized void setSplit(double fraction) {
        S.split = Math.max(0, Math.min(1, fraction));
        changed();
    }

    public static synchronized boolean isALeft() {
        return S.aLeft;
    }

    public static synchronized void setALeft(boolean aLeft) {
        S.aLeft = aLeft;
        changed();
    }

    static double clampRate(double hz) {
        return Double.isFinite(hz) ? Math.max(MIN_RATE, Math.min(MAX_RATE, hz)) : DEFAULT_RATE;
    }

    /** One A-B cycle per 1/rate seconds, so each layer shows for half of it. */
    static int halfPeriodMillis(double hz) {
        return (int) Math.round(500 / clampRate(hz));
    }

    /** Both layers chosen, distinct, and still in the scene. */
    private static boolean active() {
        if (S.mode == Mode.Off || S.a == null || S.b == null || S.a == S.b)
            return false;
        List<ImageLayer> layers = Layers.getImageLayers();
        return layers.contains(S.a) && layers.contains(S.b);
    }

    /**
     * Whether blink, or a drawn difference (B is inside A's picture), keeps this layer off screen
     * this instant. Any other layer, and every other mode, draws as usual.
     */
    public static synchronized boolean hides(ImageLayer layer) {
        if (S.mode == Mode.Difference)
            return layer == S.b && differenceFor(S.a) != null;
        if (S.mode != Mode.Blink || !active())
            return false;
        return S.showingB ? layer == S.a : layer == S.b;
    }

    /** Layer B while A draws the difference: its own picture and colour bar are not on screen. Blink is not counted. */
    public static synchronized boolean insideDifference(ImageLayer layer) {
        return S.mode == Mode.Difference && hides(layer);
    }

    /** What layer A's draw takes in Difference mode: B's frame for the difference texture, and the shader's coefficients. */
    public record DifferenceDraw(View.ImageData subtrahend, float[] coefficients) {}

    /** For layer A while Difference is on and the verdict lets it be drawn; null for every other layer and case. */
    @Nullable
    public static synchronized DifferenceDraw differenceFor(@Nullable ImageLayer layer) {
        if (layer == null || layer != S.a || S.mode != Mode.Difference || !active())
            return null;
        View.ImageData b = S.b.getImageData();
        CompareDifference.Verdict v = differenceVerdict();
        return b == null || v == null || !v.drawn() ? null : new DifferenceDraw(b, v.coefficients());
    }

    /** The verdict on A minus B for the frames on screen now; null unless Difference is chosen with two layers. */
    @Nullable
    public static synchronized CompareDifference.Verdict differenceVerdict() {
        if (S.mode != Mode.Difference || !active())
            return null;
        View.ImageData da = S.a.getImageData(), db = S.b.getImageData();
        if (da == null || db == null)
            return CompareDifference.refuse("Waiting for " + (da == null ? "A" : "B") + " to load a frame");
        return CompareDifference.judge(side(S.a, da), side(S.b, db));
    }

    private static CompareDifference.Side side(ImageLayer layer, View.ImageData data) {
        MetaData m = data.metaData();
        FitsMetaData f = m instanceof FitsMetaData fm ? fm : null;
        return new CompareDifference.Side(m.getDisplayName(), f == null ? "" : f.getInstrument(), f == null ? "" : f.getUnit(),
                data.imageBuffer().physicalScale(), data.imageBuffer().width, data.imageBuffer().height,
                m.getViewpoint().time.milli, layer.getFilter().name(),
                layer.getDisplaySettings().getDifferenceMode() != ImageDisplaySettings.DifferenceMode.None);
    }

    /** Which side of the divider this layer keeps: {@link #SIDE_NONE} unless swipe is on and it is A or B. */
    public static synchronized int swipeSide(ImageLayer layer) {
        if (S.mode != Mode.Swipe || !active())
            return SIDE_NONE;
        if (layer == S.a)
            return S.aLeft ? SIDE_KEEP_LEFT : SIDE_KEEP_RIGHT;
        if (layer == S.b)
            return S.aLeft ? SIDE_KEEP_RIGHT : SIDE_KEEP_LEFT;
        return SIDE_NONE;
    }

    /** The divider's x in framebuffer pixels for this viewport, the coordinate gl_FragCoord.x uses. */
    public static synchronized float swipeX(Viewport vp) {
        return splitPixel(vp.x, vp.width, S.split);
    }

    /**
     * The divider's x for a viewport whose left edge is {@code x} and whose width is {@code width},
     * both in framebuffer pixels (Display sizes viewports from the drawable, so a HiDPI scale is
     * already in them). Measured from the viewport's own origin: in multiview each panel has its
     * own divider at the same fraction.
     */
    static float splitPixel(int x, int width, double fraction) {
        return (float) (x + Math.max(0, Math.min(1, fraction)) * width);
    }

    private static void syncTimer() {
        boolean want = S.mode == Mode.Blink;
        if (want && S.timer == null) {
            Timer t = new Timer(halfPeriodMillis(S.rate), e -> tick());
            t.setRepeats(true);
            S.timer = t;
            t.start();
        } else if (!want && S.timer != null) {
            S.timer.stop();
            S.timer = null;
        }
    }

    private static void tick() {
        synchronized (Compare.class) {
            if (!active())
                return;
            S.showingB = !S.showingB;
        }
        DisplayController.display(); // a redraw only: no layer event, so nothing is marked unsaved
    }

    private static void changed() {
        if (java.awt.EventQueue.isDispatchThread())
            DisplayController.display();
    }

    /** What a session saves: the choices, by layer id. Null when Compare is off, so most sessions are unchanged. */
    @Nullable
    public static synchronized JSONObject toJson() {
        if (S.mode == Mode.Off)
            return null;
        JSONObject jo = new JSONObject().put("mode", S.mode.name()).put("rate", S.rate)
                .put("split", S.split).put("aLeft", S.aLeft);
        if (S.a != null)
            jo.put("a", S.a.getId());
        if (S.b != null)
            jo.put("b", S.b.getId());
        return jo;
    }

    /** Restores {@link #toJson()} against the layers a session just created; a missing key leaves Compare off. */
    public static synchronized void fromJson(@Nullable JSONObject jo, List<? extends Layer> layers) {
        if (jo == null) {
            setMode(Mode.Off);
            return;
        }
        S.a = byId(layers, jo.optString("a", ""));
        S.b = byId(layers, jo.optString("b", ""));
        S.rate = clampRate(jo.optDouble("rate", DEFAULT_RATE));
        S.split = Math.max(0, Math.min(1, jo.optDouble("split", 0.5)));
        S.aLeft = jo.optBoolean("aLeft", true);
        Mode mode = Mode.Off;
        try {
            mode = Mode.valueOf(jo.optString("mode", "Off"));
        } catch (IllegalArgumentException ignore) { // a mode from a newer build: stay off
        }
        setMode(mode);
    }

    @Nullable
    private static ImageLayer byId(List<? extends Layer> layers, String id) {
        if (id.isEmpty())
            return null;
        for (Layer l : layers)
            if (l instanceof ImageLayer il && id.equals(il.getId()))
                return il;
        return null;
    }

}
