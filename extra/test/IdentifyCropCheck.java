package org.helioviewer.jhv.identify;

import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.math.Vec3;

/**
 * Highlight all can leave out what lies past the Crop (Gilly, 2026-11-22): a sight line is inside
 * when the imagery's surface meets it within the Crop radius. Numbers below are computed here, for
 * an observer at 215 solar radii: 215 tan 10 deg = 37.9, 215 sin 10 deg = 37.3 solar radii.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyCropCheck
 */
public final class IdentifyCropCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        double d = 215;
        double e = Math.toRadians(10);
        Vec3 tenDegrees = new Vec3(Math.sin(e), 0, -Math.cos(e)); // 10 deg from the Sun, which is down -z
        check(!IdentifyGlow.insideCrop(tenDegrees, d, 30, SurfaceModel.PlaneOfSky), "10 deg out (37.9 Rsun on the plane of sky) is past a 30 Rsun Crop");
        check(IdentifyGlow.insideCrop(tenDegrees, d, 45, SurfaceModel.PlaneOfSky), "and inside a 45 Rsun Crop");
        check(IdentifyGlow.insideCrop(tenDegrees, d, 37.6, SurfaceModel.ThomsonSphere)
                        && !IdentifyGlow.insideCrop(tenDegrees, d, 37.6, SurfaceModel.PlaneOfSky),
                "the surface decides: 37.3 Rsun on the Thomson sphere is inside 37.6, 37.9 on the plane is not");
        check(IdentifyGlow.insideCrop(new Vec3(0, 0, -1), d, 2, SurfaceModel.PlaneOfSky), "the Sun's centre is inside any Crop");
        check(IdentifyGlow.insideCrop(tenDegrees, d, 0, SurfaceModel.PlaneOfSky), "no Crop (auto) hides nothing");
        System.out.println(failures == 0 ? "IdentifyCropCheck: PASS" : "IdentifyCropCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private IdentifyCropCheck() {}
}
