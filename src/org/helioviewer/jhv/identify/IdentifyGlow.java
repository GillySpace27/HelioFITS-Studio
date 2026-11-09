package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** The most outlines drawn at once for Highlight All: past it the page is a wall of rings (judgement). */
    static final int MAX_RINGS = 400;
    /** A body this small on the sky (0.2 arcsec) is a point: its ring is the minimum circle, with no limb to solve for. */
    private static final double POINT_RADIUS = 1e-6;
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
    static void matched(List<Identifier.Match> matches, List<Drawn.Hit> drawn) {
        String key = key(matches, drawn);
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

    /** As {@link #key(List)}, with the drawn objects that carry an icon: a star, a comet, a cluster. */
    static String key(List<Identifier.Match> matches, List<Drawn.Hit> drawn) {
        StringBuilder sb = new StringBuilder(key(matches));
        for (Drawn.Hit h : drawn)
            if (h.mark() != null)
                sb.append(h.mark().key()).append(';');
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
    static List<double[]> outline(Viewport vp, MapView mv, Identifier.Target target, Vec3 centreDir, double angR, @Nullable double[] start) {
        double[] centre = IdentifyPins.locate(vp, mv, target, centreDir, start);
        if (centre == null)
            return null;
        List<double[]> out = new ArrayList<>(POINTS + 1);
        out.add(centre);
        if (angR < POINT_RADIUS) { // a point: atLeast lays the minimum circle about it
            for (int i = 0; i < POINTS; i++)
                out.add(centre);
            return out;
        }
        double[] seed = centre;
        for (Vec3 d : limb(centreDir, angR, POINTS)) {
            double[] p = IdentifyPins.locate(vp, mv, target, d, seed);
            out.add(p);
            if (p != null)
                seed = p;
        }
        return out;
    }

    /** One outline on the page: its colour, its points, its centre, and whether the hover pulse runs on it. */
    private record Ring(int[] rgb, List<double[]> points, double[] centre, boolean hover) {}

    /** The hover's marks: the bodies named under the pointer (not the Sun) and the drawn objects that carry an icon. */
    static List<Mark> hoverMarks() {
        boolean light = IdentifySettings.lightTime();
        List<Mark> out = new ArrayList<>();
        for (Identifier.Match m : outlined(IdentifyHover.lastMatches()))
            out.add(bodyMark(m.body(), m.layer(), Math.toRadians(m.angularRadius()), light));
        for (Drawn.Hit h : IdentifyHover.lastDrawn())
            if (h.mark() != null)
                out.add(h.mark());
        return out;
    }

    static Mark bodyMark(Bodies.Body body, String layer, double angR, boolean light) {
        return new Mark(body.name() + '/' + layer, Kind.of(body), layer, observer -> Identifier.bodyDirection(body, observer, light), angR);
    }

    /** Every named thing the view holds: the bodies the imagery shows, named stars and the switched-on datasets. */
    static List<Mark> allMarks(Viewport vp, MapView mv, List<Identifier.Target> targets) {
        Identifier.Options options = Identifier.Options.fromSettings();
        Set<String> seen = new LinkedHashSet<>();
        List<Mark> out = new ArrayList<>();
        for (Identifier.Target t : targets)
            for (Identifier.Match m : Identifier.identifyOne(t, new Vec3(0, 0, -1), Math.PI, options)) // tolerance of pi: every body, with its qualifier
                if (m.qualifier() == Identifier.Qualifier.NONE && seen.add(m.body().name()))
                    out.add(bodyMark(m.body(), m.layer(), Math.toRadians(m.angularRadius()), options.lightTime()));
        out.addAll(Drawn.allMarks(vp, mv, targets.getFirst()));
        return out;
    }

    /** Draws the glow and the pulse, in the screen pass that recordings never capture. */
    public static void render(Viewport full) {
        if (!IdentifySettings.enabled() || ExportMovie.isRecording())
            return;
        double phase = pulsePhase(System.currentTimeMillis(), STATE.pulseStart);
        boolean pulse = IdentifySettings.pulse() && phase < 1;
        boolean all = IdentifySettings.highlightAll();
        if (!IdentifySettings.glow() && !pulse && !all)
            return;
        Viewport vp = Display.getActiveViewport(); // the scene the probes look through, as for the footer and pins
        MapView mv = GLRenderer.getMapView();
        List<Identifier.Target> targets = IdentifyHover.targets(vp, mv);
        double scale = Display.pixelScale[1];

        Map<String, Mark> marks = new LinkedHashMap<>();
        Set<String> hovered = new LinkedHashSet<>();
        for (Mark m : hoverMarks()) {
            marks.put(m.key(), m);
            hovered.add(m.key());
        }
        if (all)
            for (Mark m : allMarks(vp, mv, targets))
                marks.putIfAbsent(m.key(), m);
        if (marks.isEmpty())
            return;

        double[] pointer = IdentifyHover.pointer();
        List<Ring> rings = new ArrayList<>();
        for (Mark m : marks.values()) {
            if (rings.size() >= MAX_RINGS)
                break;
            boolean hover = hovered.contains(m.key());
            if (!hover && !all)
                continue;
            Identifier.Target target = m.layer() == null ? targets.getFirst() : byName(targets, m.layer());
            if (target == null)
                continue;
            Vec3 dir = m.where().direction(target.observer());
            if (dir == null)
                continue;
            List<double[]> o = outline(vp, mv, target, dir, m.angularRadius(), hover ? pointer : null);
            if (o == null)
                continue;
            double[] c = o.getFirst();
            rings.add(new Ring(IdentifySettings.iconRgb(m.kind()), atLeast(o.subList(1, o.size()), c, MIN_RADIUS * scale), c, hover));
        }
        if (rings.isEmpty())
            return;

        // Bright for a moment when something new is reached, then steady
        double boost = pulse ? 1 + 0.6 * (1 - phase) : 1;
        Map<Integer, List<Ring>> byColour = new LinkedHashMap<>();
        for (Ring r : rings)
            byColour.computeIfAbsent(r.rgb()[0] << 16 | r.rgb()[1] << 8 | r.rgb()[2], k -> new ArrayList<>()).add(r);
        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, full.width, 0, full.height);
        Transform.pushView();
        Transform.setIdentityView();
        try {
            for (List<Ring> group : byColour.values()) {
                int[] rgb = group.getFirst().rgb();
                for (int pass = 0; pass < WIDTHS.length; pass++)
                    if (IdentifySettings.glow() || all)
                        draw(LINES[pass], full, group, false, 0, rgb, (int) Math.min(255, ALPHAS[pass] * boost), WIDTHS[pass] * scale);
                if (pulse) {
                    double eased = 1 - Math.pow(1 - phase, 3);
                    int alpha = (int) (190 * Math.pow(1 - phase, 2));
                    draw(LINES[3], full, group, true, PULSE_TRAVEL * scale * eased, rgb, alpha, (2.5 - 1.5 * phase) * scale);
                }
            }
        } finally {
            Transform.popView();
            Transform.popProjection();
        }
    }

    private static void draw(GLSLLine line, Viewport full, List<Ring> rings, boolean pulseOnly, double grow, int[] rgb, int alpha, double widthPx) {
        if (alpha <= 0)
            return;
        byte[] col = {(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) alpha};
        BUF.clear();
        double seam = SEAM_FRACTION * Math.max(full.width, full.height);
        for (Ring r : rings) {
            if (pulseOnly && !r.hover())
                continue;
            putRing(full, pulseOnly ? grown(r.points(), r.centre(), grow) : r.points(), col, seam);
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
