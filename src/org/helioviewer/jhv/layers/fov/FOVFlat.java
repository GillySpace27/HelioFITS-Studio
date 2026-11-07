package org.helioviewer.jhv.layers.fov;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;

/**
 * The FOV layer in the flat projections (HPC, Helioradial Unrolled, flat Helioradial, Latitudinal,
 * Observer Sky), where nothing is drawn as 3D geometry: fields projected onto the current
 * observer's map, the boundary of the solar surface each spacecraft sees, and where bodies are,
 * with an arrow at the edge for those off screen. The geometry is here, without GL, so a check can
 * pin it.
 */
final class FOVFlat {

    /** Points per straight 3D edge: a straight line in space is a curve on the sky. */
    static final int EDGE_STEPS = 32;
    /** Map coordinates beyond this are a projection's "cannot draw" answer, not a place. */
    private static final double OFF_MAP = 100;

    private FOVFlat() {}

    /** {@code n + 1} points from a to b, both included. */
    static List<double[]> segment(double[] a, double[] b, int n) {
        List<double[]> pts = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            pts.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2])});
        }
        return pts;
    }

    /** A closed outline, each side divided so it bends on the map as it should. */
    static List<double[]> loop(double[][] pts) {
        List<double[]> out = new ArrayList<>();
        int steps = pts.length >= 16 ? 1 : EDGE_STEPS; // a 72-point ring is fine as it is
        for (int i = 0; i < pts.length; i++) {
            List<double[]> s = segment(pts[i], pts[(i + 1) % pts.length], steps);
            out.addAll(i == 0 ? s : s.subList(1, s.size()));
        }
        return out;
    }

    /**
     * The edge of the solar surface seen from {@code observer} (world coordinates, solar radii):
     * the circle of points whose angle from the sub-observer point is acos(1 / distance), closed.
     * Null inside the Sun.
     */
    @Nullable
    static List<Vec3> coverage(double[] observer, int n) {
        double d = FOVCone.len(observer);
        if (!(d > 1))
            return null;
        double[] u = FOVCone.scale(observer, 1 / d);
        double[] a = FOVCone.across(new double[]{0, 0, 1}, u);
        if (a == null)
            a = FOVCone.across(new double[]{1, 0, 0}, u);
        double[] b = FOVCone.cross(u, a);
        double c = 1 / d, s = Math.sqrt(1 - c * c);
        List<Vec3> ring = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double phi = 2 * Math.PI * i / n;
            double ca = Math.cos(phi) * s, sb = Math.sin(phi) * s;
            ring.add(new Vec3(c * u[0] + ca * a[0] + sb * b[0], c * u[1] + ca * a[1] + sb * b[1], c * u[2] + ca * a[2] + sb * b[2]));
        }
        return ring;
    }

    /**
     * Whether a map shows the direction from {@code viewer} to {@code p}: the Sun-centred sky maps
     * (HPC, Helioradial) hold only the hemisphere facing the Sun, and a point behind the viewer
     * would land on them mirrored; Observer Sky holds the whole sky and says itself what it cannot.
     */
    static boolean onMap(boolean wholeSky, double[] viewer, double[] p) {
        // Points right beside the viewer (a field of SOHO's, seen from SDO) swing across the whole
        // sky for a tiny step in space and draw streaks that mean nothing: leave them out.
        double near = FOVCone.len(FOVCone.sub(p, viewer));
        if (near < NEAR_VIEWER * FOVCone.len(viewer))
            return false;
        return wholeSky || FOVCone.elongation(viewer, p) < 90;
    }

    /** A point closer to the viewer than this fraction of its distance from the Sun is not drawn. */
    static final double NEAR_VIEWER = 0.05;

    /** A world point on the current map (the units Transform.ortho2D sets), or null where it cannot be drawn. */
    @Nullable
    static Vec2 project(MapView mv, Viewport vp, double[] viewer, double[] p) {
        if (!onMap(mv.isObserverSky() || mv.isLatitudinal(), viewer, p))
            return null;
        Vec2 v = mv.projectToScreen(vp, new Vec3(p[0], p[1], p[2]));
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Math.abs(v.x) < OFF_MAP && Math.abs(v.y) < OFF_MAP ? v : null;
    }

    /** Projects a polyline, breaking it where a point cannot be drawn. */
    static void putLine(MapView mv, Viewport vp, double[] viewer, List<double[]> pts, byte[] color, BufVertex buf) {
        boolean open = false;
        for (double[] p : pts) {
            Vec2 v = project(mv, vp, viewer, p);
            if (v == null) {
                if (open)
                    buf.endLine();
                open = false;
                continue;
            }
            if (open)
                buf.putVertex((float) v.x, (float) v.y, 0, 1, color);
            else
                buf.startLine((float) v.x, (float) v.y, 0, 1, color);
            open = true;
        }
        if (open)
            buf.endLine();
    }

    /**
     * A point in the same position angle as {@code p} seen from {@code viewer}, but just inside 90
     * degrees from the Sun, for a body behind the viewer on a map that holds only the sunward half
     * of the sky: its arrow then points the right way round the edge. Null for a body on the Sun
     * line.
     */
    @Nullable
    static double[] towardEdge(double[] viewer, double[] p) {
        double d = FOVCone.len(viewer);
        double[] s = FOVCone.scale(viewer, -1 / d);
        double[] u = FOVCone.across(FOVCone.sub(p, viewer), s);
        if (u == null)
            return null;
        double a = Math.toRadians(89);
        return FOVCone.add(viewer, FOVCone.scale(FOVCone.add(FOVCone.scale(s, Math.cos(a)), FOVCone.scale(u, Math.sin(a))), d));
    }

    /**
     * Where a marker at (x, y) outside the box [x0, x1] x [y0, y1] is shown: on the box's edge, on
     * the line from the box's centre toward it. Null when it is inside.
     */
    @Nullable
    static double[] edgePoint(double x, double y, double x0, double x1, double y0, double y1) {
        if (x >= x0 && x <= x1 && y >= y0 && y <= y1)
            return null;
        double cx = 0.5 * (x0 + x1), cy = 0.5 * (y0 + y1);
        double dx = x - cx, dy = y - cy;
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x1 - cx : x0 - cx) / dx;
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y1 - cy : y0 - cy) / dy;
        double t = Math.min(tx, ty);
        return new double[]{cx + t * dx, cy + t * dy};
    }

}
