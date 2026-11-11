package org.helioviewer.jhv.display;

import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.identify.Probes;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The Observer Sky identify probe against solarSky.frag. The imagery shader composes the sky (undoes
 * the radial scale from the surface distance, lines 81-101) and discards past the rim; the probe has
 * to ask the same question of the same pixel, per observer when "Surface from: image observer" is on.
 *
 * <p>{@link #shader} is that block in Java, written from the GLSL and not from SkyMap, with
 * imageCommon.frag's unwarpRadiusWith / warpUnitWith and solarCommon's surfaceElongation.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.SkyProbeCheck
 */
public final class SkyProbeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        if (!ok) {
            System.out.println("  FAIL " + what);
            failures++;
        }
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
        double u = lambda == 0 ? Math.log(r) / Math.log(outer)
                : (Math.pow(r, lambda) - 1) / (Math.pow(outer, lambda) - 1);
        return limb + u * (1 - limb);
    }

    private static double surfaceElongation(double r, double d, double model) {
        if (d <= 0)
            return 0;
        double ratio = r / d;
        if (model == 1)
            return Math.asin(Math.clamp(ratio, -1, 1));
        if (model == .5)
            return 2 * Math.asin(Math.clamp(.5 * ratio, -1, 1));
        return Math.atan(ratio);
    }

    /** solarSky.frag main(), page (degrees) to ray, ARC projection aimed at (0, 0); null where it discards. */
    private static Vec3 shader(Vec2 pageDeg, double surfaceFrom, double model, MapScale warp) {
        double px = Math.toRadians(pageDeg.x), py = Math.toRadians(pageDeg.y);
        double radius = Math.hypot(px, py);
        double rho = radius;
        if (rho > Math.PI)
            return null;
        Vec3 ray = radius < 1e-9 ? new Vec3(0, 0, -1)
                : new Vec3(Math.sin(rho) * px / radius, Math.sin(rho) * py / radius, -Math.cos(rho));
        if (ray.z >= 0)
            return null;
        double outer = warp.warpOuterRadius(), limb = warp.warpLimb(), lambda = warp.warpLambda();
        double reach = model <= 0 ? 1e30 : surfaceFrom / model;
        double rMax = Math.min(outer, reach * 0.999);
        double eMax = surfaceElongation(rMax, surfaceFrom, model);
        double uMax = warpUnitWith(rMax, outer, limb, lambda);
        double eDome = Math.acos(Math.clamp(-ray.z, -1, 1));
        if (eMax <= 0 || eDome > eMax)
            return null;
        double r = unwarpRadiusWith(uMax * eDome / eMax, outer, limb, lambda);
        double eTrue = surfaceElongation(r, surfaceFrom, model);
        double len = Math.hypot(ray.x, ray.y);
        return len < 1e-9 ? new Vec3(0, 0, -1)
                : new Vec3(ray.x / len * Math.sin(eTrue), ray.y / len * Math.sin(eTrue), -Math.cos(eTrue));
    }

    // Display.setSurfaceModel starts a transition, which reaches SPICE through DisplayController.
    private static void setSurface(SurfaceModel model) throws Exception {
        java.lang.reflect.Field f = Display.class.getDeclaredField("surfaceModel");
        f.setAccessible(true);
        f.set(null, model);
    }

    private static double angle(Vec3 a, Vec3 b) {
        return Math.acos(Math.clamp(a.x * b.x + a.y * b.y + a.z * b.z, -1, 1));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-sky-probe").toString());
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs(); // else each Settings write logs a stack trace
        SurfaceTransition.setAnimateEnabled(false);
        Display.fieldRadius = (DoubleSupplier) () -> 245; // the layer stack needs SPICE; this is the loaded field
        Display.mode = MapMode.ObserverSky;
        Display.setSkyBase(MapMode.Helioradial);
        Display.setSkyProjection(SkyProjection.AzimuthalEquidistant);
        Display.setSkyLook(0, 0);
        expect("the sky is composed", Display.skyComposeScale() != null);

        double viewpoint = Sun.MeanEarthDistance;
        double[] observers = {viewpoint, 0.7 * viewpoint}; // two image observers
        Position[] obs = new Position[observers.length];
        for (int i = 0; i < obs.length; i++)
            obs[i] = new Position(new JHVTime("2025-09-22T05:46:30"), observers[i], 0, 0);

        int compared = 0, nulls = 0;
        double perObserverSpread = 0;
        for (boolean fromImage : new boolean[]{true, false}) {
            Display.setSurfaceFromImage(fromImage);
            for (double lambda : new double[]{1, 0.5, 0}) {
                Display.setWarpLambda(lambda);
                for (double crop : new double[]{0, 60}) {
                    Display.setWarpOuterRadius(crop);
                    MapScale warp = Display.skyComposeScale();
                    for (SurfaceModel surface : new SurfaceModel[]{SurfaceModel.PlaneOfSky, SurfaceModel.ThomsonSphere, SurfaceModel.CelestialSphere}) {
                        setSurface(surface);
                        String tag = String.format("fromImage %b, lambda %.1f, crop %.0f, %s", fromImage, lambda, crop, surface);
                        Vec3[] first = new Vec3[1];
                        for (Position o : obs) {
                            double from = fromImage ? o.distance : viewpoint;
                            Vec3 prev = null;
                            for (double az = 0; az < 360; az += 45) {
                                for (double deg = 0; deg <= 179; deg += 0.5) {
                                    Vec2 page = new Vec2(deg * Math.cos(Math.toRadians(az)), deg * Math.sin(Math.toRadians(az)));
                                    Vec3 want = shader(page, from, surface.depthFactor(), warp);
                                    Vec3 got = Probes.sky(page, viewpoint).direction(o);
                                    compared++;
                                    if (want == null) {
                                        nulls++;
                                        expect(tag + ": past the rim at " + page.x + "," + page.y + " is null", got == null);
                                    } else {
                                        boolean ok = got != null && angle(want, got) < 1e-6;
                                        expect(tag + ": ray at " + page.x + "," + page.y, ok);
                                        if (ok && o == obs[1] && first[0] != null && az == 0 && Math.abs(deg - 5) < 1e-9)
                                            perObserverSpread = Math.max(perObserverSpread, angle(first[0], got));
                                    }
                                    if (o == obs[0] && az == 0 && Math.abs(deg - 5) < 1e-9)
                                        first[0] = got;
                                }
                            }
                        }
                    }
                }
            }
        }
        Display.setSurfaceFromImage(true);
        Display.setWarpOuterRadius(0);
        setSurface(SurfaceModel.PlaneOfSky);
        expect("some points were past the rim and some inside it", nulls > 0 && nulls < compared);
        expect("two image observers disagree about the same pixel (" + perObserverSpread + " rad)", perObserverSpread > 1e-4);

        if (failures > 0)
            throw new AssertionError(failures + " sky probe failure(s)");
        System.out.println("SkyProbeCheck: PASS (" + compared + " points, " + nulls + " past the rim)");
    }

}
