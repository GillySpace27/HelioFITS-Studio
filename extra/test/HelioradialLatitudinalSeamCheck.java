package org.helioviewer.jhv.display;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The combined mode's disk band and corona band must meet at the limb, exactly.
 *
 * <p>HelioradialUnrolledLatitudinal is two maps glued along one coordinate line: below the
 * limb the vertical coordinate is heliocentric angle gamma from the sub-observer point, above
 * it the plane radius in solar radii. The glue holds because gamma = 90 degrees projects to
 * plane radius 1 for every observer distance, so both branches place the limb at the same
 * map position, parameterized by the same position angle. If either branch drifts (a changed
 * forward map in the shader's Java twin, a changed inverse, a changed Box-Cox limb anchor)
 * the imagery tears at the limb or the grid detaches from the picture. Nothing throws; the
 * seam is only visible on screen, which is why it is pinned here.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.HelioradialLatitudinalSeamCheck
 */
public final class HelioradialLatitudinalSeamCheck {

    private static int failures;

    public static void main(String[] args) {
        // A plain Earth-like viewpoint; SPICE is deliberately not needed.
        Position viewpoint = new Position(new JHVTime(0), 215, 0, 0);
        MapMode mode = MapMode.HelioradialUnrolledLatitudinal;

        for (double lambda : new double[]{1, 0, -1}) {
            Display.setWarpLambda(lambda);
            MapScale scale = MapScale.boxCoxRadial(6);

            // Disk round trip: map point -> surface point -> map point. Stays below the
            // gamma where the plane radius crosses 1 (89.47 degrees at 1 au), above which a
            // surface point legitimately lands on the corona branch at the seam.
            for (double angleDeg : new double[]{0, 77, 200}) {
                for (double mapY : new double[]{0.2, 0.6, 0.95}) {
                    Vec3 world = ProjectedMap.unproject(mode, viewpoint, Quat.ZERO, new Vec2(angleDeg, mapY));
                    Vec2 back = ProjectedMap.project(mode, viewpoint, scale, Quat.ZERO, world);
                    double dAngle = Math.abs((scale.toMapX(back.x + 0.5) - angleDeg + 540) % 360 - 180); // wrap-safe
                    near(dAngle, 0, 1e-9, "angle round trip at (" + angleDeg + "," + mapY + "), lambda=" + lambda);
                    near(back.y + 0.5, mapY * scale.warpLimb(), 1e-9, // unit-Y is the gamma fraction scaled into the band
                            "radial round trip at (" + angleDeg + "," + mapY + "), lambda=" + lambda);
                }
            }

            // Corona branch: a plane point at radius r must land exactly where
            // HelioradialUnrolled puts it (the off-limb map is unchanged).
            for (double r : new double[]{1.5, 4}) {
                Vec3 world = viewpoint.toQuat().rotateInverseVector(new Vec3(0, r, 0)); // position angle 0 = north = +y
                Vec2 pt = ProjectedMap.project(mode, viewpoint, scale, Quat.ZERO, world);
                near(pt.y + 0.5, scale.toUnitY(r), 1e-9, "corona branch matches the unrolled map at r=" + r + ", lambda=" + lambda);
            }

            // Seam continuity: the limb approached from the disk (gamma -> 90 degrees) and
            // from the corona (r -> 1) must land at the same map position, the Box-Cox limb
            // anchor. (unproject snaps to the visible surface, so world-space identity across
            // the seam is not the contract; the shared map line is.)
            double eps = 1e-6;
            Vec3 diskLimb = ProjectedMap.unproject(mode, viewpoint, Quat.ZERO, new Vec2(120, 1 - eps));
            Vec3 coronaLimb = ProjectedMap.unproject(mode, viewpoint, Quat.ZERO, new Vec2(120, 1 + eps));
            Vec2 diskSide = ProjectedMap.project(mode, viewpoint, scale, Quat.ZERO, diskLimb);
            Vec2 coronaSide = ProjectedMap.project(mode, viewpoint, scale, Quat.ZERO, coronaLimb);
            near(diskSide.y + 0.5, scale.warpLimb(), 1e-4, "the limb from below sits at the Box-Cox limb anchor");
            near(coronaSide.y + 0.5, scale.warpLimb(), 1e-4, "the limb from above sits at the Box-Cox limb anchor");

            // A surface point at heliocentric angle gamma lands at the gamma fraction of the
            // disk band: the registration the shader's forward map must reproduce.
            // 89.47 degrees (1 au) starts the fold strip that deliberately lands on the seam,
            // so the sweep stops just short of it.
            for (double gammaDeg : new double[]{10, 45, 80, 89}) {
                double gamma = Math.toRadians(gammaDeg);
                // Position angle 0 is solar north, +y in viewpoint space (PolarBasis).
                Vec3 view = new Vec3(0, Math.sin(gamma), Math.cos(gamma));
                Vec3 world = viewpoint.toQuat().rotateInverseVector(view);
                Vec2 pt = ProjectedMap.project(mode, viewpoint, scale, Quat.ZERO, world);
                near(pt.y + 0.5, gammaDeg / 90 * scale.warpLimb(), 1e-9,
                        "surface point at gamma=" + gammaDeg + " lands at its gamma fraction, lambda=" + lambda);
            }
        }
        Display.setWarpLambda(0); // leave the global where the app defaults it

        System.out.println(failures == 0 ? "HelioradialLatitudinalSeamCheck: PASS" : "HelioradialLatitudinalSeamCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private HelioradialLatitudinalSeamCheck() {}
}
