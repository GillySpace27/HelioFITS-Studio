package org.helioviewer.jhv.plugins.pointcloud;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.DirectBufVertex;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;

// Turns a PointCloudData plus display parameters into ready-to-upload vertex buffers.
// The alpha-shape is not triangulated here: the producer shipped the Delaunay
// tetrahedra sorted by circumradius, so a given alpha selects a prefix of them, and
// the boundary is the set of triangular faces owned by exactly one selected tet.
final class PointCloudMesh {

    record Parameters(PointCloudData data, double alphaPct, String lutName, boolean colorByValue,
                      boolean showPoints, double pointSize, boolean showWire,
                      boolean showSurface, double opacity) {
    }

    record Result(Parameters parameters, DirectBufVertex points, DirectBufVertex wire, DirectBufVertex surface) {
    }

    static Result build(Parameters p) {
        PointCloudData d = p.data();
        LUT lut = LUT.get(p.lutName());
        byte[][] colorOf = pointColors(d, lut, p.colorByValue(), 255);

        DirectBufVertex points = p.showPoints() ? buildPoints(d, colorOf, p.pointSize()) : null;

        DirectBufVertex wire = null;
        DirectBufVertex surface = null;
        if (p.showWire() || p.showSurface()) {
            long[] faces = boundaryFaces(d, p.alphaPct());
            if (p.showSurface())
                surface = buildSurface(d, faces, lut, p.colorByValue(), p.opacity());
            if (p.showWire())
                wire = buildWire(d, faces);
        }
        return new Result(p, points, wire, surface);
    }

    // Degenerate (near-flat) tetrahedra carry a huge sentinel circumradius and must
    // never enter the mesh, even at alpha = 100 (the convex hull).
    static final float SENTINEL = 1e29f;

    static boolean selectable(float radius) {
        return Float.isFinite(radius) && radius < SENTINEL;
    }

    // Number of real tetrahedra selected at this alpha. alphaPct is a percentile over
    // the selectable circumradii, which is a perceptually even control. Radii are
    // sorted ascending, so the sentinels sit at the tail and are excluded here.
    static int tetCount(PointCloudData d, double alphaPct) {
        int real = d.numTets();
        while (real > 0 && !selectable(d.radiiSorted()[real - 1]))
            real--;
        return (int) Math.round(Math.clamp(alphaPct, 0, 100) / 100. * real);
    }

    // Boundary = faces appearing exactly once among the selected tets. Copy the prefix
    // of face keys, sort, and emit keys with no equal neighbour (a face is shared by at
    // most two tets, so duplicates are adjacent pairs). ~400k longs for the fabric.
    private static long[] boundaryFaces(PointCloudData d, double alphaPct) {
        int k = tetCount(d, alphaPct);
        long[] keys = Arrays.copyOf(d.faceKey(), 4 * k);
        Arrays.sort(keys);

        long[] out = new long[keys.length];
        int n = 0;
        for (int i = 0; i < keys.length; ) {
            int j = i + 1;
            while (j < keys.length && keys[j] == keys[i])
                j++;
            if (j - i == 1)
                out[n++] = keys[i];
            i = j;
        }
        return Arrays.copyOf(out, n);
    }

    private static int[] lutTable(LUT lut) {
        ByteBuffer b = (lut == null ? LUT.gray() : lut).rgba();
        int n = b.remaining() / 4;
        int[] t = new int[n];
        for (int i = 0; i < n; i++) {
            int r = b.get() & 0xFF, g = b.get() & 0xFF, bl = b.get() & 0xFF;
            b.get(); // skip source alpha
            t[i] = (r << 16) | (g << 8) | bl;
        }
        return t;
    }

    private static byte[][] pointColors(PointCloudData d, LUT lut, boolean colorByValue, int alpha) {
        byte[][] c = new byte[d.numPoints()][];
        int[] table = lutTable(lut);
        int last = table.length - 1;
        if (colorByValue && d.hasValues()) {
            float[] v = d.values();
            float lo = (float) (Double.isNaN(d.dataMin()) ? min(v) : d.dataMin());
            float hi = (float) (Double.isNaN(d.dataMax()) ? max(v) : d.dataMax());
            float span = hi > lo ? hi - lo : 1;
            for (int i = 0; i < c.length; i++) {
                int idx = (int) Math.clamp((v[i] - lo) / span * last, 0, last);
                c[i] = rgba(table[idx], alpha);
            }
        } else {
            byte[] solid = rgba(table[last * 220 / 255], alpha);
            Arrays.fill(c, solid);
        }
        return c;
    }

    private static DirectBufVertex buildPoints(PointCloudData d, byte[][] colorOf, double size) {
        BufVertex buf = new BufVertex(d.numPoints() * GLSLShape.stride);
        float[] pos = d.scenePos();
        for (int i = 0; i < d.numPoints(); i++)
            buf.putVertex(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], (float) size, colorOf[i]);
        return new DirectBufVertex(buf);
    }

    private static DirectBufVertex buildSurface(PointCloudData d, long[] faces, LUT lut, boolean colorByValue, double opacity) {
        int a255 = (int) Math.round(Math.clamp(opacity, 0, 1) * 255);
        byte[][] colorOf = pointColors(d, lut, colorByValue, a255); // Colors.bytes premultiplies alpha
        BufVertex buf = new BufVertex(3 * faces.length * GLSLShape.stride);
        float[] pos = d.scenePos();
        for (long f : faces) {
            emitVertex(buf, pos, PointCloudLoader.faceA(f), colorOf);
            emitVertex(buf, pos, PointCloudLoader.faceB(f), colorOf);
            emitVertex(buf, pos, PointCloudLoader.faceC(f), colorOf);
        }
        return new DirectBufVertex(buf);
    }

    private static void emitVertex(BufVertex buf, float[] pos, int i, byte[][] colorOf) {
        buf.putVertex(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], 1, colorOf[i]);
    }

    // Wireframe = the distinct edges of the boundary triangles (not all Delaunay edges).
    // Each edge is one GLSLLine polyline: Colors.Null sentinel, v0, v1, Colors.Null.
    private static DirectBufVertex buildWire(PointCloudData d, long[] faces) {
        long[] edges = new long[3 * faces.length];
        int n = 0;
        for (long f : faces) {
            int a = PointCloudLoader.faceA(f), b = PointCloudLoader.faceB(f), c = PointCloudLoader.faceC(f);
            edges[n++] = edgeKey(a, b);
            edges[n++] = edgeKey(a, c);
            edges[n++] = edgeKey(b, c);
        }
        Arrays.sort(edges);

        BufVertex buf = new BufVertex(4 * faces.length * GLSLLine.stride);
        float[] pos = d.scenePos();
        byte[] col = Colors.Blue;
        long prev = -1;
        for (long e : edges) {
            if (e == prev)
                continue;
            prev = e;
            int i = (int) (e >>> 21), j = (int) (e & 0x1FFFFF);
            buf.putVertex(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], 1, Colors.Null);
            buf.putVertex(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], 1, col);
            buf.putVertex(pos[3 * j], pos[3 * j + 1], pos[3 * j + 2], 1, col);
            buf.putVertex(pos[3 * j], pos[3 * j + 1], pos[3 * j + 2], 1, Colors.Null);
        }
        return new DirectBufVertex(buf);
    }

    private static long edgeKey(int i, int j) {
        int lo = Math.min(i, j), hi = Math.max(i, j);
        return ((long) lo << 21) | hi;
    }

    private static byte[] rgba(int packed, int alpha) {
        return Colors.bytes((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF, alpha);
    }

    private static float min(float[] v) {
        float m = Float.POSITIVE_INFINITY;
        for (float x : v)
            m = Math.min(m, x);
        return m;
    }

    private static float max(float[] v) {
        float m = Float.NEGATIVE_INFINITY;
        for (float x : v)
            m = Math.max(m, x);
        return m;
    }

    private PointCloudMesh() {
    }

}
