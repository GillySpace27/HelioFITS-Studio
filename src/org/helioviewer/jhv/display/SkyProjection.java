package org.helioviewer.jhv.display;

import javax.annotation.Nullable;

/**
 * How the observer's sky is laid flat, for {@link MapMode#ObserverSky}.
 *
 * <p>All three are zenithal (azimuthal) projections about a steerable reference direction: the
 * azimuth of a point around that direction is preserved exactly, and the projections differ only
 * in the one radial law that turns the angular distance from it into a distance on the page. They
 * are the FITS WCS zenithal projections TAN, STG and ARC of Calabretta and Greisen (2002), and the
 * formulae below are those definitions rather than approximations of them, with the native radial
 * coordinate written in radians instead of the standard's degrees.
 *
 * <p>The choice is not cosmetic, because it decides how far you can turn before the picture stops
 * being usable. Gnomonic diverges at 90 degrees from the reference point, which is exactly the
 * elongation a coronagraph reaches, so it cannot show a full field. Stereographic reaches nearly
 * the whole sky but inflates the edges, trading area for shape. Azimuthal equidistant reaches the
 * anti-solar point at a constant angular scale, which is the one property a planetarium master
 * needs: on a dome, distance from the centre of the image IS angle from the centre of the dome.
 */
public enum SkyProjection {

    // Ordered by how far each one reaches, so the list reads as a ladder.
    Gnomonic("Gnomonic", "TAN", Math.toRadians(85)),
    Stereographic("Stereographic", "STG", Math.toRadians(175)),
    AzimuthalEquidistant("Azimuthal equidistant", "ARC", Math.PI);

    private final String label;
    private final String wcsCode;
    private final double maxAngle;

    SkyProjection(String _label, String _wcsCode, double _maxAngle) {
        label = _label;
        wcsCode = _wcsCode;
        maxAngle = _maxAngle;
    }

    /** The default: the only one that reaches the anti-sun, and the dome-native law. */
    public static final SkyProjection DEFAULT = AzimuthalEquidistant;

    /**
     * The largest angular distance from the reference direction this projection can draw, in
     * radians. TAN and STG both diverge at their limit rather than ending, so their caps are set
     * short of it: what lies beyond is not clipped detail but a radius running to infinity.
     */
    public double maxAngle() {
        return maxAngle;
    }

    /** Radius on the page for a point this many radians from the reference direction. */
    public double radiusFromAngle(double rho) {
        return switch (this) {
            case Gnomonic -> Math.tan(rho);
            case Stereographic -> 2 * Math.tan(0.5 * rho);
            case AzimuthalEquidistant -> rho;
        };
    }

    /** The inverse of {@link #radiusFromAngle}: what angle a page radius stands for. */
    public double angleFromRadius(double radius) {
        return switch (this) {
            case Gnomonic -> Math.atan(radius);
            case Stereographic -> 2 * Math.atan(0.5 * radius);
            case AzimuthalEquidistant -> radius;
        };
    }

    /** The FITS WCS code for the same projection, for anyone matching this against a header. */
    public String wcsCode() {
        return wcsCode;
    }

    /** The shader selects on this; see solarSky.frag, which carries the same three cases. */
    public float shaderCode() {
        return ordinal();
    }

    public String tooltip() {
        return switch (this) {
            case Gnomonic -> "Straight lines on the sky stay straight, and the scale is exact only "
                    + "at the centre. Diverges at 90° from the centre, so it cannot show a whole "
                    + "coronagraph field at once. Matches a TAN image header.";
            case Stereographic -> "Shapes are preserved everywhere (conformal), at the cost of "
                    + "inflating the outer field. Reaches almost the whole sky. Matches an STG header.";
            case AzimuthalEquidistant -> "Distance from the centre of the picture is proportional to "
                    + "angle from the centre of the field, all the way to the anti-solar point. The "
                    + "projection a fisheye dome master is defined in. Matches an ARC header.";
        };
    }

    @Override
    public String toString() {
        return label;
    }

    @Nullable
    public static SkyProjection fromName(String name) {
        for (SkyProjection p : values())
            if (p.name().equals(name))
                return p;
        return null;
    }

}
