package org.helioviewer.jhv.layers.stars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.SpiceMath;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.layers.StarLayer;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

/**
 * The starfield (StarSky, StarCatalog, StarLayer settings).
 *
 * <p>Expected star positions come from sunpy 8.0.1 / astropy 8.0.2 Helioprojective for real
 * Hipparcos stars, computed 2026-11-07 by the starfield spike's fixtures.py (project file
 * heliad/starfield/spike/). Positions are the catalogue's, with no proper motion, on both sides.
 * Pins: (1) the observer frame and Tx, Ty from its rays; (2) the end-to-end path through Spice.j2000ToSun and the
 * viewpoint's negated longitude, for Earth on 2026-08-22; (3) the far-side and wide-angle cull;
 * (4) B-V to temperature and the colour direction; (5) catalogue parsing; (6) settings round trip.
 */
public final class StarSkyCheck {

    private static int failures;

    // name, star Carrington lon, lat (direction from the observer), observer Carrington lon, lat, expected Tx, Ty [deg]
    private static final Object[][] PROJ = {
            {"HIP 51624 from Earth", 290.427910, -7.004608, 102.554430, 6.927789, -7.814371, -0.141482},
            {"HIP 46771 from Earth", 275.419047, -9.766717, 102.554430, 6.927789, 7.040451, -2.891740},
            {"HIP 44331 from Earth", 261.842057, 16.151459, 102.554430, 6.927789, 21.592961, 22.613684},
    };
    // stars that must be culled: far side of the sky, or more than 80 deg from the Sun
    private static final Object[][] CULLED = {
            {"HIP 92016 from Earth (132 deg from the Sun)", 54.275217, 5.045428, 102.554430, 6.927789},
            {"HIP 78401 from 0.3 AU (168.5 deg from the Sun)", 16.969550, -3.621956, 12.496141, 7.000000},
            {"HIP 112519 from Earth (84.8 deg from the Sun)", 224.130255, 67.065283, 102.554430, 6.927789},
    };
    // HIP, ICRS ra, dec [deg] (Hipparcos), expected Tx, Ty from Earth on 2026-08-22T00:00 [deg]
    private static final double[][] END_TO_END = {
            {51624, 158.20281305, 9.30659431, -7.814371, -0.141482},
            {44331, 135.41996619, 39.71370429, 21.592961, 22.613684},
    };

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("star-sky-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");

        checkProjection();
        checkColour();
        checkCatalog();

        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();
        checkEndToEnd();
        checkOrbit();
        checkSettings();

        if (failures != 0) {
            System.out.println("StarSkyCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("StarSkyCheck: PASS");
    }

    // The ray in the observer's frame for a Carrington direction (lon, lat) seen from (lon0, lat0)
    private static double[] ray(Object[] r) {
        double[] ray = new double[3];
        StarSky.toRay(StarSky.frame(rad(r[3]), rad(r[4])), SpiceMath.radrec(1, rad(r[1]), rad(r[2])), ray);
        return ray;
    }

    private static void checkProjection() {
        double[] theta = new double[2];
        for (Object[] r : PROJ) {
            boolean in = StarSky.theta(ray(r), theta);
            double dx = Math.toDegrees(theta[0]) - (double) r[5], dy = Math.toDegrees(theta[1]) - (double) r[6];
            expect(in && Math.abs(dx) < 1e-4 && Math.abs(dy) < 1e-4,
                    r[0] + ": Tx, Ty match sunpy to 1e-4 deg (off by " + fmt(dx) + ", " + fmt(dy) + ")");
        }
        for (Object[] r : CULLED)
            expect(!StarSky.theta(ray(r), theta), r[0] + " is culled from the 3D plane");
        // The sky maps place far-side stars themselves; HPC drops a ray pointing away from the Sun (z > 0)
        double[] back = ray(CULLED[1]);
        expect(Math.abs(back[2] - Math.cos(Math.toRadians(180 - 168.5))) < 1e-3, "a star 168.5 deg from the Sun points away from it (ray z " + fmt(back[2]) + ")");
    }

    private static void checkEndToEnd() throws Exception {
        JHVTime time = new JHVTime("2026-08-22T00:00:00");
        Position earth = Spice.getCarrington("EARTH", time);
        expect(earth != null, "Earth's Carrington position from SPICE");
        if (earth == null)
            return;
        double[] sc = StarSky.observer(earth);
        double[][] frame = StarSky.frame(sc[1], sc[2]);
        double[][] carr = Spice.j2000ToSun.get(time);
        double[] theta = new double[2];
        double[] ray = new double[3];
        for (double[] s : END_TO_END) {
            StarSky.starRay(frame, carr, Math.toRadians(s[1]), Math.toRadians(s[2]), ray);
            boolean in = StarSky.theta(ray, theta);
            double dx = Math.toDegrees(theta[0]) - s[3], dy = Math.toDegrees(theta[1]) - s[4];
            // SPICE's Earth and sunpy's differ by light time and frame details; 0.02 deg is about a pixel on a LASCO C3 frame
            expect(in && Math.abs(dx) < 0.02 && Math.abs(dy) < 0.02,
                    "HIP " + (int) s[0] + " from Earth through SPICE: within 0.02 deg of sunpy (off by " + fmt(dx) + ", " + fmt(dy) + ")");
        }

        // A viewpoint at Earth whose location carries no name must not draw Earth (it came out as a dot on the Sun)
        StarSky.Points p = StarSky.compute(earth, StarCatalog.parse("hip,ra,de,pmra,pmde,vmag,b_v\n"), new StarSky.Style(6, true, 1, 1, true, true));
        byte[] blue = SpaceObject.get("Earth").getColor();
        boolean drawsEarth = false;
        for (int i = 0; i < p.count(); i++)
            drawsEarth |= Arrays.equals(Arrays.copyOfRange(p.colour(), 4 * i, 4 * i + 4), blue);
        expect(p.count() >= 2 && !drawsEarth, "the planets from Earth leave out Earth itself (" + p.count() + " bodies)");
    }

    private static void checkColour() {
        double t = StarSky.bvToKelvin(0.65);
        expect(Math.abs(t - 5778) < 2, "B-V 0.65 gives 5778 K (got " + fmt(t) + ")");
        // A blackbody fit runs cool for the hottest stars (B-V -0.2 gives about 13,600 K); fine for a tint
        expect(StarSky.bvToKelvin(-0.2) > 12000, "a B star (B-V -0.2) is hotter than 12000 K");
        expect(StarSky.bvToKelvin(1.8) < 3800, "an M star (B-V 1.8) is cooler than 3800 K");

        float[] hot = StarSky.kelvinToRgb(15000), cool = StarSky.kelvinToRgb(3500);
        expect(hot[2] > hot[0], "a hot star is bluer than red");
        expect(cool[0] > cool[2], "a cool star is redder than blue");
        float[] white = StarSky.saturate(cool, 0);
        expect(white[0] == 1 && white[1] == 1 && white[2] == 1, "saturation 0 is white");
        float[] same = StarSky.saturate(cool, 1);
        expect(Math.abs(same[2] - cool[2]) < 1e-6, "saturation 1 keeps the blackbody colour");
        float[] more = StarSky.saturate(cool, 2);
        expect(more[2] < cool[2], "saturation 2 exaggerates it");

        StarSky.Style s = new StarSky.Style(6, true, 1, 1, false, false);
        expect(StarSky.starSize(6, s) < StarSky.starSize(1, s), "brighter stars are bigger");
        byte[] grey = StarSky.starColour(Double.NaN, 6, s);
        expect(grey[0] == grey[2], "a star with no B-V is drawn white");
    }

    private static void checkCatalog() throws Exception {
        String csv = "hip,ra,de,pmra,pmde,vmag,b_v\n"
                + "32349,101.28854105,-16.71314306,-546.01,-1223.08,-1.44,0.009\n"
                + "1,,,,,9.1,\n" // a Hipparcos entry with no position
                + "91262,279.23410832,38.78299311,201.02,287.46,0.03,\n";
        StarCatalog c = StarCatalog.parse(csv);
        expect(c.size() == 2, "rows without a position are skipped (got " + c.size() + ")");
        expect(c.vmag[0] == -1.44 && Double.isNaN(c.bv[1]), "magnitude read, missing B-V kept as NaN");

        boolean threw = false;
        try {
            StarCatalog.appendRows("source_id,ra,dec\n1,2,3\n", new StringBuilder());
        } catch (java.io.IOException e) {
            threw = true;
        }
        expect(threw, "a result with other columns is refused");
        expect(StarCatalog.bandQuery(60, 90, true).endsWith("de <= 90") && StarCatalog.bandQuery(-90, -60, false).contains("de >= -90"),
                "declination bands cover both poles");
    }

    // The 3D sheet is a sky at infinity: orbiting turns the directions instead of swinging a flat plane edge-on
    private static void checkOrbit() {
        StarSky.Points p = new StarSky.Points(2, new float[]{0, 0, -1, 1, 0, 0}, new float[]{3, 3}, new byte[8]);
        BufVertex none = new BufVertex(), turned = new BufVertex();
        StarSky.plane(p, 215, null, none);
        StarSky.plane(p, 215, org.helioviewer.jhv.math.Quat.createAxisY(Math.PI / 2), turned);
        expect(none.getCount() == 1 && none.toBuffer().getFloat(0) == 0, "no drag: the Sun-line star is placed, the one 90 deg west is culled");
        expect(turned.getCount() == 1 && Math.abs(turned.toBuffer().getFloat(0)) < 1e-3 && Math.abs(turned.toBuffer().getFloat(4)) < 1e-3,
                "a 90 deg orbit brings the star that was 90 deg west onto the new line of sight and culls the old one");
        BufVertex flat = StarSky.plane(p, 215);
        expect(flat.getCount() == none.getCount() && flat.toBuffer().getFloat(0) == none.toBuffer().getFloat(0), "plane(p, d) is the no-drag placement");
    }

    private static void checkSettings() {
        StarLayer a = new StarLayer(null);
        a.setMagLimit(7);
        a.setColour(false);
        a.setSaturation(1.5);
        a.setPlanets(false);
        a.setMoon(true);
        a.setOnTop(true);
        JSONObject jo = new JSONObject();
        a.serialize(jo);
        StarLayer b = new StarLayer(jo);
        expect(b.getMagLimit() == 7 && !b.isColour() && b.getSaturation() == 1.5 && !b.isPlanets() && b.isMoon() && b.isOnTop(), "settings survive a session round trip");
        StarLayer d = new StarLayer(new JSONObject());
        expect(d.getMagLimit() == StarLayer.DEFAULT_MAG && d.isColour() && d.isPlanets() && !d.isMoon() && !d.isOnTop(), "an empty record gives the defaults");
        expect(!d.isEnabled(), "the layer starts off");
    }

    private static double rad(Object deg) {
        return Math.toRadians((double) deg);
    }

    private static String fmt(double d) {
        return String.format("%.5f", d);
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
