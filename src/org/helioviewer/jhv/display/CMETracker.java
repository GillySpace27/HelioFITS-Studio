package org.helioviewer.jhv.display;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.thread.EDTTimer;
import org.helioviewer.jhv.time.TimeListener;

// Pins a CACTus CME front at a fixed fraction of the radial field of view by animating
// the warp lambda with movie time: as the front travels outward the Box-Cox warp is
// re-solved every frame so the front stays at a constant screen radius while the corona
// rubber-bands around it. Works in either projection that uses lambda — RadialWarp (disk)
// and RectWarp (unwrap) share the warp, so the same solve drives both. Transient, like
// camera tracking: engaged from a CACTus event dialog, disengaged by moving the lambda
// slider or leaving those projections.
public final class CMETracker implements TimeListener.Change {

    private static final double SCREEN_FRACTION = 0.60; // front pinned at this fraction of the outer FOV radius
    private static final double EDGE_FRACTION = 0.10;   // log-radial band held at the FOV edges: below it the entry
                                                        // lambda is held (the front emerges naturally), beyond it
                                                        // the lambda freezes and the front drifts out
    private static final double ONSET_RSUN = 2.4;       // front start radius; matches SWEKLayer.DIST_SUN_BEGIN so
                                                        // the drawn CACTus arc rides exactly at the pinned front

    private static final CMETracker instance = new CMETracker();
    private static final List<Runnable> listeners = new ArrayList<>(); // notified when tracking engages/disengages

    private static double speed; // km/s
    private static long onset;   // event start, milliseconds
    private static double paDeg;  // event principal angle (degrees), for the front marker

    // Movie frames arrive at the ~20 fps playback timer (one cached frame per tick), so solving
    // lambda straight off the frame time makes the warp step. Instead we ease a continuous
    // "animation time" toward the latest frame time on a 60 fps timer and solve lambda for the
    // interpolated front — the front stays exactly pinned (lambda is solved for the same radius
    // the marker uses) while the corona glides between frames.
    private static boolean tracking;
    private static double targetMilli; // latest frame's movie time
    private static double animMilli;   // eased movie time actually driving the warp + marker
    private static final EDTTimer smoother = new EDTTimer(16, CMETracker::step);

    public static void track(double speedKmSec, long onsetMilli, double positionAngleDeg) {
        speed = speedKmSec;
        onset = onsetMilli;
        paDeg = positionAngleDeg;
        tracking = true;
        targetMilli = animMilli = Player.getTime().milli; // snap on engage, no ease-in from a stale value
        Player.addTimeListener(instance); // no-op when already registered
        solveAndSet(animMilli);
        smoother.start();
        fireChanged();
        DisplayController.display();
    }

    public static void stop() {
        if (!tracking)
            return;
        tracking = false; // the listener stays registered: removal inside Player's forEach would throw
        smoother.stop();
        fireChanged();
    }

    public static boolean isTracking() {
        return tracking;
    }

    // The eased front radius (R_sun) currently driving the warp — a constant-speed extrapolation
    // from the catalog onset at the interpolated animation time. The front marker uses this so it
    // stays on the freeze circle even mid-ease (lambda is solved for the same radius).
    public static double currentFront() {
        return ONSET_RSUN + speed * (animMilli - onset) / Sun.RadiusMeter; // km/s * milli == m
    }

    public static double positionAngleDeg() {
        return paDeg;
    }

    public static double screenFraction() {
        return SCREEN_FRACTION;
    }

    // Notified (on the EDT) whenever tracking turns on or off — the SWEK "Track"/"Tracking"
    // button uses this to reflect the state. Listeners live for the app; register once.
    public static void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    public static void removeChangeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void fireChanged() {
        for (Runnable r : listeners)
            r.run();
    }

    @Override
    public void timeChanged(long milli) {
        if (!tracking)
            return;
        if (!Display.mode.usesWarpLambda()) { // RadialWarp or RectWarp; leaving both disengages
            tracking = false;
            smoother.stop();
            fireChanged();
            return;
        }
        targetMilli = milli;
        if (!smoother.isRunning())
            smoother.start(); // resume easing toward the new frame
    }

    // 60 fps: ease the animation time toward the latest frame, then re-solve lambda and render.
    private static void step() {
        if (!tracking) {
            smoother.stop();
            return;
        }
        animMilli += (targetMilli - animMilli) * 0.3;
        boolean converged = Math.abs(targetMilli - animMilli) < 1;
        if (converged)
            animMilli = targetMilli;
        solveAndSet(animMilli);
        DisplayController.display();
        if (converged)
            smoother.stop(); // caught up; the next frame's timeChanged restarts it
    }

    private static void solveAndSet(double milli) {
        double userOut = Display.getWarpOuterRadius(); // honor the radial crop, like the renderer
        double rOut = userOut > 0 ? userOut : ImageLayers.getLargestRadialSize();
        if (rOut <= 1) // no warped corona visible: hold lambda
            return;
        double rCme = ONSET_RSUN + speed * (milli - onset) / Sun.RadiusMeter; // km/s * milli == m
        double logOut = Math.log(rOut);
        double rEnter = Math.exp(EDGE_FRACTION * logOut);
        double rExit = Math.exp((1 - EDGE_FRACTION) * logOut);
        Display.setWarpLambda(solve(Math.clamp(rCme, rEnter, rExit), rOut));
    }

    // Normalized radial screen position of physical radius r for a given lambda: an exact copy of
    // MapScale.BoxCoxRadialScale.toUnitY, but with lambda as a free variable so it can be solved
    // for, instead of reading the Display global.
    private static double unitY(double r, double rOut, double lambda) {
        // Same limb anchor as BoxCoxRadialScale: the lambda-dependent origin-anchored
        // position (limb varies with the solve's trial lambda — still monotone).
        double bc = rOut <= 1 ? 0
                : (lambda == 0 ? Math.log(rOut) : (Math.pow(rOut, lambda) - 1) / lambda);
        double limb = Math.max(1 / rOut, 1 / (1 + bc));
        if (rOut <= 1 || r <= 1)
            return r * limb;
        double u = lambda == 0
                ? Math.log(r) / Math.log(rOut)
                : (Math.pow(r, lambda) - 1) / (Math.pow(rOut, lambda) - 1);
        return limb + u * (1 - limb);
    }

    // Find lambda in [-1, 1] such that the front lands at SCREEN_FRACTION of the outer FOV
    // radius: unitY(r, rOut, lambda) == SCREEN_FRACTION. The fraction decreases monotonically
    // in lambda (lambda -> -1 stretches the inner corona, pushing mid-radii outward), so bisect;
    // endpoint saturation keeps lambda continuous when the target is out of range.
    private static double solve(double r, double rOut) {
        double lo = -1, hi = 1;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (unitY(r, rOut, mid) > SCREEN_FRACTION)
                lo = mid; // front lands too far out: raise lambda toward linear
            else
                hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    private CMETracker() {
    }

}
