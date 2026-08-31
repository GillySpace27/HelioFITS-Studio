package org.helioviewer.jhv.display;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.wcs.WcsProjection;

/**
 * The observer's sky, laid flat about a direction you can steer: the geometry behind
 * {@link MapMode#ObserverSky}.
 *
 * <p>Nothing here is a new coordinate system. Helioprojective longitude and latitude already ARE
 * the observer's sky: they are angles measured at the observer, so a coronagraph frame is a picture
 * of a patch of that sky. What every other projection in this viewer does is centre that patch on
 * the Sun. This one lets the centre move, which is the whole of "look around" -- the imagery does
 * not have to be re-derived, only re-aimed.
 *
 * <p>That is why this is a map rather than a camera. A perspective camera placed at Earth would
 * mean a frustum, a near plane, and an unprojection that is no longer linear, and every screen-to-
 * world inverse in the viewer assumes otherwise. A zenithal map needs none of it, keeps the
 * orthographic pipeline exactly as it is, and is the more faithful instrument anyway: what a
 * coronagraph measures is a direction on the sky and a brightness along it, not a position in a
 * scene, and an angular projection is the honest way to draw that.
 *
 * <p><b>Convention.</b> Everything below works in the observer's frame, where the observer sits at
 * (0, 0, D) and the Sun at the origin, so the direction toward the Sun is -z. This matches
 * solarCommon.frag's {@code helioprojectiveToObserverRay} and solarSky.frag, which carry the same
 * geometry for the fragment stage; the two have to agree or the grid drifts off the picture.
 *
 * <p>Page coordinates are the WCS native radial coordinate in DEGREES, following Calabretta and
 * Greisen (2002). For the azimuthal equidistant default that makes the map units literally degrees
 * of arc from the centre of the field, which is what the field-of-view control reads out.
 */
final class SkyMap {

    /** The reference direction: a unit ray from the observer, at helioprojective (lon, lat). */
    static Vec3 lookRay(double lon, double lat) {
        double cosLat = Math.cos(lat);
        return new Vec3(Math.sin(lon) * cosLat, Math.sin(lat), -Math.cos(lon) * cosLat);
    }

    /** Unit vector along increasing helioprojective longitude. Independent of latitude. */
    private static Vec3 east(double lon) {
        return new Vec3(Math.cos(lon), 0, Math.sin(lon));
    }

    /** Unit vector along increasing helioprojective latitude at (lon, lat). */
    private static Vec3 north(double lon, double lat) {
        double sinLat = Math.sin(lat);
        return new Vec3(-Math.sin(lon) * sinLat, Math.cos(lat), Math.cos(lon) * sinLat);
    }

    /**
     * Where a world point lands on the page, in normalized map coordinates centred on zero.
     *
     * @return null when the point is outside what this projection can draw, which the callers
     * must treat as a break in a line rather than as a coordinate
     */
    @Nullable
    static Vec2 project(Position viewpoint, MapScale scale, SkyProjection projection,
                        double lookLon, double lookLat, Vec3 world) {
        Vec3 view = viewpoint.toQuat().rotateVector(world);
        // The ray from the observer to the point, unnormalized: the observer is at +z.
        double zeta = viewpoint.distance - view.z;
        double rx = view.x, ry = view.y, rz = -zeta;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len == 0)
            return null;
        rx /= len;
        ry /= len;
        rz /= len;

        Vec3 look = lookRay(lookLon, lookLat);
        double cosRho = Math.clamp(rx * look.x + ry * look.y + rz * look.z, -1, 1);
        double rho = Math.acos(cosRho);
        if (rho > projection.maxAngle())
            return null;

        double radius = projection.radiusFromAngle(rho);
        double x, y;
        double sinRho = Math.sqrt(Math.max(0, 1 - cosRho * cosRho));
        if (sinRho < 1e-12) { // dead centre: the azimuth is undefined and the radius is zero anyway
            x = 0;
            y = 0;
        } else {
            Vec3 e = east(lookLon);
            Vec3 n = north(lookLon, lookLat);
            double scaleToRadius = radius / sinRho;
            x = (rx * e.x + ry * e.y + rz * e.z) * scaleToRadius;
            y = (rx * n.x + ry * n.y + rz * n.z) * scaleToRadius;
        }
        return new Vec2(
                scale.toUnitX(Math.toDegrees(x)) - 0.5,
                scale.toUnitY(Math.toDegrees(y)) - 0.5);
    }

    /**
     * The reverse: a point on the page back to a world point, by intersecting its ray with the
     * solar surface the way the other projected modes do.
     */
    static Vec3 unproject(Position viewpoint, SkyProjection projection,
                          double lookLon, double lookLat, Vec2 page) {
        Vec3 ray = pageToRay(projection, lookLon, lookLat, page);
        // Back to helioprojective angles, which is what the shared unprojection speaks.
        double lon = Math.atan2(ray.x, -ray.z);
        double lat = Math.asin(Math.clamp(ray.y, -1, 1));
        return WcsProjection.helioprojectiveToWorld(viewpoint, lon, lat);
    }

    /** A point on the page, in degrees of native radius, as a unit ray from the observer. */
    static Vec3 pageToRay(SkyProjection projection, double lookLon, double lookLat, Vec2 page) {
        double px = Math.toRadians(page.x);
        double py = Math.toRadians(page.y);
        double radius = Math.hypot(px, py);
        Vec3 look = lookRay(lookLon, lookLat);
        if (radius < 1e-12)
            return look;

        double rho = projection.angleFromRadius(radius);
        double cosRho = Math.cos(rho), sinRho = Math.sin(rho);
        Vec3 e = east(lookLon);
        Vec3 n = north(lookLon, lookLat);
        double ux = px / radius, uy = py / radius;
        return new Vec3(
                cosRho * look.x + sinRho * (ux * e.x + uy * n.x),
                cosRho * look.y + sinRho * (ux * e.y + uy * n.y),
                cosRho * look.z + sinRho * (ux * e.z + uy * n.z));
    }

    /**
     * Turn the head: move the aim by an angle east and an angle north, both in radians.
     *
     * <p>A tripod, not a free gimbal. North is a step along the meridian, which is exact: the local
     * north direction IS the meridian, so the step adds to the latitude and leaves the longitude
     * alone. East is a turn about the sky's own pole, so it adds to the longitude at any latitude.
     *
     * <p>Chosen over a great-circle step in the local east direction, which was the first thing
     * tried and is subtly wrong for a control. A great circle aimed east does not stay east: it
     * drifts in latitude as it goes, so dragging out and back along one leaves the aim somewhere
     * else. That is holonomy on a sphere, not a bug, but a look-around that does not return to
     * where it started when you undo the gesture is unusable. This version is exactly reversible
     * everywhere, has no singularity at the pole, and keeps the page's up aligned with north so no
     * roll ever accumulates.
     *
     * <p>The price, stated plainly: a horizontal drag moves the sky by cos(latitude) times the
     * pointer, so it lags when aimed far north or south of the Sun. That is what any tripod does,
     * and the alternative correction, dividing by cos(latitude), diverges exactly where the
     * all-sky projection is meant to be able to look.
     */
    static double[] steer(double lookLon, double lookLat, double stepEast, double stepNorth) {
        return new double[]{lookLon + stepEast, Math.clamp(lookLat + stepNorth, -Math.PI / 2, Math.PI / 2)};
    }

    private SkyMap() {}
}
