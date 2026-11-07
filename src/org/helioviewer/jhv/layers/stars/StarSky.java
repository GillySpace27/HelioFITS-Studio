package org.helioviewer.jhv.layers.stars;

import java.util.List;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.SpiceMath;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.time.JHVTime;

import spice.basic.SpiceErrorException;

/**
 * Where the background stars and planets lie in an observer's sky, and how each one is drawn.
 *
 * <p>Everything is computed once per viewpoint as a DIRECTION: a unit ray in the observer's frame,
 * which is the frame the sky maps already speak (the Sun along -z, x toward increasing
 * helioprojective longitude, y toward increasing latitude, solar north up, so P = 0). Each view
 * then places those directions its own way: the 3D views on the plane through Sun centre
 * ({@link #plane}), HPC and Observer Sky through their page mapping (MapView.projectDirection).
 *
 * <p>The geometry reproduces GaiaClient.calcProj3, which the starfield spike (2026-11-07) checked
 * against sunpy's Helioprojective frame for real stars from Earth and from an observer at 0.3 AU:
 * agreement better than 0.0001 deg. Working in rays rather than calcProj3's angles also removes the
 * fold the spike found, where a star on the far side of the sky came back onto the field shifted
 * 180 deg in Tx. The 3D placement d tan(T) still runs away far from the Sun, so {@link #plane}
 * drops anything more than {@link #MAX_ELONGATION_DEG} from it; the sky maps need no such cut.
 *
 * <p>Solar light deflection, parallax and aberration are ignored: they are arcsecond effects, far
 * below a point a few pixels wide on a coronagraph field. Proper motion is applied.
 */
public final class StarSky {

    /** What the options panel controls. Saturation 0 is white, 1 the blackbody colour, above 1 exaggerated. */
    public record Style(double magLimit, boolean colour, double saturation, double sizeScale, boolean planets, boolean moon) {}

    /**
     * Every body drawn for one viewpoint: n unit rays in the observer's frame (x, y, z per body),
     * sizes in pixels, and premultiplied RGBA. Arrays are not copied; nothing writes them after
     * {@link #compute} returns.
     */
    public record Points(int count, float[] ray, float[] size, byte[] colour) {}

    public static final double MAX_ELONGATION_DEG = 80;
    private static final double COS_MAX_ELONGATION = Math.cos(Math.toRadians(MAX_ELONGATION_DEG));

    private static final double JYEAR_SEC = 365.25 * 86400;
    // J1991.25 = JD 2448349.0625; Unix seconds = (JD - 2440587.5) * 86400. The TT/UTC offset is a minute, irrelevant here.
    private static final double EPOCH_SEC = (2448349.0625 - 2440587.5) * 86400;
    private static final double MAS = Math.toRadians(1 / 3600000.);

    private static final List<String> PLANETS = List.of("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune");
    private static final float PLANET_PX = 6;
    private static final float MOON_PX = 7;
    // Nearer than this (solar radii, about 170,000 km) a body is the one the view is from, or the
    // one it orbits: an observer at Earth whose location carries no name, or SDO over Earth. Its
    // "direction" is then light-time and frame noise, and it was drawn as a dot on the Sun.
    private static final double NEAR_RSUN = 0.25;
    // A body this close to the Sun line (1 arcsec) is one the viewpoint was placed on: an image
    // with no observer position is put on the Sun-Earth line, and Earth then lands exactly in
    // front of or behind the Sun. A real planet is never that exactly aligned with a real observer.
    private static final double SUN_LINE_SIN = Math.toRadians(1 / 3600.);

    /** The whole sky for one viewpoint, brighter than the style's limit, planets first. */
    public static Points compute(Position viewpoint, StarCatalog cat, Style style) throws SpiceErrorException {
        int max = cat.size() + PLANETS.size() + 1;
        Points out = new Points(0, new float[3 * max], new float[max], new byte[4 * max]);
        int n = 0;
        JHVTime time = viewpoint.time;
        double[] sc = observer(viewpoint);
        double[][] frame = frame(sc[1], sc[2]);
        double[] ray = new double[3];

        if (style.planets()) {
            double[] rsc = SpiceMath.radrec(sc[0], sc[1], sc[2]);
            String self = viewpoint.getLocation();
            for (String name : PLANETS)
                n = putBody(name, self, PLANET_PX, rsc, frame, time, ray, out, n, style);
            if (style.moon())
                n = putBody("Moon", self, MOON_PX, rsc, frame, time, ray, out, n, style);
        }

        double dyr = (time.milli / 1000. - EPOCH_SEC) / JYEAR_SEC;
        double[][] carr = Spice.j2000ToSun.get(time);
        for (int i = 0; i < cat.size(); i++) {
            double mag = cat.vmag[i];
            if (mag > style.magLimit())
                continue;
            double dec = Math.toRadians(cat.dec[i]) + cat.pmdec[i] * MAS * dyr;
            double ra = Math.toRadians(cat.ra[i]) + cat.pmra[i] * MAS * dyr / Math.cos(Math.toRadians(cat.dec[i]));
            starRay(frame, carr, ra, dec, ray);
            n = put(ray, starSize(mag, style), starColour(cat.bv[i], mag, style), out, n);
        }
        return new Points(n, out.ray(), out.size(), out.colour());
    }

    /**
     * Vertices for the 3D views: x, y on the plane through Sun centre normal to the line of sight
     * (d tan Tx, d tan Ty, the placement GaiaClient used), size in pixels. Bodies more than
     * MAX_ELONGATION_DEG from the Sun are left out.
     */
    public static BufVertex plane(Points p, double distance) {
        BufVertex buf = new BufVertex(Math.min(p.count(), 20000) + 10);
        double[] ray = new double[3];
        double[] theta = new double[2];
        byte[] colour = new byte[4];
        for (int i = 0; i < p.count(); i++) {
            ray[0] = p.ray()[3 * i];
            ray[1] = p.ray()[3 * i + 1];
            ray[2] = p.ray()[3 * i + 2];
            if (!theta(ray, theta))
                continue;
            System.arraycopy(p.colour(), 4 * i, colour, 0, 4);
            buf.putVertex((float) (distance * Math.tan(theta[0])), (float) (distance * Math.tan(theta[1])), 0, p.size()[i], colour);
        }
        return buf;
    }

    /**
     * The observer's frame in Carrington coordinates, for an observer at Carrington (lon0, lat0)
     * radians: rows are the x (solar west on the sky), y (solar north) and z (Sun toward observer)
     * axes, so multiplying a Carrington direction by it gives the ray in the observer's frame.
     */
    static double[][] frame(double lon0, double lat0) {
        double cl = Math.cos(lon0), sl = Math.sin(lon0), cb = Math.cos(lat0), sb = Math.sin(lat0);
        return new double[][]{{-sl, cl, 0}, {-sb * cl, -sb * sl, cb}, {cb * cl, cb * sl, sb}};
    }

    /** A Carrington direction, any length, as a unit ray in the observer's frame. */
    static void toRay(double[][] frame, double[] carrington, double[] ray) {
        double[] r = SpiceMath.mxv(frame, carrington);
        double len = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        ray[0] = r[0] / len;
        ray[1] = r[1] / len;
        ray[2] = r[2] / len;
    }

    /** One star's ray; ra, dec ICRS radians. */
    static void starRay(double[][] frame, double[][] j2000ToCarrington, double ra, double dec, double[] ray) {
        toRay(frame, SpiceMath.mxv(j2000ToCarrington, SpiceMath.radrec(1, ra, dec)), ray);
    }

    /**
     * Helioprojective (Tx, Ty) of a ray, in radians, for the 3D placement. False, with theta
     * untouched, for anything more than MAX_ELONGATION_DEG from the Sun, which includes the whole
     * far hemisphere.
     */
    static boolean theta(double[] ray, double[] theta) {
        if (-ray[2] < COS_MAX_ELONGATION) // the Sun is along -z, so -z is the cosine of the elongation
            return false;
        theta[0] = Math.atan2(ray[0], -ray[2]);
        theta[1] = Math.asin(Math.clamp(ray[1], -1, 1));
        return true;
    }

    /** The {distance, Carrington lon, lat} of the observer, from a viewpoint (whose lon is stored negated). */
    static double[] observer(Position viewpoint) {
        return new double[]{viewpoint.distance, -viewpoint.lon, viewpoint.lat};
    }

    private static int putBody(String name, String self, float px, double[] rsc, double[][] frame, JHVTime time, double[] ray, Points out, int n, Style style) {
        SpaceObject body = SpaceObject.get(name);
        if (body == null || body.getSpiceName().equals(self))
            return n;
        double[] pl;
        try {
            pl = Spice.getPosition("SUN", body.getSpiceName(), "SOLO_IAU_SUN_2009", time);
        } catch (SpiceErrorException e) { // the outer-planet kernel is optional; a missing body is skipped, not the sky
            return n;
        }
        pl[0] -= rsc[0]; // observer -> body = Sun -> body - Sun -> observer
        pl[1] -= rsc[1];
        pl[2] -= rsc[2];
        if (pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2] < NEAR_RSUN * NEAR_RSUN)
            return n;
        toRay(frame, pl, ray);
        if (Math.hypot(ray[0], ray[1]) < SUN_LINE_SIN)
            return n;
        return put(ray, (float) (px * style.sizeScale()), body.getColor(), out, n);
    }

    private static int put(double[] ray, float size, byte[] colour, Points out, int n) {
        out.ray()[3 * n] = (float) ray[0];
        out.ray()[3 * n + 1] = (float) ray[1];
        out.ray()[3 * n + 2] = (float) ray[2];
        out.size()[n] = size;
        System.arraycopy(colour, 0, out.colour(), 4 * n, 4);
        return n + 1;
    }

    /** Point diameter in pixels: the faintest star shown is 1.2 px, each magnitude brighter adds 0.8, capped. */
    static float starSize(double mag, Style style) {
        double px = 1.2 + 0.8 * Math.max(0, style.magLimit() - mag);
        return (float) (Math.min(px, 9) * style.sizeScale());
    }

    /** Premultiplied RGBA. Faint stars are dimmer so a deep limit reads as texture, not clutter. */
    static byte[] starColour(double bv, double mag, Style style) {
        double alpha = Math.clamp(0.35 + 0.65 * (style.magLimit() - mag) / 3, 0.35, 1);
        float[] rgb = style.colour() && !Double.isNaN(bv) ? saturate(kelvinToRgb(bvToKelvin(bv)), style.saturation()) : new float[]{1, 1, 1};
        return new byte[]{(byte) Math.round(255 * rgb[0] * alpha), (byte) Math.round(255 * rgb[1] * alpha),
                (byte) Math.round(255 * rgb[2] * alpha), (byte) Math.round(255 * alpha)};
    }

    /**
     * Effective temperature from B-V for a blackbody, Ballesteros (2012, EPL 97, 34008):
     * T = 4600 K (1 / (0.92 (B-V) + 1.7) + 1 / (0.92 (B-V) + 0.62)). B-V = 0.65 gives 5778 K, the
     * value PyAstronomy's implementation of the same formula lists. Clamped to the B-V range real
     * stars span so a bad catalogue value cannot blow up the colour.
     */
    static double bvToKelvin(double bv) {
        double x = 0.92 * Math.clamp(bv, -0.4, 2.0);
        return 4600 * (1 / (x + 1.7) + 1 / (x + 0.62));
    }

    // Representative wavelengths for display red, green and blue, in metres
    private static final double[] LAMBDA = {610e-9, 550e-9, 465e-9};
    private static final double HC_K = 6.62607015e-34 * 299792458 / 1.380649e-23; // hc/k [m K]

    /**
     * A display colour for a blackbody: Planck's law sampled at three wavelengths, scaled so the
     * brightest channel is 1. A visual aid only, not colourimetry: it is meant to make hot stars
     * look blue-white and cool ones orange, which it does, not to reproduce a measured colour.
     */
    static float[] kelvinToRgb(double kelvin) {
        double[] b = new double[3];
        double max = 0;
        for (int i = 0; i < 3; i++) {
            double l = LAMBDA[i];
            b[i] = 1 / (Math.pow(l, 5) * Math.expm1(HC_K / (l * kelvin)));
            max = Math.max(max, b[i]);
        }
        return new float[]{(float) (b[0] / max), (float) (b[1] / max), (float) (b[2] / max)};
    }

    /** Moves a colour away from (or, below 1, toward) white, keeping the brightest channel at 1. */
    static float[] saturate(float[] rgb, double s) {
        float[] out = new float[3];
        float max = 0;
        for (int i = 0; i < 3; i++) {
            out[i] = (float) Math.max(0, 1 + s * (rgb[i] - 1));
            max = Math.max(max, out[i]);
        }
        for (int i = 0; i < 3; i++)
            out[i] /= max;
        return out;
    }

    private StarSky() {}

}
