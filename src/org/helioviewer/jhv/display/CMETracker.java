package org.helioviewer.jhv.display;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.TimeListener;

// Pins a CACTus CME front at a fixed fraction of the RadialWarp field of view by
// animating the radial exponent p with movie time: as the front travels outward the
// warp is re-solved every frame so the front stays at a constant screen radius while
// the corona rubber-bands around it. Transient, like camera tracking: engaged from a
// CACTus event dialog, disengaged by moving the p slider or leaving RadialWarp.
public final class CMETracker implements TimeListener.Change {

    private static final double SCREEN_FRACTION = 0.60; // front pinned at this fraction of the outer FOV radius
    private static final double EDGE_FRACTION = 0.10;   // log-radial band held at the FOV edges: below it the entry
                                                        // exponent is held (the front emerges naturally), beyond it
                                                        // the exponent freezes and the front drifts out
    private static final double ONSET_RSUN = 2.4;       // front start radius; matches SWEKLayer.DIST_SUN_BEGIN so
                                                        // the drawn CACTus arc rides exactly at the pinned front

    private static final CMETracker instance = new CMETracker();

    private static double speed; // km/s
    private static long onset;   // event start, milliseconds
    private static boolean tracking;

    public static void track(double speedKmSec, long onsetMilli) {
        speed = speedKmSec;
        onset = onsetMilli;
        tracking = true;
        Player.addTimeListener(instance); // no-op when already registered
        instance.timeChanged(Player.getTime().milli); // solve now: re-engaging fires no callback
        DisplayController.display();
    }

    public static void stop() {
        tracking = false; // the listener stays registered: removal inside Player's forEach would throw
    }

    public static boolean isTracking() {
        return tracking;
    }

    @Override
    public void timeChanged(long milli) {
        if (!tracking)
            return;
        if (Display.mode != MapMode.RadialWarp) { // transient mode: leaving the projection disengages
            tracking = false;
            return;
        }
        double rOut = ImageLayers.getLargestRadialSize();
        if (rOut <= 1) // no warped corona visible: hold p
            return;
        double rCme = ONSET_RSUN + speed * (milli - onset) / Sun.RadiusMeter; // km/s * milli == m
        double logOut = Math.log(rOut);
        double rEnter = Math.exp(EDGE_FRACTION * logOut);
        double rExit = Math.exp((1 - EDGE_FRACTION) * logOut);
        Display.setDiskPower(solve(Math.clamp(rCme, rEnter, rExit), rOut));
    }

    // Find p in [-1, 1] such that the front lands at SCREEN_FRACTION of the outer FOV
    // radius: warp(p, r) / warp(p, rOut) == SCREEN_FRACTION (the warp is anchored at 0,
    // see MapScale.diskPower). The fraction decreases monotonically in p over the useful
    // range, so bisect; endpoint saturation keeps p continuous when out of range.
    private static double solve(double r, double rOut) {
        double lo = -1, hi = 1;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (MapScale.PowerMapScale.warp(mid, r) > SCREEN_FRACTION * MapScale.PowerMapScale.warp(mid, rOut))
                lo = mid; // front lands too far out: less compression
            else
                hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    private CMETracker() {
    }

}
