package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.Transform;

/**
 * A soft glow along the outline of the body the identifier names under the pointer, and a short
 * pulse outward from it when the pointer reaches something new. Both are switches in the Identify
 * panel, on by default.
 *
 * <p>The outline is the body's own perimeter, not a circle drawn round a point: the cone of sight
 * lines that grazes the body's limb, as seen by the observer of the layer that named it, put on the
 * screen by the identifier run backwards ({@link IdentifyPins#locate}). So in every view the
 * identifier supports it follows the projection the imagery is drawn in: round in the orthographic
 * view, stretched where a helioradial or unrolled view stretches the sky. A body smaller than a
 * few pixels keeps that shape, enlarged about its centre until the glow can be seen.
 *
 * <p>The Sun is left out: on the disk it is under the pointer all the time, and a ring round the
 * whole Sun at every move would hide what the glow is for (judgement). Drawn objects (models,
 * the orbit plot, catalog events, stars) are not outlined yet; the event layer already highlights
 * the event under the pointer.
 *
 * <p>Screen pass only, like the label beside the pointer: never in a grab, an export or a recording.
 */
public final class IdentifyGlow {

    /** Points round each outline: smooth at any size a body reaches on screen (judgement). */
    static final int POINTS = 96;
    /** The smallest outline radius, in screen points, so a planet a pixel across still glows visibly (judgement). */
    static final double MIN_RADIUS = 7;
    /** How long the pulse runs, and how far it travels out from the outline in screen points (judgement). */
    static final long PULSE_MS = 650;
    static final double PULSE_TRAVEL = 16;
    /** A soft blue-white that reads on dark imagery and on the white of a coronagraph's occulter edge (judgement). */
    private static final int[] RGB = {150, 215, 255};
    /** Outlines broken where a projection's seam would draw a chord across the page. */
    private static final double SEAM_FRACTION = 0.25;

    /** The halo, the middle and the core of the glow, from wide and faint to thin and bright, then the pulse. */
    private static final double[] WIDTHS = {9, 4.5, 1.6};
    private static final int[] ALPHAS = {38, 70, 200};

    private static final class State {
        String key = "";
        long pulseStart;
    }

    private static final State STATE = new State();
    private static final GLSLLine[] LINES = {new GLSLLine(true), new GLSLLine(true), new GLSLLine(true), new GLSLLine(true)};
    private static final BufVertex BUF = new BufVertex(4096);
    private static final Timer TICK = new Timer(33, e -> {
        if (!pulsing())
            ((Timer) e.getSource()).stop();
        DisplayController.display();
    });

    public static void init() {
        for (GLSLLine l : LINES)
            l.init();
    }

    public static void dispose() {
        for (GLSLLine l : LINES)
            l.dispose();
    }

    /** Each identification: start the pulse and redraw when what is named changes. */
    static void matched(List<Identifier.Match> matches) {
        String key = key(matches);
        if (key.equals(STATE.key))
            return;
        STATE.key = key;
        if (!key.isEmpty() && IdentifySettings.pulse()) {
            STATE.pulseStart = System.currentTimeMillis();
            TICK.start();
        }
        if (IdentifySettings.glow() || IdentifySettings.pulse())
            DisplayController.display();
    }

    /** The bodies that get an outline, as one string, so a change of body can be told from a move over the same one. */
    static String key(List<Identifier.Match> matches) {
        StringBuilder sb = new StringBuilder();
        for (Identifier.Match m : outlined(matches))
            sb.append(m.body().name()).append('/').append(m.layer()).append(';');
        return sb.toString();
    }

    static List<Identifier.Match> outlined(List<Identifier.Match> matches) {
        List<Identifier.Match> out = new ArrayList<>();
        for (Identifier.Match m : matches)
            if (!m.body().name().equals("Sun"))
                out.add(m);
        return out;
    }

    /** 0 at the start of the pulse, 1 at its end; above 1 when there is none. */
    static double pulsePhase(long now, long start) {
        return start == 0 ? 2 : (now - start) / (double) PULSE_MS;
    }

    private static boolean pulsing() {
        return IdentifySettings.pulse() && pulsePhase(System.currentTimeMillis(), STATE.pulseStart) < 1;
    }

    /**
     * The limb of a body seen along unit sight line {@code centre} at angular radius {@code angR}
     * (radians): {@code n} unit sight lines, each that angle from the centre, once round.
     */
    static List<Vec3> limb(Vec3 centre, double angR, int n) {
        Vec3 c = Identifier.unit(centre);
        // Any axis not along the centre, then two directions square to it and to each other
        Vec3 a = Math.abs(c.x) < 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = Identifier.unit(Vec3.cross(c, a));
        Vec3 v = Vec3.cross(c, u);
        double ca = Math.cos(angR), sa = Math.sin(angR);
        List<Vec3> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            double ct = Math.cos(t), st = Math.sin(t);
            out.add(new Vec3(ca * c.x + sa * (ct * u.x + st * v.x),
                    ca * c.y + sa * (ct * u.y + st * v.y),
                    ca * c.z + sa * (ct * u.z + st * v.z)));
        }
        return out;
    }

    /**
     * Points about {@code centre} pushed out so their mean distance from it is at least
     * {@code minRadius}, keeping the shape; null entries (off the page) stay null.
     */
    static List<double[]> atLeast(List<double[]> points, double[] centre, double minRadius) {
        double sum = 0;
        int count = 0;
        for (double[] p : points)
            if (p != null) {
                sum += Math.hypot(p[0] - centre[0], p[1] - centre[1]);
                count++;
            }
        double mean = count == 0 ? 0 : sum / count;
        if (count == 0 || mean >= minRadius)
            return points;
        List<double[]> out = new ArrayList<>(points.size());
        for (double[] p : points) {
            if (p == null) {
                out.add(null);
                continue;
            }
            double dx = p[0] - centre[0], dy = p[1] - centre[1];
            double r = Math.hypot(dx, dy);
            // A body under a pixel: its limb collapses on the centre, so lay the points on the circle
            if (mean < 1e-3 || r < 1e-6) {
                double t = 2 * Math.PI * out.size() / points.size();
                out.add(new double[]{centre[0] + minRadius * Math.cos(t), centre[1] + minRadius * Math.sin(t)});
            } else {
                double f = minRadius / mean;
                out.add(new double[]{centre[0] + dx * f, centre[1] + dy * f});
            }
        }
        return out;
    }

    /** {@code points} pushed out from {@code centre} by {@code by}, along each point's own radius. */
    static List<double[]> grown(List<double[]> points, double[] centre, double by) {
        List<double[]> out = new ArrayList<>(points.size());
        for (double[] p : points) {
            if (p == null) {
                out.add(null);
                continue;
            }
            double dx = p[0] - centre[0], dy = p[1] - centre[1];
            double r = Math.hypot(dx, dy);
            out.add(r < 1e-9 ? p : new double[]{p[0] + dx / r * by, p[1] + dy / r * by});
        }
        return out;
    }

    /**
     * One body's outline on the canvas, in canvas pixels, with its centre first; null when its
     * centre is not on the page. Each limb point starts Newton's method from the one before, so
     * the ring costs a few steps a point.
     */
    @Nullable
    static List<double[]> outline(Viewport vp, MapView mv, Identifier.Target target, Vec3 centreDir, double angR) {
        double[] centre = IdentifyPins.locate(vp, mv, target, centreDir, null);
        if (centre == null)
            return null;
        List<double[]> out = new ArrayList<>(POINTS + 1);
        out.add(centre);
        double[] seed = centre;
        for (Vec3 d : limb(centreDir, angR, POINTS)) {
            double[] p = IdentifyPins.locate(vp, mv, target, d, seed);
            out.add(p);
            if (p != null)
                seed = p;
        }
        return out;
    }

    /** Draws the glow and the pulse, in the screen pass that recordings never capture. */
    public static void render(Viewport full) {
        if (!IdentifySettings.enabled() || ExportMovie.isRecording())
            return;
        double phase = pulsePhase(System.currentTimeMillis(), STATE.pulseStart);
        boolean pulse = IdentifySettings.pulse() && phase < 1;
        if (!IdentifySettings.glow() && !pulse)
            return;
        List<Identifier.Match> matches = outlined(IdentifyHover.lastMatches());
        if (matches.isEmpty())
            return;

        Viewport vp = Display.getActiveViewport(); // the scene the probes look through, as for the footer and pins
        MapView mv = GLRenderer.getMapView();
        List<Identifier.Target> targets = IdentifyHover.targets(vp, mv);
        double scale = Display.pixelScale[1];
        boolean light = IdentifySettings.lightTime();

        List<List<double[]>> rings = new ArrayList<>();
        List<double[]> centres = new ArrayList<>();
        for (Identifier.Match m : matches) {
            Identifier.Target target = byName(targets, m.layer());
            if (target == null)
                continue;
            Vec3 dir = Identifier.bodyDirection(m.body(), target.observer(), light);
            if (dir == null)
                continue;
            List<double[]> o = outline(vp, mv, target, dir, Math.toRadians(m.angularRadius()));
            if (o == null)
                continue;
            double[] c = o.getFirst();
            centres.add(c);
            rings.add(atLeast(o.subList(1, o.size()), c, MIN_RADIUS * scale));
        }
        if (rings.isEmpty())
            return;

        // Bright for a moment when something new is reached, then steady
        double boost = pulse ? 1 + 0.6 * (1 - phase) : 1;
        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, full.width, 0, full.height);
        Transform.pushView();
        Transform.setIdentityView();
        try {
            if (IdentifySettings.glow())
                for (int pass = 0; pass < WIDTHS.length; pass++)
                    draw(LINES[pass], full, rings, null, 0, (int) Math.min(255, ALPHAS[pass] * boost), WIDTHS[pass] * scale);
            if (pulse) {
                double eased = 1 - Math.pow(1 - phase, 3);
                int alpha = (int) (190 * Math.pow(1 - phase, 2));
                draw(LINES[3], full, rings, centres, PULSE_TRAVEL * scale * eased, alpha, (2.5 - 1.5 * phase) * scale);
            }
        } finally {
            Transform.popView();
            Transform.popProjection();
        }
    }

    private static void draw(GLSLLine line, Viewport full, List<List<double[]>> rings, @Nullable List<double[]> centres,
                             double grow, int alpha, double widthPx) {
        if (alpha <= 0)
            return;
        byte[] col = {(byte) RGB[0], (byte) RGB[1], (byte) RGB[2], (byte) alpha};
        BUF.clear();
        double seam = SEAM_FRACTION * Math.max(full.width, full.height);
        for (int i = 0; i < rings.size(); i++) {
            List<double[]> ring = centres == null ? rings.get(i) : grown(rings.get(i), centres.get(i), grow);
            putRing(full, ring, col, seam);
        }
        line.upload(BUF);
        if (full.height > 0)
            line.renderLine(full, widthPx / full.height);
    }

    /** One closed ring as polyline runs, broken at points off the page and at seam-sized jumps. */
    private static void putRing(Viewport full, List<double[]> ring, byte[] col, double seam) {
        int n = ring.size();
        // Start just after a gap if there is one, so a broken ring is not joined across it
        int start = 0;
        boolean closed = true;
        for (int i = 0; i < n; i++)
            if (ring.get(i) == null) {
                start = (i + 1) % n;
                closed = false;
                break;
            }
        double[] prev = null;
        boolean open = false;
        for (int k = 0; k < (closed ? n + 1 : n); k++) {
            double[] p = ring.get((start + k) % n);
            if (p == null || (prev != null && Math.hypot(p[0] - prev[0], p[1] - prev[1]) > seam)) {
                if (open)
                    BUF.endLine();
                open = false;
                prev = p;
                if (p == null)
                    continue;
            }
            // Canvas pixels (AWT, y down) to the full viewport's GL frame (y up)
            float x = (float) (p[0] - full.x), y = (float) (full.height - (p[1] - full.yAWT));
            if (open)
                BUF.putVertex(x, y, 0, 1, col);
            else {
                BUF.startLine(x, y, 0, 1, col);
                open = true;
            }
            prev = p;
        }
        if (open)
            BUF.endLine();
    }

    @Nullable
    private static Identifier.Target byName(List<Identifier.Target> targets, String name) {
        for (Identifier.Target t : targets)
            if (t.name().equals(name))
                return t;
        return null;
    }

    private IdentifyGlow() {}
}
