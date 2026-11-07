package org.helioviewer.jhv.layers.fov;

import java.util.List;

import org.helioviewer.jhv.math.Vec3;

/**
 * The FOV layer in the flat projections (FOVFlat). Pins: a straight edge is divided with both ends
 * kept; a four-cornered outline is divided and closed, a ring is not divided again; the coverage
 * ring is on the solar surface, exactly on the limb seen from the spacecraft, closed, and absent
 * inside the Sun; an off-screen marker lands on the box edge on the line from the centre, and an
 * on-screen one gets none; a body behind the viewer is turned to 89 degrees at the same position
 * angle; the Sun-centred sky maps drop the far hemisphere and Observer Sky keeps it; points right beside
 * the viewer are left out. Pure geometry.
 */
public final class FOVFlatCheck {

    private static int failures;

    public static void main(String[] args) {
        List<double[]> seg = FOVFlat.segment(new double[]{0, 0, 0}, new double[]{2, 4, 6}, 4);
        expect(seg.size() == 5 && same(seg.get(0), new double[]{0, 0, 0}) && same(seg.get(4), new double[]{2, 4, 6})
                && same(seg.get(2), new double[]{1, 2, 3}), "a straight edge is divided with both ends kept");

        double[][] square = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
        List<double[]> loop = FOVFlat.loop(square);
        expect(loop.size() == 4 * FOVFlat.EDGE_STEPS + 1 && same(loop.getFirst(), loop.getLast()),
                "a four-cornered outline is divided and closed (" + loop.size() + " points)");
        double[][] ring = new double[72][];
        for (int i = 0; i < 72; i++)
            ring[i] = new double[]{Math.cos(i * Math.PI / 36), Math.sin(i * Math.PI / 36), 0};
        expect(FOVFlat.loop(ring).size() == 73, "a 72-point ring is closed but not divided again");

        double[] earth = {150, 120, 30};
        double d = FOVCone.len(earth);
        List<Vec3> cov = FOVFlat.coverage(earth, 180);
        boolean onSun = true, onLimb = true;
        for (Vec3 p : cov) {
            onSun &= Math.abs(p.length() - 1) < 1e-9;
            // The limb: the line of sight from the spacecraft grazes the surface, so it is perpendicular to the radius.
            onLimb &= Math.abs((earth[0] - p.x) * p.x + (earth[1] - p.y) * p.y + (earth[2] - p.z) * p.z) < 1e-9;
        }
        expect(onSun, "the coverage ring is on the solar surface");
        expect(onLimb, "and exactly on the limb seen from " + Math.round(d) + " Rsun");
        expect(cov.size() == 181 && Math.abs(cov.getFirst().x - cov.getLast().x) < 1e-12, "and it is closed");
        expect(FOVFlat.coverage(new double[]{0.5, 0, 0}, 36) == null, "and there is none from inside the Sun");

        expect(FOVFlat.edgePoint(0.1, 0.2, -1, 1, -0.5, 0.5) == null, "an on-screen marker gets no arrow");
        double[] e = FOVFlat.edgePoint(4, 1, -1, 1, -0.5, 0.5);
        expect(e != null && Math.abs(e[0] - 1) < 1e-12 && Math.abs(e[1] - 0.25) < 1e-12,
                "one off to the right lands on the right edge, on the line from the centre (got " + str(e) + ")");
        double[] top = FOVFlat.edgePoint(0.2, 5, -1, 1, -0.5, 0.5);
        expect(top != null && Math.abs(top[1] - 0.5) < 1e-12 && Math.abs(top[0] - 0.02) < 1e-12,
                "one far above lands on the top edge (got " + str(top) + ")");

        double[] viewer = {215, 0, 0};
        double[] behind = {300, 40, 10}; // further from the Sun than the viewer: on the far side of its sky
        expect(FOVCone.elongation(viewer, behind) > 90, "the test body is more than 90 deg from the Sun");
        double[] q = FOVFlat.towardEdge(viewer, behind);
        double[] across = FOVCone.across(FOVCone.sub(behind, viewer), new double[]{-1, 0, 0});
        double[] acrossQ = FOVCone.across(FOVCone.sub(q, viewer), new double[]{-1, 0, 0});
        expect(Math.abs(FOVCone.elongation(viewer, q) - 89) < 1e-9 && FOVCone.dot(across, acrossQ) > 1 - 1e-12,
                "a body behind the viewer is turned to 89 deg at the same position angle");
        expect(!FOVFlat.onMap(false, viewer, behind) && FOVFlat.onMap(true, viewer, behind) && FOVFlat.onMap(false, viewer, new double[]{0, 5, 0}),
                "HPC and Helioradial drop the far hemisphere; Observer Sky keeps it");
        expect(!FOVFlat.onMap(true, viewer, new double[]{214, 1, 0}) && FOVFlat.onMap(true, viewer, new double[]{200, 0, 0}),
                "a point beside the viewer (1.4 Rsun away) is left out, one 15 Rsun sunward is kept");

        if (failures != 0) {
            System.out.println("FOVFlatCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("FOVFlatCheck: PASS");
        System.exit(0);
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-12 && Math.abs(a[1] - b[1]) < 1e-12 && Math.abs(a[2] - b[2]) < 1e-12;
    }

    private static String str(double[] v) {
        return v == null ? "null" : v[0] + ", " + v[1];
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
