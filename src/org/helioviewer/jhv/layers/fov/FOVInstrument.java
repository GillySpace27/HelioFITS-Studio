package org.helioviewer.jhv.layers.fov;

import javax.annotation.Nullable;

import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.FOVShape;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

class FOVInstrument {

    /**
     * RECTANGULAR and CIRCULAR: sizes are full angles in degrees about the Sun line (inner, the
     * occulter, also a full angle). ELONGATION: inner and outer are the range of elongation
     * (angle from Sun centre), in degrees, or in solar radii in the plane of the sky when
     * {@code solarRadii}, converted at the spacecraft's distance; drawn about the Sun line or to
     * one side ({@link Side}).
     */
    enum FOVType {RECTANGULAR, CIRCULAR, ELONGATION}

    /**
     * Where an off-Sun field points, after HelioFleet's instruments.js (S. Dash, MIT): EAST, the
     * solar-east side seen from the spacecraft; EARTH, the side of the Sun where Earth is (STEREO
     * HI); RAM, ahead along the direction of travel (WISPR). CENTRED is about the Sun line.
     */
    enum Side {CENTRED, EAST, EARTH, RAM}

    private static final double TEXT_SCALE = 0.075;

    private final String name;
    private final FOVType type;
    private final double inner;
    private final double wide;
    private final double high;

    // As given, for the cones.
    private final double innerDeg;
    private final double wideDeg;
    private final double highDeg;
    private final boolean solarRadii;
    private final Side side;
    @Nullable
    private final JHVTime from;

    private boolean enabled;

    private double centerX = 0;
    private double centerY = 0;

    FOVInstrument(String _name, FOVType _type, double _innerDeg, double _wideDeg, double _highDeg, JSONObject jo) {
        this(_name, _type, _innerDeg, _wideDeg, _highDeg, false, Side.CENTRED, null, jo);
    }

    private FOVInstrument(String _name, FOVType _type, double _innerDeg, double _wideDeg, double _highDeg,
                          boolean _solarRadii, Side _side, @Nullable JHVTime _from, JSONObject jo) {
        name = _name;
        type = _type;
        innerDeg = _innerDeg;
        wideDeg = _wideDeg;
        highDeg = _highDeg;
        solarRadii = _solarRadii;
        side = _side;
        from = _from;
        inner = 0.5 * Math.tan(innerDeg * (Math.PI / 180.));
        wide = 0.5 * Math.tan(wideDeg * (Math.PI / 180.));
        high = 0.5 * Math.tan(highDeg * (Math.PI / 180.));
        enabled = jo.optBoolean(name, false);
    }

    /** A range of elongation, in degrees. {@code fromDate} (yyyy-mm-dd) is when it started observing, or null. */
    static FOVInstrument elongation(String name, double innerDeg, double outerDeg, Side side, @Nullable String fromDate, JSONObject jo) {
        return new FOVInstrument(name, FOVType.ELONGATION, innerDeg, outerDeg, outerDeg, false, side, date(fromDate), jo);
    }

    /** A coronagraph's range in solar radii in the plane of the sky, about the Sun line. */
    static FOVInstrument solarRadii(String name, double innerRs, double outerRs, @Nullable String fromDate, JSONObject jo) {
        return new FOVInstrument(name, FOVType.ELONGATION, innerRs, outerRs, outerRs, true, Side.CENTRED, date(fromDate), jo);
    }

    @Nullable
    private static JHVTime date(@Nullable String d) {
        return d == null ? null : new JHVTime(d + "T00:00:00");
    }

    /** False before the instrument started observing. */
    boolean flyingAt(JHVTime time) {
        return from == null || time.milli >= from.milli;
    }

    Side side() {
        return side;
    }

    /** The elongation range in degrees at a spacecraft {@code distance} solar radii from the Sun. */
    double[] elongationDeg(double distance) {
        if (!solarRadii)
            return new double[]{innerDeg, wideDeg};
        return new double[]{Math.toDegrees(Math.atan2(innerDeg, distance)), Math.toDegrees(Math.atan2(wideDeg, distance))};
    }

    /**
     * The field as rays from the spacecraft (FOVCone). {@code sideVector} is the direction its
     * {@link Side} names, in world coordinates, or null for a centred field.
     */
    @Nullable
    FOVCone cone(double[] observer, double[] north, @Nullable double[] sideVector) {
        double distance = FOVCone.len(observer);
        return switch (type) {
            case RECTANGULAR -> FOVCone.rectangle(observer, north, wideDeg / 2, highDeg / 2);
            case CIRCULAR -> FOVCone.annulus(observer, north, innerDeg / 2, wideDeg / 2);
            case ELONGATION -> {
                double[] e = elongationDeg(distance);
                if (side == Side.CENTRED)
                    yield FOVCone.annulus(observer, north, e[0], e[1]);
                yield sideVector == null ? null : FOVCone.toSide(observer, sideVector, e[0], e[1]);
            }
        };
    }

    void setCenterX(double _centerX) {
        centerX = _centerX;
    }

    void setCenterY(double _centerY) {
        centerY = _centerY;
    }

    void putGeometry(double distance, double lineWidth, byte[] color, SdfTextRenderer renderer, BufVertex lineBuf, BufVertex centerBuf) {
        if (!enabled)
            return;
        if (type == FOVType.ELONGATION) { // outlines in the plane of the sky; an off-Sun field has none there worth drawing
            if (side != Side.CENTRED)
                return;
            double[] e = elongationDeg(distance);
            double fovCenterX = centerX * distance, fovCenterY = centerY * distance;
            if (e[0] > 0)
                FOVShape.putCircLine(fovCenterX, fovCenterY, Math.tan(Math.toRadians(e[0])) * distance, false, lineWidth, color, lineBuf);
            FOVShape.putCircLine(fovCenterX, fovCenterY, Math.tan(Math.toRadians(e[1])) * distance, false, lineWidth, color, lineBuf);
            return;
        }

        double fovCenterX = centerX * distance;
        double fovCenterY = centerY * distance;
        FOVShape.putCenter(fovCenterX, fovCenterY, false, lineWidth, color, centerBuf);

        if (inner > 0)
            FOVShape.putCircLine(fovCenterX, fovCenterY, inner * distance, false, lineWidth, color, lineBuf);
        if (type == FOVType.RECTANGULAR) {
            FOVShape.putRectLine(fovCenterX, fovCenterY, wide * distance, high * distance, false, lineWidth, color, lineBuf);
            float x = (float) ((centerX - wide) * distance);
            float y = (float) ((centerY - high) * distance);
            double labelSize = high * distance;
            float scaleFactor = (float) (TEXT_SCALE / renderer.getFontSize() * labelSize);
            renderer.draw(name, x, y, 0, scaleFactor); // using SurfacePut
        } else {
            FOVShape.putCircLine(fovCenterX, fovCenterY, wide * distance, false, lineWidth, color, lineBuf);
            double halfSide = wide / Math.sqrt(2);
            float x = (float) ((centerX - halfSide) * distance);
            float y = (float) ((centerY - halfSide) * distance);
            double labelSize = halfSide * distance;
            float scaleFactor = (float) (TEXT_SCALE / renderer.getFontSize() * labelSize);
            renderer.draw(name, x, y, 0, scaleFactor); // using SurfacePut
        }
    }

    /**
     * Whether a point of the observer's plane of sky, in units of the observer's distance (the
     * tangent of the angle from the platform's boresight), lies inside the outline putGeometry draws:
     * outside the inner circle, within the rectangle's half sizes or the circle's radius.
     */
    boolean contains(double u, double v) {
        if (!enabled)
            return false;
        double du = u - centerX, dv = v - centerY;
        if (inner > 0 && Math.hypot(du, dv) < inner)
            return false;
        return type == FOVType.RECTANGULAR ? Math.abs(du) <= wide && Math.abs(dv) <= high : Math.hypot(du, dv) <= wide;
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean _enabled) {
        enabled = _enabled;
    }

    @Override
    public String toString() {
        return name;
    }

}
