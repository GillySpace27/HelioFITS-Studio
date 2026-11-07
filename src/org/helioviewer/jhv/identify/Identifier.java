package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.wcs.WcsHeader;
import org.helioviewer.jhv.wcs.WcsProjection;

/**
 * Which solar system bodies lie in a direction, as seen by each image layer's own observer.
 *
 * <p>The question is per layer because each image was taken from its own place at its own time:
 * PUNCH from near Earth, LASCO from L1, an EUI frame from Solar Orbiter. The cursor is turned into
 * a sight line in each layer's frame ({@link Probe}), each body's position is put in the same
 * frame, and a body is under the cursor when the angle between them is within the body's own
 * angular radius, plus the uncertainty of where a near-Earth observer really was, plus the
 * pointer tolerance in screen pixels. No GL and no Swing, so a check can drive it directly.
 */
public final class Identifier {

    /** One image layer as the identifier needs it. {@code wcs} and {@code region} are null for the bare display viewpoint. */
    public record Target(String name, Position observer, @Nullable WcsHeader wcs, @Nullable Region region,
                         double innerRadius, double outerRadius) {
        public static Target viewpoint(Position observer) {
            return new Target("view", observer, null, null, 0, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * The cursor as a sight line in a layer's frame: a unit vector from the layer's observer, which
     * sits at (0, 0, distance) looking down -z with solar north up. Null where the view shows no sky.
     */
    @FunctionalInterface
    public interface Probe {
        @Nullable
        Vec3 direction(Position observer);
    }

    /** Why a body under the cursor might not be visible in the imagery. NONE means it should be. */
    public enum Qualifier {
        NONE(""), BEHIND_SUN("behind the Sun"), OCCULTED("behind the occulter"), OUTSIDE_FIELD("outside the field");

        public final String text;

        Qualifier(String _text) {
            text = _text;
        }
    }

    /** A body under the cursor, for one layer. Angles in degrees, distance from the observer in AU. */
    public record Match(Bodies.Body body, String layer, double separation, double angularRadius, double tolerance,
                        double distanceAu, double elongation, Qualifier qualifier) {
        /** How far inside its match radius the cursor is, as a fraction: 0 at the centre, 1 at the edge. */
        double closeness() {
            double r = angularRadius + tolerance;
            return r > 0 ? separation / r : 0;
        }

        public String label() {
            return qualifier == Qualifier.NONE ? body.name() : body.name() + " (" + qualifier.text + ")";
        }
    }

    /** What to look for, so a check and a command can say it without the settings file. */
    public record Options(double tolerancePixels, double earthUncertaintyKm, boolean lightTime, boolean pluto) {
        public static Options fromSettings() {
            return new Options(IdentifySettings.tolerancePixels(), IdentifySettings.earthUncertaintyKm(),
                    IdentifySettings.lightTime(), IdentifySettings.pluto());
        }
    }

    /** An observer this close to Earth's centre may have been put there by its header (judgement: well past the Moon's orbit). */
    private static final double NEAR_EARTH_KM = 1.0e6;
    /** A body this many of its radii from the observer is the observer's own platform, not a target (judgement). */
    private static final double OWN_BODY_RADII = 50;

    /**
     * Every body under the cursor, one entry per body (the layer that shows it best), nearest
     * first. {@code probe1} is the same probe one screen pixel to the right; it sets the pointer
     * tolerance's angle, and may be null to use none.
     */
    public static List<Match> identify(List<Target> targets, Probe probe, @Nullable Probe probe1, Options options) {
        Map<String, Match> best = new LinkedHashMap<>();
        for (Target t : targets) {
            Vec3 dir = probe.direction(t.observer());
            if (dir == null)
                continue;
            double pixel = 0;
            if (probe1 != null) {
                Vec3 dir1 = probe1.direction(t.observer());
                if (dir1 != null)
                    pixel = angle(dir, dir1);
            }
            for (Match m : identifyOne(t, dir, pixel * options.tolerancePixels(), options)) {
                Match old = best.get(m.body().name());
                if (old == null || better(m, old))
                    best.put(m.body().name(), m);
            }
        }
        List<Match> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(Match::closeness));
        return out;
    }

    /**
     * Where a body is in an observer's sky: the unit sight line in the observer's frame (observer at
     * +z, Sun at -z, solar north up), or null outside the ephemeris. The helioprojective angles are
     * {@link #helioprojective} of it.
     */
    @Nullable
    public static Vec3 bodyDirection(Bodies.Body body, Position observer, boolean lightTime) {
        Vec3 obsWorld = ModelPlacement.toWorld(observer);
        Vec3 world = Bodies.apparent(body, observer.time, obsWorld, lightTime);
        if (world == null)
            return null;
        return unit(observer.toQuat().rotateVector(new Vec3(world.x - obsWorld.x, world.y - obsWorld.y, world.z - obsWorld.z)));
    }

    /** A match the imagery shows beats one it does not; then the closer one. */
    private static boolean better(Match a, Match b) {
        boolean aShown = a.qualifier() == Qualifier.NONE, bShown = b.qualifier() == Qualifier.NONE;
        if (aShown != bShown)
            return aShown;
        return a.closeness() < b.closeness();
    }

    /** The bodies near {@code dir} for one layer; {@code toleranceRad} is the pointer tolerance as an angle. */
    static List<Match> identifyOne(Target t, Vec3 dir, double toleranceRad, Options options) {
        List<Match> out = new ArrayList<>();
        Position obs = t.observer();
        Quat q = obs.toQuat();
        Vec3 obsWorld = ModelPlacement.toWorld(obs);
        Vec3 earth = Bodies.world(Bodies.byName("Earth"), obs.time);
        boolean nearEarth = earth != null && distance(earth, obsWorld) * Sun.RadiusMeter / 1e3 < NEAR_EARTH_KM;
        double sunAngR = Math.asin(Math.min(1, Sun.Radius / obs.distance));

        for (Bodies.Body body : Bodies.ALL) {
            if (!options.pluto() && body.name().equals("Pluto"))
                continue;
            Vec3 world = Bodies.apparent(body, obs.time, obsWorld, options.lightTime());
            if (world == null)
                continue;
            Vec3 rel = q.rotateVector(new Vec3(world.x - obsWorld.x, world.y - obsWorld.y, world.z - obsWorld.z));
            double range = rel.length();
            if (range < OWN_BODY_RADII * body.radius())
                continue;
            Vec3 bodyDir = scale(rel, 1 / range);
            double sep = angle(dir, bodyDir);
            double angR = Math.asin(Math.min(1, body.radius() / range));
            double unc = nearEarth && !body.name().equals("Sun") ? Math.atan(options.earthUncertaintyKm() / (range * Sun.RadiusMeter / 1e3)) : 0;
            double tol = unc + toleranceRad;
            if (sep > angR + tol)
                continue;
            double elongation = angle(bodyDir, new Vec3(0, 0, -1)); // the Sun is at -z from the observer
            Qualifier qual = qualifier(t, body, bodyDir, range, elongation, sunAngR);
            out.add(new Match(body, t.name(), Math.toDegrees(sep), Math.toDegrees(angR), Math.toDegrees(tol),
                    range * Sun.MeanEarthDistanceInv, Math.toDegrees(elongation), qual));
        }
        return out;
    }

    private static Qualifier qualifier(Target t, Bodies.Body body, Vec3 bodyDir, double range, double elongation, double sunAngR) {
        if (!body.name().equals("Sun") && range > t.observer().distance && elongation < sunAngR)
            return Qualifier.BEHIND_SUN;
        if (t.wcs() == null || t.region() == null)
            return Qualifier.NONE;
        if (bodyDir.z >= 0) // behind the observer: no image plane holds it
            return Qualifier.OUTSIDE_FIELD;
        Vec2 hpc = hpcPlane(bodyDir, t.observer().distance);
        double r = Math.hypot(hpc.x, hpc.y);
        if (r > t.outerRadius())
            return Qualifier.OUTSIDE_FIELD;
        if (r < t.innerRadius() && !body.name().equals("Sun"))
            return Qualifier.OCCULTED;
        return inField(t.wcs(), t.region(), bodyDir) ? Qualifier.NONE : Qualifier.OUTSIDE_FIELD;
    }

    /** Whether a direction lands on the image, by the same mapping the shaders sample with (wcsPlaneToUnclampedTexcoord). */
    static boolean inField(WcsHeader wcs, Region region, Vec3 dir) {
        if (wcs.projection.isSurfaceMap())
            return false;
        Vec2 hp = helioprojective(dir);
        Vec2 c = WcsProjection.helioprojectiveToPlane(wcs, hp.x, hp.y);
        if (c == null)
            return false;
        double u = (c.x - region.llx) / region.width;
        double v = (-c.y - region.lly) / region.height;
        return u >= 0 && u <= 1 && v >= 0 && v <= 1;
    }

    /** (Tx, Ty) in radians of a sight line, the shaders' worldToHelioprojective convention. */
    public static Vec2 helioprojective(Vec3 dir) {
        return new Vec2(Math.atan2(dir.x, -dir.z), Math.atan2(dir.y, Math.hypot(dir.x, dir.z)));
    }

    /** The unit sight line for (Tx, Ty) in radians: helioprojectiveRay in WcsProjection, without the far-side flip. */
    public static Vec3 ray(double tx, double ty) {
        double cy = Math.cos(ty);
        return new Vec3(Math.sin(tx) * cy, Math.sin(ty), -Math.cos(tx) * cy);
    }

    /** Where a sight line crosses the plane of sky through Sun centre, in solar radii. */
    static Vec2 hpcPlane(Vec3 dir, double observerDistance) {
        return new Vec2(-observerDistance * dir.x / dir.z, -observerDistance * dir.y / dir.z);
    }

    static double angle(Vec3 a, Vec3 b) {
        // atan2 of |a x b| and a.b: accurate at small angles, where acos is not
        Vec3 c = Vec3.cross(a, b);
        return Math.atan2(c.length(), Vec3.dot(a, b));
    }

    private static double distance(Vec3 a, Vec3 b) {
        return new Vec3(a.x - b.x, a.y - b.y, a.z - b.z).length();
    }

    static Vec3 scale(Vec3 v, double f) {
        return new Vec3(v.x * f, v.y * f, v.z * f);
    }

    static Vec3 unit(Vec3 v) {
        double l = v.length();
        return l == 0 ? v : scale(v, 1 / l);
    }

    private Identifier() {}
}
