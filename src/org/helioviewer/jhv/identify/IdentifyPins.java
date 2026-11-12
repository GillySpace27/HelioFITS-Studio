package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.opengl.GLText;

import org.json.JSONArray;

/**
 * Labels pinned to bodies: Alt-click on a named body pins it, Alt-click on it again unpins it, and
 * View > Find Body... pins the body it finds. A pinned label follows its body through time and
 * every view mode, and is saved with the session.
 *
 * <p>Where a body is drawn comes from {@link SkyScreen#place}, the forward map of each view mode,
 * which checks itself against the probe the footer uses (the probe at the placed pixel must give
 * the body's sight line back). So a pin sits exactly where hovering would name the body, in every
 * mode the identifier supports, at the cost of a few arithmetic steps and no solving.
 */
public final class IdentifyPins {

    private static final List<String> pins = new CopyOnWriteArrayList<>();
    private static final int MAX_STEPS = 20;
    private static final double CONVERGED_PIXELS = 0.05;

    /** The pinned bodies, in the order they were pinned. */
    public static List<String> all() {
        return List.copyOf(pins);
    }

    public static boolean isPinned(String body) {
        return pins.contains(body);
    }

    /** Pins a body that is not pinned, unpins one that is; returns whether it is pinned now. */
    public static boolean toggle(String body) {
        boolean now;
        if (pins.remove(body))
            now = false;
        else {
            pins.add(body);
            now = true;
        }
        DisplayController.display();
        return now;
    }

    public static void pin(String body) {
        if (!pins.contains(body)) {
            pins.add(body);
            DisplayController.display();
        }
    }

    /** Replaces the pins, keeping only names the identifier knows. */
    public static void set(Collection<String> bodies) {
        pins.clear();
        for (String b : bodies)
            if (Bodies.byName(b) != null && !pins.contains(b))
                pins.add(b);
        DisplayController.display();
    }

    public static JSONArray toJson() {
        return new JSONArray(pins);
    }

    public static void fromJson(@Nullable JSONArray ja) {
        List<String> names = new ArrayList<>();
        if (ja != null)
            for (int i = 0; i < ja.length(); i++)
                names.add(ja.optString(i));
        set(names);
    }

    /**
     * Alt-click: toggles a pin on the nearest body the identifier names at (x, y). Returns the body's
     * name, or null when nothing is named there.
     */
    @Nullable
    public static String toggleAt(Viewport vp, MapView mv, int x, int y) {
        List<Identifier.Match> matches = IdentifyHover.identify(vp, mv, x, y, Identifier.Options.fromSettings());
        if (matches.isEmpty())
            return null;
        String name = matches.getFirst().body().name();
        toggle(name);
        return name;
    }

    /** Screen to sky: the helioprojective direction (radians) the probe looks along at canvas point (x, y), or null. */
    interface Sky {
        @Nullable
        Vec2 at(double x, double y);
    }

    /**
     * The canvas point (x, y) at which {@code target}'s observer sees the sight line {@code want}
     * drawn, or null when it is off the page, hidden (behind the Sun in the orthographic view) or
     * in a mode with no sky. The forward map ({@link SkyScreen#place}), closed form in every mode;
     * the Newton solver below is kept as the cross-check, not used to draw.
     */
    @Nullable
    static double[] locate(Viewport vp, MapView mv, Identifier.Target target, Vec3 want) {
        double[] p = SkyScreen.place(mv, vp, target.observer(), want);
        return p != null && p[0] >= vp.x && p[0] <= vp.x + vp.width && p[1] >= vp.yAWT && p[1] <= vp.yAWT + vp.height ? p : null;
    }

    /** As {@link #locate(Viewport, MapView, Identifier.Target, Vec3)}; a start point is no longer needed and is ignored. */
    @Nullable
    static double[] locate(Viewport vp, MapView mv, Identifier.Target target, Vec3 want, @Nullable double[] seed) {
        return locate(vp, mv, target, want);
    }

    /**
     * Newton's method on the probe, from {@code seed} (canvas pixels) when given: the old way to find
     * {@link #locate}'s answer, for a check to compare the forward map with. Never used to draw.
     */
    @Nullable
    static double[] locateByProbe(Viewport vp, MapView mv, Identifier.Target target, Vec3 want, @Nullable double[] seed) {
        // The probe reads whole pixels, so interpolate between the four around (x, y): a smooth function Newton can settle on
        Sky sky = (x, y) -> {
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
            double u = x - ix, w = y - iy;
            Vec2 a = pixel(mv, vp, target, ix, iy), b = pixel(mv, vp, target, ix + 1, iy);
            Vec2 c = pixel(mv, vp, target, ix, iy + 1), d = pixel(mv, vp, target, ix + 1, iy + 1);
            if (a == null || b == null || c == null || d == null)
                return null;
            return new Vec2((a.x * (1 - u) + b.x * u) * (1 - w) + (c.x * (1 - u) + d.x * u) * w,
                    (a.y * (1 - u) + b.y * u) * (1 - w) + (c.y * (1 - u) + d.y * u) * w);
        };
        Vec2 goal = Identifier.helioprojective(want);
        double[] found = solve(sky, goal, vp.x, vp.yAWT, vp.width, vp.height, seed);
        if (found != null || seed != null)
            return found;
        // The middle of a polar page is singular (the Sun in Helioradial), so try other starts before giving up
        for (double[] f : new double[][]{{.25, .25}, {.75, .25}, {.25, .75}, {.75, .75}}) {
            found = solve(sky, goal, vp.x, vp.yAWT, vp.width, vp.height, new double[]{vp.x + f[0] * vp.width, vp.yAWT + f[1] * vp.height});
            if (found != null)
                return found;
        }
        return null;
    }

    @Nullable
    private static Vec2 pixel(MapView mv, Viewport vp, Identifier.Target target, int x, int y) {
        Identifier.Probe p = Probes.at(mv, vp, x, y);
        Vec3 dir = p == null ? null : p.direction(target.observer());
        return dir == null ? null : Identifier.helioprojective(dir);
    }

    /** Newton's method on the screen: the point in the box (x0, y0, w, h) where {@code sky} reads {@code goal}. */
    @Nullable
    static double[] solve(Sky sky, Vec2 goal, double x0, double y0, double w, double h) {
        return solve(sky, goal, x0, y0, w, h, null);
    }

    /** As above, starting at {@code seed} instead of the middle of the box when one is given. */
    @Nullable
    static double[] solve(Sky sky, Vec2 goal, double x0, double y0, double w, double h, @Nullable double[] seed) {
        double x = seed == null ? x0 + w / 2 : seed[0], y = seed == null ? y0 + h / 2 : seed[1];
        double limit = Math.max(w, h);
        for (int step = 0; step < MAX_STEPS; step++) {
            Vec2 f = sky.at(x, y), fx = sky.at(x + 1, y), fy = sky.at(x, y + 1);
            if (f == null || fx == null || fy == null)
                return null;
            double a = fx.x - f.x, b = fy.x - f.x, c = fx.y - f.y, d = fy.y - f.y; // per pixel
            double det = a * d - b * c;
            if (det == 0 || !Double.isFinite(det))
                return null;
            double ex = goal.x - f.x, ey = goal.y - f.y;
            double dx = (d * ex - b * ey) / det, dy = (-c * ex + a * ey) / det;
            double len = Math.hypot(dx, dy);
            if (len > limit) { // a damped step: the probe is not linear across the page
                dx *= limit / len;
                dy *= limit / len;
            }
            x += dx;
            y += dy;
            if (len < CONVERGED_PIXELS)
                return x >= x0 && x <= x0 + w && y >= y0 && y <= y0 + h ? new double[]{x, y} : null;
        }
        return null;
    }

    /** Draws each pinned body's label at its body, in the screen pass that recordings never capture. */
    public static void render(Viewport full) {
        if (pins.isEmpty() || ExportMovie.isRecording())
            return;
        Viewport vp = Display.getActiveViewport(); // the scene the probes look through, as for the footer
        MapView mv = GLRenderer.getMapView();
        List<Identifier.Target> targets = IdentifyHover.targets(vp, mv);
        if (targets.isEmpty())
            return;
        Identifier.Target target = targets.getFirst();
        boolean light = IdentifySettings.lightTime();
        for (String name : pins) {
            Bodies.Body body = Bodies.byName(name);
            if (body == null)
                continue;
            Vec3 dir = Identifier.bodyDirection(body, target.observer(), light);
            if (dir == null)
                continue;
            double[] p = locate(vp, mv, target, dir);
            if (p == null)
                continue;
            // The point is in canvas pixels, like the pointer; the text is placed in the full viewport
            GLText.drawTextFloat(full, List.of("+ " + name), (int) Math.round(p[0]) - full.x - 5, (int) Math.round(p[1]) - full.yAWT + 4); // puts the "+" on the point (measured)
        }
    }

    private IdentifyPins() {}
}
