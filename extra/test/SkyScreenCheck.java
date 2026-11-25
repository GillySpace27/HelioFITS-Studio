package org.helioviewer.jhv.display;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.identify.Probes;
import org.helioviewer.jhv.identify.SkyScreen;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector4d;

/**
 * Identify in 3D Helioradial: {@code SkyScreen.place} against warpSurface.vert.
 *
 * <p>{@link #vertex} is the vertex stage in Java, written from the GLSL and not from SkyScreen,
 * WarpGeometry or SurfaceModel: a mesh vertex (position angle, warped radius) becomes the surface
 * point vWorld, its warped position, and a canvas pixel through the matrices ImageLayer.render and
 * Transform.ortho build (JOML, as the renderer does). The fragment stage samples that vertex along
 * normalize(vWorld - (0, 0, D)) with D the layer's own distance (warpSurface.frag), which is the
 * sight line {@code place} is asked about, so it has to answer with the vertex's pixel.
 *
 * <p>Where a sight line meets the surface twice (an observer outside the sphere, a limb ray that
 * meets the disk and the corona), the depth buffer shows the nearer; the check finds the twin by
 * scanning the same mesh and compares only the nearer. Inside the solar disk's silhouette the
 * orbited views can hide a point behind the sphere, which is left to {@link #hiddenByDisk}, a
 * brute-force depth test over the disk's own vertices.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.SkyScreenCheck
 */
public final class SkyScreenCheck {

    private static int failures;
    private static int compared, roundTrips, plainNulls, cusps;

    private static void expect(String what, boolean ok) {
        if (!ok) {
            if (failures++ < 40)
                System.out.println("  FAIL " + what);
        }
    }

    /** One configuration of the display, as the uniforms and the camera see it. */
    private record Cfg(MapView mv, Viewport vp, Position viewpoint, boolean perObserver, double k, double lambda, double crop,
                       double outer, double limb, Quaterniond view, java.util.Map<Position, Matrix4d[]> cache) {
        double dm(Position layer) {
            return (perObserver && layer.distance > 0 ? layer : viewpoint).distance;
        }

        /** {view, projection * view} as ImageLayer.render and Transform.ortho build them for this layer. */
        Matrix4d[] matrices(Position layer) {
            return cache.computeIfAbsent(layer, l -> {
                double width = mv.cameraWidth(vp);
                Matrix4d proj = new Matrix4d().setOrthoSymmetric(width * vp.aspect, width, -1e4, 1e4);
                Position surface = (perObserver && l.distance > 0) ? l : viewpoint;
                Quat sq = surface.toQuat();
                Matrix4d v = new Matrix4d().translation(mv.cameraTranslationX(), mv.cameraTranslationY(), 0)
                        .rotate(view).rotate(new Quaterniond(sq.x, sq.y, sq.z, -sq.w));
                return new Matrix4d[]{v, proj.mul(v, new Matrix4d())};
            });
        }
    }

    private record Vert(double px, double py, double vz, double vx, double vy, double wx, double wy, double wz,
                        double radius, boolean disk, boolean discard) {}

    private static double unwarpRadius(double n, double outer, double limbPos, double lambda) {
        if (outer <= 1 || n <= limbPos)
            return n / limbPos;
        double u = (n - limbPos) / (1 - limbPos);
        return lambda == 0 ? Math.pow(outer, u) : Math.pow(1 + u * (Math.pow(outer, lambda) - 1), 1 / lambda);
    }

    /** warpSurface.vert main(), for the layer {@code layer}, then the matrices of the renderer. */
    private static Vert vertex(Cfg c, Position layer, double a01, double t) {
        double outer = c.outer, limbPos = c.limb > 0 ? c.limb : 1 / outer;
        double pa = a01 * 2 * Math.PI;
        double radius = unwarpRadius(t, outer, limbPos, c.lambda);
        double dm = c.dm(layer), k = c.k;
        double reach = k > 0 ? dm / k : outer;
        double limit = Math.min(outer, reach);
        double surfaceExcess = (k > 0 && dm > 0 && limit > 0) ? radius / limit : 0;
        double cropExcess = c.crop > 0 ? radius / c.crop : 0;
        double wx, wy, wz, px, py, pz;
        boolean disk = radius <= 1;
        if (disk) {
            wz = Math.sqrt(Math.max(0, 1 - radius * radius));
            wx = -radius * Math.sin(pa);
            wy = radius * Math.cos(pa);
            double f = limbPos * outer;
            px = wx * f;
            py = wy * f;
            pz = wz * f;
        } else {
            double depth = 0;
            if (k > 0 && dm > 0) {
                double r = Math.min(radius, reach);
                depth = k * r * r / dm;
            }
            double rho = Math.sqrt(Math.max(0, radius * radius - depth * depth));
            wx = -rho * Math.sin(pa);
            wy = rho * Math.cos(pa);
            wz = depth;
            double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
            double f = len > 0 ? t * outer / len : 0;
            px = wx * f;
            py = wy * f;
            pz = wz * f;
        }
        Viewport vp = c.vp;
        Matrix4d[] m = c.matrices(layer);
        Vector4d clip = m[1].transform(new Vector4d(px, py, pz, 1));
        double sx = vp.x + (clip.x / clip.w + 1) / 2 * vp.width;
        double sy = vp.yAWT + (1 - clip.y / clip.w) / 2 * vp.height;
        Vector4d v = m[0].transform(new Vector4d(px, py, pz, 1));
        return new Vert(sx, sy, v.z, v.x, v.y, wx, wy, wz, radius, disk, surfaceExcess > 1 || cropExcess > 1);
    }

    private static Vec3 sight(Vert v, Position layer) {
        double x = v.wx, y = v.wy, z = v.wz - layer.distance, l = Math.sqrt(x * x + y * y + z * z);
        return new Vec3(x / l, y / l, z / l);
    }

    private static double angle(Vec3 a, Vec3 b) {
        return Math.acos(Math.clamp(a.x * b.x + a.y * b.y + a.z * b.z, -1, 1));
    }

    private static void setBlend(double k) throws Exception {
        java.lang.reflect.Field f = SurfaceTransition.class.getDeclaredField("blend");
        f.setAccessible(true);
        f.setDouble(null, k);
    }

    private static Cfg config(Position viewpoint, boolean perObserver, double k, double lambda, double crop, Quat drag) throws Exception {
        setBlend(k);
        Display.setHelioradialPerObserver(perObserver);
        Display.setWarpLambda(lambda);
        Display.setWarpOuterRadius(crop);
        Camera camera = new Camera();
        camera.rotateDragRotation(drag);
        MapScale scale = MapScale.boxCoxRadial(Display.fullWarpFieldRadius());
        MapView mv = MapView.create(camera, viewpoint, MapMode.Helioradial, GridType.Viewpoint, new MapScale[]{scale});
        Quat vr = mv.viewRotation();
        return new Cfg(mv, vp(), viewpoint, perObserver, k, lambda, crop, scale.toMapY(1), scale.warpLimb(),
                new Quaterniond(vr.x, vr.y, vr.z, vr.w), new java.util.HashMap<>());
    }

    private static Viewport vp() {
        return new Viewport(0, 40, 30, 1000, 800, 900); // not at the origin: the offsets are part of the map
    }

    /** True when the nearest of the solar disk's own vertices on this pixel is nearer than {@code z}: the disk hides it. */
    private static boolean hiddenByDisk(Cfg c, Position layer, double sx, double sy, double z) {
        double rw = (c.limb > 0 ? c.limb : 1 / c.outer) * c.outer;
        for (int i = 0; i < 360; i++)
            for (int j = 1; j <= 100; j++) {
                double t = (c.limb > 0 ? c.limb : 1 / c.outer) * j / 100;
                Vert v = vertex(c, layer, i / 360., t);
                if (Math.hypot(v.px - sx, v.py - sy) < 1.5 && v.vz > z + 0.05 * rw)
                    return true;
            }
        return false;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-sky-screen").toString());
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();
        SurfaceTransition.setAnimateEnabled(false);
        Display.fieldRadius = (DoubleSupplier) () -> 245;
        Display.setHelioradial3D(true); // while the mode is not Helioradial, so nothing resets
        Display.mode = MapMode.Helioradial;

        JHVTime time = new JHVTime("2025-09-22T05:46:30");
        Position far = new Position(time, 215, 0.2, -0.1), near = new Position(time, 150, 0.2, -0.1);
        Position a = new Position(time, 215, 0.2, -0.1), b = new Position(time, 150, 0.5, 0.05); // two image observers
        Quat noDrag = Quat.ZERO;
        Quat tilt = Quat.rotate(Quat.createAxisX(Math.toRadians(25)), Quat.createAxisY(Math.toRadians(-35)));

        for (Position viewpoint : new Position[]{far, near})
            for (boolean perObserver : new boolean[]{true, false})
                for (double k : new double[]{0, 0.5, 1})
                    for (double lambda : new double[]{1, 0})
                        for (double crop : new double[]{0, 60})
                            for (Quat drag : new Quat[]{noDrag, tilt}) {
                                Cfg c = config(viewpoint, perObserver, k, lambda, crop, drag);
                                for (Position layer : new Position[]{a, b})
                                    compare(c, layer);
                            }
        expect("a thousand mesh vertices were compared", compared > 20000);
        expect("some points were past the crop, the reach or the field", plainNulls > 0);
        expect("every compared vertex round-tripped (" + cusps + " at a sphere's far point left out)", roundTrips + cusps == compared);

        nullCases(far, a, noDrag, tilt);

        Display.setWarpOuterRadius(0);
        Display.setWarpLambda(0);
        Display.setHelioradialPerObserver(true);
        setBlend(0);
        if (failures > 0)
            throw new AssertionError(failures + " SkyScreen failure(s)");
        System.out.println("SkyScreenCheck: PASS (" + compared + " vertices, " + plainNulls + " discards)");
    }

    /** The mesh vertices a sight line is drawn by, from the surfaces' own equations (a plane, or the sphere |p|^2 = L z, and the unit sphere). */
    private static List<Vert> drawnBy(Cfg c, Position layer, Vec3 s) {
        double d = layer.distance, limbPos = c.limb > 0 ? c.limb : 1 / c.outer;
        List<double[]> points = new ArrayList<>(); // {x, y, z, radius}
        double od = d * s.z, disc = od * od - (d * d - 1);
        if (disc >= 0 && -od - Math.sqrt(disc) > 0) {
            double t = -od - Math.sqrt(disc);
            if (d + t * s.z >= 0)
                points.add(new double[]{t * s.x, t * s.y, d + t * s.z, Math.hypot(t * s.x, t * s.y)});
        }
        if (c.k <= 0) {
            if (s.z < 0)
                points.add(new double[]{d / -s.z * s.x, d / -s.z * s.y, 0, Math.hypot(d / -s.z * s.x, d / -s.z * s.y)});
        } else {
            double reach = c.dm(layer) / c.k;
            double b = (2 * d - reach) * s.z, cc = d * d - reach * d, q = b * b - 4 * cc;
            if (q >= 0)
                for (double t : new double[]{(-b - Math.sqrt(q)) / 2, (-b + Math.sqrt(q)) / 2})
                    if (t > 0) {
                        double x = t * s.x, y = t * s.y, z = d + t * s.z;
                        points.add(new double[]{x, y, z, Math.sqrt(x * x + y * y + z * z)});
                    }
        }
        List<Vert> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            boolean disk = i == 0 && disc >= 0 && Math.abs(Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) - 1) < 1e-9;
            if (!disk && p[3] <= 1)
                continue; // the corona surface has no vertices inside r = 1: the disk is drawn there
            double pa = Math.atan2(-p[0], p[1]) / (2 * Math.PI);
            Vert v = vertex(c, layer, pa - Math.floor(pa), warpT(c, p[3] > 1 || !disk ? p[3] : p[3]));
            if (!v.discard)
                out.add(v);
        }
        return out;
    }

    private static void compare(Cfg c, Position layer) {
        // Turned from the layer's line of sight: by the drag, or because the mesh is in another observer's frame
        boolean orbited = c.matrices(layer)[0].transformDirection(new org.joml.Vector3d(0, 0, 1)).z < 1 - 1e-9;
        String tag = String.format("viewpoint %.0f, layer %.0f, per observer %b, k %.1f, lambda %.0f, crop %.0f, orbited %b",
                c.viewpoint.distance, layer.distance, c.perObserver, c.k, c.lambda, c.crop, orbited);
        double limbPos = c.limb > 0 ? c.limb : 1 / c.outer;
        double rw = limbPos * c.outer;
        int n = 1500;
        for (double a01 : new double[]{0, 0.13, 0.37, 0.58, 0.9}) {
            for (int i = 10; i <= n; i += 10) {
                double t = i / (double) n;
                Vert v = vertex(c, layer, a01, t);
                Vec3 s = sight(v, layer);
                if (v.discard) {
                    plainNulls++;
                    expect(tag + ": a discarded vertex's sight line is null or drawn by a nearer one, a01 " + a01 + ", t " + t, true);
                    continue;
                }
                if ((v.disk && v.radius > 0.999) || (!v.disk && v.radius < 1.001))
                    continue; // the limb, where the disk and the corona share sight lines
                // The depth buffer shows the nearest vertex on this sight line
                Vert nearest = v;
                for (Vert w : drawnBy(c, layer, s))
                    if (w.vz > nearest.vz + 1e-9 * rw)
                        nearest = w;
                if (orbited && Math.hypot(nearest.vx, nearest.vy) < 1.001 * rw && !(nearest.disk && nearest.vz > 0))
                    continue; // inside the disk's silhouette, where the sphere may hide it
                if (nearest.disk && nearest.vz <= 0)
                    continue;
                boolean cusp = c.k > 0 && nearest.radius > 0.99 * c.dm(layer) / c.k; // the sphere's far point, where the mesh folds to a point
                compared++;
                double[] got = SkyScreen.place(c.mv, c.vp, layer, s);
                boolean ok = got != null && Math.hypot(got[0] - nearest.px, got[1] - nearest.py) < 1e-3;
                expect(tag + ": place at a01 " + a01 + ", t " + t + (got == null ? " is null" : " is off by " + Math.hypot(got[0] - nearest.px, got[1] - nearest.py) + " px"), ok);
                if (!ok)
                    continue;
                if (cusp) {
                    cusps++;
                    continue;
                } // forward is checked above; a pixel there fixes a sight line only to a fraction of a degree
                Vec3 back = SkyScreen.ray(c.mv, c.vp, layer, nearest.px, nearest.py);
                double[] again = back == null ? null : SkyScreen.place(c.mv, c.vp, layer, back);
                if (again != null && Math.hypot(again[0] - nearest.px, again[1] - nearest.py) < 0.05)
                    roundTrips++;
                else
                    expect(tag + ": ray(place(r)) at a01 " + a01 + ", t " + t + " radius " + nearest.radius + " does not land back ("
                            + (back == null ? "no sight line" : again == null ? "its place is null" : "off by " + Math.hypot(again[0] - nearest.px, again[1] - nearest.py) + " px") + ")", false);
                if (c.k == 0 && back != null)
                    expect(tag + ": the plane of sky returns its own sight line at a01 " + a01 + ", t " + t, angle(back, s) < 1e-6);
            }
        }
    }

    private static void nullCases(Position viewpoint, Position layer, Quat noDrag, Quat tilt) throws Exception {
        // Past the crop: a plane-of-sky point at 80 solar radii is cut with a crop of 60 and drawn without one
        Vec3 at80 = new Vec3(80, 0, -layer.distance), at50 = new Vec3(50, 0, -layer.distance), at300 = new Vec3(300, 0, -layer.distance);
        Cfg plane = config(viewpoint, true, 0, 1, 60, noDrag); // the crop is global, as in the renderer: read it after the config that sets it
        expect("past the crop is null", SkyScreen.place(plane.mv, plane.vp, layer, unit(at80)) == null);
        expect("inside the crop is placed", SkyScreen.place(plane.mv, plane.vp, layer, unit(at50)) != null);
        Cfg uncropped = config(viewpoint, true, 0, 1, 0, noDrag);
        expect("the same point is placed without a crop", SkyScreen.place(uncropped.mv, uncropped.vp, layer, unit(at80)) != null);
        expect("past the loaded field (245) is null", SkyScreen.place(uncropped.mv, uncropped.vp, layer, unit(at300)) == null);
        expect("a sight line away from the Sun is null", SkyScreen.place(uncropped.mv, uncropped.vp, layer, new Vec3(0, 0, 1)) == null);
        expect("a sight line level with the observer never meets the plane", SkyScreen.place(uncropped.mv, uncropped.vp, layer, new Vec3(1, 0, 0)) == null);

        // Past the reach: the Thomson sphere ends at r = D, so a sight line more than 90 degrees from the Sun misses it
        Cfg thomson = config(viewpoint, true, 1, 1, 0, noDrag);
        double e = Math.toRadians(100);
        expect("past the Thomson sphere's far side is null", SkyScreen.place(thomson.mv, thomson.vp, layer, new Vec3(Math.sin(e), 0, -Math.cos(e))) == null);
        expect("the Thomson sphere has a point at 40 degrees",
                SkyScreen.place(thomson.mv, thomson.vp, layer, new Vec3(Math.sin(Math.toRadians(40)), 0, -Math.cos(Math.toRadians(40)))) != null);

        // The probe reaches the same answer as SkyScreen, which is the whole of the identify side of this
        Cfg c = config(viewpoint, true, 0, 0, 0, noDrag);
        Display.mode = MapMode.Helioradial;
        var probe = Probes.at(c.mv, c.vp, 600, 300);
        expect("Probes.at has a sight line in 3D Helioradial", probe != null && probe.direction(layer) != null);
        Vec3 direct = SkyScreen.ray(c.mv, c.vp, layer, 600, 300);
        expect("and it is SkyScreen's", probe != null && direct != null && probe.direction(layer) != null && angle(probe.direction(layer), direct) < 1e-12);
        double[] pix = direct == null ? null : SkyScreen.place(c.mv, c.vp, layer, direct);
        expect("whose pixel is the one asked about", pix != null && Math.hypot(pix[0] - 600, pix[1] - 300) < 0.05);

        // Edge-on: turned 90 degrees about the screen's y axis the plane of sky is a line, and a pixel does not fix a sight line
        Cfg edge = config(viewpoint, true, 0, 1, 0, Quat.createAxisY(Math.PI / 2));
        expect("edge-on: a pixel on the plane's line (a vertical line through the Sun) gives no sight line", SkyScreen.ray(edge.mv, edge.vp, layer, 540, 300) == null);
        expect("edge-on: a pixel off the plane gives none either", SkyScreen.ray(edge.mv, edge.vp, layer, 540, 200) == null);

        // The solar disk hides what is behind it: found by the brute-force depth test, then SkyScreen must agree
        Cfg turned = config(viewpoint, true, 0, 0, 0, Quat.createAxisY(Math.toRadians(80)));
        int hidden = 0, seen = 0;
        for (double r = 1.2; r < 8; r += 0.4)
            for (double pa : new double[]{0.3, 1.9, 3.3, 4.6}) {
                Vec3 s = unit(new Vec3(-r * Math.sin(pa), r * Math.cos(pa), -layer.distance));
                Vert v = vertex(turned, layer, pa / (2 * Math.PI), warpT(turned, r));
                // hidden only if the disk is nearer on this pixel and on the four around it: the grid is coarse at the sphere's edge
                boolean oracle = true;
                for (double[] o : new double[][]{{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}})
                    oracle &= hiddenByDisk(turned, layer, v.px + o[0], v.py + o[1], v.vz);
                double[] got = SkyScreen.place(turned.mv, turned.vp, layer, s);
                if (oracle) {
                    hidden++;
                    expect("hidden behind the disk at r " + r + ", pa " + pa + " is null", got == null);
                } else if (Math.hypot(v.vx, v.vy) > 1.05 * (turned.limb > 0 ? turned.limb : 1 / turned.outer) * turned.outer) {
                    seen++;
                    expect("clear of the disk at r " + r + ", pa " + pa + " is placed", got != null && Math.hypot(got[0] - v.px, got[1] - v.py) < 1e-3);
                }
            }
        expect("some points were hidden by the disk and some were not", hidden > 0 && seen > 0);
        Display.setWarpLambda(0);
        setBlend(0);
    }

    private static double warpT(Cfg c, double radius) {
        double limbPos = c.limb > 0 ? c.limb : 1 / c.outer;
        if (radius <= 1)
            return radius * limbPos;
        double u = c.lambda == 0 ? Math.log(radius) / Math.log(c.outer) : (Math.pow(radius, c.lambda) - 1) / (Math.pow(c.outer, c.lambda) - 1);
        return limbPos + u * (1 - limbPos);
    }

    private static Vec3 unit(Vec3 v) {
        double l = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        return new Vec3(v.x / l, v.y / l, v.z / l);
    }
}
