package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.thread.EDTTimer;

/**
 * Drives the crossfade when the projection changes: a snapshot of the outgoing scene fades
 * from opaque to transparent over the new one. Pure timing and state -- the actual GL capture
 * and blit live in GLRenderer, which is the only place a GL call is proven safe to make (every
 * GL call this app makes already runs from inside GLRenderer.display(), never from a button's
 * action listener directly, and the rendering canvas keeps its context current on the EDT with
 * no per-call make-current/release bracketing to piggyback on).
 *
 * <p>That constraint is why the switch is a two-step request/apply rather than immediate:
 * {@link #requestSwitch} (called from ViewState, on the EDT, before anything about the scene
 * changes) only records what to switch to and asks for a repaint; {@link #applyPendingSwitch}
 * (called from GLRenderer.display(), at the top, before that frame's own mapView is rebuilt)
 * captures the still-current outgoing scene, then flips Display.mode, then starts the fade.
 * The one-frame delay between a menu click and the GL-side switch is invisible: UI feedback
 * (the radio button, slider enablement) reads ViewState.getProjection(), already updated
 * synchronously in requestSwitch's caller.
 *
 * <p>A pure crossfade -- unlike a geometric morph between projections, deliberately not built
 * -- contains no motion: nothing moves or distorts, only alpha changes. That is the one
 * animation style generally considered safe for vestibular motion sensitivity, which is why it
 * defaults on; {@link #setAnimateEnabled} is the escape hatch for anyone it still bothers.
 */
public final class ProjectionTransition {

    private static final int DURATION_MS = 280;

    private static boolean animateEnabled = !"false".equals(Settings.getProperty("display.animateProjectionChanges"));

    private static MapMode pendingMode;
    private static boolean pendingPreserveDiskSize;

    private static boolean active;
    private static long startMilli;
    private static final EDTTimer driver = new EDTTimer(16, ProjectionTransition::step);

    public static boolean isAnimateEnabled() {
        return animateEnabled;
    }

    public static void setAnimateEnabled(boolean enabled) {
        if (animateEnabled == enabled)
            return;
        animateEnabled = enabled;
        Settings.setProperty("display.animateProjectionChanges", Boolean.toString(enabled));
    }

    /** Called from ViewState.setProjection for an interactive switch (never for session restore,
     *  which applies instantly and deterministically like every other restored setting). */
    public static void requestSwitch(MapMode newMode, boolean preserveDiskSize) {
        if (!animateEnabled) {
            Display.setMapMode(newMode, preserveDiskSize);
            return;
        }
        pendingMode = newMode;
        pendingPreserveDiskSize = preserveDiskSize;
        DisplayController.display();
    }

    public static boolean hasPendingSwitch() {
        return pendingMode != null;
    }

    /**
     * Capture the outgoing scene (via the given callback, which does the actual GL work while
     * the old Display.mode and mapView are still live), then flip the projection and start the
     * fade. Called once, from the top of GLRenderer.display(), before that frame rebuilds its
     * own mapView -- capturing any later would already be looking at the new projection.
     */
    public static void applyPendingSwitch(Runnable captureOutgoingScene) {
        MapMode newMode = pendingMode;
        boolean preserve = pendingPreserveDiskSize;
        pendingMode = null;
        if (newMode == null)
            return;

        captureOutgoingScene.run();
        Display.setMapMode(newMode, preserve);
        active = true;
        startMilli = System.currentTimeMillis();
        driver.start();
    }

    public static boolean isActive() {
        return active;
    }

    public static double fadeAlpha() {
        return easedFade(System.currentTimeMillis() - startMilli, DURATION_MS);
    }

    // Eased 1 -> 0 over the duration (smoothstep, so the fade has no velocity kink at either
    // end) rather than the raw linear ramp. A pure function of its arguments so the curve
    // itself -- easy to get backwards with a stray sign or clamp -- can be checked without the
    // rest of the app (ProjectionTransitionCheck), unlike fadeAlpha() itself, which reads live
    // timer state.
    static double easedFade(double elapsedMs, double durationMs) {
        double t = Math.clamp(elapsedMs / durationMs, 0, 1);
        double eased = t * t * (3 - 2 * t);
        return 1 - eased;
    }

    private static void step() {
        if (!active) {
            driver.stop();
            return;
        }
        if (System.currentTimeMillis() - startMilli >= DURATION_MS) {
            active = false;
            driver.stop();
        }
        DisplayController.display();
    }

    private ProjectionTransition() {}
}
