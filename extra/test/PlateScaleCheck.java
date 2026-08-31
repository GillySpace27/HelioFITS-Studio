package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;

/**
 * The plate scale the layer readout prints, and the cases where there isn't one.
 *
 * <p>The trap this guards is a division that always succeeds. {@code getUnitPerPixelY() /
 * getUnitPerArcsec()} looks like arcseconds per pixel and is, for an angular WCS, exactly that. For
 * a surface map it is a longitude step divided by radians-per-arcsec, and for a pixel-based product
 * both sides are placeholders; in neither case does it fail, it just yields a plausible number that
 * is not a plate scale. A readout is believed, so those cases have to report nothing rather than
 * something. Hence {@link MetaData#getArcsecPerPixel}, which is 0 unless a real angular WCS set it.
 *
 * <p>Run: javac must be given extra/test/MapMetaDataContainer.java on the same command line; see
 * FitsMetaDataChpolarityCheck for why.
 */
public final class PlateScaleCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        initSpice(); // Sun.<clinit> resolves Earth through SPICE

        // An angular WCS: the scale is the header's own CDELT, in arcseconds, unconverted.
        for (double cdelt : new double[]{0.6, 2.5, 11.4, 56}) {
            FitsMetaData meta = helioprojective(cdelt);
            near(meta.getArcsecPerPixel(), cdelt, 1e-9,
                    "a TAN header must report its own CDELT2 of " + cdelt + " arcsec/px");
        }

        // Sign is a direction, not a scale: a negative CDELT is a flipped axis, and a readout
        // saying "-2.5 arcsec per pixel" would be nonsense.
        near(helioprojective(-2.5).getArcsecPerPixel(), 2.5, 1e-9, "a negative CDELT2 reports positive");

        // A surface map has no plate scale at all. Its pixels are degrees of heliographic
        // longitude, and the ratio that looks like a plate scale would still produce a number.
        FitsMetaData surface = surfaceMap();
        expect(surface.getWcsHeader().projection.isSurfaceMap(), "the surface-map fixture must be a surface map");
        expect(surface.getArcsecPerPixel() == 0, "a surface map must report no plate scale, got "
                + surface.getArcsecPerPixel());
        expect(surface.getUnitPerArcsec() > 0 && surface.getUnitPerPixelY() / surface.getUnitPerArcsec() > 0,
                "and the division it replaces must still yield a number, or this check guards nothing");

        // Anything that never went through an angular WCS, including the empty placeholder every
        // layer starts life with.
        expect(BasicMetaData.EMPTY.getArcsecPerPixel() == 0, "placeholder metadata has no plate scale");
        expect(new BasicMetaData(1024, 1024, "test").getArcsecPerPixel() == 0,
                "a pixel-based product has no plate scale");

        if (failures != 0)
            throw new AssertionError(failures + " plate-scale failure(s)");
        System.out.println("PlateScaleCheck: PASS");
    }

    /** A plain helioprojective-cartesian frame, the shape a coronagraph or an EUV imager writes. */
    private static FitsMetaData helioprojective(double cdelt) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("NAXIS1", "1024");
        headers.put("NAXIS2", "1024");
        headers.put("CTYPE1", "HPLN-TAN");
        headers.put("CTYPE2", "HPLT-TAN");
        headers.put("CUNIT1", "arcsec");
        headers.put("CUNIT2", "arcsec");
        headers.put("CDELT1", String.valueOf(cdelt));
        headers.put("CDELT2", String.valueOf(cdelt));
        headers.put("CRPIX1", "512.5");
        headers.put("CRPIX2", "512.5");
        headers.put("CRVAL1", "0.0");
        headers.put("CRVAL2", "0.0");
        headers.put("DATE-OBS", "2025-09-09T04:19:22.127");
        return new FitsMetaData(new MapMetaDataContainer(headers));
    }

    /** A Carrington map: pixels are degrees of longitude and latitude, not angles on the sky. */
    private static FitsMetaData surfaceMap() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("NAXIS1", "3600");
        headers.put("NAXIS2", "1800");
        headers.put("CTYPE1", "CRLN-CAR");
        headers.put("CTYPE2", "CRLT-CAR");
        headers.put("CDELT1", "0.1");
        headers.put("CDELT2", "0.1");
        headers.put("CRPIX1", "1800.5");
        headers.put("CRPIX2", "900.5");
        headers.put("CRVAL1", "0.0");
        headers.put("CRVAL2", "0.0");
        headers.put("DATE-OBS", "2025-09-09T04:19:22.127");
        return new FitsMetaData(new MapMetaDataContainer(headers));
    }

    private static void near(double got, double want, double tolerance, String what) {
        expect(Math.abs(got - want) <= tolerance, what + ": got " + got);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void initSpice() throws Exception {
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();
    }
}
