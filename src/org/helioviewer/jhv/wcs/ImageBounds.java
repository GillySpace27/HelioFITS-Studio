package org.helioviewer.jhv.wcs;

import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;

public final class ImageBounds {

    public static double radial(MetaData metaData) {
        return radial(metaData.getWcsHeader(), metaData.getViewpoint().distance, metaData.getPhysicalRegion());
    }

    /** Radius of the smallest Sun-centred circle that contains the FOV: the distance to a corner. */
    static double radial(WcsHeader wcsHeader, double distance, Region region) {
        double x0 = region.llx;
        double x1 = region.urx;
        double y0 = region.lly;
        double y1 = region.ury;
        double xm = 0.5 * (x0 + x1);
        double ym = 0.5 * (y0 + y1);
        if (!wcsHeader.projection.isSurfaceMap()) {
            double radius = radialBound(wcsHeader, distance, x0, y0);
            radius = Math.max(radius, radialBound(wcsHeader, distance, x1, y0));
            radius = Math.max(radius, radialBound(wcsHeader, distance, x0, y1));
            radius = Math.max(radius, radialBound(wcsHeader, distance, x1, y1));
            radius = Math.max(radius, radialBound(wcsHeader, distance, xm, y0));
            radius = Math.max(radius, radialBound(wcsHeader, distance, xm, y1));
            radius = Math.max(radius, radialBound(wcsHeader, distance, x0, ym));
            radius = Math.max(radius, radialBound(wcsHeader, distance, x1, ym));
            if (radius > 0)
                return radius;
        }

        Vec2 sun = sunCenter(wcsHeader);
        double dx0 = x0 - sun.x;
        double dx1 = x1 - sun.x;
        double dy0 = y0 - sun.y;
        double dy1 = y1 - sun.y;
        return Math.max(
                Math.max(Math.hypot(dx0, dy0), Math.hypot(dx1, dy0)),
                Math.max(Math.hypot(dx0, dy1), Math.hypot(dx1, dy1)));
    }

    public static double inscribed(MetaData metaData) {
        return inscribed(metaData.getWcsHeader(), metaData.getViewpoint().distance, metaData.getPhysicalRegion());
    }

    /**
     * Radius of the largest Sun-centred circle that fits INSIDE the FOV: the distance to the
     * nearest edge, in the same projected units {@link #radial} reports.
     *
     * <p>The two answers are not a factor of √2 apart, which is the intuition a square frame
     * invites. They are a factor of tan(a√2)/tan(a) apart, because the corner is further out in
     * ANGLE and the projection to the plane of sky is a tangent. A PUNCH mosaic spans ±46° of
     * elongation: its edge lands at D·tan46° ≈ 227 R☉ and its corner at D·tan65° ≈ 474 R☉. Out
     * past the edge radius the frame holds data only in four shrinking corners, so framing a
     * view on the corner figure spends most of the page on emptiness.
     *
     * <p>Sampled at the edge midpoints, which is exact while the Sun sits at the centre of the
     * frame and a fair approximation when it does not. Whether the Sun is in the frame at all
     * has to be asked separately: every edge midpoint projects to a perfectly good radius
     * whether or not the circle through it encloses anything, so the sampling cannot tell. 0
     * when it does not; callers that must still show such a layer fall back to {@link #radial}.
     */
    static double inscribed(WcsHeader wcsHeader, double distance, Region region) {
        double x0 = region.llx;
        double x1 = region.urx;
        double y0 = region.lly;
        double y1 = region.ury;
        double xm = 0.5 * (x0 + x1);
        double ym = 0.5 * (y0 + y1);
        Vec2 center = sunCenter(wcsHeader);
        if (!(center.x > x0 && center.x < x1 && center.y > y0 && center.y < y1))
            return 0;
        if (!wcsHeader.projection.isSurfaceMap()) {
            double radius = Math.min(
                    Math.min(radialBound(wcsHeader, distance, xm, y0), radialBound(wcsHeader, distance, xm, y1)),
                    Math.min(radialBound(wcsHeader, distance, x0, ym), radialBound(wcsHeader, distance, x1, ym)));
            if (radius > 0)
                return radius;
        }

        return Math.max(0, Math.min(
                Math.min(x1 - center.x, center.x - x0),
                Math.min(y1 - center.y, center.y - y0)));
    }

    private static double radialBound(WcsHeader wcsHeader, double distance, double x, double y) {
        Vec2 helioprojective = WcsProjection.planeToHelioprojective(wcsHeader, x, y);
        Vec2 hpcPlane = WcsProjection.helioprojectiveToHpcPlane(distance, helioprojective.x, helioprojective.y);
        if (hpcPlane != null && Double.isFinite(hpcPlane.x) && Double.isFinite(hpcPlane.y))
            return Math.hypot(hpcPlane.x, hpcPlane.y);
        return 0;
    }

    private static Vec2 sunCenter(WcsHeader wcsHeader) {
        if (!wcsHeader.projection.isSurfaceMap()) {
            Vec2 sun = WcsProjection.helioprojectiveToPlane(wcsHeader, 0, 0);
            if (sun != null && Double.isFinite(sun.x) && Double.isFinite(sun.y))
                return sun;
        }
        return wcsHeader.crval;
    }

    public static Region hpc(MetaData metaData) {
        Region region = metaData.getPhysicalRegion();
        WcsHeader wcsHeader = metaData.getWcsHeader();
        double x0 = region.llx;
        double x1 = region.urx;
        double y0 = region.lly;
        double y1 = region.ury;
        double xm = 0.5 * (x0 + x1);
        double ym = 0.5 * (y0 + y1);
        double[] bounds = {
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        updateHpcBounds(bounds, wcsHeader, x0, y0);
        updateHpcBounds(bounds, wcsHeader, x1, y0);
        updateHpcBounds(bounds, wcsHeader, x0, y1);
        updateHpcBounds(bounds, wcsHeader, x1, y1);
        updateHpcBounds(bounds, wcsHeader, xm, y0);
        updateHpcBounds(bounds, wcsHeader, xm, y1);
        updateHpcBounds(bounds, wcsHeader, x0, ym);
        updateHpcBounds(bounds, wcsHeader, x1, ym);
        return regionFromBounds(bounds);
    }

    private static void updateHpcBounds(double[] bounds, WcsHeader wcsHeader, double x, double y) {
        Vec2 helioprojective = WcsProjection.planeToHelioprojective(wcsHeader, x, y);
        double hpcX = Math.toDegrees(helioprojective.x);
        double hpcY = Math.toDegrees(helioprojective.y);
        bounds[0] = Math.min(bounds[0], hpcX);
        bounds[1] = Math.max(bounds[1], hpcX);
        bounds[2] = Math.min(bounds[2], hpcY);
        bounds[3] = Math.max(bounds[3], hpcY);
    }

    private static Region regionFromBounds(double[] bounds) {
        return new Region(bounds[0], bounds[2],
                Math.max(Math.nextUp(0.0), bounds[1] - bounds[0]),
                Math.max(Math.nextUp(0.0), bounds[3] - bounds[2]));
    }

    private ImageBounds() {}
}
