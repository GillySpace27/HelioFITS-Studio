package org.helioviewer.jhv.layers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.HelioFleet;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

/**
 * Spacecraft placed from HelioFleet or JPL Horizons (ACE, Wind, DSCOVR, Ulysses, MESSENGER).
 *
 * <p>Offline. Pins: (1) a Horizons vector table, captured from the API with Studio's own request
 * (extra/test/data/horizons-ulysses-2005-06-01.txt), parses to UT times and km, and Horizons' "no
 * ephemeris" answer becomes the message the layer shows; (2) interpolation is linear between
 * samples and refuses times outside them; (3) the frame and unit conversion: Earth's own J2000
 * ecliptic vector from SPICE, fed through it, lands exactly on Earth's Carrington position, and a
 * zero geocentric vector (an L1 monitor's frame origin) lands on Earth too; (4) the distance is the
 * length of the fixture's vector (about 5.1 AU for Ulysses on 2005-06-01); (5) a model
 * layer accepts and saves these names.
 */
public final class ExternalEphemerisCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("external-ephemeris-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();

        checkHorizons();
        checkConversion();
        checkSettings();

        if (failures != 0) {
            System.out.println("ExternalEphemerisCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ExternalEphemerisCheck: PASS");
    }

    private static void checkHorizons() throws Exception {
        HelioFleet.Samples s = JplHorizons.parse(Files.readString(Path.of("extra/test/data/horizons-ulysses-2005-06-01.txt")));
        expect(s.milli().length == 5 && !s.geocentric(), "the Horizons table gives five heliocentric samples (got " + s.milli().length + ")");
        expect(s.milli()[0] == new JHVTime("2005-06-01T00:00:00").milli && s.milli()[4] == new JHVTime("2005-06-02T00:00:00").milli,
                "sample times are the table's UT Julian dates");
        expect(s.km()[0] == -6.886269351134081E+08 && s.km()[14] == -2.167047614011129E+08, "positions are the table's X, Y, Z in km");

        double[] mid = ExternalEphemeris.interpolate(s, new JHVTime("2005-06-01T03:00:00").milli);
        expect(mid != null && Math.abs(mid[0] - (-6.886269351134081E+08 + -6.885188831178669E+08) / 2) < 1e-3, "halfway between samples is the mean of the two");
        expect(ExternalEphemeris.interpolate(s, new JHVTime("2005-06-03T00:00:00").milli) == null, "a time past the samples has no position");

        try {
            JplHorizons.parse("""
                    *******
                    No ephemeris for target "MESSENGER (spacecraft)" after A.D. 2015-MAY-01 18:49:57.1850 TDB
                    """);
            expect(false, "Horizons' refusal becomes an error");
        } catch (IOException e) {
            expect(e.getMessage().contains("No ephemeris for target \"MESSENGER (spacecraft)\" after"), "Horizons' refusal is passed on as the reason (" + e.getMessage() + ")");
        }

        Position p = ExternalEphemeris.at(s, new JHVTime("2005-06-01T12:00:00"));
        double km = Math.sqrt(sq(-6.884107329721775E+08) + sq(2.300800975997123E+08) + sq(-2.163941785368618E+08));
        double au = p.distance / Sun.MeanEarthDistance;
        expect(Math.abs(p.distance - km * Sun.RadiusKMeterInv) < 1e-6 * p.distance && au > 5 && au < 5.2,
                "Ulysses is " + String.format("%.4f", au) + " AU from the Sun, the length of the fixture's vector");
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void checkConversion() throws Exception {
        JHVTime t = new JHVTime("2025-06-21T00:00:00"); // near a solstice, where ecliptic and equatorial frames differ most for Earth
        double[] ecl = Spice.getPosition("SUN", "EARTH", "ECLIPJ2000", t); // solar radii
        double toKm = 1 / Sun.RadiusKMeterInv;
        long ms = t.milli;
        HelioFleet.Samples helio = new HelioFleet.Samples(new long[]{ms - 3600_000, ms + 3600_000},
                new double[]{ecl[0] * toKm, ecl[1] * toKm, ecl[2] * toKm, ecl[0] * toKm, ecl[1] * toKm, ecl[2] * toKm}, false);
        Vec3 got = ModelPlacement.toWorld(ExternalEphemeris.at(helio, t));
        double[] c = Spice.getPosition("SUN", "EARTH", "SOLO_IAU_SUN_2009", t);
        Vec3 want = ModelPlacement.toWorld(ModelPlacement.carringtonPosition(t, c[0], c[1], c[2]));
        expect(dist(got, want) < 1e-6, "Earth's J2000 ecliptic vector in km lands on Earth's Carrington position (off by " + dist(got, want) + " Rsun)");

        HelioFleet.Samples geo = new HelioFleet.Samples(new long[]{ms - 3600_000, ms + 3600_000}, new double[6], true);
        Vec3 g = ModelPlacement.toWorld(ExternalEphemeris.at(geo, t));
        expect(dist(g, want) < 1e-6, "a geocentric vector of zero lands on Earth");

        // 1.5 million km sunward of Earth along the Sun-Earth line, as an L1 monitor roughly sits
        double r = Math.sqrt(ecl[0] * ecl[0] + ecl[1] * ecl[1] + ecl[2] * ecl[2]);
        double l1 = -1.5e6 / r;
        HelioFleet.Samples near = new HelioFleet.Samples(new long[]{ms - 3600_000, ms + 3600_000},
                new double[]{ecl[0] * l1, ecl[1] * l1, ecl[2] * l1, ecl[0] * l1, ecl[1] * l1, ecl[2] * l1}, true);
        Vec3 n = ModelPlacement.toWorld(ExternalEphemeris.at(near, t));
        expect(Math.abs(want.length() - n.length() - 1.5e6 * Sun.RadiusKMeterInv) < 1e-3, "a geocentric vector toward the Sun puts the body that much closer to it");
    }

    private static double dist(Vec3 a, Vec3 b) {
        double x = a.x - b.x, y = a.y - b.y, z = a.z - b.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void checkSettings() {
        for (String name : new String[]{"ACE", "Wind", "DSCOVR", "Ulysses", "MESSENGER"})
            expect(ModelPlacement.isTarget(name), name + " is a target a model can be placed at");
        ModelPlacement m = new ModelPlacement();
        m.setTarget("Ulysses");
        JSONObject jo = new JSONObject();
        m.serialize(jo);
        ModelPlacement r = new ModelPlacement();
        r.deserialize(new JSONObject(jo.toString()));
        expect(r.target().equals("Ulysses"), "a Horizons target survives a session round trip");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
