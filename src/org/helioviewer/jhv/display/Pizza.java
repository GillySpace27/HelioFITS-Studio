package org.helioviewer.jhv.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.Player;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The sun pizza (HS-25): every enabled image layer drawn in its own wedges (or tiles) of one Sun,
 * so the same patch of the Sun is seen through every filter side by side.
 *
 * <p>Like {@link Compare}, nothing here touches a layer. Each layer is loaded and drawn whole as
 * always; at draw time ImageLayer.render asks {@link #uniforms} which cells it keeps and the
 * fragment shader discards the rest (imageCommon.frag, clipPizza). Off is the motion switch: every
 * layer draws as usual and nothing is hidden, so the imagery a layer shows never changes because
 * of the pizza ("Selecting layers must never change the imagery").
 *
 * <p>Slots are the enabled image layers in the layer list's order, less the ones left out. Each
 * belongs to a ring: the inner ring by default, the outer one for a layer whose metadata declares
 * an occulter past the limb (a coronagraph) when two rings are on. A ring has N slots and
 * repeats them R times around, so it has N times R cells; cell c belongs to slot
 * {@link #slotOfCell}. In the Sun's own frame (the default) a wedge edge is a solar position
 * angle, measured from solar north towards east in the plane of each image, so the pizza stays on
 * the Sun as the camera moves; in screen space it is an angle about the viewport's centre.
 *
 * <p>State lives in one final holder (guards.py ratchet), and every method is synchronized on the
 * class because the palette (EDT) and the renderer (GL thread, the EDT here) both read it.
 */
public final class Pizza {

    /** How the Sun is cut. Tiles are wedges cut again into rings: in Helioradial Unrolled, squares. */
    public enum Shape {
        Off("Off"), Wedges("Wedges"), Squares("Squares"), Tiles("Polar tiles");

        private final String label;

        Shape(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Sun: cells fixed to each image's solar frame (position angle). Screen: fixed to the viewport. */
    public enum Space {Sun, Screen}

    /** What turns the pizza. Live turns it on screen; Movie turns it a fixed step per movie frame, so a loop recording turns it too. */
    public enum Spin {
        Off("Still"), Live("Live"), Movie("With the movie");

        private final String label;

        Spin(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Where per-wedge labels go. */
    public enum Labels {
        Off("Off"), Every("Every wedge"), First("First repeat");

        private final String label;

        Labels(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int INNER = 0;
    public static final int OUTER = 1;
    /** A layer whose metadata masks the Sun out to at least this many solar radii is a coronagraph: the outer ring. */
    static final double OCCULTER_RADIUS = 1.05;
    public static final int MAX_REPEATS = 24;
    public static final int MAX_FRAMES = 3600;

    private static final class Ring {
        int repeats = 1;
        double angle; // degrees, added to the turn
        double direction = 1; // the turn's sign for this ring: -1 counter-rotates
    }

    private static final class State {
        Shape shape = Shape.Off;
        Space space = Space.Sun;
        final Ring[] rings = {new Ring(), new Ring()};
        boolean twoRings;
        double boundary = 1.15; // solar radii between the rings
        double outerRadius; // solar radii, 0 for none
        double gap; // degrees between wedges; a share of a tile for tiles
        double jitter; // 0 to 1: spokes of uneven length, needs an outer radius
        int seed; // 0: slots in order every repeat; s: repeat r turned by r times s
        double tile = 0.5; // solar radii (Sun) or pixels / 100 (Screen) per square, or per polar row
        int rows = 6; // polar tiles in Helioradial: rows from the centre to the edge of the page
        int shift = 1; // squares and polar tiles: how far each row moves the slots along
        double turn; // degrees
        Spin spin = Spin.Off;
        double degPerSec = 6;
        double degPerFrame = 2;
        @Nullable Timer liveTimer;
        long lastTick;
        final Set<String> excluded = new LinkedHashSet<>();
        final Map<String, Integer> ringOf = new HashMap<>(); // explicit choices; others are automatic
        Labels labels = Labels.Off;
        boolean angstrom; // labels in angstrom rather than nanometres
        boolean colourNames;
        double labelScale = 1;
        final Map<String, String> colourName = new HashMap<>(); // per layer id, written by the person
        // Spin recording: the frame being captured, -1 when none.
        int spinFrame = -1;
        int spinFrames = 180;
        double spinBase;
    }

    private static final State S = new State();
    private static final List<Runnable> listeners = new ArrayList<>();

    private Pizza() {}

    // -- the cut ------------------------------------------------------------------------------

    /**
     * The slot that owns cell {@code c} of a ring with {@code n} slots: imageCommon.frag, pizzaSlotOf,
     * in Java. With a seed, repeat r is turned by r times the seed. Pure.
     */
    public static int slotOfCell(int c, int n, int seed) {
        if (n <= 0)
            return 0;
        return Math.floorMod(c + Math.floorDiv(c, n) * seed, n);
    }

    /**
     * The angle, in degrees, the pizza must turn for the picture to come back to where it started:
     * one repeat for a ring alone, the common period of both rings, a full turn when shuffled. Pure.
     */
    static double loopDegrees(int repeatsInner, int repeatsOuter, boolean twoRings, int seed) {
        if (seed != 0)
            return 360;
        int r = twoRings ? gcd(Math.max(1, repeatsInner), Math.max(1, repeatsOuter)) : Math.max(1, repeatsInner);
        return 360. / r;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    public static synchronized boolean isOn() {
        return S.shape != Shape.Off;
    }

    /** Whether the pizza can be drawn in this projection; the latitudinal map and the observer sky are not Sun-centred. */
    public static boolean supports(MapMode mode) {
        return mode == MapMode.Orthographic || mode == MapMode.HPC || mode == MapMode.Helioradial || mode == MapMode.HelioradialUnrolled;
    }

    /** The image layers that take part, in slot order, each with its ring. */
    public record Slot(ImageLayer layer, int ring, int index) {}

    public static synchronized List<Slot> slots(int ring) {
        List<Slot> out = new ArrayList<>();
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isEnabled() || S.excluded.contains(layer.getId()))
                continue;
            if (ringFor(layer) == ring)
                out.add(new Slot(layer, ring, out.size()));
        }
        return out;
    }

    private static int ringFor(ImageLayer layer) {
        if (!S.twoRings)
            return INNER;
        Integer chosen = S.ringOf.get(layer.getId());
        if (chosen != null)
            return chosen;
        return isCoronagraph(layer) ? OUTER : INNER;
    }

    private static boolean isCoronagraph(ImageLayer layer) {
        org.helioviewer.jhv.view.View.ImageData data = layer.getImageData();
        return data != null && data.metaData().getInnerRadius() >= OCCULTER_RADIUS;
    }

    /** The turn of a ring right now, in radians, counterclockwise from solar north. Also read by PizzaLabels. */
    static synchronized double turnRadians(int ring) {
        double turn = S.turn;
        if (S.spin == Spin.Movie && S.spinFrame < 0)
            turn += S.degPerFrame * Player.getCurrentFrameNumber();
        Ring r = S.rings[ring];
        return Math.toRadians(r.angle + r.direction * turn);
    }

    private static final float[] OFF = new float[16];

    /**
     * The 16 shader floats for one layer in one viewport (DisplayBlock pizzaA to pizzaD). All
     * zeros, which the shader reads as off, unless the pizza is on, the projection is Sun-centred
     * and the layer is one of the slots.
     */
    public static synchronized float[] uniforms(ImageLayer layer, Viewport vp, MapMode mode) {
        if (S.shape == Shape.Off || !supports(mode) || !layer.isEnabled() || S.excluded.contains(layer.getId()))
            return OFF;
        int ring = ringFor(layer);
        List<Slot> slots = slots(ring);
        int index = -1;
        for (Slot s : slots)
            if (s.layer() == layer)
                index = s.index();
        if (index < 0)
            return OFF;

        boolean unrolled = mode == MapMode.HelioradialUnrolled;
        boolean helioradial = mode == MapMode.Helioradial || unrolled;
        // Squares in the unrolled page are its own squares: polar tiles counted on the page's radius.
        Shape shape = S.shape == Shape.Squares && unrolled && S.space == Space.Sun ? Shape.Tiles : S.shape;
        boolean screen = S.space == Space.Screen;
        boolean warpRows = shape == Shape.Tiles && helioradial && !screen;

        int n = slots.size();
        int repeats = Math.clamp(S.rings[ring].repeats, 1, MAX_REPEATS);
        float[] u = new float[16];
        u[0] = shape.ordinal();
        u[1] = index;
        u[2] = n * repeats;
        u[3] = n;

        u[4] = (float) turnRadians(ring);
        u[5] = (float) (shape == Shape.Wedges ? Math.toRadians(S.gap) / 2 : Math.clamp(S.gap / 100, 0, .45));
        if (!screen) {
            double outer = S.outerRadius;
            if (S.twoRings) {
                u[6] = ring == OUTER ? (float) S.boundary : 0;
                u[7] = ring == OUTER ? (float) outer : (float) S.boundary;
            } else
                u[7] = (float) outer;
        }

        double scale = screen ? 100 * displayScale() : 1;
        u[8] = (float) (warpRows ? Math.max(1, S.rows) : Math.max(1e-3, S.tile) * scale);
        u[9] = S.shift;
        u[10] = screen ? 1 : 0;
        u[11] = (float) Math.clamp(S.jitter, 0, .9);

        u[12] = vp.x + vp.width / 2f;
        u[13] = vp.yGL + vp.height / 2f;
        u[14] = S.seed;
        u[15] = warpRows ? 1 : 0;
        return u;
    }

    // Screen tiles are sized in interface pixels; the framebuffer may be HiDPI.
    private static double displayScale() {
        return Math.max(1, Display.pixelScale[0]);
    }

    // -- settings ------------------------------------------------------------------------------

    public static synchronized Shape getShape() {
        return S.shape;
    }

    public static synchronized void setShape(Shape shape) {
        S.shape = shape;
        syncSpin();
        changed();
    }

    public static synchronized Space getSpace() {
        return S.space;
    }

    public static synchronized void setSpace(Space space) {
        S.space = space;
        changed();
    }

    public static synchronized int getRepeats(int ring) {
        return S.rings[ring].repeats;
    }

    public static synchronized void setRepeats(int ring, int repeats) {
        S.rings[ring].repeats = Math.clamp(repeats, 1, MAX_REPEATS);
        changed();
    }

    public static synchronized double getRingAngle(int ring) {
        return S.rings[ring].angle;
    }

    public static synchronized void setRingAngle(int ring, double degrees) {
        S.rings[ring].angle = wrap(degrees);
        changed();
    }

    public static synchronized boolean isCounterRotating(int ring) {
        return S.rings[ring].direction < 0;
    }

    public static synchronized void setCounterRotating(int ring, boolean counter) {
        S.rings[ring].direction = counter ? -1 : 1;
        changed();
    }

    public static synchronized boolean isTwoRings() {
        return S.twoRings;
    }

    public static synchronized void setTwoRings(boolean two) {
        S.twoRings = two;
        changed();
    }

    public static synchronized double getBoundary() {
        return S.boundary;
    }

    public static synchronized void setBoundary(double radius) {
        S.boundary = Math.clamp(radius, 0.1, 30);
        changed();
    }

    public static synchronized double getOuterRadius() {
        return S.outerRadius;
    }

    /** Solar radii; 0 or less is no outer limit. */
    public static synchronized void setOuterRadius(double radius) {
        S.outerRadius = Double.isFinite(radius) && radius > 0 ? Math.min(radius, 100) : 0;
        changed();
    }

    public static synchronized double getGap() {
        return S.gap;
    }

    /** Degrees between wedges, or percent of a tile for squares and polar tiles. */
    public static synchronized void setGap(double gap) {
        S.gap = Math.clamp(gap, 0, 45);
        changed();
    }

    public static synchronized double getJitter() {
        return S.jitter;
    }

    public static synchronized void setJitter(double jitter) {
        S.jitter = Math.clamp(jitter, 0, .9);
        changed();
    }

    public static synchronized int getSeed() {
        return S.seed;
    }

    public static synchronized void setSeed(int seed) {
        S.seed = Math.clamp(seed, 0, 999);
        changed();
    }

    public static synchronized double getTile() {
        return S.tile;
    }

    public static synchronized void setTile(double tile) {
        S.tile = Math.clamp(tile, 0.02, 20);
        changed();
    }

    public static synchronized int getRows() {
        return S.rows;
    }

    public static synchronized void setRows(int rows) {
        S.rows = Math.clamp(rows, 1, 64);
        changed();
    }

    public static synchronized int getShift() {
        return S.shift;
    }

    public static synchronized void setShift(int shift) {
        S.shift = Math.clamp(shift, 0, 64);
        changed();
    }

    public static synchronized double getTurn() {
        return S.turn;
    }

    /** The Automation setter (key {@code pizza.turn}): the cheap one, no redraw and no listeners, so parameter animation can drive it every frame. */
    public static synchronized void aimTurn(double degrees) {
        S.turn = wrap(degrees);
    }

    public static synchronized void setTurn(double degrees) {
        S.turn = wrap(degrees);
        changed();
    }

    public static synchronized boolean isIncluded(ImageLayer layer) {
        return !S.excluded.contains(layer.getId());
    }

    public static synchronized void setIncluded(ImageLayer layer, boolean included) {
        if (included)
            S.excluded.remove(layer.getId());
        else
            S.excluded.add(layer.getId());
        changed();
    }

    public static synchronized int getRing(ImageLayer layer) {
        return ringFor(layer);
    }

    public static synchronized void setRing(ImageLayer layer, int ring) {
        S.ringOf.put(layer.getId(), ring == OUTER ? OUTER : INNER);
        changed();
    }

    public static synchronized Labels getLabels() {
        return S.labels;
    }

    public static synchronized void setLabels(Labels labels) {
        S.labels = labels;
        changed();
    }

    public static synchronized boolean isAngstrom() {
        return S.angstrom;
    }

    public static synchronized void setAngstrom(boolean angstrom) {
        S.angstrom = angstrom;
        changed();
    }

    public static synchronized boolean isColourNames() {
        return S.colourNames;
    }

    public static synchronized void setColourNames(boolean on) {
        S.colourNames = on;
        changed();
    }

    public static synchronized double getLabelScale() {
        return S.labelScale;
    }

    public static synchronized void setLabelScale(double scale) {
        S.labelScale = Math.clamp(scale, .5, 3);
        changed();
    }

    public static synchronized String getColourName(ImageLayer layer) {
        return S.colourName.getOrDefault(layer.getId(), "");
    }

    public static synchronized void setColourName(ImageLayer layer, String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty())
            S.colourName.remove(layer.getId());
        else
            S.colourName.put(layer.getId(), n);
        changed();
    }

    // -- spin --------------------------------------------------------------------------------

    public static synchronized Spin getSpin() {
        return S.spin;
    }

    public static synchronized void setSpin(Spin spin) {
        S.spin = spin;
        syncSpin();
        changed();
    }

    public static synchronized double getDegPerSec() {
        return S.degPerSec;
    }

    public static synchronized void setDegPerSec(double v) {
        S.degPerSec = Math.clamp(v, -360, 360);
    }

    public static synchronized double getDegPerFrame() {
        return S.degPerFrame;
    }

    public static synchronized void setDegPerFrame(double v) {
        S.degPerFrame = Math.clamp(v, -180, 180);
        changed();
    }

    /** The degrees one seamless loop turns through for the current rings and seed. */
    public static synchronized double loopDegrees() {
        return loopDegrees(S.rings[INNER].repeats, S.rings[OUTER].repeats, S.twoRings, S.seed);
    }

    private static final int LIVE_TICK_MILLIS = 33;

    private static void syncSpin() {
        boolean want = S.shape != Shape.Off && S.spin == Spin.Live && S.spinFrame < 0;
        if (want && S.liveTimer == null) {
            Timer t = new Timer(LIVE_TICK_MILLIS, e -> liveTick());
            t.setRepeats(true);
            S.liveTimer = t;
            S.lastTick = System.nanoTime();
            t.start();
        } else if (!want && S.liveTimer != null) {
            S.liveTimer.stop();
            S.liveTimer = null;
        }
    }

    private static void liveTick() {
        synchronized (Pizza.class) {
            long now = System.nanoTime();
            double dt = (now - S.lastTick) * 1e-9;
            S.lastTick = now;
            S.turn = wrap(S.turn + S.degPerSec * Math.min(dt, .25));
        }
        DisplayController.display(); // a redraw only: nothing is marked unsaved
    }

    /** Whether a spin recording is under way. */
    public static synchronized boolean isRecordingSpin() {
        return S.spinFrame >= 0;
    }

    /**
     * The turn for frame {@code i} of an {@code frames}-frame spin from {@code base}: the loop is
     * cut into equal steps and the last frame stops one step short of the first, so the file
     * loops without a repeated frame. Pure.
     */
    static double spinTurn(double base, int i, int frames, double loopDegrees, double sign) {
        return base + sign * loopDegrees * i / Math.max(1, frames);
    }

    /**
     * Turn through one seamless loop in {@code frames} steps, one rendered and captured frame per
     * step, with the data standing still: the movie is paused and only the cut moves. The
     * caller has started an unlimited recording; this drives it and stops it.
     */
    public static synchronized void beginSpinRecording(int frames) {
        S.spinFrames = Math.clamp(frames, 2, MAX_FRAMES);
        S.spinBase = S.turn;
        S.spinFrame = 0;
        syncSpin();
        Player.pause();
    }

    /**
     * Called by the renderer once a frame has been drawn and handed to the export. Moves the spin
     * on by one step, asks for the next frame, and on the last asks the recording to stop (which
     * draws and captures that last frame itself).
     */
    public static void frameRendered() {
        boolean stop;
        synchronized (Pizza.class) {
            if (S.spinFrame < 0)
                return;
            if (!org.helioviewer.jhv.movie.ExportMovie.isRecording()) { // stopped from elsewhere
                endSpin();
                return;
            }
            S.spinFrame++;
            double sign = S.degPerSec < 0 ? -1 : 1;
            S.turn = spinTurn(S.spinBase, S.spinFrame, S.spinFrames, loopDegrees(), sign);
            stop = S.spinFrame >= S.spinFrames - 1;
            if (stop)
                endSpin();
        }
        if (stop)
            java.awt.EventQueue.invokeLater(org.helioviewer.jhv.movie.ExportMovie::shallStop);
        else
            java.awt.EventQueue.invokeLater(DisplayController::display);
    }

    private static void endSpin() {
        S.spinFrame = -1;
        syncSpin();
    }

    // -- listeners and persistence ------------------------------------------------------------

    public static synchronized void addListener(Runnable r) {
        listeners.add(r);
    }

    private static void changed() {
        if (java.awt.EventQueue.isDispatchThread()) {
            DisplayController.display();
            for (Runnable r : List.copyOf(listeners))
                r.run();
        }
    }

    private static double wrap(double degrees) {
        if (!Double.isFinite(degrees))
            return 0;
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }

    /** What a session saves. Null while the pizza is off and untouched, so most sessions are unchanged. */
    @Nullable
    public static synchronized JSONObject toJson() {
        if (S.shape == Shape.Off)
            return null;
        JSONObject jo = new JSONObject()
                .put("shape", S.shape.name()).put("space", S.space.name())
                .put("twoRings", S.twoRings).put("boundary", S.boundary).put("outerRadius", S.outerRadius)
                .put("gap", S.gap).put("jitter", S.jitter).put("seed", S.seed)
                .put("tile", S.tile).put("rows", S.rows).put("shift", S.shift).put("turn", S.turn)
                .put("spin", S.spin.name()).put("degPerSec", S.degPerSec).put("degPerFrame", S.degPerFrame)
                .put("labels", S.labels.name()).put("angstrom", S.angstrom).put("colourNames", S.colourNames)
                .put("labelScale", S.labelScale);
        JSONArray rings = new JSONArray();
        for (Ring r : S.rings)
            rings.put(new JSONObject().put("repeats", r.repeats).put("angle", r.angle).put("counter", r.direction < 0));
        jo.put("rings", rings);
        jo.put("excluded", new JSONArray(S.excluded));
        JSONObject ringOf = new JSONObject();
        S.ringOf.forEach(ringOf::put);
        jo.put("ringOf", ringOf);
        JSONObject names = new JSONObject();
        S.colourName.forEach(names::put);
        jo.put("colourNames", S.colourNames).put("colourName", names);
        return jo;
    }

    /**
     * Restores {@link #toJson()} against the layers a session just created. Layer ids carry over
     * (the session restores them), so the left-out set and the ring choices apply by id; a missing
     * object leaves the pizza off.
     */
    public static synchronized void fromJson(@Nullable JSONObject jo, List<? extends Layer> layers) {
        S.excluded.clear();
        S.ringOf.clear();
        S.colourName.clear();
        if (jo == null) {
            S.shape = Shape.Off;
            S.spin = Spin.Off;
            syncSpin();
            changed();
            return;
        }
        S.shape = parse(Shape.class, jo.optString("shape"), Shape.Off);
        S.space = parse(Space.class, jo.optString("space"), Space.Sun);
        S.twoRings = jo.optBoolean("twoRings", false);
        S.boundary = Math.clamp(jo.optDouble("boundary", 1.15), 0.1, 30);
        S.outerRadius = Math.max(0, jo.optDouble("outerRadius", 0));
        S.gap = Math.clamp(jo.optDouble("gap", 0), 0, 45);
        S.jitter = Math.clamp(jo.optDouble("jitter", 0), 0, .9);
        S.seed = Math.clamp(jo.optInt("seed", 0), 0, 999);
        S.tile = Math.clamp(jo.optDouble("tile", .5), 0.02, 20);
        S.rows = Math.clamp(jo.optInt("rows", 6), 1, 64);
        S.shift = Math.clamp(jo.optInt("shift", 1), 0, 64);
        S.turn = wrap(jo.optDouble("turn", 0));
        S.spin = parse(Spin.class, jo.optString("spin"), Spin.Off);
        S.degPerSec = Math.clamp(jo.optDouble("degPerSec", 6), -360, 360);
        S.degPerFrame = Math.clamp(jo.optDouble("degPerFrame", 2), -180, 180);
        S.labels = parse(Labels.class, jo.optString("labels"), Labels.Off);
        S.angstrom = jo.optBoolean("angstrom", false);
        S.colourNames = jo.optBoolean("colourNames", false);
        S.labelScale = Math.clamp(jo.optDouble("labelScale", 1), .5, 3);
        JSONArray rings = jo.optJSONArray("rings");
        for (int i = 0; i < S.rings.length; i++) {
            JSONObject r = rings == null ? null : rings.optJSONObject(i);
            S.rings[i].repeats = r == null ? 1 : Math.clamp(r.optInt("repeats", 1), 1, MAX_REPEATS);
            S.rings[i].angle = r == null ? 0 : wrap(r.optDouble("angle", 0));
            S.rings[i].direction = r != null && r.optBoolean("counter", false) ? -1 : 1;
        }
        Set<String> ids = new java.util.HashSet<>();
        for (Layer l : layers)
            if (l instanceof ImageLayer il)
                ids.add(il.getId());
        JSONArray excluded = jo.optJSONArray("excluded");
        if (excluded != null)
            for (int i = 0; i < excluded.length(); i++)
                if (ids.contains(excluded.optString(i)))
                    S.excluded.add(excluded.optString(i));
        JSONObject ringOf = jo.optJSONObject("ringOf");
        if (ringOf != null)
            for (String id : ringOf.keySet())
                if (ids.contains(id))
                    S.ringOf.put(id, ringOf.optInt(id) == OUTER ? OUTER : INNER);
        JSONObject names = jo.optJSONObject("colourName");
        if (names != null)
            for (String id : names.keySet())
                if (ids.contains(id) && !names.optString(id).isBlank())
                    S.colourName.put(id, names.optString(id));
        syncSpin();
        changed();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) { // a value from a newer build, or absent
            return fallback;
        }
    }

    /** One line for the processing record: what was cut and how. Null while off. */
    @Nullable
    public static synchronized JSONObject recordStep() {
        JSONObject jo = toJson();
        if (jo == null)
            return null;
        JSONArray order = new JSONArray();
        for (int ring = INNER; ring <= OUTER; ring++)
            for (Slot s : slots(ring))
                order.put(new JSONObject().put("ring", ring).put("slot", s.index()).put("layer", s.layer().getId())
                        .put("name", s.layer().getName()));
        return jo.put("slots", order).put("loopDegrees", loopDegrees());
    }
}
