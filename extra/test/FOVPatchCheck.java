package org.helioviewer.jhv.layers.fov;

/**
 * The far patch of a field of view (FOVCone.patch): every point of it on the sphere about the
 * spacecraft, its edge the outline the cone already had, and the shapes it takes for each kind of
 * field. No GL.
 */
public final class FOVPatchCheck {

    private static int failures;

    public static void main(String[] args) {
        double[] observer = {0, 0, 200}; // 200 solar radii from the Sun, on +Z
        double[] north = {0, 1, 0};
        double d = FOVCone.len(observer);

        FOVCone annulus = FOVCone.annulus(observer, north, 5, 45);
        FOVCone.Patch ring = annulus.patch(8);
        expect(ring.wrap() && ring.rows().length == 9 && ring.rows()[0].length == FOVCone.RING, "an annulus gives a wrapped ring of rows");
        expect(onSphere(ring, observer, d), "every point of the annulus's patch is a spacecraft distance from the spacecraft");
        expect(near(ring.rows()[8][3], annulus.outer[3]), "its last row is the outer outline");
        expect(near(ring.rows()[0][3], annulus.inner[3]), "its first row is the inner outline");

        FOVCone disk = FOVCone.annulus(observer, north, 0, 20);
        expect(near(disk.patch(8).rows()[0][10], new double[]{0, 0, 0}), "a disk's patch starts at Sun centre, the far end of the Sun line");

        FOVCone side = FOVCone.toSide(observer, new double[]{1, 0, 0}, 13.5, 53);
        FOVCone.Patch grid = side.patch(6);
        expect(!grid.wrap() && grid.rows().length == 7 && grid.rows()[0].length == 7, "an off-Sun field gives a grid");
        expect(onSphere(grid, observer, d), "every point of the grid is a spacecraft distance from the spacecraft");
        expect(near(grid.rows()[0][0], side.outer[0]) && near(grid.rows()[0][6], side.outer[1])
                && near(grid.rows()[6][6], side.outer[2]) && near(grid.rows()[6][0], side.outer[3]), "its corners are the field's four corners");

        // A chord between two far corners cuts inside the sphere; the patch's middle edge does not.
        double[] mid = grid.rows()[0][3];
        double[] chord = FOVCone.lerp(side.outer[0], side.outer[1], 0.5);
        expect(FOVCone.len(FOVCone.sub(mid, observer)) > FOVCone.len(FOVCone.sub(chord, observer)) + 1e-6, "an edge of the patch bulges out past the chord between its corners");

        if (failures > 0) {
            System.out.println("FOVPatchCheck: " + failures + " failed");
            System.exit(1);
        }
    }

    private static boolean onSphere(FOVCone.Patch p, double[] apex, double d) {
        for (double[][] row : p.rows())
            for (double[] q : row)
                if (!(Math.abs(FOVCone.len(FOVCone.sub(q, apex)) - d) <= 1e-6 * d)) // also false for NaN
                    return false;
        return true;
    }

    private static boolean near(double[] a, double[] b) {
        return FOVCone.len(FOVCone.sub(a, b)) < 1e-6 * Math.max(1, FOVCone.len(b));
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
