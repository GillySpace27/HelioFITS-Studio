package org.helioviewer.jhv.layers.grid;

import org.joml.Vector3f;

import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.Transform;

/**
 * How the observer's place in the 3D scene is marked: not at all, a dot, a camera or an eye.
 *
 * <p>The glyphs are small pixel-art grids drawn as flat quads that face the screen: the scene is
 * an orthographic view of a rotated frame, so "facing the screen" is found by asking the current
 * matrices where three unit steps land and inverting that, rather than assuming any rotation
 * convention. They go through the same vertex stage as every other 3D mark, so they stay on the
 * observer under the Helioradial warp.
 */
public final class ViewpointMarker {

    public enum Style {
        HIDDEN("Hidden"), DOT("Dot"), CAMERA("Camera"), EYE("Eye");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Style parse(String name, Style fallback) {
            for (Style s : values())
                if (s.name().equalsIgnoreCase(name))
                    return s;
            return fallback;
        }
    }

    private static final String[] CAMERA = {
            "...####....",
            "###########",
            "#.........#",
            "#...###...#",
            "#..#...#..#",
            "#..#...#..#",
            "#...###...#",
            "###########"};

    private static final String[] EYE = {
            "...#####...",
            ".##.....##.",
            "#...###...#",
            "#..#####..#",
            "#...###...#",
            ".##.....##.",
            "...#####..."};

    /** Vertices a glyph can need: every cell of the largest, as two triangles. */
    public static final int MAX_VERTICES = 6 * 11 * 8;

    /** The glyph's rows, or null for a style that is not a glyph. */
    public static String[] glyph(Style style) {
        return switch (style) {
            case CAMERA -> CAMERA;
            case EYE -> EYE;
            default -> null;
        };
    }

    /**
     * Fills {@code buf} with the glyph centred on {@code (0, 0, distance)} of the current view,
     * each cell {@code cellPx} pixels square on a viewport of the given size. Returns false when
     * the view is degenerate (nothing to face), leaving the buffer empty.
     */
    public static boolean build(BufVertex buf, Style style, double distance, double cellPx, int vpWidth, int vpHeight,
                                byte[] color) {
        String[] rows = glyph(style);
        if (rows == null || vpWidth <= 0 || vpHeight <= 0)
            return false;

        Vector3f p0 = Transform.toNdc(0, 0, (float) distance, new Vector3f());
        double[][] j = new double[2][3]; // NDC change per unit step along each local axis
        for (int axis = 0; axis < 3; axis++) {
            Vector3f p = Transform.toNdc(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, (float) distance + (axis == 2 ? 1 : 0), new Vector3f());
            j[0][axis] = p.x - p0.x;
            j[1][axis] = p.y - p0.y;
        }
        // Minimum-norm local vectors that move the NDC point by one cell right / up: J^T (J J^T)^-1 d
        double a = dot(j[0], j[0]), b = dot(j[0], j[1]), d = dot(j[1], j[1]);
        double det = a * d - b * b;
        if (!(Math.abs(det) > 1e-12 * a * d)) // relative: a wide camera shrinks every term together
            return false;
        double[] u = step(j, a, b, d, det, cellPx * 2.0 / vpWidth, 0);
        double[] v = step(j, a, b, d, det, 0, cellPx * 2.0 / vpHeight);

        int h = rows.length, w = rows[0].length();
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                if (rows[r].charAt(c) != '#')
                    continue;
                double cx = c - (w - 1) / 2.0, cy = (h - 1) / 2.0 - r;
                quad(buf, cx, cy, u, v, distance, color);
            }
        return buf.getCount() > 0;
    }

    /**
     * Unit vectors, in the current view's local frame, that run along the screen's right and up:
     * what text needs to face the camera whatever the scene is rotated to. Null when the view is degenerate.
     */
    public static double[][] screenAxes(int vpWidth, int vpHeight) {
        if (vpWidth <= 0 || vpHeight <= 0)
            return null;
        Vector3f p0 = Transform.toNdc(0, 0, 0, new Vector3f());
        double[][] j = new double[2][3];
        for (int axis = 0; axis < 3; axis++) {
            Vector3f p = Transform.toNdc(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0, new Vector3f());
            j[0][axis] = p.x - p0.x;
            j[1][axis] = p.y - p0.y;
        }
        double a = dot(j[0], j[0]), b = dot(j[0], j[1]), d = dot(j[1], j[1]);
        double det = a * d - b * b;
        if (!(Math.abs(det) > 1e-12 * a * d)) // relative: a wide camera shrinks every term together
            return null;
        double[] u = step(j, a, b, d, det, 2.0 / vpWidth, 0), v = step(j, a, b, d, det, 0, 2.0 / vpHeight);
        double lu = Math.sqrt(dot(u, u)), lv = Math.sqrt(dot(v, v));
        for (int k = 0; k < 3; k++) {
            u[k] /= lu;
            v[k] /= lv;
        }
        return new double[][]{u, v};
    }

    private static double dot(double[] p, double[] q) {
        return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
    }

    private static double[] step(double[][] j, double a, double b, double d, double det, double dx, double dy) {
        // (J J^T)^-1 (dx, dy) = [d -b; -b a] / det
        double lx = (d * dx - b * dy) / det, ly = (-b * dx + a * dy) / det;
        return new double[]{j[0][0] * lx + j[1][0] * ly, j[0][1] * lx + j[1][1] * ly, j[0][2] * lx + j[1][2] * ly};
    }

    private static void quad(BufVertex buf, double cx, double cy, double[] u, double[] v, double distance, byte[] color) {
        float[][] corner = new float[4][3];
        for (int k = 0; k < 4; k++) {
            double sx = cx + ((k & 1) == 0 ? -0.5 : 0.5), sy = cy + ((k & 2) == 0 ? -0.5 : 0.5);
            corner[k][0] = (float) (sx * u[0] + sy * v[0]);
            corner[k][1] = (float) (sx * u[1] + sy * v[1]);
            corner[k][2] = (float) (distance + sx * u[2] + sy * v[2]);
        }
        int[] tri = {0, 1, 3, 0, 3, 2};
        for (int k : tri)
            buf.putVertex(corner[k][0], corner[k][1], corner[k][2], 1, color);
    }

    private ViewpointMarker() {}
}
