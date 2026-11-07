package org.helioviewer.jhv.layers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The locator map's geometry (LocatorInset): seen from solar north with Earth straight below the Sun.
 *
 * <p>Offline, against the bundled kernels. Pins: (1) Earth always lands straight below the Sun at its
 * distance; (2) the view really is from the north: Venus, which overtakes Earth, moves
 * counterclockwise on the map over ten days; (3) STEREO Ahead, whose orbit is smaller than Earth's,
 * gains on Earth over a month, and the side of the map agrees with "ahead" or "behind"; (4) the
 * map's extent is at least 1.2 au and grows to hold a distant observer.
 */
public final class LocatorInsetCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("locator-inset-check");
        System.setProperty("user.home", home.toString());
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();

        JHVTime t = new JHVTime("2010-01-01T00:00:00");
        Position earth = Sun.getEarth(t);
        double[] e = LocatorInset.project(earth, earth);
        double inPlane = earth.distance * Math.cos(earth.lat) / Sun.MeanEarthDistance; // the map is the solar equator's plane
        expect(Math.abs(e[0]) < 1e-12 && Math.abs(e[1] + inPlane) < 1e-12, "Earth lands straight below the Sun at its distance");
        expect(LocatorInset.describe(earth, earth).contains("on the Sun-Earth line"), "an observer at Earth reads as on the Sun-Earth line");

        JHVTime later = new JHVTime("2010-01-11T00:00:00");
        double[] v0 = LocatorInset.project(at("VENUS", t), earth);
        double[] v1 = LocatorInset.project(at("VENUS", later), Sun.getEarth(later));
        double turn = v0[0] * v1[1] - v0[1] * v1[0];
        expect(turn > 0, "Venus, overtaking Earth, turns counterclockwise on the map, so the view is from the north (cross " + turn + ")");

        // STEREO Ahead's orbit is smaller than Earth's, so it gains on Earth month by month: the
        // angle "ahead of Earth" must grow, whatever it is on the day.
        JHVTime s0 = new JHVTime("2025-06-01T00:00:00"), s1 = new JHVTime("2025-07-01T00:00:00");
        Position sta = at("STEREO AHEAD", s0);
        double ahead0 = LocatorInset.degreesAhead(sta, Sun.getEarth(s0));
        double ahead1 = LocatorInset.degreesAhead(at("STEREO AHEAD", s1), Sun.getEarth(s1));
        expect(ahead1 > ahead0, String.format("STEREO Ahead gains on Earth: %.2f then %.2f degrees ahead a month later", ahead0, ahead1));
        double[] a = LocatorInset.project(sta, Sun.getEarth(s0));
        expect(Math.signum(a[0]) == Math.signum(ahead0), "ahead of Earth is to the right on the map, behind is to the left (x " + a[0] + " au)");
        expect(LocatorInset.describe(sta, Sun.getEarth(s0)).contains(ahead0 > 0 ? "ahead of Earth" : "behind Earth"), "and the tooltip says which");

        expect(LocatorInset.extent(List.of(e)) == 1.2, "the map spans at least 1.2 au");
        expect(Math.abs(LocatorInset.extent(List.of(e, new double[]{0, 5})) - 5.75) < 1e-12, "an observer at 5 au widens the map to hold it");

        if (failures != 0) {
            System.out.println("LocatorInsetCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("LocatorInsetCheck: PASS");
    }

    private static Position at(String body, JHVTime t) throws Exception {
        double[] v = Spice.getPosition("SUN", body, "SOLO_IAU_SUN_2009", t);
        return ModelPlacement.carringtonPosition(t, v[0], v[1], v[2]);
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
