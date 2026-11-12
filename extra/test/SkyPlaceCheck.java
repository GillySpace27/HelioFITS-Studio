package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Camera;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.SkyProjection;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.display.SurfaceTransition;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * SkyScreen.place for every flat mode, against the shaders.
 *
 * <p>The oracle is {@link Oracle}: each mode's fragment shader (imageHpc.frag, solarSky.frag,
 * imageRadialWarp.frag, imageRectWarp.frag, imageOrtho.frag, with the helpers of imageCommon.frag),
 * transcribed to Java from the GLSL, one canvas pixel to the sight line it samples. It shares
 * nothing with SkyScreen or Probes beyond the MapScale numbers the shaders also receive as
 * uniforms (map bounds, outer radius, limb, lambda). Three things are asserted for each mode and
 * layer:
 * <ul>
 * <li>the sight line the shader draws at a pixel is placed back on that pixel (or, where two pixels
 * show the same sight line, on one that the shader also draws it at);</li>
 * <li>the real-valued probe agrees with the shader, and {@code ray(place(r))} gives {@code r}
 * back;</li>
 * <li>a sight line placed anywhere is placed where the shader draws it, and a sight line the page
 * does not hold (behind the observer, past the field) has no place.</li>
 * </ul>
 * Two layers at different distances and frames, a pan, a zoom, and the orbited orthographic view.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.SkyPlaceCheck
 */
public final class SkyPlaceCheck {

    private static int failures;
    private static int compared, alternates, nulls, tried, agreed;

    private static void expect(String what, boolean ok) {
        if (!ok && failures++ < 40)
            System.out.println("  FAIL " + what);
    }

    /** One canvas pixel to the sight line a layer's shader samples there, or null where it discards. */
    private interface Oracle {
        Vec3 ray(double px, double py, Position layer);
    }

    /** {@code reachDeg}: how far from the Sun's direction to throw sight lines so a fair share lands on this page. */
    private record Cfg(String name, MapView mv, Viewport vp, Oracle oracle, boolean probeMatchesNulls, double reachDeg) {}

    // --- imageCommon.frag ---------------------------------------------------------------------

    private static double[] viewPosition(MapView mv, Viewport vp, double px, double py) {
        double ndcX = 2 * (px - vp.x) / vp.width - 1, ndcY = 1 - 2 * (py - vp.yAWT) / vp.height;
        double w = mv.cameraWidth(vp); // the projection is an orthographic box w * aspect by w
        return new double[]{ndcX * 0.5 * w * vp.aspect - mv.cameraTranslationX(), ndcY * 0.5 * w - mv.cameraTranslationY()};
    }

    /** getNormalizedMapPos: null where clipNormalizedCoord discards. */
    private static double[] mapPos(MapView mv, Viewport vp, double px, double py) {
        double[] v = viewPosition(mv, vp, px, py);
        double x = v[0] / vp.aspect + .5, y = v[1] + .5;
        return x < 0 || x > 1 || y < 0 || y > 1 ? null : new double[]{x, y};
    }

    private static double unwarpRadiusWith(double n, double outer, double limb, double lambda) {
        if (outer <= 1 || n <= limb)
            return n / limb;
        double u = (n - limb) / (1 - limb);
        return lambda == 0 ? Math.pow(outer, u) : Math.pow(1 + u * (Math.pow(outer, lambda) - 1), 1 / lambda);
    }

    private static double warpUnitWith(double r, double outer, double limb, double lambda) {
        if (outer <= 1 || r <= 1)
            return r * limb;
        double u = lambda == 0 ? Math.log(r) / Math.log(outer) : (Math.pow(r, lambda) - 1) / (Math.pow(outer, lambda) - 1);
        return limb + u * (1 - limb);
    }

    private static Vec3 observerRay(double phi, double theta) {
        double cosPhi = Math.cos(phi), cosTheta = Math.cos(theta);
        double sign = cosPhi * cosTheta < 0 ? -1 : 1;
        return new Vec3(sign * Math.sin(phi) * cosTheta, sign * Math.sin(theta), -sign * cosPhi * cosTheta);
    }

    /** worldToHelioprojective of a point of the plane of sky (hpcXY, 0), then back to a ray: sampleWarpedHpcColor. */
    private static Vec3 planeRay(double hx, double hy, double distance) {
        double zeta = distance;
        return observerRay(Math.atan2(hx, zeta), Math.atan2(hy, Math.sqrt(hx * hx + zeta * zeta)));
    }

    // --- one oracle per shader ----------------------------------------------------------------

    /** imageHpc.frag. */
    private static Oracle hpc(MapView mv, Viewport vp, MapScale s) {
        return (px, py, layer) -> {
            double[] m = mapPos(mv, vp, px, py);
            if (m == null)
                return null;
            double tx = Math.toRadians(s.toMapX(0) + m[0] * (s.toMapX(1) - s.toMapX(0)));
            double ty = Math.toRadians(s.toMapY(0) + m[1] * (s.toMapY(1) - s.toMapY(0)));
            Vec3 ray = observerRay(tx, ty);
            return ray.z >= 0 ? null : ray;
        };
    }

    /** imageRadialWarp.frag: Helioradial drawn flat. */
    private static Oracle radial(MapView mv, Viewport vp, MapScale s) {
        return (px, py, layer) -> {
            double[] w = viewPosition(mv, vp, px, py);
            double viewRadius = Math.hypot(w[0], w[1]);
            double t = 2 * viewRadius;
            if (t > 1 || t == 0)
                return null;
            double outer = s.toMapY(1), limb = s.warpLimb() > 0 ? s.warpLimb() : 1 / outer;
            double radial = unwarpRadiusWith(t, outer, limb, s.warpLambda());
            return planeRay(radial / viewRadius * w[0], radial / viewRadius * w[1], layer.distance);
        };
    }

    /** imageRectWarp.frag: Helioradial Unrolled. */
    private static Oracle unrolled(MapView mv, Viewport vp, MapScale s) {
        return (px, py, layer) -> {
            double[] m = mapPos(mv, vp, px, py);
            if (m == null)
                return null;
            double outer = s.toMapY(1), limb = s.warpLimb() > 0 ? s.warpLimb() : 1 / outer;
            double angle = m[0] * 2 * Math.PI;
            double radial = unwarpRadiusWith(m[1], outer, limb, s.warpLambda());
            return planeRay(-radial * Math.sin(angle), radial * Math.cos(angle), layer.distance);
        };
    }

    /** solarSky.frag, any projection, aimed at (lon, lat), composed or not. */
    private static Oracle sky(MapView mv, Viewport vp, MapScale page, SkyProjection proj, double lon, double lat,
                              MapScale warp, double model, double viewpointDistance, boolean fromImage) {
        double code = proj.ordinal(); // 0 TAN, 1 STG, 2 ARC
        return (px, py, layer) -> {
            double[] m = mapPos(mv, vp, px, py);
            if (m == null)
                return null;
            double pageX = Math.toRadians(page.toMapX(0) + m[0] * (page.toMapX(1) - page.toMapX(0)));
            double pageY = Math.toRadians(page.toMapY(0) + m[1] * (page.toMapY(1) - page.toMapY(0)));
            double radius = Math.hypot(pageX, pageY);
            double rho = code < .5 ? Math.atan(radius) : code < 1.5 ? 2 * Math.atan(.5 * radius) : radius;
            double max = code < .5 ? Math.toRadians(85) : code < 1.5 ? Math.toRadians(175) : Math.toRadians(180);
            if (rho > max)
                return null;
            Vec3 look = new Vec3(Math.sin(lon) * Math.cos(lat), Math.sin(lat), -Math.cos(lon) * Math.cos(lat));
            Vec3 ray;
            if (radius < 1e-9)
                ray = look;
            else {
                Vec3 east = new Vec3(Math.cos(lon), 0, Math.sin(lon));
                Vec3 north = new Vec3(-Math.sin(lon) * Math.sin(lat), Math.cos(lat), Math.cos(lon) * Math.sin(lat));
                double ux = pageX / radius, uy = pageY / radius;
                ray = new Vec3(Math.cos(rho) * look.x + Math.sin(rho) * (ux * east.x + uy * north.x),
                        Math.cos(rho) * look.y + Math.sin(rho) * (ux * east.y + uy * north.y),
                        Math.cos(rho) * look.z + Math.sin(rho) * (ux * east.z + uy * north.z));
            }
            if (ray.z >= 0)
                return null;
            if (warp != null) {
                double outer = warp.toMapY(1), limb = warp.warpLimb() > 0 ? warp.warpLimb() : 1 / outer, lambda = warp.warpLambda();
                double from = fromImage ? layer.distance : viewpointDistance;
                double reach = model <= 0 ? 1e30 : from / model;
                double rMax = Math.min(outer, reach * 0.999);
                double eMax = elongation(rMax, from, model);
                double uMax = warpUnitWith(rMax, outer, limb, lambda);
                double eDome = Math.acos(Math.clamp(-ray.z, -1, 1));
                if (eMax <= 0 || eDome > eMax)
                    return null;
                double r = unwarpRadiusWith(uMax * eDome / eMax, outer, limb, lambda);
                double eTrue = elongation(r, from, model);
                double len = Math.hypot(ray.x, ray.y);
                ray = len < 1e-9 ? new Vec3(0, 0, -1) : new Vec3(ray.x / len * Math.sin(eTrue), ray.y / len * Math.sin(eTrue), -Math.cos(eTrue));
            }
            return ray;
        };
    }

    private static double elongation(double r, double d, double model) {
        double ratio = r / d;
        if (model == 1)
            return Math.asin(Math.clamp(ratio, -1, 1));
        if (model == .5)
            return 2 * Math.asin(Math.clamp(.5 * ratio, -1, 1));
        return Math.atan(ratio);
    }

    /** imageOrtho.frag main() for one image, JOML quaternions, up to the sight line sampleOrthoTexcoord reads. */
    private static Oracle ortho(MapView mv, Viewport vp) {
        Quat vr = mv.viewRotation();
        Quaterniond view = new Quaterniond(vr.x, vr.y, vr.z, vr.w);
        return (px, py, layer) -> {
            Quat oq = layer.toQuat();
            Quaterniond cameraDiff = new Quaterniond(view).mul(new Quaterniond(oq.x, oq.y, oq.z, -oq.w));
            Quaterniond inverse = new Quaterniond(cameraDiff).conjugate();
            double[] v = viewPosition(mv, vp, px, py);
            double radius2 = v[0] * v[0] + v[1] * v[1];
            boolean onDisk = radius2 <= 1;
            Vector3d hit = new Vector3d(), rotated = new Vector3d();
            if (onDisk) {
                hit.set(v[0], v[1], Math.sqrt(1 - radius2));
                inverse.transform(hit, rotated);
            }
            if (rotated.z <= 0) { // off-limb or back: intersectImagePlane
                Vector3d alt = cameraDiff.transform(new Vector3d(0, 0, 1));
                if ((onDisk && alt.z <= 0) || Math.abs(alt.z) < 1e-8)
                    return null;
                hit.set(v[0], v[1], -(alt.x * v[0] + alt.y * v[1]) / alt.z);
                inverse.transform(hit, rotated);
                if ((onDisk && hit.z < 0) || rotated.lengthSquared() <= 1) // behind the sphere; the central disk
                    return null;
            }
            double zeta = layer.distance - rotated.z;
            return observerRay(Math.atan2(rotated.x, zeta), Math.atan2(rotated.y, Math.sqrt(rotated.x * rotated.x + zeta * zeta)));
        };
    }

    // --- setup --------------------------------------------------------------------------------

    private static Viewport viewport() throws Exception {
        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
    }

    private static void resetCamera(Position viewpoint, double width) throws Exception {
        Camera c = Display.getCamera();
        var reset = Camera.class.getDeclaredMethod("reset", Position.class);
        reset.setAccessible(true);
        reset.invoke(c, viewpoint);
        if (width > 0) { // the flat pages are normalized: the camera is one page wide
            var f = Camera.class.getDeclaredField("cameraWidth");
            f.setAccessible(true);
            f.setDouble(c, width);
        }
    }

    private static void setSurface(SurfaceModel model) throws Exception {
        var f = Display.class.getDeclaredField("surfaceModel");
        f.setAccessible(true);
        f.set(null, model);
    }

    /** Probes.at as it was before the real-valued overload: through MapView.mouseToMap at a whole pixel. */
    private static Vec3 beforeExact(MapView mv, Viewport vp, int x, int y, Position layer) {
        if (mv.isOrthographic())
            return null;
        Vec2 m = mv.mouseToMap(vp, x, y);
        if (mv.isHpc())
            return Probes.helioprojective(Math.toRadians(m.x), Math.toRadians(m.y)).direction(layer);
        if (mv.isObserverSky())
            return Probes.sky(m, mv.viewpoint().distance).direction(layer);
        double w = mv.cameraWidth(vp);
        double ux = org.helioviewer.jhv.display.ViewportMath.computeUpX(vp, w, mv.cameraTranslationX(), x);
        double uy = org.helioviewer.jhv.display.ViewportMath.computeUpY(vp, w, mv.cameraTranslationY(), y);
        if (mv.isHelioradial() ? 2 * Math.hypot(ux, uy) > 1 : Math.abs(ux / vp.aspect) > .5 || Math.abs(uy) > .5)
            return null;
        double theta = Math.toRadians(m.x);
        return Probes.plane(org.helioviewer.jhv.math.PolarBasis.x(m.y, theta), org.helioviewer.jhv.math.PolarBasis.y(m.y, theta)).direction(layer);
    }

    private static double angle(Vec3 a, Vec3 b) {
        return Identifier.angle(a, b); // atan2 form: acos cannot see an angle under 1e-8
    }

    // --- the checks ---------------------------------------------------------------------------

    private static void run(Cfg c, Position[] layers) {
        String tag = c.name();
        Position first = layers[0];
        Random rnd = new Random(7);
        for (Position layer : layers) {
            String t = tag + " layer " + (int) layer.distance;
            // 1. the shader's sight line at each pixel is placed back on it, and the probe agrees with the shader
            int seen = 0;
            for (double py = 33.3; py < 800; py += 61.7)
                for (double px = 41.9; px < 1000; px += 67.1) {
                    Vec3 r = c.oracle().ray(px, py, layer);
                    var probe = Probes.exact(c.mv(), c.vp(), px, py);
                    Vec3 viaProbe = probe == null ? null : probe.direction(layer);
                    if (r == null) {
                        if (c.probeMatchesNulls())
                            expect(t + ": the shader draws nothing at " + px + "," + py + " and the probe has no sight line", viaProbe == null);
                        continue;
                    }
                    seen++;
                    compared++;
                    expect(t + ": probe at " + px + "," + py + " is the shader's sight line", viaProbe != null && angle(r, viaProbe) < 1e-7);
                    double[] q = SkyScreen.place(c.mv(), c.vp(), layer, r);
                    if (q == null) {
                        expect(t + ": place has an answer for the sight line drawn at " + px + "," + py, false);
                        continue;
                    }
                    if (Math.hypot(q[0] - px, q[1] - py) > 0.05) { // two pixels may draw one sight line (the disk's limb sliver)
                        alternates++;
                        Vec3 there = c.oracle().ray(q[0], q[1], layer);
                        expect(t + ": place put the sight line of " + px + "," + py + " at " + q[0] + "," + q[1] + ", where the shader draws something else",
                                there != null && angle(there, r) < 1e-6);
                    }
                    Vec3 back = SkyScreen.ray(c.mv(), c.vp(), layer, q[0], q[1]);
                    expect(t + ": ray(place(r)) is r at " + px + "," + py, back != null && angle(back, r) < 1e-6);
                }
            expect(t + ": the shader draws something on this page", seen > 20);

            // 2. sight lines anywhere: placed where the shader draws them, or not placed
            int placed = 0, unplaced = 0;
            for (int i = 0; i < 600; i++) {
                double reach = Math.toRadians(i < 400 ? c.reachDeg() : 150); // the last 200 range over the whole sky
                double a = rnd.nextDouble() * 2 * Math.PI, e = Math.pow(rnd.nextDouble(), 0.7) * reach;
                Vec3 r = new Vec3(Math.sin(e) * Math.cos(a), Math.sin(e) * Math.sin(a), -Math.cos(e));
                double[] q = SkyScreen.place(c.mv(), c.vp(), layer, r);
                if (q == null) {
                    unplaced++;
                    nulls++;
                    continue;
                }
                if (q[0] < c.vp().x || q[0] > c.vp().x + c.vp().width || q[1] < c.vp().yAWT || q[1] > c.vp().yAWT + c.vp().height) {
                    nulls++; // placed off the canvas: the shader has no pixel for it, and locate drops it
                    continue;
                }
                placed++;
                Vec3 there = c.oracle().ray(q[0], q[1], layer);
                // HPC and the plain sky probe a sight line wherever the canvas is, even past the page edge the shader clips (as before): only a drawn pixel is judged
                expect(t + ": a sight line placed at " + q[0] + "," + q[1] + " is drawn there by the shader",
                        there == null ? !c.probeMatchesNulls() : angle(there, r) < 1e-6);
            }
            expect(t + ": some of the sight lines are on the page (" + placed + ")", placed > 20);
        }
        // the footer's whole-pixel probe is unchanged by the real-valued one
        if (!c.mv().isOrthographic())
            for (int y = 37; y < 800; y += 97)
                for (int x = 29; x < 1000; x += 103) {
                    var now = Probes.at(c.mv(), c.vp(), x + 0.3, y - 0.2); // rounds to (x, y)
                    Vec3 a = now == null ? null : now.direction(first);
                    Vec3 b = beforeExact(c.mv(), c.vp(), x, y, first);
                    expect(tag + ": Probes.at at " + x + "," + y + " is what mouseToMap gave", a == null ? b == null : b != null && angle(a, b) < 1e-12);
                }
        // locate is the placement inside the viewport; the old way (Newton on the probe) agrees with it where it converges
        if (!c.name().startsWith("sky") && !c.name().startsWith("ortho orbit 85") && !c.name().startsWith("ortho orbit 172")) {
            Identifier.Target target = new Identifier.Target("t", layers[0], null, null, 0, 0);
            for (double py = 80; py < 760; py += 190)
                for (double px = 90; px < 950; px += 210) {
                    Vec3 r = c.oracle().ray(px, py, layers[0]);
                    if (r == null)
                        continue;
                    double[] a = IdentifyPins.locate(c.vp(), c.mv(), target, r);
                    double[] n = IdentifyPins.locateByProbe(c.vp(), c.mv(), target, r, new double[]{px, py});
                    tried++;
                    expect(tag + ": locate finds " + px + "," + py, a != null);
                    if (a != null && n != null) {
                        agreed++;
                        expect(tag + ": locate and Newton on the probe agree at " + px + "," + py, Math.hypot(a[0] - n[0], a[1] - n[1]) < 0.6);
                    }
                }
        }
        // 3. directions no page holds
        expect(tag + ": a sight line pointing away from the Sun has no place on a sunward page or plane",
                c.name().startsWith("sky") || c.name().startsWith("ortho") || SkyScreen.place(c.mv(), c.vp(), first, new Vec3(0, 0, 1)) == null);
        expect(tag + ": a sight line level with the observer has no place on a plane",
                !(c.name().startsWith("radial") || c.name().startsWith("unrolled")) || SkyScreen.place(c.mv(), c.vp(), first, new Vec3(1, 0, 0)) == null);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-sky-place").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        SurfaceTransition.setAnimateEnabled(false);
        var fr = Display.class.getDeclaredField("fieldRadius");
        fr.setAccessible(true);
        fr.set(null, (DoubleSupplier) () -> 60); // nothing is loaded: the field the warp is normalized over
        Display.setHelioradial3D(false);

        JHVTime time = new JHVTime("2026-03-26T12:00:00");
        Position viewpoint = new Position(time, 215, 0, 0);
        Position[] layers = {viewpoint, new Position(time, 150, 0.2, -0.05)}; // two layers: another distance and another frame
        Viewport vp = viewport();
        List<Cfg> cfgs = new ArrayList<>();

        // HPC, panned and zoomed
        Display.setMapMode(MapMode.HPC);
        for (double zoom : new double[]{1, 2.5}) {
            for (double[] pan : new double[][]{{0, 0}, {0.07, -0.03}}) {
                resetCamera(viewpoint, 1);
                Display.getCamera().setTranslation(pan[0], pan[1]);
                vp.zoom = zoom;
                MapScale s = MapScale.hpc(2.5, 2.0); // degrees
                MapView mv = MapView.create(Display.getCamera(), viewpoint, MapMode.HPC, GridType.Viewpoint, new MapScale[]{s});
                cfgs.add(new Cfg("hpc zoom " + zoom + " pan " + pan[0], mv, vp, hpc(mv, vp, s), false, 3));
                run(cfgs.getLast(), layers);
                vp = viewport();
            }
        }

        // Helioradial and Unrolled, over the warp (lambda 1, 0.4, 0) and a crop
        for (MapMode mode : new MapMode[]{MapMode.Helioradial, MapMode.HelioradialUnrolled}) {
            Display.setMapMode(mode);
            for (double lambda : new double[]{1, 0.4, 0}) {
                for (double crop : new double[]{0, 30}) {
                    Display.setWarpLambda(lambda);
                    Display.setWarpOuterRadius(crop);
                    resetCamera(viewpoint, 0);
                    Display.getCamera().setTranslation(0.03, 0.02);
                    MapScale s = Display.warpScale();
                    MapView mv = MapView.create(Display.getCamera(), viewpoint, mode, GridType.Viewpoint, new MapScale[]{s});
                    String name = (mode == MapMode.Helioradial ? "radial" : "unrolled") + " lambda " + lambda + " crop " + crop;
                    cfgs.add(new Cfg(name, mv, vp, mode == MapMode.Helioradial ? radial(mv, vp, s) : unrolled(mv, vp, s), true, 12));
                    run(cfgs.getLast(), layers);
                }
            }
        }
        Display.setWarpOuterRadius(0);
        Display.setWarpLambda(1);

        // Observer Sky: plain and composed, three projections, aimed away from the Sun, per-observer surfaces
        Display.setMapMode(MapMode.ObserverSky);
        for (MapMode base : new MapMode[]{MapMode.Orthographic, MapMode.Helioradial}) {
            Display.setSkyBase(base);
            for (SkyProjection proj : SkyProjection.values()) {
                for (double[] look : new double[][]{{0, 0}, {0.12, -0.07}}) {
                    for (boolean fromImage : new boolean[]{true, false}) {
                        for (SurfaceModel model : base == MapMode.Helioradial ? new SurfaceModel[]{SurfaceModel.PlaneOfSky, SurfaceModel.ThomsonSphere} : new SurfaceModel[]{SurfaceModel.PlaneOfSky}) {
                            Display.setSkyProjection(proj);
                            Display.setSkyFieldDegrees(8);
                            Display.setSkyLook(look[0], look[1]);
                            Display.setSurfaceFromImage(fromImage);
                            setSurface(model);
                            resetCamera(viewpoint, 0);
                            double half = Math.toDegrees(proj.radiusFromAngle(Math.toRadians(Display.getSkyFieldDegrees())));
                            MapScale s = MapScale.sky(half * vp.aspect, half);
                            MapView mv = MapView.create(Display.getCamera(), viewpoint, MapMode.ObserverSky, GridType.Viewpoint, new MapScale[]{s});
                            MapScale warp = Display.skyComposeScale();
                            expect("sky base " + base + " composes only on Helioradial", (warp != null) == (base == MapMode.Helioradial));
                            String name = String.format("sky %s %s look %.2f fromImage %b %s", base, proj, look[0], fromImage, model);
                            cfgs.add(new Cfg(name, mv, vp, sky(mv, vp, s, proj, Display.getSkyLookLon(), Display.getSkyLookLat(), warp,
                                    model.depthFactor(), viewpoint.distance, fromImage), warp != null, 10));
                            run(cfgs.getLast(), layers);
                        }
                    }
                }
            }
        }
        Display.setSurfaceFromImage(true);
        setSurface(SurfaceModel.PlaneOfSky);

        // Orthographic: straight on, then orbited 40 and 85 degrees; a pan and a zoom
        Display.setMapMode(MapMode.Orthographic);
        for (double deg : new double[]{0, 40, 85, 172}) {
            resetCamera(viewpoint, 0);
            Display.getCamera().rotateDragRotation(Quat.createAxisY(Math.toRadians(deg)));
            Display.getCamera().rotateDragRotation(Quat.createAxisX(Math.toRadians(deg / 3)));
            Display.getCamera().setTranslation(0.4, -0.2);
            vp = viewport();
            vp.zoom = deg > 50 ? 0.5 : 1;
            MapView mv = MapView.create(Display.getCamera(), viewpoint, MapMode.Orthographic, GridType.Viewpoint, new MapScale[]{MapScale.ortho});
            cfgs.add(new Cfg("ortho orbit " + deg, mv, vp, ortho(mv, vp), false, 1.2));
            run(cfgs.getLast(), layers);
        }
        resetCamera(viewpoint, 0);

        expect("Newton on the probe was compared on many points", agreed > 40 && tried >= agreed);
        expect("the pages were compared", compared > 5000);
        expect("some sight lines had no place", nulls > 100);
        if (failures > 0)
            throw new AssertionError(failures + " placement failure(s)");
        System.out.println("SkyPlaceCheck: PASS (" + cfgs.size() + " views, " + compared + " pixels compared, " + alternates
                + " placed on a second pixel that shows the same sight line, " + nulls + " sight lines with no place)");
    }

    private SkyPlaceCheck() {}
}
