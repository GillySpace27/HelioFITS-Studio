package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.thread.EDTTimer;

/**
 * Morphs the coronagraph surface between plane of sky and the Thomson sphere instead of switching.
 *
 * <p>This is a geometry morph, not a crossfade, which is why it is separate from
 * {@link ProjectionTransition}: nothing is drawn twice and nothing is blended. The surface model
 * was already a float uniform the vertex stage compared against 1, so making it a continuous
 * blend in [0, 1] turns the same expression into a morph: {@code depth = blend * r^2 / D} carries
 * every vertex from flat to curved along its own line of sight.
 *
 * <p>That the geometry falls out of one number is worth stating, because it is what makes the
 * animation honest rather than decorative. Every intermediate frame is a real surface, the one
 * where the brightness is placed a fraction of the way toward the Thomson depth, and the imagery
 * is sampled against it. It is not two pictures dissolved into each other.
 *
 * <p>The crop radius is carried along with it, from the full field at 0 to the observer distance
 * at 1. Snapping that at either end would put a hard pop into an otherwise smooth movement, since
 * the Thomson model has no surface past r = D and the outer field has to go.
 */
public final class SurfaceTransition {

    // Long enough to read as a movement rather than a jump, short enough not to be in the way of
    // someone comparing the two surfaces by switching back and forth.
    private static final int DURATION_MS = 420;

    private static boolean animateEnabled = !"false".equals(Settings.getProperty("display.animateSurfaceChanges"));

    /** Where the morph is now: 0 is plane of sky, 1 is the Thomson sphere. */
    private static double blend = SurfaceModel.PlaneOfSky == Display.getSurfaceModel() ? 0 : 1;
    private static double from;
    private static double to;
    private static long startMilli;
    private static boolean active;

    private static final EDTTimer driver = new EDTTimer(16, SurfaceTransition::step);

    public static boolean isAnimateEnabled() {
        return animateEnabled;
    }

    public static void setAnimateEnabled(boolean enabled) {
        if (animateEnabled == enabled)
            return;
        animateEnabled = enabled;
        Settings.setProperty("display.animateSurfaceChanges", Boolean.toString(enabled));
    }

    /** The blend the renderer should draw with. */
    public static double blend() {
        return blend;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Begin moving toward a model. Called from {@link Display#setSurfaceModel}, so every route to
     * the setting animates: the palette, a restored session, and the exclusivity rule that drops
     * back to plane of sky when the viewpoint moves inside the field.
     */
    public static void requestSurface(SurfaceModel target) {
        double want = target == SurfaceModel.ThomsonSphere ? 1 : 0;
        if (!animateEnabled) {
            blend = want;
            active = false;
            driver.stop();
            DisplayController.display();
            return;
        }
        if (want == to && active)
            return; // already on the way there
        if (want == blend) {
            active = false;
            driver.stop();
            return;
        }
        from = blend;
        to = want;
        startMilli = System.currentTimeMillis();
        active = true;
        driver.start();
    }

    private static void step() {
        if (!active) {
            driver.stop();
            return;
        }
        double t = Math.clamp((System.currentTimeMillis() - startMilli) / (double) DURATION_MS, 0, 1);
        // Smoothstep: the surface leaves and arrives at rest, so the eye reads one movement rather
        // than a start and a stop.
        blend = from + (to - from) * (t * t * (3 - 2 * t));
        if (t >= 1) {
            blend = to;
            active = false;
            driver.stop();
        }
        DisplayController.display();
    }

    private SurfaceTransition() {}
}
