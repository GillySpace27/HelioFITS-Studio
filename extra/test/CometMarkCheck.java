package org.helioviewer.jhv.identify;

import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * A selected comet is followed through time: its mark reads the track at the observer's own time,
 * so two observers a day apart see it in two places, and one outside the track sees nothing.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.CometMarkCheck
 */
public final class CometMarkCheck {

    public static void main(String[] args) {
        long t0 = 1_736_726_400_000L, day = 86_400_000L; // 2025-01-13
        var track = new Comets.Track(new long[]{t0, t0 + day}, new double[]{10, 20}, new double[]{0, 90});
        var mark = CometSky.mark(new CometSky.Tracked(new Comets.Comet("C/test", "test", 0.1, t0), track));
        Vec3 a = mark.where().direction(new Position(new JHVTime(t0), 200, 0, 0));
        Vec3 b = mark.where().direction(new Position(new JHVTime(t0 + day), 200, 0, 0));
        Vec3 off = mark.where().direction(new Position(new JHVTime(t0 + 2 * day), 200, 0, 0));
        boolean ok = a != null && b != null && off == null && Identifier.angle(a, b) > 0.01;
        System.out.println((ok ? "  ok   " : "  FAIL ") + "comet mark moves with the observer's time");
        if (!ok)
            System.exit(1);
    }

    private CometMarkCheck() {}
}
