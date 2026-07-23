package org.helioviewer.jhv.plugins.pointcloud;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

// Self-contained incremental 3-D Delaunay triangulation (Bowyer-Watson), plain Java, no
// third-party dependencies. Point clouds are small (~10^2-10^3 points), so the cavity is
// found by a linear scan over the live tetrahedra rather than by a walk + flood fill:
// simpler, and the O(n^2) total is a few million predicate evaluations.
//
// All points are enclosed in a super-tetrahedron; every tet still referencing one of its
// four vertices is dropped at the end, leaving the Delaunay tets of the input alone.
// Insertion order is shuffled with a fixed seed - it breaks the degenerate patterns of
// sorted input while keeping the output bit-for-bit reproducible.
//
// Predicates run in double even though the cloud is float: the insphere determinant is
// degree 5 in the coordinates, so float would lose the sign long before the tolerance.
final class Delaunay3D {

    // Circumradius of the super-tetrahedron, in units of the half-diagonal of the input
    // bounding box. Two-sided constraint, measured against scipy/Qhull on the CME clouds:
    // below ~3e4 the super-tet swallows the flattest convex-hull slivers (their circumsphere
    // reaches past it) and they are lost; above ~5e6 the coordinate range costs the insphere
    // determinant too many digits and the mesh degrades. 3e5 is the middle of that window.
    private static final double SUPER_SCALE = 3e5;

    private static final double INSPHERE_EPS = 1e-13;  // relative, on the 4x4 determinant
    private static final double SINGULAR_EPS = 1e-13;  // relative, on the circumcentre 3x3
    private static final float DEGENERATE_RADIUS = 1e30f;

    private Delaunay3D() {
    }

    // Delaunay tetrahedra of the given points. pts is a flat [x0,y0,z0, x1,y1,z1, ...] array of
    // numPoints points. Returns a flat int[] of 4*numTets vertex indices into the point array.
    static int[] triangulate(float[] pts, int numPoints) {
        if (numPoints < 4 || pts.length < 3 * numPoints)
            return new int[0];

        double[] c = new double[3 * (numPoints + 4)];
        for (int i = 0; i < 3 * numPoints; i++)
            c[i] = pts[i];
        if (!addSuperTetrahedron(c, numPoints))
            return new int[0];

        Mesh mesh = new Mesh();
        mesh.add(c, numPoints, numPoints + 1, numPoints + 2, numPoints + 3);

        int[] order = shuffledOrder(numPoints);
        IntList cavity = new IntList();
        IntList faces = new IntList();
        Map<Long, Integer> shared = new HashMap<>();

        for (int oi = 0; oi < numPoints; oi++) {
            int p = order[oi];

            cavity.clear();
            for (int t = 0; t < mesh.count; t++)
                if (mesh.alive[t] && inSphere(c, mesh.v[4 * t], mesh.v[4 * t + 1], mesh.v[4 * t + 2], mesh.v[4 * t + 3], p))
                    cavity.add(t);
            if (cavity.size == 0) // outside every circumsphere: cannot happen inside the super-tet
                continue;

            // A face interior to the cavity is used by two of its tets; the boundary faces are
            // the ones used once. Counted first, emitted in tet order afterwards, so the result
            // does not depend on hash iteration order.
            shared.clear();
            for (int i = 0; i < cavity.size; i++)
                for (int f = 0; f < 4; f++)
                    shared.merge(faceKey(mesh, cavity.a[i], f), 1, Integer::sum);

            faces.clear();
            for (int i = 0; i < cavity.size; i++) {
                int t = cavity.a[i];
                for (int f = 0; f < 4; f++) {
                    if (shared.get(faceKey(mesh, t, f)) != 1)
                        continue;
                    faces.add(mesh.v[4 * t + FACE[3 * f]]);
                    faces.add(mesh.v[4 * t + FACE[3 * f + 1]]);
                    faces.add(mesh.v[4 * t + FACE[3 * f + 2]]);
                }
            }
            if (faces.size < 12) { // degenerate cavity boundary: drop the point rather than corrupt the mesh
                continue;
            }

            for (int i = 0; i < cavity.size; i++)
                mesh.remove(cavity.a[i]);
            for (int i = 0; i < faces.size; i += 3)
                mesh.add(c, faces.a[i], faces.a[i + 1], faces.a[i + 2], p);
        }

        int numTets = 0;
        for (int t = 0; t < mesh.count; t++)
            if (mesh.isReal(t, numPoints))
                numTets++;

        int[] out = new int[4 * numTets];
        int k = 0;
        for (int t = 0; t < mesh.count; t++) {
            if (!mesh.isReal(t, numPoints))
                continue;
            out[k++] = mesh.v[4 * t];
            out[k++] = mesh.v[4 * t + 1];
            out[k++] = mesh.v[4 * t + 2];
            out[k++] = mesh.v[4 * t + 3];
        }
        return out;
    }

    // Circumradius of each tetrahedron. tets is the flat array returned by triangulate().
    // Degenerate/near-flat tets get DEGENERATE_RADIUS, which the caller treats as a sentinel.
    static float[] circumradii(float[] pts, int[] tets) {
        int numTets = tets.length / 4;
        float[] radii = new float[numTets];
        for (int t = 0; t < numTets; t++) {
            int a = 3 * tets[4 * t], b = 3 * tets[4 * t + 1], d = 3 * tets[4 * t + 2], e = 3 * tets[4 * t + 3];
            // rows of the 3x3 system 2*(vi).x = |vi|^2, vi = b-a, c-a, d-a; R = |x|
            double m00 = pts[b] - pts[a], m01 = pts[b + 1] - pts[a + 1], m02 = pts[b + 2] - pts[a + 2];
            double m10 = pts[d] - pts[a], m11 = pts[d + 1] - pts[a + 1], m12 = pts[d + 2] - pts[a + 2];
            double m20 = pts[e] - pts[a], m21 = pts[e + 1] - pts[a + 1], m22 = pts[e + 2] - pts[a + 2];

            double c0 = m11 * m22 - m12 * m21, c1 = m12 * m20 - m10 * m22, c2 = m10 * m21 - m11 * m20;
            double det = m00 * c0 + m01 * c1 + m02 * c2;
            double scale = Math.sqrt((m00 * m00 + m01 * m01 + m02 * m02) *
                    (m10 * m10 + m11 * m11 + m12 * m12) * (m20 * m20 + m21 * m21 + m22 * m22));
            if (!(Math.abs(det) > SINGULAR_EPS * scale)) { // flat or repeated vertices
                radii[t] = DEGENERATE_RADIUS;
                continue;
            }

            double r0 = .5 * (m00 * m00 + m01 * m01 + m02 * m02);
            double r1 = .5 * (m10 * m10 + m11 * m11 + m12 * m12);
            double r2 = .5 * (m20 * m20 + m21 * m21 + m22 * m22);
            // Cramer: x = M^-1 r, with the cofactors above reused as the first column of adj(M)
            double x = (r0 * c0 + r1 * (m02 * m21 - m01 * m22) + r2 * (m01 * m12 - m02 * m11)) / det;
            double y = (r0 * c1 + r1 * (m00 * m22 - m02 * m20) + r2 * (m02 * m10 - m00 * m12)) / det;
            double z = (r0 * c2 + r1 * (m01 * m20 - m00 * m21) + r2 * (m00 * m11 - m01 * m10)) / det;

            double radius = Math.sqrt(x * x + y * y + z * z);
            radii[t] = radius < DEGENERATE_RADIUS ? (float) radius : DEGENERATE_RADIUS;
        }
        return radii;
    }

    // Regular tetrahedron around the bounding sphere of the cloud, written into slots
    // numPoints .. numPoints+3. False if the cloud has no extent at all.
    private static boolean addSuperTetrahedron(double[] c, int numPoints) {
        double lox = Double.MAX_VALUE, loy = Double.MAX_VALUE, loz = Double.MAX_VALUE;
        double hix = -Double.MAX_VALUE, hiy = -Double.MAX_VALUE, hiz = -Double.MAX_VALUE;
        for (int i = 0; i < numPoints; i++) {
            double x = c[3 * i], y = c[3 * i + 1], z = c[3 * i + 2];
            if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)))
                return false;
            lox = Math.min(lox, x);
            hix = Math.max(hix, x);
            loy = Math.min(loy, y);
            hiy = Math.max(hiy, y);
            loz = Math.min(loz, z);
            hiz = Math.max(hiz, z);
        }
        double dx = hix - lox, dy = hiy - loy, dz = hiz - loz;
        double radius = .5 * Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(radius > 0))
            return false;

        double s = SUPER_SCALE * radius / Math.sqrt(3);
        double cx = .5 * (lox + hix), cy = .5 * (loy + hiy), cz = .5 * (loz + hiz);
        for (int i = 0; i < 4; i++) {
            c[3 * (numPoints + i)] = cx + s * SUPER_DIR[3 * i];
            c[3 * (numPoints + i) + 1] = cy + s * SUPER_DIR[3 * i + 1];
            c[3 * (numPoints + i) + 2] = cz + s * SUPER_DIR[3 * i + 2];
        }
        return true;
    }

    private static final double[] SUPER_DIR = {1, 1, 1, 1, -1, -1, -1, 1, -1, -1, -1, 1};

    // Face f of a tet, as vertex slots, oriented so that appending a point outside the tet
    // yields positive orientation (see orient()).
    private static final int[] FACE = {1, 2, 3, 0, 3, 2, 0, 1, 3, 0, 2, 1};

    private static long faceKey(Mesh mesh, int t, int f) {
        int a = mesh.v[4 * t + FACE[3 * f]], b = mesh.v[4 * t + FACE[3 * f + 1]], c = mesh.v[4 * t + FACE[3 * f + 2]];
        int m; // sort the triple: the key must not depend on which side the face is seen from
        if (a > b) { m = a; a = b; b = m; }
        if (b > c) { m = b; b = c; c = m; }
        if (a > b) { m = a; a = b; b = m; }
        return ((long) a << 42) | ((long) b << 21) | c;
    }

    private static int[] shuffledOrder(int numPoints) {
        int[] order = new int[numPoints];
        for (int i = 0; i < numPoints; i++)
            order[i] = i;
        Random rnd = new Random(42); // fixed seed: same input -> same triangulation
        for (int i = numPoints - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order;
    }

    // 6x the signed volume of (a,b,c,d): det[b-a, c-a, d-a].
    private static double orient(double[] p, int a, int b, int c, int d) {
        double ax = p[3 * b] - p[3 * a], ay = p[3 * b + 1] - p[3 * a + 1], az = p[3 * b + 2] - p[3 * a + 2];
        double bx = p[3 * c] - p[3 * a], by = p[3 * c + 1] - p[3 * a + 1], bz = p[3 * c + 2] - p[3 * a + 2];
        double cx = p[3 * d] - p[3 * a], cy = p[3 * d + 1] - p[3 * a + 1], cz = p[3 * d + 2] - p[3 * a + 2];
        return ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx);
    }

    // Is e strictly inside the circumsphere of the positively oriented tet (a,b,c,d)?
    // 4x4 determinant of the rows [q-e, |q-e|^2]; that determinant is negative for an
    // interior e when orient(a,b,c,d) > 0. Ties (near-cospherical) count as outside.
    private static boolean inSphere(double[] p, int a, int b, int c, int d, int e) {
        double ex = p[3 * e], ey = p[3 * e + 1], ez = p[3 * e + 2];
        double ax = p[3 * a] - ex, ay = p[3 * a + 1] - ey, az = p[3 * a + 2] - ez;
        double bx = p[3 * b] - ex, by = p[3 * b + 1] - ey, bz = p[3 * b + 2] - ez;
        double cx = p[3 * c] - ex, cy = p[3 * c + 1] - ey, cz = p[3 * c + 2] - ez;
        double dx = p[3 * d] - ex, dy = p[3 * d + 1] - ey, dz = p[3 * d + 2] - ez;
        double aw = ax * ax + ay * ay + az * az;
        double bw = bx * bx + by * by + bz * bz;
        double cw = cx * cx + cy * cy + cz * cz;
        double dw = dx * dx + dy * dy + dz * dz;

        // Laplace by complementary 2x2 minors: columns (x,y) against columns (z,w)
        double mab = ax * by - bx * ay, nab = az * bw - bz * aw;
        double mac = ax * cy - cx * ay, nac = az * cw - cz * aw;
        double mad = ax * dy - dx * ay, nad = az * dw - dz * aw;
        double mbc = bx * cy - cx * by, nbc = bz * cw - cz * bw;
        double mbd = bx * dy - dx * by, nbd = bz * dw - dz * bw;
        double mcd = cx * dy - dx * cy, ncd = cz * dw - dz * cw;

        double det = mab * ncd - mac * nbd + mad * nbc + mbc * nad - mbd * nac + mcd * nab;
        double mag = Math.abs(mab * ncd) + Math.abs(mac * nbd) + Math.abs(mad * nbc)
                + Math.abs(mbc * nad) + Math.abs(mbd * nac) + Math.abs(mcd * nab);
        return det < -INSPHERE_EPS * mag;
    }

    // Tetrahedra as flat quadruples with a free list; slots are reused as cavities are
    // carved and refilled, so the arrays stay near the live tet count.
    private static final class Mesh {

        private int[] v = new int[4 * 256];
        private boolean[] alive = new boolean[256];
        private int count; // high-water mark of used slots
        private int[] free = new int[64];
        private int freeCount;

        void add(double[] p, int a, int b, int c, int d) {
            if (orient(p, a, b, c, d) < 0) { // normalize: the insphere sign test assumes it
                int t = a;
                a = b;
                b = t;
            }
            int s;
            if (freeCount > 0)
                s = free[--freeCount];
            else {
                if (count == alive.length) {
                    int[] nv = new int[2 * v.length];
                    System.arraycopy(v, 0, nv, 0, v.length);
                    v = nv;
                    boolean[] na = new boolean[2 * alive.length];
                    System.arraycopy(alive, 0, na, 0, alive.length);
                    alive = na;
                }
                s = count++;
            }
            v[4 * s] = a;
            v[4 * s + 1] = b;
            v[4 * s + 2] = c;
            v[4 * s + 3] = d;
            alive[s] = true;
        }

        void remove(int s) {
            alive[s] = false;
            if (freeCount == free.length) {
                int[] nf = new int[2 * free.length];
                System.arraycopy(free, 0, nf, 0, free.length);
                free = nf;
            }
            free[freeCount++] = s;
        }

        boolean isReal(int s, int numPoints) {
            return alive[s] && v[4 * s] < numPoints && v[4 * s + 1] < numPoints
                    && v[4 * s + 2] < numPoints && v[4 * s + 3] < numPoints;
        }
    }

    private static final class IntList {

        private int[] a = new int[256];
        private int size;

        void clear() {
            size = 0;
        }

        void add(int x) {
            if (size == a.length) {
                int[] na = new int[2 * a.length];
                System.arraycopy(a, 0, na, 0, a.length);
                a = na;
            }
            a[size++] = x;
        }
    }

}
