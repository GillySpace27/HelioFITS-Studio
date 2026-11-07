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
 * Where the background stars and planets fall in an observer's view, and how each one is drawn.
 *
 * <p>The geometry is GaiaClient's, which the starfield spike (2026-11-07) checked against sunpy's
 * Helioprojective frame for real stars from Earth and from an observer at 0.3 AU: agreement better
 * than 0.0001 deg. Two things are new, both found by that spike. Stars on the far side of the sky
 * are culled (calcProj3 alone folds them onto the field, shifted 180 deg in Tx), and so is anything
 * more than {@link #MAX_ELONGATION_DEG} from the Sun, where the d tan(T) placement runs away.
 *
 * <p>Solar light deflection, parallax and aberration are ignored: they are arcsecond effects, far
 * below a point a few pixels wide on a coronagraph field. Proper motion is applied.
 */
public final class StarSky {

    /** What the options panel controls. Saturation 0 is white, 1 the blackbody colour, above 1 exaggerated. */
    public record Style(double magLimit, boolean colour, double saturation, double sizeScale, boolean planets, boolean moon) {}

    public static final double MAX_ELONGATION_DEG = 80;
    private static final double COS_MAX_ELONGATION = Math.cos(Math.toRadians(MAX_ELONGATION_DEG));

    private static final double JYEAR_SEC = 365.25 * 86400;
    // J1991.25 = JD 2448349.0625; Unix seconds = (JD - 2440587.5) * 86400. The TT/UTC offset is a minute, irrelevant here.
    private static final double EPOCH_SEC = (2448349.0625 - 2440587.5) * 86400;
    private static final double MAS = Math.toRadians(1 / 3600000.);

    private static final List<String> PLANETS = List.of("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune");
    private static final float PLANET_PX = 6;
    private static final float MOON_PX = 7;

    /** Vertices for one viewpoint: x, y on the plane through Sun centre normal to the line of sight, size in pixels. */
    public static BufVertex compute(Position viewpoint, StarCatalog cat, Style style) throws SpiceErrorException {
        BufVertex buf = new BufVertex(Math.min(cat.size(), 20000) + 10);
        JHVTime time = viewpoint.time;
        double[] sc = observer(viewpoint);
        double[] theta = new double[2];

        if (style.planets()) {
            double[] rsc = SpiceMath.radrec(sc[0], sc[1], sc[2]);
            String self = viewpoint.getLocation();
            for (String name : PLANETS)
                putBody(name, self, PLANET_PX, rsc, sc, time, theta, buf, style);
            if (style.moon())
                putBody("Moon", self, MOON_PX, rsc, sc, time, theta, buf, style);
        }

        double dyr = (time.milli / 1000. - EPOCH_SEC) / JYEAR_SEC;
        double[][] carr = Spice.j2000ToSun.get(time);
        for (int i = 0; i < cat.size(); i++) {
            double mag = cat.vmag[i];
            if (mag > style.magLimit())
                continue;
            double dec = Math.toRadians(cat.dec[i]) + cat.pmdec[i] * MAS * dyr;
            double ra = Math.toRadians(cat.ra[i]) + cat.pmra[i] * MAS * dyr / Math.cos(Math.toRadians(cat.dec[i]));
            if (!starTheta(sc, carr, ra, dec, theta))
                continue;
            put(theta, sc[0], starSize(mag, style), starColour(cat.bv[i], mag, style), buf);
        }
        return buf;
    }

    /** One star's (Tx, Ty) for an observer at sc = {distance, Carrington lon, lat}; false when culled. ra, dec ICRS radians. */
    static boolean starTheta(double[] sc, double[][] j2000ToCarrington, double ra, double dec, double[] theta) {
        double[] st = SpiceMath.recrad(SpiceMath.mxv(j2000ToCarrington, SpiceMath.radrec(1, ra, dec)));
        return project(st[1], st[2], sc[1], sc[2], theta);
    }

    /** The {distance, Carrington lon, lat} the projection wants, from a viewpoint (whose lon is stored negated). */
    static double[] observer(Position viewpoint) {
        return new double[]{viewpoint.distance, -viewpoint.lon, viewpoint.lat};
    }

    private static void putBody(String name, String self, float px, double[] rsc, double[] sc, JHVTime time, double[] theta, BufVertex buf, Style style) {
        SpaceObject body = SpaceObject.get(name);
        if (body == null || body.getSpiceName().equals(self))
            return;
        double[] pl;
        try {
            pl = Spice.getPosition("SUN", body.getSpiceName(), "SOLO_IAU_SUN_2009", time);
        } catch (SpiceErrorException e) { // the outer-planet kernel is optional; a missing body is skipped, not the sky
            return;
        }
        pl[0] -= rsc[0]; // observer -> body = Sun -> body - Sun -> observer
        pl[1] -= rsc[1];
        pl[2] -= rsc[2];
        pl = SpiceMath.recrad(pl);
        if (project(pl[1], pl[2], sc[1], sc[2], theta))
            put(theta, sc[0], (float) (px * style.sizeScale()), body.getColor(), buf);
    }

    private static void put(double[] theta, double dist, float size, byte[] colour, BufVertex buf) {
        buf.putVertex((float) (dist * Math.tan(theta[0])), (float) (dist * Math.tan(theta[1])), 0, size, colour);
    }

    /**
     * GaiaClient.calcProj3 with P = 0, plus the cull. (lon, lat) is the direction of the star from
     * the observer, (lon0, lat0) the direction of the observer from the Sun, both Carrington and in
     * radians. Returns false for anything more than MAX_ELONGATION_DEG from the Sun as seen by the
     * observer, which includes the whole far hemisphere.
     */
    static boolean project(double lon, double lat, double lon0, double lat0, double[] theta) {
        double cosRho = Math.cos(lat) * Math.cos(lat0) * Math.cos(lon - lon0) + Math.sin(lat) * Math.sin(lat0);
        // rho is measured from the observer's own direction, so the Sun lies at rho = 180 deg and
        // the elongation from the Sun is 180 - rho: keep cos(180 - rho) = -cosRho >= cos(max)
        if (-cosRho < COS_MAX_ELONGATION)
            return false;
        double phi = Math.atan2(Math.sin(lon - lon0), Math.tan(lat) * Math.cos(lat0) - Math.sin(lat0) * Math.cos(lon - lon0));
        double rho = Math.acos(Math.clamp(cosRho, -1, 1));
        theta[0] = Math.atan(-Math.tan(rho) * Math.sin(phi));
        theta[1] = Math.asin(Math.clamp(Math.sin(rho) * Math.cos(phi), -1, 1));
        return true;
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
