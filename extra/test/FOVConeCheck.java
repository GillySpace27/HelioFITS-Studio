package org.helioviewer.jhv.layers.fov;

import java.util.HashSet;
import java.util.Set;

import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

/**
 * The FOV layer's 3D cones (FOVCone, FOVInstrument.cone) and the catalog's view switches. Pins:
 * every ray is as long as the spacecraft's distance from the Sun; a coronagraph's annulus spans the
 * elongations its solar radii give at that distance; an off-Sun field (HI, SoloHI, WISPR) lies on
 * the side it names, spans its elongations and is square; a side straight along the Sun line gives
 * no field; a rectangle's corners sit at its half angles; an instrument before its first light is
 * not flying; names are unique within each platform (they are the session keys); Cones and Labels
 * survive the session JSON and default on. Pure geometry: no GL, no SPICE.
 */
public final class FOVConeCheck {

    private static int failures;

    public static void main(String[] args) {
        double[] earth = {215, 0, 0};
        double[] north = {0, 0, 1};
        JSONObject none = new JSONObject();

        // A coronagraph about the Sun line, in solar radii (LASCO C2, 2.2 to 6).
        FOVInstrument c2 = FOVInstrument.solarRadii("LASCO C2", 2.2, 6, null, none);
        FOVCone ann = c2.cone(earth, north, null);
        expect(ann != null && ann.inner != null, "a centred coronagraph is an annulus with a hole");
        double out = Math.toDegrees(Math.atan2(6, 215)), in = Math.toDegrees(Math.atan2(2.2, 215));
        boolean lengths = true, outerOk = true, innerOk = true;
        for (int i = 0; i < FOVCone.RING; i++) {
            lengths &= close(FOVCone.len(FOVCone.sub(ann.outer[i], earth)), 215);
            outerOk &= close(FOVCone.elongation(earth, ann.outer[i]), out);
            innerOk &= close(FOVCone.elongation(earth, ann.inner[i]), in);
        }
        expect(lengths, "every ray is as long as the spacecraft is from the Sun");
        expect(outerOk, "the outer edge is at atan(6 Rsun / 215 Rsun) = " + fmt(out) + " deg");
        expect(innerOk, "the occulter edge is at atan(2.2 / 215) = " + fmt(in) + " deg");
        double[] label = ann.labelAnchor();
        expect(label[2] > 0 && Math.abs(label[1]) < 1e-9, "an annulus is labelled at its northern edge, not under the Sun");

        // An off-Sun field toward Earth, from a spacecraft 90 degrees from Earth (HI-1, 4 to 24 deg).
        double[] sta = {0, 205, 0};
        FOVInstrument hi1 = FOVInstrument.elongation("HI-1", 4, 24, FOVInstrument.Side.EARTH, null, none);
        double[] towardEarth = FOVCone.sub(earth, sta);
        FOVCone side = hi1.cone(sta, north, towardEarth);
        expect(side != null && side.inner == null && side.outer.length == 4, "an off-Sun field is a four-sided cone");
        double minE = 1e9, maxE = 0;
        boolean earthSide = true;
        double[] s = FOVCone.scale(sta, -1 / FOVCone.len(sta));
        double[] u = FOVCone.across(towardEarth, s);
        for (double[] p : side.outer) {
            double e = FOVCone.elongation(sta, p);
            minE = Math.min(minE, e);
            maxE = Math.max(maxE, e);
            earthSide &= FOVCone.dot(FOVCone.sub(p, sta), u) > 0;
        }
        expect(earthSide, "HI-1 looks to the side of the Sun where Earth is");
        // Its corners are 10 deg either side of the centre line at 4 and 24 deg: cos e = cos(edge) cos(10).
        double near = Math.toDegrees(Math.acos(Math.cos(Math.toRadians(4)) * Math.cos(Math.toRadians(10))));
        double far = Math.toDegrees(Math.acos(Math.cos(Math.toRadians(24)) * Math.cos(Math.toRadians(10))));
        expect(close(minE, near) && close(maxE, far),
                "it spans 4 to 24 deg along its centre line (corners " + fmt(near) + " and " + fmt(far) + ", got " + fmt(minE) + " and " + fmt(maxE) + ")");
        double across = Math.toDegrees(Math.acos(FOVCone.dot(unit(FOVCone.sub(side.outer[1], sta)), unit(FOVCone.sub(side.outer[2], sta)))));
        expect(close(across, 20), "and is as wide across as it is long (20 deg, got " + fmt(across) + ")");
        expect(FOVCone.toSide(sta, s, 4, 24) == null, "a side straight at the Sun gives no field");

        // A rectangle about the Sun line.
        FOVInstrument rect = new FOVInstrument("FSI", FOVInstrument.FOVType.RECTANGULAR, 0, 4, 2, none);
        FOVCone r = rect.cone(earth, north, null);
        double diag = Math.toDegrees(Math.atan(Math.hypot(Math.tan(Math.toRadians(2)), Math.tan(Math.toRadians(1)))));
        boolean corners = true;
        for (double[] p : r.outer)
            corners &= close(FOVCone.elongation(earth, p), diag);
        expect(corners, "a 4 by 2 deg rectangle's corners are at its half angles (" + fmt(diag) + " deg off the Sun line)");

        // First light.
        FOVInstrument ccor1 = FOVInstrument.solarRadii("CCOR-1", 3.7, 17, "2024-06-25", none);
        expect(!ccor1.flyingAt(new JHVTime("2024-06-24T23:59:59")) && ccor1.flyingAt(new JHVTime("2024-06-25T00:00:00")),
                "CCOR-1 is not drawn before 2024-06-25");

        // The catalog.
        FOVCatalog catalog = new FOVCatalog(null);
        boolean unique = true;
        Set<String> platformNames = new HashSet<>();
        for (FOVPlatform p : catalog.platforms()) {
            unique &= platformNames.add(p.toString());
            Set<String> names = new HashSet<>();
            for (FOVInstrument i : p.instruments())
                unique &= names.add(i.toString());
        }
        expect(unique, "platform and instrument names are unique (they are the session keys)");
        expect(catalog.cones() && catalog.labels(), "Cones and Labels are on by default");
        catalog.setCones(false);
        catalog.setLabels(false);
        JSONObject saved = new JSONObject();
        catalog.serialize(saved);
        FOVCatalog back = new FOVCatalog(new JSONObject(saved.toString()));
        expect(!back.cones() && !back.labels(), "Cones and Labels survive the session JSON");

        if (failures != 0) {
            System.out.println("FOVConeCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("FOVConeCheck: PASS");
        System.exit(0);
    }

    private static double[] unit(double[] v) {
        return FOVCone.scale(v, 1 / FOVCone.len(v));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-6 * Math.max(1, Math.abs(b));
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
