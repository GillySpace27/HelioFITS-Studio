package org.helioviewer.jhv.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.Probes;
import org.helioviewer.jhv.identify.SkyScreen;
import org.helioviewer.jhv.layers.stars.StarSky;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.time.JHVTime;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector4d;

/**
 * The Stars overlay puts a star where {@link SkyScreen#place} does, in every mode it draws.
 *
 * <p>The vertices are what StarLayer hands to GL: Orthographic, StarSky.plane's buffer drawn under
 * the layer's own pose; Helioradial in 3D, {@code Layout#surface} drawn under the surface observer's
 * pose and moved by shape.vert's radial warp (transcribed here from warpCommon.vert, not taken from
 * WarpGeometry); HPC, Observer Sky and flat Helioradial, {@code Layout#page} drawn in the flat scene.
 * Each is carried to a canvas pixel by the matrices of Transform.ortho / ortho2D (JOML, as the
 * renderer builds them) and has to equal {@code SkyScreen.place} for the same ray. In the flat modes
 * the pixel is also read back through the shader-pinned probe ({@link Probes#exact}), so the star
 * sits on the sight line the imagery shows there.
 *
 * <p>Each view is tried with two observers at different distances and in different frames (the
 * second is the "active layer" of a two-layer row), and the overlay must follow the one it is given,
 * not the viewpoint's.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.StarOverlayCheck
 */
public final class StarOverlayCheck {

    private static int failures, compared, placedChecks, dropped, differsFromViewpoint;

    private static void expect(String what, boolean ok) {
        if (!ok && failures++ < 40)
            System.out.println("  FAIL " + what);
    }

    private static Viewport vp() {
        return new Viewport(0, 40, 30, 1000, 800, 900); // not at the origin: the offsets are part of the map
    }

    /** Unit rays near the Sun in the observer's frame, as the float rays StarSky.Points holds. */
    private static StarSky.Points points(long seed, double maxDeg, int n) {
        Random rnd = new Random(seed);
        float[] rays = new float[3 * n];
        for (int i = 0; i < n; i++) {
            Vec3 r = Identifier.ray(Math.toRadians((rnd.nextDouble() * 2 - 1) * maxDeg), Math.toRadians((rnd.nextDouble() * 2 - 1) * maxDeg));
            rays[3 * i] = (float) r.x;
            rays[3 * i + 1] = (float) r.y;
            rays[3 * i + 2] = (float) r.z;
        }
        return new StarSky.Points(n, rays, new float[n], new byte[4 * n]);
    }

    private static Vec3 ray(StarSky.Points p, int i) {
        double x = p.ray()[3 * i], y = p.ray()[3 * i + 1], z = p.ray()[3 * i + 2], l = Math.sqrt(x * x + y * y + z * z); // unit, so acos of a dot is not read from float rounding
        return new Vec3(x / l, y / l, z / l);
    }

    /** Transform.ortho / ortho2D for a vertex already under {@code model}: the canvas pixel, AWT y down. */
    private static double[] canvas(MapView mv, Viewport vp, Matrix4d model, double x, double y, double z) {
        double width = mv.cameraWidth(vp);
        Matrix4d proj = new Matrix4d().setOrthoSymmetric(width * vp.aspect, width, -1e4, 1e4);
        Matrix4d view = new Matrix4d().translation(mv.cameraTranslationX(), mv.cameraTranslationY(), 0).mul(model);
        Vector4d clip = proj.mul(view, new Matrix4d()).transform(new Vector4d(x, y, z, 1));
        return new double[]{vp.x + (clip.x / clip.w + 1) / 2 * vp.width, vp.yAWT + (1 - clip.y / clip.w) / 2 * vp.height};
    }

    /** rotate(view quaternion), then the inverse of the observer's pose: ImageLayer.render / StarLayer.render. */
    private static Matrix4d pose(MapView mv, Quat observer) {
        Quat vr = mv.viewRotation();
        return new Matrix4d().rotate(new Quaterniond(vr.x, vr.y, vr.z, vr.w)).rotate(new Quaterniond(observer.x, observer.y, observer.z, -observer.w));
    }

    private static void setBlend(double k) throws Exception {
        java.lang.reflect.Field f = SurfaceTransition.class.getDeclaredField("blend");
        f.setAccessible(true);
        f.setDouble(null, k);
    }

    /** warpCommon.vert warpWorld with the uniforms of GLSLWarp.enable(scale). */
    private static double[] warpWorld(MapScale scale, double[] p) {
        double outer = scale.warpOuterRadius(), limb = scale.warpLimb(), lambda = scale.warpLambda();
        double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (r <= 0)
            return p;
        double limbPos = limb > 0 ? limb : 1 / outer, unit;
        if (outer <= 1 || r <= 1)
            unit = r * limbPos;
        else {
            double u = lambda == 0 ? Math.log(r) / Math.log(outer) : (Math.pow(r, lambda) - 1) / (Math.pow(outer, lambda) - 1);
            unit = limbPos + u * (1 - limbPos);
        }
        double f = unit * outer / r;
        return new double[]{p[0] * f, p[1] * f, p[2] * f};
    }

    private static double angle(Vec3 a, Vec3 b) {
        double cx = a.y * b.z - a.z * b.y, cy = a.z * b.x - a.x * b.z, cz = a.x * b.y - a.y * b.x; // atan2, not acos: near-equal vectors
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), a.x * b.x + a.y * b.y + a.z * b.z);
    }

    private static boolean near(double[] a, double[] b, double tol) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]) <= tol;
    }

    // --- Helioradial in 3D ---------------------------------------------------------------------

    private static void helioradial3D(String tag, MapView mv, Viewport vp, Position viewpoint, boolean perObserver, Position observer, StarSky.Points p) {
        SkyScreen.Layout layout = SkyScreen.layout(mv, vp, observer);
        Position surface = perObserver && observer.distance > 0 ? observer : viewpoint; // ImageLayer.render's surface observer
        Matrix4d model = pose(mv, surface.toQuat());
        Quat drawn = layout.rotation(); // what StarLayer rotates the vertices by
        Quat want = surface.toQuat();
        expect(tag + ": the vertices are drawn under the surface observer's pose",
                Math.abs(drawn.x - want.x) + Math.abs(drawn.y - want.y) + Math.abs(drawn.z - want.z) + Math.abs(drawn.w - want.w) < 1e-12);
        MapScale scale = mv.scale(vp);
        for (int i = 0; i < p.count(); i++) {
            Vec3 r = ray(p, i);
            if (!StarSky.inCut(r.z))
                continue;
            Vec3 at = layout.surface(r);
            double[] place = SkyScreen.place(mv, vp, observer, r);
            compared++;
            expect(tag + ": the overlay draws a ray exactly where place has one (ray " + i + ")", (at == null) == (place == null));
            if (at == null || place == null) {
                dropped++;
                continue;
            }
            double[] w = warpWorld(scale, new double[]{at.x, at.y, at.z});
            double[] vertex = canvas(mv, vp, model, w[0], w[1], w[2]);
            placedChecks++;
            expect(tag + ": the vertex equals place for ray " + i + " (" + fmt(vertex) + " against " + fmt(place) + ")", near(vertex, place, 0.01));
            if (!perObserver || observer == viewpoint)
                continue;
            double[] other = SkyScreen.place(mv, vp, viewpoint, r);
            if (other != null && !near(other, place, 1))
                differsFromViewpoint++;
        }
    }

    // --- Orthographic --------------------------------------------------------------------------

    private static void orthographic(String tag, MapView mv, Viewport vp, Position viewpoint, Position observer, StarSky.Points p) {
        BufVertex buf = StarSky.plane(p, observer.distance);
        java.nio.ByteBuffer b = buf.toBuffer().order(java.nio.ByteOrder.nativeOrder());
        int stride = buf.getCount() == 0 ? 0 : b.limit() / buf.getCount();
        Matrix4d model = pose(mv, observer.toQuat());
        int slot = 0;
        for (int i = 0; i < p.count(); i++) {
            Vec3 r = ray(p, i);
            if (!StarSky.inCut(r.z))
                continue;
            double x = b.getFloat(slot * stride), y = b.getFloat(slot * stride + 4);
            slot++;
            compared++;
            if (Math.hypot(x, y) < 1.5) { // on the solar disk, which hides it (place answers with the disk point)
                dropped++;
                continue;
            }
            double[] vertex = canvas(mv, vp, model, x, y, 0);
            double[] place = SkyScreen.place(mv, vp, observer, r);
            placedChecks++;
            expect(tag + ": ray " + i + " has a place", place != null);
            if (place == null)
                continue;
            expect(tag + ": the vertex equals place for ray " + i + " (" + fmt(vertex) + " against " + fmt(place) + ")", near(vertex, place, 0.01));
            Identifier.Probe probe = Probes.exact(mv, vp, vertex[0], vertex[1]);
            Vec3 back = probe == null ? null : probe.direction(observer);
            expect(tag + ": the imagery under the vertex shows the star's own direction (ray " + i + ")", back != null && angle(back, r) < 1e-6);
            double[] other = SkyScreen.place(mv, vp, viewpoint, r);
            if (other != null && !near(other, place, 1))
                differsFromViewpoint++;
        }
    }

    // --- HPC, Observer Sky, flat Helioradial ----------------------------------------------------

    private static void flat(String tag, MapView mv, Viewport vp, Position viewpoint, Position observer, StarSky.Points p) {
        SkyScreen.Layout layout = SkyScreen.layout(mv, vp, observer);
        Matrix4d model = new Matrix4d();
        int placed = 0;
        for (int i = 0; i < p.count(); i++) {
            Vec3 r = ray(p, i);
            double[] pg = layout.page(r);
            double[] place = SkyScreen.place(mv, vp, observer, r);
            compared++;
            if (pg == null) {
                expect(tag + ": no page point means no place (ray " + i + ")", place == null);
                dropped++;
                continue;
            }
            double[] vertex = canvas(mv, vp, model, pg[0], pg[1], 0);
            if (place == null) { // place also reads the pixel back through the probe: a point the page cannot hold is dropped there
                dropped++;
                continue;
            }
            placed++;
            placedChecks++;
            expect(tag + ": the vertex equals place for ray " + i + " (" + fmt(vertex) + " against " + fmt(place) + ")", near(vertex, place, 0.01));
            Identifier.Probe probe = Probes.exact(mv, vp, vertex[0], vertex[1]);
            Identifier.Probe next = Probes.exact(mv, vp, vertex[0] + 1, vertex[1]);
            Vec3 back = probe == null ? null : probe.direction(observer), back1 = next == null ? null : next.direction(observer);
            double pixel = back == null || back1 == null ? 0 : angle(back, back1);
            expect(tag + ": the imagery under the vertex shows the star's own direction (ray " + i + ")",
                    back != null && angle(back, r) <= Math.max(1e-9, 0.01 * pixel));
            double[] other = SkyScreen.place(mv, vp, viewpoint, r);
            if (other != null && !near(other, place, 1))
                differsFromViewpoint++;
        }
        expect(tag + ": some stars are on the page (" + placed + ")", placed > 20);
    }

    private static String fmt(double[] a) {
        return a == null ? "null" : String.format("%.3f,%.3f", a[0], a[1]);
    }

    private static void resetCamera(Camera camera, Position viewpoint, double width) throws Exception {
        camera.reset(viewpoint);
        if (width > 0) {
            var f = Camera.class.getDeclaredField("cameraWidth");
            f.setAccessible(true);
            f.setDouble(camera, width);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-star-overlay").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        SurfaceTransition.setAnimateEnabled(false);
        var fr = Display.class.getDeclaredField("fieldRadius");
        fr.setAccessible(true);
        fr.set(null, (DoubleSupplier) () -> 245);

        JHVTime time = new JHVTime("2025-09-22T05:46:30");
        Position viewpoint = new Position(time, 215, 0.2, -0.1);
        Position b = new Position(time, 150, 0.5, 0.05); // the active layer of a second row: another distance, another frame
        Position[] observers = {viewpoint, b};
        Quat noDrag = Quat.ZERO;
        Quat tilt = Quat.rotate(Quat.createAxisX(Math.toRadians(25)), Quat.createAxisY(Math.toRadians(-35)));

        // Helioradial in 3D: the selected surface, per observer on and off, a crop, the Thomson sphere, orbited
        Display.mode = MapMode.HPC;
        Display.setHelioradial3D(true);
        Display.mode = MapMode.Helioradial;
        StarSky.Points wide = points(7, 40, 1500);
        for (boolean perObserver : new boolean[]{true, false})
            for (double k : new double[]{0, 0.5, 1})
                for (double lambda : new double[]{1, 0})
                    for (double crop : new double[]{0, 60})
                        for (Quat drag : new Quat[]{noDrag, tilt}) {
                            setBlend(k);
                            Display.setHelioradialPerObserver(perObserver);
                            Display.setWarpLambda(lambda);
                            Display.setWarpOuterRadius(crop);
                            Camera camera = new Camera();
                            camera.rotateDragRotation(drag);
                            MapView mv = MapView.create(camera, viewpoint, MapMode.Helioradial, GridType.Viewpoint, new MapScale[]{MapScale.boxCoxRadial(Display.fullWarpFieldRadius())});
                            for (Position observer : observers)
                                helioradial3D(String.format("3D Helioradial obs %.0f per observer %b k %.1f lambda %.0f crop %.0f %s", observer.distance, perObserver, k, lambda, crop, drag == noDrag ? "" : "orbited"),
                                        mv, vp(), viewpoint, perObserver, observer, wide);
                        }
        setBlend(0);
        Display.setWarpOuterRadius(0);
        Display.setWarpLambda(1);
        Display.setHelioradialPerObserver(true);

        // Orthographic: straight on and orbited, two observers
        Display.mode = MapMode.Orthographic;
        for (Quat drag : new Quat[]{noDrag, tilt, Quat.createAxisY(Math.toRadians(85))}) {
            Camera camera = new Camera();
            camera.reset(viewpoint);
            camera.rotateDragRotation(drag);
            camera.setTranslation(0.4, -0.2);
            MapView mv = MapView.create(camera, viewpoint, MapMode.Orthographic, GridType.Viewpoint, new MapScale[]{MapScale.ortho});
            for (Position observer : observers)
                orthographic(String.format("Orthographic obs %.0f %s", observer.distance, drag == noDrag ? "" : "orbited"), mv, vp(), viewpoint, observer, wide);
        }

        // Flat modes: HPC, flat Helioradial, Observer Sky (plain and composed)
        Display.mode = MapMode.HPC;
        Display.setHelioradial3D(false);
        StarSky.Points near = points(11, 3, 600), mid = points(13, 12, 600);
        Camera camera = new Camera();
        resetCamera(camera, viewpoint, 1);
        MapView hpc = MapView.create(camera, viewpoint, MapMode.HPC, GridType.Viewpoint, new MapScale[]{MapScale.hpc(2.5, 2.0)});
        for (Position observer : observers)
            flat(String.format("HPC obs %.0f", observer.distance), hpc, vp(), viewpoint, observer, near);
        for (double lambda : new double[]{1, 0.4, 0}) {
            Display.setWarpLambda(lambda);
            Display.mode = MapMode.Helioradial;
            camera = new Camera();
            resetCamera(camera, viewpoint, 0);
            MapView mv = MapView.create(camera, viewpoint, MapMode.Helioradial, GridType.Viewpoint, new MapScale[]{Display.warpScale()});
            for (Position observer : observers)
                flat(String.format("flat Helioradial lambda %.1f obs %.0f", lambda, observer.distance), mv, vp(), viewpoint, observer, mid);
        }
        Display.setWarpLambda(1);
        Display.mode = MapMode.ObserverSky;
        for (MapMode base : new MapMode[]{MapMode.Orthographic, MapMode.Helioradial})
            for (SkyProjection proj : SkyProjection.values())
                for (boolean fromImage : new boolean[]{true, false}) {
                    Display.setSkyBase(base);
                    Display.setSkyProjection(proj);
                    Display.setSkyFieldDegrees(8);
                    Display.setSkyLook(0.12, -0.07);
                    Display.setSurfaceFromImage(fromImage);
                    camera = new Camera();
                    resetCamera(camera, viewpoint, 0);
                    double half = Math.toDegrees(proj.radiusFromAngle(Math.toRadians(Display.getSkyFieldDegrees())));
                    MapView mv = MapView.create(camera, viewpoint, MapMode.ObserverSky, GridType.Viewpoint, new MapScale[]{MapScale.sky(half * vp().aspect, half)});
                    for (Position observer : observers)
                        flat(String.format("Observer Sky %s %s fromImage %b obs %.0f", base, proj, fromImage, observer.distance), mv, vp(), viewpoint, observer, mid);
                }
        Display.setSurfaceFromImage(true);

        expect("a thousand placements were compared", compared > 20000);
        expect("most of them had a place to compare (" + placedChecks + ")", placedChecks > 10000);
        expect("the overlay follows the observer it is given, not the viewpoint's (" + differsFromViewpoint + " stars moved by more than a pixel)", differsFromViewpoint > 500);
        if (failures > 0)
            throw new AssertionError(failures + " star overlay failure(s)");
        System.out.println("StarOverlayCheck: PASS (" + compared + " rays, " + placedChecks + " vertices equal place, " + dropped + " with no place, " + differsFromViewpoint + " moved off the viewpoint's frame)");
    }

    private StarOverlayCheck() {}
}
