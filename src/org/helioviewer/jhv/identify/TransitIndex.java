package org.helioviewer.jhv.identify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.wcs.WcsHeader;

/**
 * When each body is in an instrument's field: the answer to "a date without the Moon".
 *
 * <p>Computed from the ephemeris, not from the data on disk: a date has the Moon in PUNCH's field
 * whether or not a file for it was ever downloaded, so the calendar, the timeline band and the
 * identifyInField command can all show it before anything is loaded. The observer moves with time
 * (its SPICE position), the field is fixed in the observer's helioprojective frame, and a body
 * counts as in the field when its centre is. Samples at a fixed step, then bisects each change to
 * one minute.
 */
public final class TransitIndex {

    /** A field of view in the observer's helioprojective frame. */
    @FunctionalInterface
    public interface Field {
        boolean contains(Vec3 dir, Position observer);
    }

    /**
     * Everything between two elongations from Sun centre, optionally cut to a square of half
     * width {@code halfWidthDeg} in Tx and Ty (0 for no square). PUNCH's mosaics are about a 46
     * degree half width (CDELT 0.0225 deg times 2048 pixels, from a PTM header).
     */
    public record Annulus(double innerDeg, double outerDeg, double halfWidthDeg) implements Field {
        public static final Annulus PUNCH = new Annulus(0, 90, 46.08);

        @Override
        public boolean contains(Vec3 dir, Position observer) {
            double e = Math.toDegrees(Identifier.angle(dir, new Vec3(0, 0, -1)));
            if (e < innerDeg || e > outerDeg)
                return false;
            if (halfWidthDeg <= 0)
                return true;
            if (dir.z >= 0)
                return false;
            Vec2 hp = Identifier.helioprojective(dir);
            return Math.abs(Math.toDegrees(hp.x)) <= halfWidthDeg && Math.abs(Math.toDegrees(hp.y)) <= halfWidthDeg;
        }
    }

    /** An image layer's own field: its pixels, inside its occulter and outer radius, as the identifier tests them. */
    public record LayerField(WcsHeader wcs, Region region, double innerRadius, double outerRadius) implements Field {
        @Override
        public boolean contains(Vec3 dir, Position observer) {
            if (dir.z >= 0)
                return false;
            Vec2 hpc = Identifier.hpcPlane(dir, observer.distance);
            double r = Math.hypot(hpc.x, hpc.y);
            return r >= innerRadius && r <= outerRadius && Identifier.inField(wcs, region, dir);
        }
    }

    /** [start, end] in milliseconds since 1970, UTC. */
    public record Interval(long start, long end) {}

    /** Where the observer is at a time, or null outside its ephemeris. */
    @FunctionalInterface
    public interface Observer {
        @Nullable
        Position at(JHVTime time);

        /** A SPICE target, as the viewpoint menus name them: EARTH, SOHO, STEREO AHEAD, SOLO, PSP. */
        static Observer spice(String name) {
            return t -> Spice.getCarrington(name, t);
        }
    }

    private static final long REFINE_MILLIS = 60_000;

    /**
     * Each body's in-field intervals between {@code start} and {@code end}, sampled every
     * {@code step} milliseconds. The Sun is left out (it is always at the centre of a solar
     * field), as is a body the observer is on.
     */
    public static Map<String, List<Interval>> compute(Observer observer, Field field, long start, long end, long step, boolean pluto) {
        Map<String, List<Interval>> out = new LinkedHashMap<>();
        if (end < start || step <= 0)
            return out;
        for (Bodies.Body body : Bodies.ALL) {
            if (body.name().equals("Sun") || (!pluto && body.name().equals("Pluto")))
                continue;
            List<Interval> list = new ArrayList<>();
            long t = start;
            Boolean prev = in(observer, field, body, t);
            long open = Boolean.TRUE.equals(prev) ? start : -1;
            while (t < end) {
                long next = Math.min(end, t + step);
                Boolean cur = in(observer, field, body, next);
                if (cur != null && prev != null && !cur.equals(prev)) {
                    long edge = refine(observer, field, body, t, next, prev);
                    if (cur)
                        open = edge;
                    else {
                        list.add(new Interval(open, edge));
                        open = -1;
                    }
                } else if (cur != null && prev == null && cur) {
                    open = next;
                } else if (cur == null && open >= 0) {
                    list.add(new Interval(open, t));
                    open = -1;
                }
                prev = cur;
                t = next;
            }
            if (open >= 0)
                list.add(new Interval(open, end));
            if (!list.isEmpty())
                out.put(body.name(), list);
        }
        return out;
    }

    /** The first time after lo at which the state differs from {@code atLo}, to a minute. */
    private static long refine(Observer observer, Field field, Bodies.Body body, long lo, long hi, boolean atLo) {
        while (hi - lo > REFINE_MILLIS) {
            long mid = lo + (hi - lo) / 2;
            Boolean m = in(observer, field, body, mid);
            if (m == null || m == atLo)
                lo = mid;
            else
                hi = mid;
        }
        return hi;
    }

    /** Whether the body's centre is in the field at {@code milli}; null where the ephemeris has no answer, or the observer is on the body. */
    @Nullable
    static Boolean in(Observer observer, Field field, Bodies.Body body, long milli) {
        JHVTime time = new JHVTime(milli);
        Position obs = observer.at(time);
        if (obs == null)
            return null;
        Vec3 dir = Identifier.bodyDirection(body, obs, false);
        if (dir == null)
            return null;
        Vec3 world = Bodies.world(body, time);
        Vec3 o = org.helioviewer.jhv.layers.ModelPlacement.toWorld(obs);
        if (world != null && new Vec3(world.x - o.x, world.y - o.y, world.z - o.z).length() < 50 * body.radius())
            return null;
        return field.contains(dir, obs);
    }

    /** Which bodies are in the field at some time on each UTC day the intervals touch. */
    public static Map<LocalDate, Set<String>> byDay(Map<String, List<Interval>> intervals) {
        Map<LocalDate, Set<String>> out = new TreeMap<>();
        for (Map.Entry<String, List<Interval>> e : intervals.entrySet())
            for (Interval i : e.getValue()) {
                LocalDate d = day(i.start());
                LocalDate last = day(Math.max(i.start(), i.end() - 1));
                for (; !d.isAfter(last); d = d.plusDays(1))
                    out.computeIfAbsent(d, k -> new TreeSet<>()).add(e.getKey());
            }
        return out;
    }

    static LocalDate day(long milli) {
        return Instant.ofEpochMilli(milli).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private TransitIndex() {}
}
