package org.helioviewer.jhv.layers;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.math.Vec3;

import org.json.JSONObject;

/**
 * Where a model layer is drawn and how big: the settings and the arithmetic, with no GL and no
 * Swing, so a headless check can pin them (ModelPlacementCheck).
 *
 * <p>Two placements. ORIGIN is what the layer always did: the file's coordinates are world
 * coordinates, so an ordinary glTF sits on Sun centre at whatever size its numbers give. EPHEMERIS
 * puts the model's bounding-box centre at a tracked object's position and sizes it from physical
 * units: glTF declares metres, so one file unit is one metre unless the user says otherwise.
 *
 * <p>True size is invisible at heliospheric distances (a few metres is about 4e-9 solar radii), so
 * two things enlarge it, and the layer reports the factor actually drawn so an enlarged spacecraft
 * is never mistaken for a true-size one: an exaggeration factor the user sets, and a floor on the
 * on-screen size in pixels.
 */
public final class ModelPlacement {

    public enum Mode {ORIGIN, EPHEMERIS}

    public static final String DEFAULT_TARGET = "Earth";
    public static final double DEFAULT_MIN_PIXELS = 24;
    /** The exaggeration slider runs over this many decades, 1x to 1e10x. */
    public static final int MAX_EXAGGERATION_DECADES = 10;

    private Mode mode = Mode.ORIGIN;
    private String target = DEFAULT_TARGET;
    private double metersPerUnit = 1;
    private double exaggeration = 1;
    private double minPixels = DEFAULT_MIN_PIXELS;

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode _mode) {
        mode = _mode == null ? Mode.ORIGIN : _mode;
    }

    public String target() {
        return target;
    }

    /** Only names SpaceObject or {@link #externalTargets()} know are kept; anything else falls back to Earth. */
    public void setTarget(String _target) {
        target = isTarget(_target) ? _target : DEFAULT_TARGET;
    }

    /** Whether a model can be placed at this name: a SpaceObject other than the Sun, or a spacecraft from HelioFleet or JPL Horizons. */
    public static boolean isTarget(@Nullable String name) {
        return name != null && !"Sun".equals(name) && (SpaceObject.get(name) != null || ExternalEphemeris.get(name) != null);
    }

    /** Spacecraft placed from HelioFleet or JPL Horizons rather than Studio's kernels or the ROB service. */
    public static java.util.Set<String> externalTargets() {
        return ExternalEphemeris.names();
    }

    @Nullable
    public SpaceObject targetObject() {
        return SpaceObject.get(target);
    }

    public double metersPerUnit() {
        return metersPerUnit;
    }

    public void setMetersPerUnit(double v) {
        metersPerUnit = Double.isFinite(v) && v > 0 ? v : 1;
    }

    public double exaggeration() {
        return exaggeration;
    }

    public void setExaggeration(double v) {
        exaggeration = Double.isFinite(v) ? Math.clamp(v, 1, Math.pow(10, MAX_EXAGGERATION_DECADES)) : 1;
    }

    public double minPixels() {
        return minPixels;
    }

    /** Zero switches the floor off and draws exactly true size times the exaggeration. */
    public void setMinPixels(double v) {
        minPixels = Double.isFinite(v) ? Math.clamp(v, 0, 2000) : DEFAULT_MIN_PIXELS;
    }

    /** World units (solar radii) per file unit at true size. */
    public double trueScale() {
        return metersPerUnit / Sun.RadiusMeter;
    }

    /**
     * World units per file unit as drawn: true size times the exaggeration, raised if needed so the
     * model's largest dimension spans at least {@code minPixels} on screen. Under the orthographic
     * camera the on-screen size of a length L is {@code L * viewportHeight / cameraWidth}, whatever
     * its distance, which is why the floor needs no depth.
     *
     * @param extent the model's largest bounding-box dimension, in file units
     */
    public double drawnScale(double extent, double cameraWidth, double viewportHeight) {
        double scale = trueScale() * exaggeration;
        if (minPixels > 0 && extent > 0 && cameraWidth > 0 && viewportHeight > 0)
            scale = Math.max(scale, minPixels * cameraWidth / (viewportHeight * extent));
        return scale;
    }

    /**
     * A heliographic position (Carrington, Position's conventions) as a point in the world frame the
     * Sun and the images are drawn in. This is the same rotation the heliocentric glTF interface uses
     * for an observer at CRLN_OBS, CRLT_OBS (HeliocentricCartesianMetaData.observerRotation), so a
     * model placed here and a heliocentric product agree about where that direction is.
     */
    public static Vec3 toWorld(Position p) {
        Vec3 dir = p.toQuat().rotateInverseVector(Vec3.ZAxis);
        return new Vec3(dir.x * p.distance, dir.y * p.distance, dir.z * p.distance);
    }

    /**
     * A Sun-centred rectangular vector in the rotating Carrington frame (SPICE SOLO_IAU_SUN_2009,
     * solar radii) as a Position, with the sign conventions of Spice.getCarrington.
     */
    public static Position carringtonPosition(org.helioviewer.jhv.time.JHVTime time, double x, double y, double z) {
        double r = Math.sqrt(x * x + y * y + z * z);
        if (r == 0)
            return new Position(time, 0, 0, 0);
        double lon = Math.atan2(y, x);
        if (lon < 0)
            lon += 2 * Math.PI;
        return new Position(time, r, -lon, Math.asin(Math.clamp(z / r, -1., 1.)));
    }

    public void serialize(JSONObject jo) {
        JSONObject p = new JSONObject();
        p.put("mode", mode.name());
        p.put("target", target);
        p.put("metersPerUnit", metersPerUnit);
        p.put("exaggeration", exaggeration);
        p.put("minPixels", minPixels);
        jo.put("placement", p);
    }

    /** Absent or damaged fields keep their defaults: a session from before placement opens as it did. */
    public void deserialize(JSONObject jo) {
        JSONObject p = jo.optJSONObject("placement");
        if (p == null)
            return;
        try {
            setMode(Mode.valueOf(p.optString("mode", Mode.ORIGIN.name())));
        } catch (IllegalArgumentException e) {
            setMode(Mode.ORIGIN);
        }
        setTarget(p.optString("target", DEFAULT_TARGET));
        setMetersPerUnit(p.optDouble("metersPerUnit", 1));
        setExaggeration(p.optDouble("exaggeration", 1));
        setMinPixels(p.optDouble("minPixels", DEFAULT_MIN_PIXELS));
    }

}
