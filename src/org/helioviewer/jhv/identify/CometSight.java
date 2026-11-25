package org.helioviewer.jhv.identify;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.DisplaySettings;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * Where an image layer's own spacecraft saw a comet, at that image's own time (comet3d plan,
 * revision 2, sections 2.2 to 2.5). No GL, no Swing and no state: each answer is a function of the
 * comet's path, the layer's observer and metadata, and the time mode, so a check drives it directly.
 *
 * <ul>
 * <li>Time: the layer observer's time is DATE-OBS shifted by the time mode
 * ({@code FitsMetaData.adjustTime}); {@link #observationTime} undoes that, and both the light time
 * and the frame rotation use the recovered DATE-OBS, since the header longitude was taken relative
 * to Earth at DATE-OBS.
 * <li>Observer: a SOHO layer is placed by SOHO's position from JPL Horizons, whatever its header
 * says; every other layer by its own observer ({@link ObserverSource}).
 * <li>Light time: the comet where the light left it, iterated to a millisecond, at most
 * {@link #MAX_PASSES} passes, all in the frame at the observation time.
 * <li>Direction: in the layer observer's frame (observer at +z, Sun at -z, solar north up), the
 * frame the image is drawn in, as {@link Identifier#bodyDirection}.
 * <li>Visibility: {@link Identifier#qualify}, the bodies' rules.
 * </ul>
 */
public final class CometSight {

    /** Where a layer's observer position comes from, said in the layer's status. */
    public enum ObserverSource {
        HORIZONS("observer: SOHO from JPL Horizons"),
        SDO_AT_EARTH("observer: Earth (Studio places SDO there)"),
        HEADER_AT_EARTH("observer: at Earth (header has no position keys?)"),
        HEADER("observer: from the file header");

        public final String text;

        ObserverSource(String _text) {
            text = _text;
        }
    }

    /**
     * A sight line: unit {@code dir} in the layer observer's frame, the range in solar radii, the
     * time the light left the comet, and the observation time (the frame).
     */
    public record Sight(Vec3 dir, double rangeRsun, long emitted, JHVTime frame) {
        /** (Tx, Ty) in radians. */
        public Vec2 helioprojective() {
            return Identifier.helioprojective(dir);
        }

        /** Where the sight line crosses the plane of sky through Sun centre, solar radii, for an observer at {@code distance}. */
        public Vec2 planeRsun(double distance) {
            return Identifier.hpcPlane(dir, distance);
        }
    }

    /** One layer's answer: whose position, the sight (null where the path has none), and whether the image shows it. */
    public record Look(Identifier.Target target, ObserverSource source, @Nullable Sight sight, Identifier.Qualifier qualifier) {
        public boolean drawn() {
            return sight != null && qualifier == Identifier.Qualifier.NONE;
        }
    }

    static final int MAX_PASSES = 4;
    /** {@link #emitted} when the path has no position on the way. */
    public static final long NO_TIME = Long.MIN_VALUE;

    /**
     * DATE-OBS from a layer observer's time: the inverse of {@code FitsMetaData.adjustTime}. In Earth
     * mode Earth's distance is taken at the shifted time, which moves the answer by microseconds.
     */
    public static JHVTime observationTime(Position observer, DisplaySettings.TimeMode mode) {
        long t = observer.time.milli;
        return switch (mode) {
            case Observer -> observer.time;
            case Sun -> new JHVTime(Math.round(t + observer.distance * Sun.RadiusMilli));
            case Earth -> new JHVTime(Math.round(t + (observer.distance - Sun.getEarth(observer.time).distance) * Sun.RadiusMilli));
        };
    }

    /**
     * When the light reaching {@code observerWorld} at {@code frame} left the comet: tau(k+1) = t -
     * |comet(tau(k)) - O| / c, from tau(0) = t, until it moves by less than a millisecond.
     * {@link #NO_TIME} when the path has no position on the way.
     */
    public static long emitted(CometEphemeris.WorldPath comet, JHVTime frame, Vec3 observerWorld) {
        long tau = frame.milli;
        for (int k = 0; k < MAX_PASSES; k++) {
            Vec3 w = comet.world(tau, frame);
            if (w == null)
                return NO_TIME;
            long next = frame.milli - Math.round(distance(w, observerWorld) * Sun.RadiusMilli);
            boolean settled = next == tau;
            tau = next;
            if (settled)
                break;
        }
        return tau;
    }

    /**
     * The comet as {@code observer}'s layer saw it. {@code observerPath}, when given, places the
     * observer (SOHO from Horizons) at the observation time; otherwise the layer's own observer
     * does. Null where the paths have no position.
     */
    @Nullable
    public static Sight sight(CometEphemeris.WorldPath comet, Position observer, @Nullable CometEphemeris.WorldPath observerPath,
                              boolean lightTime, DisplaySettings.TimeMode mode) {
        JHVTime t = observationTime(observer, mode);
        Vec3 o = observerPath == null ? ModelPlacement.toWorld(observer) : observerPath.world(t.milli, t);
        if (o == null)
            return null;
        long tau = lightTime ? emitted(comet, t, o) : t.milli;
        if (tau == NO_TIME)
            return null;
        Vec3 w = comet.world(tau, t);
        if (w == null)
            return null;
        Vec3 rel = new Vec3(w.x - o.x, w.y - o.y, w.z - o.z);
        double range = rel.length();
        if (range == 0)
            return null;
        return new Sight(Identifier.unit(observer.toQuat().rotateVector(rel)), range, tau, t);
    }

    /** Whose position places a layer's observer: SOHO from Horizons, SDO at Earth, else the header (flagged when it is exactly Earth). */
    public static ObserverSource observerSource(String observatory, Position observer, JHVTime dateObs) {
        if ("SOHO".equals(observatory))
            return ObserverSource.HORIZONS;
        if ("SDO".equals(observatory))
            return ObserverSource.SDO_AT_EARTH;
        Position earth = Sun.getEarth(dateObs);
        if (observer.distance == earth.distance && observer.lon == earth.lon && observer.lat == earth.lat)
            return ObserverSource.HEADER_AT_EARTH;
        return ObserverSource.HEADER;
    }

    /**
     * One layer: its observer source, the sight and whether the image shows it. A SOHO layer with no
     * SOHO path yet has no sight.
     */
    public static Look look(CometEphemeris.WorldPath comet, Identifier.Target target, String observatory, @Nullable CometEphemeris.WorldPath soho,
                            boolean lightTime, DisplaySettings.TimeMode mode) {
        JHVTime dateObs = observationTime(target.observer(), mode);
        ObserverSource source = observerSource(observatory, target.observer(), dateObs);
        Sight s = source == ObserverSource.HORIZONS && soho == null ? null
                : sight(comet, target.observer(), source == ObserverSource.HORIZONS ? soho : null, lightTime, mode);
        Identifier.Qualifier q = s == null ? Identifier.Qualifier.OUTSIDE_FIELD : Identifier.qualify(target, s.dir(), s.rangeRsun(), false);
        return new Look(target, source, s, q);
    }

    private static double distance(Vec3 a, Vec3 b) {
        return new Vec3(a.x - b.x, a.y - b.y, a.z - b.z).length();
    }

    private CometSight() {}
}
