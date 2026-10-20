package org.helioviewer.jhv.display;

import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;

/**
 * Cancel in the Track CME panel hands the warp and crop back as they were before tracking began.
 *
 * <p>The case worth pinning is the re-track: the panel stops and engages again to switch CME or
 * mode, so "before tracking" must survive that stop and not be retaken from the previous track's
 * last solve, or Cancel would restore a value tracking itself wrote.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.TrackCancelCheck
 */
public final class TrackCancelCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Platform.init(); // Player.getTime reaches NullView, and that reaches SPICE
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        AppInit.loadSpice();

        SwingUtilities.invokeAndWait(() -> {
            Display.setWarpLambda(0.5);
            Display.setWarpOuterRadius(20);

            CMETracker.setMode(CMETracker.Mode.WARP);
            CMETracker.track(milli -> 8, milli -> 0);
            expect("warp tracking moves lambda off the user's value", Display.getWarpLambda() != 0.5);

            // Another CME, as the panel does it: stop, then engage again. The crop solve needs loaded
            // data to size against, so headless it is the lambda path that carries this. With no
            // layers the view is linear and the solve saturates: inside 0.6 of the field at -1, outside at 1.
            double firstSolve = Display.getWarpLambda();
            CMETracker.stop();
            CMETracker.track(milli -> 15, milli -> 0);
            expect("the re-track solves afresh (" + firstSolve + " then " + Display.getWarpLambda() + ")", Display.getWarpLambda() != firstSolve);

            CMETracker.cancel();
            expect("cancel stops tracking", !CMETracker.isTracking());
            expect("cancel restores lambda from before the first track, not the warp solve (" + Display.getWarpLambda() + ")",
                    Display.getWarpLambda() == 0.5);
            expect("and leaves the crop as the user had it (" + Display.getWarpOuterRadius() + ")", Display.getWarpOuterRadius() == 20);

            // The user moves on; the next track must snapshot their new values, not the old ones.
            Display.setWarpLambda(-0.25);
            CMETracker.setMode(CMETracker.Mode.WARP);
            CMETracker.track(milli -> 8, milli -> 0);
            CMETracker.cancel();
            expect("a fresh track after cancel snapshots the user's new lambda", Display.getWarpLambda() == -0.25);
        });

        System.out.println(failures == 0 ? "TrackCancelCheck: PASS" : "TrackCancelCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private TrackCancelCheck() {}
}
