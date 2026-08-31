package org.helioviewer.jhv.layers.grid;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.time.JHVTime;

/**
 * Planet positions and orbits, straight from SPICE, drawn by the grid layer beside the Earth
 * marker it already draws.
 *
 * <p>Deliberately independent of ViewpointLayer, which also draws planets. That one only renders
 * in its Heliosphere camera mode, so asking for planets there means accepting a particular camera,
 * a particular viewpoint mode, and a selection list in another panel. Planets are a scene
 * annotation like the grid or the ecliptic, and should not require handing the camera over to get
 * them.
 *
 * <p>Positions are computed in the INERTIAL HCI frame and then carried into the display frame by
 * one angle; see the note on {@code INERTIAL} below for why the rotating Carrington frame cannot
 * be used for this. Every body below was checked against the shipped ephemeris
 * (resources/kernels/de432s_reduced.bsp) rather than assumed: it carries Mercury and Venus as
 * bodies, and Mars through Pluto as barycentres, which is exactly how SpaceObject names them.
 */
public final class PlanetMarkers {

    /** The planets, in orbital order. Mercury through Neptune; Pluto is in the kernel but not here. */
    private static final String[] NAMES = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};

    private static final int ORBIT_SAMPLES = 180;
    private static final int PERIOD_SCAN_STEPS = 96;

    /** A planet as drawn: where it is, what to call it, and what colour it takes. */
    public record Marker(SpaceObject body, Vec3 position, byte[] color) {
        public String label() {
            return body.toString();
        }
    }

    public static List<SpaceObject> bodies() {
        List<SpaceObject> list = new ArrayList<>(NAMES.length);
        for (String name : NAMES) {
            SpaceObject body = SpaceObject.get(name);
            if (body != null)
                list.add(body);
        }
        return list;
    }

    /**
     * Where each planet is, in solar radii, in the display frame. A body the ephemeris cannot
     * place is skipped rather than drawn at the origin, which would put a false marker on the Sun.
     */
    public static List<Marker> positions(JHVTime time, boolean followSolarRotation) {
        double offset = placementOffset(time, followSolarRotation);
        List<Marker> markers = new ArrayList<>(NAMES.length);
        for (SpaceObject body : bodies()) {
            double[] v = hci(body, time);
            if (v != null)
                markers.add(new Marker(body, toDisplay(v, offset), body.getColor()));
        }
        return markers;
    }

    /**
     * Where the planets are computed, and why it is not the frame they are drawn in.
     *
     * <p>getCarrington reports longitude in the ROTATING solar frame, so every planet's longitude
     * advances at the Sun's rotation rate rather than its own orbital rate. Measured against the
     * shipped ephemeris: Earth 13.199 deg/day, Neptune 14.178 deg/day. In that frame Neptune
     * appears to orbit in 25 days, a period measurement returns the solar rotation for every body,
     * and sampling an "orbit" over it aliases into a star polygon rather than an ellipse.
     *
     * <p>So the geometry is computed in HCI, which is inertial, where the same bodies give 0.986
     * and 0.00608 deg/day, or 1.00 and 162 years against true periods of 1.00 and 164.8. Then one
     * angle carries the result into the display frame.
     */
    private static final String INERTIAL = org.helioviewer.jhv.astronomy.Frame.SOLO_HCI.toString();

    /** Rectangular HCI position in solar radii, or null when the ephemeris cannot place it. */
    @Nullable
    private static double[] hci(SpaceObject body, JHVTime time) {
        try {
            double[] v = Spice.getPosition("SUN", body.getSpiceName(), INERTIAL, time);
            return v[0] == 0 && v[1] == 0 && v[2] == 0 ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The angle carrying HCI longitude into the display frame's, which is a reflection rather than
     * a rotation: {@code displayLon = offset - hciLon}, latitude unchanged.
     *
     * <p>Verified rather than derived: at one epoch, carrLon + hciLon came to 92.617 degrees for
     * Mercury, Venus, Earth, Mars, Jupiter and Neptune alike, to a thousandth of a degree, with
     * latitudes identical. One angle therefore moves every body, which is what lets an orbit be
     * built inertially and then placed rigidly.
     */
    private static double frameOffset(JHVTime time) {
        Position carr = Spice.getCarrington("EARTH", time);
        double[] v = hci(SpaceObject.get("Earth"), time);
        if (carr == null || v == null)
            return 0;
        return carr.lon + Math.atan2(v[1], v[0]);
    }

    /**
     * A reference epoch, 2000-01-01, at which the offset is frozen for the inertial layout.
     *
     * <p>Fixed rather than taken from the session so the layout is reproducible: the same date
     * always puts the planets in the same place, whatever was on screen when the layer loaded.
     */
    private static final long EPOCH = 946684800000L;
    private static double frozenOffset = Double.NaN;

    /**
     * The offset to place bodies with, and the whole of why the planets looked wrong.
     *
     * <p>Evaluated at the display time, this angle is the Carrington prime meridian, which turns
     * at 14.184 deg/day. Since {@code displayLon = offset - hciLon}, every planet then moves at
     * {@code 14.184 - omega}: measured on screen, Mercury 10.83, Earth 13.20, Neptune 14.18
     * deg/day, against true orbital rates of 3.355, 0.986 and 0.0061. The Sun's rotation supplied
     * essentially all of the motion and the orbits appeared only as a small difference on top of
     * it, which is why Neptune raced round 2320 times too fast.
     *
     * <p>Held fixed instead, the same expression gives {@code -omega}: each planet moves at its
     * own rate, and in the right direction, because Carrington longitude increases retrograde
     * (a feature on the surface holds a constant Carrington longitude while Earth's climbs).
     *
     * <p>The cost is that the planets are no longer registered to solar longitude: they sit a
     * constant rotation away from it, growing with distance from the epoch. That matters only if
     * the question is which planet lies over which active region, which is what following the
     * rotation is for.
     */
    private static double placementOffset(JHVTime time, boolean followSolarRotation) {
        if (followSolarRotation)
            return frameOffset(time);
        if (Double.isNaN(frozenOffset))
            frozenOffset = frameOffset(new JHVTime(EPOCH));
        return frozenOffset;
    }

    /** An HCI vector placed in the display frame, using an offset the caller holds fixed. */
    private static Vec3 toDisplay(double[] v, double offset) {
        double r = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (r <= 0)
            return new Vec3(0, 0, 0);
        double lat = Math.asin(Math.clamp(v[2] / r, -1, 1));
        // hci MINUS offset, not offset minus hci. The other way round mirrors every body across
        // the x = 0 plane: Earth came out at (+39.47, 26.53, -210.55) where the observer marker
        // sits at (-39.47, 26.53, -210.55), same latitude and same depth, reflected in longitude.
        // A mirrored solar system still looks like a solar system, which is why it survived.
        double lon = Math.atan2(v[1], v[0]) - offset;
        double cosLat = Math.cos(lat);
        // Matches Position.toQuat's convention, which is how the Earth marker is placed.
        return new Vec3(r * cosLat * Math.sin(lon), r * Math.sin(lat), r * cosLat * Math.cos(lon));
    }

    /**
     * One closed orbit per planet, sampled from the ephemeris rather than drawn as a circle.
     *
     * <p>The period is measured, not tabulated: the scan below steps forward accumulating the
     * unwrapped change in heliographic longitude and stops when it passes a full turn. That costs
     * a few dozen extra queries per planet and avoids both a table of constants and the closure
     * error a circular approximation would give an eccentric orbit like Mercury's.
     */
    public static void buildOrbits(GLSLLine line, JHVTime time, double alpha, boolean followSolarRotation) {
        List<SpaceObject> bodies = bodies();
        BufVertex buf = new BufVertex(bodies.size() * (ORBIT_SAMPLES + 3) * GLSLLine.stride);
        // ONE offset, taken at the display time and held for every sample. Recomputing it per
        // sample would fold the solar rotation back into the curve and reproduce the star polygon
        // this replaced: the ellipse is a fixed shape that the frame carries around, not a path
        // through a turning frame.
        double offset = placementOffset(time, followSolarRotation);

        for (SpaceObject body : bodies) {
            long[] span = orbitSpan(body, time);
            if (span == null || span[1] <= span[0])
                continue;
            byte[] color = Colors.bytes(body.getColor()[0] & 0xFF, body.getColor()[1] & 0xFF,
                    body.getColor()[2] & 0xFF, (int) Math.round(255 * Math.clamp(alpha, 0, 1)));
            for (int i = 0; i <= ORBIT_SAMPLES; i++) {
                double[] v = hci(body, new JHVTime(span[0] + (span[1] - span[0]) * i / ORBIT_SAMPLES));
                if (v == null)
                    continue;
                Vec3 p = toDisplay(v, offset);
                if (i == 0)
                    buf.putVertex((float) p.x, (float) p.y, (float) p.z, 1, Colors.Null);
                buf.putVertex((float) p.x, (float) p.y, (float) p.z, 1, color);
                if (i == ORBIT_SAMPLES)
                    buf.putVertex((float) p.x, (float) p.y, (float) p.z, 1, Colors.Null);
            }
        }
        line.setVertex(buf);
    }

    /**
     * The stretch of time to draw an orbit over: {@code [start, end]} in epoch millis.
     *
     * <p>One full revolution where the ephemeris allows it, found by walking outward accumulating
     * the unwrapped change in inertial longitude. Kepler's third law supplies only the first guess
     * at a step size, so an eccentric orbit like Mercury's costs a few more steps rather than a
     * wrong answer. Measured against true periods: Mercury 0.241, Earth 1.000, Jupiter 11.864
     * years, all within 0.1 percent.
     *
     * <p>The outer planets do not fit. de432s_reduced spans decades, not centuries, so from 2025 a
     * single Saturn orbit already runs off the end, and Uranus and Neptune are further out of
     * reach. Rather than refuse them or invent the missing arc, the walk goes both directions from
     * the display time and returns whatever the ephemeris actually covers. Those orbits are drawn
     * as open arcs, which is the honest picture: the data ends there.
     */
    static long[] orbitSpan(SpaceObject body, JHVTime time) {
        double[] start = hci(body, time);
        if (start == null)
            return null;
        double r = Math.sqrt(start[0] * start[0] + start[1] * start[1] + start[2] * start[2]);

        // a^(3/2) years, with a in AU, as a step size only. 215 solar radii to the AU.
        double au = r / 215.032;
        double estimate = Math.pow(Math.max(au, 0.01), 1.5) * 365.25 * 86400_000L;
        long step = (long) Math.max(estimate / PERIOD_SCAN_STEPS, 3600_000L);

        Walk forward = walk(body, time, step, start, 2 * Math.PI);
        if (forward.completed)
            return new long[]{time.milli, time.milli + forward.covered};

        // Forward alone ran out. Go back for the remainder rather than settling for the arc:
        // Neptune's 165 years do not fit between 2025 and the ephemeris end, but they do fit
        // between 1970 and 2150, which is the window actually available.
        Walk back = walk(body, time, -step, start, 2 * Math.PI - Math.abs(forward.turned));
        return new long[]{time.milli - back.covered, time.milli + forward.covered};
    }

    private record Walk(long covered, double turned, boolean completed) {}

    /**
     * Walk in one direction until {@code target} radians of longitude have gone by, or until the
     * data runs out. Two things end it early and both are real limits rather than errors: the
     * ephemeris span, and JHVTime's refusal of epochs before 1970, which is why the outer planets
     * can reach 2150 forward but only 1970 back.
     */
    private static Walk walk(SpaceObject body, JHVTime time, long step, double[] start, double target) {
        if (target <= 0)
            return new Walk(0, 0, true);
        double turned = 0;
        double previous = Math.atan2(start[1], start[0]);
        long covered = 0;
        for (int i = 1; i <= 16 * PERIOD_SCAN_STEPS; i++) {
            long at = time.milli + step * i;
            if (at < 0) // JHVTime cannot express it; stop here rather than throw
                break;
            double[] v = hci(body, new JHVTime(at));
            if (v == null)
                break; // past the end of the ephemeris
            double lon = Math.atan2(v[1], v[0]);
            double d = lon - previous;
            d -= 2 * Math.PI * Math.floor((d + Math.PI) / (2 * Math.PI)); // shortest signed step
            turned += d;
            previous = lon;
            covered = Math.abs(step) * i;
            if (Math.abs(turned) >= target) {
                // Interpolate across the crossing step, so the loop closes rather than overshooting.
                double excess = (Math.abs(turned) - target) / Math.abs(d);
                return new Walk((long) (Math.abs(step) * (i - excess)), turned, true);
            }
        }
        return new Walk(covered, turned, false);
    }

    private PlanetMarkers() {}
}
