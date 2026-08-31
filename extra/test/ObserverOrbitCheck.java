package org.helioviewer.jhv.layers;

/**
 * The two things the orbit has to get right, carried over from the turntable's own self-check when
 * it moved out of the point-cloud plugin into {@link ObserverLayer}.
 *
 * <p>A turntable that does not close leaves a seam in a looping movie, which is the whole point of
 * the feature, and it fails silently: the frames all render, they just do not join up. The angle is
 * applied as a per-frame delta, so closure means the deltas over one revolution sum to exactly 360
 * with the wrap from 359 back to 0 counted as a step forward rather than a leap back.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.layers.ObserverOrbitCheck
 */
public final class ObserverOrbitCheck {

    public static void main(String[] args) {
        ObserverLayer o = new ObserverLayer(null);

        // Solar north is lat +90, where longitude is degenerate, so the axis must come out as the
        // scene's north (y) whatever longitude sits beside it.
        o.setAxisLat(90);
        for (double lon : new double[]{0, 45, -170, 300}) {
            o.setAxisLon(lon);
            double[] a = o.axis();
            if (Math.abs(a[0]) > 1e-12 || Math.abs(a[1] - 1) > 1e-12 || Math.abs(a[2]) > 1e-12)
                throw new AssertionError("solar-north axis wrong at lon=" + lon);
        }

        // Frame counts that do not divide 360 evenly must still close.
        for (int n : new int[]{7, 47, 180, 360, 1000}) {
            o.setFramesPerRev(n);
            double applied = 0, total = 0;
            for (int frame = 0; frame <= n; frame++) {
                double angle = o.angleAt(frame);
                double delta = angle - applied;
                delta -= 360 * Math.floor((delta + 180) / 360);
                applied = angle;
                total += delta;
                if (Math.abs(delta) > 180)
                    throw new AssertionError("framesPerRev=" + n + ": delta took the long way round: " + delta);
            }
            if (Math.abs(total - 360) > 1e-9)
                throw new AssertionError("framesPerRev=" + n + " summed to " + total + ", expected 360");
        }

        // setFramesPerRev floors at 2: a "revolution" of one frame has no delta to apply, and a
        // zero or negative count would divide by zero in angleAt.
        o.setFramesPerRev(0);
        if (o.getFramesPerRev() != 2)
            throw new AssertionError("framesPerRev floor is " + o.getFramesPerRev() + ", expected 2");

        System.out.println("ObserverOrbitCheck: PASS");
    }

}
