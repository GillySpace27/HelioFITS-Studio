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
 * </ul>
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
    ThomsonSphere("Thomson sphere");

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
        return this != ThomsonSphere || (observerDistance > 0 && outerRadius <= observerDistance);
    }

    /** Heliocentric distance, in the same units as {@code observerDistance}, of the surface point. */
    public double heliocentricRadius(double elongation, double observerDistance) {
        double e = Math.clamp(elongation, 0, MAX_ELONGATION);
        return observerDistance * (this == ThomsonSphere ? Math.sin(e) : Math.tan(e));
    }

    /**
     * Displacement toward the observer, along the Sun-observer axis, of the surface point at a
     * given heliocentric radius. Written in terms of the radius rather than the elongation
     * because that is what the caller already has once the warp has been inverted.
     */
    public double depth(double heliocentricRadius, double observerDistance) {
        if (this != ThomsonSphere || observerDistance <= 0)
            return 0;
        // z = D sin^2 e = r^2 / D on the Thomson sphere, from |p|^2 = D * p.z.
        double r = Math.min(heliocentricRadius, observerDistance);
        return r * r / observerDistance;
    }

    /** The elongation whose surface point sits at this heliocentric radius. Inverse of {@link #heliocentricRadius}. */
    public double elongation(double heliocentricRadius, double observerDistance) {
        if (observerDistance <= 0)
            return 0;
        double ratio = heliocentricRadius / observerDistance;
        return this == ThomsonSphere ? Math.asin(Math.clamp(ratio, -1, 1)) : Math.atan(ratio);
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
