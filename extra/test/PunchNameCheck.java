package org.helioviewer.jhv.metadata;

import java.util.HashMap;
import java.util.Map;

/**
 * A PUNCH layer is called by its product and pipeline version: "PUNCH CAM v0l".
 *
 * <p>It was "WFI+NFI Mosaic 530", the generic instrument-plus-wavelength name, which is the same for the
 * clear and the polarized mosaics and says nothing about the version. PUNCH releases a new pipeline version
 * every couple of months, so which one a movie is made from is what Gilly needs to see at a glance.
 *
 * <p>The cards are those of a real PUNCH Level-3 clear mosaic from the cache on 2026-09-14.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.metadata.PunchNameCheck
 */
public final class PunchNameCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static Map<String, String> punch() {
        Map<String, String> h = new HashMap<>();
        h.put("TELESCOP", "PUNCH 1-2-3-4");
        h.put("OBSRVTRY", "PUNCH");
        h.put("INSTRUME", "WFI+NFI Mosaic");
        h.put("DETECTOR", "PUNCH");
        h.put("OBSCODE", "M");
        h.put("TYPECODE", "CA");
        h.put("FILEVRSN", "0l");
        h.put("LEVEL", "3");
        h.put("WAVELNTH", "530");
        h.put("DATE-OBS", "2025-09-20T03:24:29");
        return h;
    }

    public static void main(String[] args) throws Exception {
        // Building any FitsMetaData initialises Sun, which asks SPICE for Earth's position, so the native has to
        // be loaded first. As LayersListenerIsolationCheck, in a throwaway home.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-punch-name").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        String name = FitsMetaData.observation(new MapMetaDataContainer(punch())).displayName();
        expect("a clear mosaic is \"PUNCH CAM v0l\", not \"" + name + "\"", "PUNCH CAM v0l".equals(name));

        Map<String, String> polarized = punch();
        polarized.put("TYPECODE", "PA");
        polarized.put("FILEVRSN", "0k");
        expect("the polarized one at an older version reads differently",
                "PUNCH PAM v0k".equals(FitsMetaData.observation(new MapMetaDataContainer(polarized)).displayName()));

        Map<String, String> noVersion = punch();
        noVersion.remove("FILEVRSN");
        expect("a file with no version card is named without one",
                "PUNCH CAM".equals(FitsMetaData.observation(new MapMetaDataContainer(noVersion)).displayName()));

        // Anything that is not PUNCH keeps the generic name, instrument then wavelength.
        Map<String, String> other = new HashMap<>();
        other.put("INSTRUME", "SOMETHING");
        other.put("WAVELNTH", "171");
        other.put("DATE-OBS", "2025-09-20T03:24:29");
        expect("another instrument is left alone",
                "SOMETHING 171".equals(FitsMetaData.observation(new MapMetaDataContainer(other)).displayName()));

        // A native L3 mosaic says PUNCH only in OBSRVTRY (no DETECTOR card), and still needs the inner occulter.
        try (nom.tam.fits.Fits fits = new nom.tam.fits.Fits(new java.io.File("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits"))) {
            nom.tam.fits.Header header = null;
            for (nom.tam.fits.BasicHDU<?> hdu : fits.read())
                if (hdu instanceof nom.tam.image.compression.hdu.CompressedImageHDU chdu && header == null)
                    header = chdu.getImageHeader();
            float inner = new FitsMetaData(new org.helioviewer.jhv.io.FitsHeaderContainer(header), MetaData.UNKNOWN_SOURCE_URI).getInnerRadius();
            double rsun = inner / org.helioviewer.jhv.astronomy.Sun.Radius;
            expect("a native PUNCH L3 mosaic gets the 12 Rsun inner occulter, got " + rsun, Math.abs(rsun - 12) < 1e-4);
        }

        System.out.println(failures == 0 ? "PunchNameCheck: PASS" : "PunchNameCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PunchNameCheck() {}
}
