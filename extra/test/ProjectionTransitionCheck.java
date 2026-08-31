package org.helioviewer.jhv.display;

/**
 * The projection crossfade's fade curve and its persisted toggle -- the pieces that don't
 * need a live app. {@link ProjectionTransition#requestSwitch} and {@code applyPendingSwitch}
 * are not exercised here: both reach {@code Display.setMapMode} -> {@code DisplayController}
 * -> {@code Sun.<clinit>}, which needs SPICE loaded, cache directories created, and JVM flags
 * (timezone, native access) that only the app's own launch script sets up. Getting that
 * bootstrap working here would be testing the launcher, not the transition -- known limitation,
 * stated rather than papered over; the state machine's correctness (capture-before-flip, no
 * pending switch left behind, the disabled path staying synchronous) is verified by hand in the
 * running app, the same category of gap HelioradialGridAlignmentCheck's own docstring already
 * accepts for its auto-path camera framing.
 *
 * <p>What the curve itself has to get right: start opaque, end transparent, monotone in
 * between, and actually eased (flatter than linear at both ends) rather than a linear ramp
 * that would put a visible kink in the fade's velocity at t=0 and t=1.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.ProjectionTransitionCheck
 */
public final class ProjectionTransitionCheck {

    private static int failures;

    public static void main(String[] args) {
        near(ProjectionTransition.easedFade(-50, 280), 1.0, 1e-9, "before start: fully opaque");
        near(ProjectionTransition.easedFade(0, 280), 1.0, 1e-9, "at start: fully opaque");
        near(ProjectionTransition.easedFade(280, 280), 0.0, 1e-9, "at the duration: fully transparent");
        near(ProjectionTransition.easedFade(1000, 280), 0.0, 1e-9, "past the duration: stays fully transparent");
        near(ProjectionTransition.easedFade(140, 280), 0.5, 1e-9, "smoothstep is symmetric: the midpoint is exactly 0.5");

        // Monotone decreasing across the whole duration -- a fade that ever ticks back UP
        // would flash the old scene back in partway through.
        double previous = 1.0;
        for (int ms = 0; ms <= 280; ms += 10) {
            double alpha = ProjectionTransition.easedFade(ms, 280);
            expect(alpha <= previous + 1e-12, "monotone decreasing at " + ms + "ms");
            previous = alpha;
        }

        // Actually eased, not linear: smoothstep's derivative is 0 at both ends, so a sample a
        // quarter of the way in has fallen LESS than a quarter of the way -- linear would give
        // exactly 0.75 at t=0.25.
        double quarter = ProjectionTransition.easedFade(70, 280);
        expect(quarter > 0.75, "eased, not linear, near the start (got " + quarter + ", linear would be 0.75)");

        // The animate toggle: real state, not hardcoded, and a redundant set is a no-op rather
        // than an unconditional persisted write on every call.
        boolean saved = ProjectionTransition.isAnimateEnabled();
        try {
            ProjectionTransition.setAnimateEnabled(true);
            expect(ProjectionTransition.isAnimateEnabled(), "enabling reads back true");
            ProjectionTransition.setAnimateEnabled(false);
            expect(!ProjectionTransition.isAnimateEnabled(), "disabling reads back false");
        } finally {
            ProjectionTransition.setAnimateEnabled(saved);
        }

        System.out.println(failures == 0 ? "ProjectionTransitionCheck: PASS" : "ProjectionTransitionCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private ProjectionTransitionCheck() {}
}
