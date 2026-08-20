package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.wcs.WcsHeader;

// Standalone self-check (no test framework in this repo). Confirms the ORIGIN-prefix detection
// branch: files from the ptmc_compo pipeline are recognized as indexed surface maps with a
// synthetic "CHPOL" detector; anything else is unaffected.
public final class FitsMetaDataChpolarityCheck {

    public static void main(String[] args) throws Exception {
        if (System.getProperty("user.timezone") == null) // TimeUtils' static init requires it; see JHVMetadataDump precedent
            System.setProperty("user.timezone", "UTC");
        initSpice(); // Sun.<clinit> needs SPICE loaded for getEarth(); see JHVMetadataDump precedent

        FitsMetaData indexed = build("ptmc_compo_sm_20250909_041922_cr2302DO_l3");
        assertTrue(indexed.isIndexedSurfaceMap(), "ptmc_compo origin should set isIndexedSurfaceMap");
        assertTrue("CHPOL".equals(indexed.getDetector()), "ptmc_compo origin should set detector=CHPOL, got " + indexed.getDetector());
        assertTrue(indexed.getWcsHeader().projection == WcsHeader.Projection.CAR,
                "ptmc_compo origin should force CAR projection, got " + indexed.getWcsHeader().projection);

        FitsMetaData unrelated = build("some_other_pipeline_output");
        assertTrue(!unrelated.isIndexedSurfaceMap(), "non-ptmc_compo origin must NOT set isIndexedSurfaceMap");
        assertTrue(!"CHPOL".equals(unrelated.getDetector()), "non-ptmc_compo origin must NOT set detector=CHPOL");

        System.out.println("FitsMetaDataChpolarityCheck: PASS");
    }

    // package-visible (not private): Task 4's ChpolarityLutRegistrationCheck reuses this builder
    static FitsMetaData build(String origin) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("ORIGIN", origin);
        headers.put("NAXIS1", "4013");
        headers.put("NAXIS2", "2011");
        headers.put("CTYPE1", "Longitude");
        headers.put("CTYPE2", "Latitude");
        headers.put("CDELT1", "0.0897247426998");
        headers.put("CDELT2", "0.0895816823006");
        headers.put("CRVAL1", "0.0");
        headers.put("CRVAL2", "-90.0");
        headers.put("DATE-OBS", "2025-09-09T04:19:22.127");
        MetaDataContainer m = new MapMetaDataContainer(headers);
        return new FitsMetaData(m);
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }

    // Sun.<clinit> resolves Earth position via SPICE; mirrors JHVMetadataDump's initSpice().
    private static void initSpice() throws Exception {
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();
    }
}
