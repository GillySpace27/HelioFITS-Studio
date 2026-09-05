package org.helioviewer.jhv.display;

import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Vec3;

/**
 * Where a white-light coronagraph line of sight is taken to have originated.
 *
 * <p>This is a <em>placement model</em>, not a measurement. Coronagraph brightness is a
 * line-of-sight integral of Thomson-scattered light; the scattering efficiency peaks where the
 * scattering angle is 90 degrees, but only broadly, so a wide range of depth along the line of
 * sight contributes comparably, and that plateau widens with elongation. Brightness also scales
 * with electron density, so a dense structure off the surface can outweigh a diffuse one on it.
 * Anything derived from a position placed here is model-dependent and must not be reported as a
 * measured depth.
 *
 * <p>Geometry, in the helioprojective viewpoint frame this codebase uses throughout: the Sun is at
 * the origin, the observer sits at {@code (0, 0, D)}, and elongation {@code e} is the angle at the
 * observer between the Sun and the line of sight (compare
 * {@code worldToHelioprojective} in {@code solarCommon.frag}, which uses the same frame).
 *
 * <ul>
 * <li><b>Plane of sky</b>: the plane through the Sun normal to the Sun-observer line, so
 *     {@code r = D tan e} and {@code z = 0}. This is what the code assumed everywhere before this
 *     class existed, hard-coded as {@code vec3(hpcXY, 0.)}.
 * <li><b>Thomson sphere</b>: the locus of 90-degree scattering, which by Thales is the sphere
 *     having the Sun-observer line as its diameter. There {@code r = D sin e} and
 *     {@code z = r^2 / D}.
 * <li><b>Celestial sphere</b>: the sphere of radius D centred on the observer, which is the
 *     observer's sky drawn at the Sun's distance. It passes through the Sun, so it is also the
 *     sphere of diameter 2D through the Sun tangent to the plane of sky there, which is why it
 *     shares the Thomson depth law with D replaced by 2D: {@code r = 2D sin(e/2)} and
 *     {@code z = r^2 / (2D)}. It is not a scattering model at all. It is where a planetarium
 *     puts everything, and it is what the Thomson-sphere brightness looks like projected back out
 *     along each line of sight onto the sky, which is the comparison it exists to make.
 * </ul>
 *
 * <p>All three are members of one family, the spheres of diameter L through the Sun tangent to the
 * plane of sky, with {@code z = r^2 / L}: L = D is Thomson, L = 2D is celestial, and L infinite is
 * the plane. The shader takes the family parameter {@code k = D / L} ({@link #depthFactor}),
 * which is what lets one uniform morph between any two of them along each line of sight.
 *
 * <p>The two agree to first order and diverge fast: at 10 degrees elongation plane-of-sky places
 * material 1.5% further out, at 30 degrees 15%, at 45 degrees 41%. That is the reason this is
 * worth having for a wide-field instrument.
 *
 * <p>Kept in step with the GLSL twin in {@code resources/glsl/solarCommon.frag}
 * ({@code surfaceHeliocentricRadius} and friends), the same way {@link MapScale} is twinned with
 * {@code unwarpRadius}. {@code extra/test/SurfaceModelCheck.java} guards the Java side.
 */
public enum SurfaceModel {

    PlaneOfSky("Plane of sky"),
    ThomsonSphere("Thomson sphere"),
    CelestialSphere("Celestial sphere");

    /**
     * Both models degenerate at 90 degrees: plane-of-sky sends {@code tan e} to infinity, and the
     * Thomson surface folds back onto the observer itself ({@code z -> D}, and the distance from
     * the observer goes to zero). Clamp rather than divide. Well beyond any real coronagraph
     * field, so this bounds arithmetic without bounding the instrument.
     */
    public static final double MAX_ELONGATION = Math.toRadians(89);

    private final String label;

    SurfaceModel(String _label) {
        label = _label;
    }

    /**
     * Whether this model can describe a field reaching {@code outerRadius}, seen from
     * {@code observerDistance}.
     *
     * <p>The Thomson sphere has diameter D and its mapping is {@code r = D sin(e)}, which
     * saturates at {@code r = D}: a point further from the Sun than the observer is not on the
     * surface at any elongation, so the model has nothing to say about it. That is not a rounding
     * problem, it is the domain running out.
     *
     * <p>It matters because the failure is silent and looks like data. {@link #depth} clamps its
     * radius, so past D the depth pins at D while the in-plane radius keeps growing, and the
     * surface extrudes into a flat sheet at constant depth. Rendered, that sheet is
     * indistinguishable from corona correctly placed on a plane, and it is neither. Observed with
     * a 245 solar-radii field seen from 66: three quarters of the picture was that sheet.
     *
     * <p>Plane of sky has no such limit; it is defined at every elongation short of 90 degrees.
     *
     * <p>This does not gate the mode. Refusing it outright was tried and made the Thomson sphere
     * unselectable in exactly the wide-field, near-Sun views it exists for, so the renderer clips
     * the undescribable part away instead and this reports whether anything is being lost.
     */
    public boolean canDescribe(double observerDistance, double outerRadius) {
        return switch (this) {
            case PlaneOfSky -> true;
            case ThomsonSphere -> observerDistance > 0 && outerRadius <= observerDistance;
            case CelestialSphere -> observerDistance > 0 && outerRadius <= 2 * observerDistance; // its far point is the anti-Sun, r = 2D
        };
    }

    /**
     * The family parameter the shader morphs on: {@code k = D / L} for the sphere of diameter L
     * through the Sun, so depth is {@code k r^2 / D}. Plane of sky 0, Thomson 1, celestial 1/2.
     */
    public double depthFactor() {
        return switch (this) {
            case PlaneOfSky -> 0;
            case ThomsonSphere -> 1;
            case CelestialSphere -> 0.5;
        };
    }

    /** The far extent of the surface: the sphere's diameter, or unbounded for the plane. */
    public double reach(double observerDistance) {
        return this == PlaneOfSky ? Double.POSITIVE_INFINITY : observerDistance / depthFactor();
    }

    /** Heliocentric distance, in the same units as {@code observerDistance}, of the surface point. */
    public double heliocentricRadius(double elongation, double observerDistance) {
        double e = Math.clamp(elongation, 0, MAX_ELONGATION);
        return observerDistance * switch (this) {
            case PlaneOfSky -> Math.tan(e);
            case ThomsonSphere -> Math.sin(e);
            case CelestialSphere -> 2 * Math.sin(e / 2); // chord of the observer-centred sphere
        };
    }

    /**
     * Displacement toward the observer, along the Sun-observer axis, of the surface point at a
     * given heliocentric radius. Written in terms of the radius rather than the elongation
     * because that is what the caller already has once the warp has been inverted.
     */
    public double depth(double heliocentricRadius, double observerDistance) {
        if (this == PlaneOfSky || observerDistance <= 0)
            return 0;
        // z = r^2 / L on the sphere of diameter L through the Sun, from |p|^2 = L * p.z: L = D for
        // Thomson, 2D for celestial. Pinned at the sphere's far point, which is where it ends.
        double reach = reach(observerDistance);
        double r = Math.min(heliocentricRadius, reach);
        return r * r / reach;
    }

    /** The elongation whose surface point sits at this heliocentric radius. Inverse of {@link #heliocentricRadius}. */
    public double elongation(double heliocentricRadius, double observerDistance) {
        if (observerDistance <= 0)
            return 0;
        double ratio = heliocentricRadius / observerDistance;
        return switch (this) {
            case PlaneOfSky -> Math.atan(ratio);
            case ThomsonSphere -> Math.asin(Math.clamp(ratio, -1, 1));
            case CelestialSphere -> 2 * Math.asin(Math.clamp(ratio / 2, -1, 1));
        };
    }

    /**
     * The 3D surface point for a line of sight, in the helioprojective viewpoint frame.
     *
     * @param positionAngle radians, 0 at north increasing anti-clockwise, matching {@link PolarBasis}
     */
    public Vec3 surfacePoint(double positionAngle, double elongation, double observerDistance) {
        double r = heliocentricRadius(elongation, observerDistance);
        double z = depth(r, observerDistance);
        // The in-plane radius shrinks as the surface curves toward the observer: rho^2 = r^2 - z^2.
        double rho = Math.sqrt(Math.max(0, r * r - z * z));
        return new Vec3(PolarBasis.x(rho, positionAngle), PolarBasis.y(rho, positionAngle), z);
    }

    @Override
    public String toString() {
        return label;
    }

}
