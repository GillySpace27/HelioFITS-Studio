package org.helioviewer.jhv.identify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

import spice.basic.SpiceErrorException;

/**
 * The solar system bodies the identifier can name, and where each one is.
 *
 * <p>Positions come from the bundled SPICE kernels (de432s_reduced covers the Sun, the Moon, the
 * planets and the Pluto barycentre from 1989-12-30 to 2050-01-01), the same ephemeris the planet
 * markers and the model layers use. A position is returned in the world frame the images are drawn
 * in (solar radii, the Carrington frame at the time asked for, the axes of ModelPlacement.toWorld),
 * so it can be compared directly with a layer's observer.
 */
public final class Bodies {

    /** One body: the name shown, the SPICE name, and the mean radius in solar radii. */
    public record Body(String name, String spiceName, double radius) {}

    /** In order of distance from the Sun, the Moon after the Earth. Pluto can be turned off in Settings > Identify. */
    public static final List<Body> ALL = List.of(
            body("Sun"), body("Mercury"), body("Venus"), body("Earth"), body("Moon"), body("Mars"),
            body("Jupiter"), body("Saturn"), body("Uranus"), body("Neptune"), body("Pluto"));

    private static Body body(String name) {
        if (name.equals("Sun"))
            return new Body("Sun", SpaceObject.SUN.getSpiceName(), Sun.Radius);
        SpaceObject o = SpaceObject.get(name);
        if (o == null)
            throw new IllegalStateException("no SpaceObject " + name);
        return new Body(name, o.getSpiceName(), o.getRadius());
    }

    @Nullable
    public static Body byName(String name) {
        for (Body b : ALL)
            if (b.name().equalsIgnoreCase(name))
                return b;
        return null;
    }

    /** Ephemeris lookups, for the cost guard: a frame of marks should cost a handful, then none while the time holds. */
    static final java.util.concurrent.atomic.LongAdder APPARENT = new java.util.concurrent.atomic.LongAdder();

    private static final int CACHE_SIZE = 4096;
    private static final Map<String, Vec3> cache = new LinkedHashMap<>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Vec3> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * Where the body is at {@code time}, in the world frame at {@code time}. Null outside the
     * kernel's coverage.
     */
    @Nullable
    public static Vec3 world(Body body, JHVTime time) {
        String key = body.spiceName() + '@' + time.milli;
        synchronized (cache) {
            Vec3 hit = cache.get(key);
            if (hit != null)
                return hit;
        }
        Vec3 v = worldAt(body, time.milli, time);
        if (v != null)
            synchronized (cache) {
                cache.put(key, v);
            }
        return v;
    }

    /**
     * Where an observer at {@code observer} sees the body at {@code time}: with light time, the
     * body's position when the light left it (one iteration, which for the Moon and the planets is
     * well below a pixel of any instrument in Studio), expressed in the frame at {@code time} so it
     * stays comparable with the observer.
     */
    @Nullable
    public static Vec3 apparent(Body body, JHVTime time, Vec3 observer, boolean lightTime) {
        APPARENT.increment();
        Vec3 geometric = world(body, time);
        if (!lightTime || geometric == null)
            return geometric;
        double range = new Vec3(geometric.x - observer.x, geometric.y - observer.y, geometric.z - observer.z).length();
        long emitted = time.milli - Math.round(range * Sun.RadiusMilli);
        return worldAt(body, emitted, time);
    }

    @Nullable
    private static Vec3 worldAt(Body body, long emittedMilli, JHVTime frameTime) {
        if (body.spiceName().equals("SUN"))
            return Vec3.ZERO;
        try {
            double[] v;
            if (emittedMilli == frameTime.milli) {
                v = Spice.getPosition("SUN", body.spiceName(), "SOLO_IAU_SUN_2009", frameTime);
            } else {
                double[] inertial = Spice.getPosition("SUN", body.spiceName(), "J2000", new JHVTime(emittedMilli));
                v = Spice.rotate("J2000", "SOLO_IAU_SUN_2009", frameTime.milli, inertial);
            }
            if (v[0] == 0 && v[1] == 0 && v[2] == 0)
                return null;
            return ModelPlacement.toWorld(ModelPlacement.carringtonPosition(frameTime, v[0], v[1], v[2]));
        } catch (SpiceErrorException e) { // outside the kernel's coverage
            return null;
        }
    }

    private Bodies() {}
}
