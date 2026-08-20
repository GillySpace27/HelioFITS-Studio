package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.wcs.WcsHeader;

// Standalone self-check (no test framework in this repo — see extra/test/JHVMetadataDump.java for
// the established pattern). Confirms forcing CAR routes WcsInterpreter through the surface-map
// geometry branch even when CTYPE1/CTYPE2 don't literally end in "CAR".
public final class WcsInterpreterForcedProjectionCheck {

    public static void main(String[] args) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("CTYPE1", "Longitude");
        headers.put("CTYPE2", "Latitude");
        headers.put("CDELT1", "0.0897247426998");
        headers.put("CDELT2", "0.0895816823006");
        headers.put("CRVAL1", "0.0");
        headers.put("CRVAL2", "-90.0");
        MetaDataContainer m = new MapMetaDataContainer(headers);

        WcsInterpreter.Result unforced = WcsInterpreter.read(m);
        assertTrue(unforced.projection() == WcsHeader.Projection.TAN,
                "unforced: expected TAN (literal 'Longitude'/'Latitude' CTYPE doesn't match fromCtype), got " + unforced.projection());

        WcsInterpreter.Result forced = WcsInterpreter.read(m, WcsHeader.Projection.CAR);
        assertTrue(forced.projection() == WcsHeader.Projection.CAR,
                "forced: expected CAR, got " + forced.projection());
        assertTrue(forced.unitPerPixelX() > 0,
                "forced surface-map branch should compute a nonzero unitPerPixelX, got " + forced.unitPerPixelX());
        assertTrue(forced.unitPerPixelY() > 0,
                "forced surface-map branch should compute a nonzero unitPerPixelY, got " + forced.unitPerPixelY());

        System.out.println("WcsInterpreterForcedProjectionCheck: PASS");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }
}
