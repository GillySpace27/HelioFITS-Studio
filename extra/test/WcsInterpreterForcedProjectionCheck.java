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

        // A ">0" check on unitPerPixelX/Y does NOT discriminate correct routing from the rejected
        // "wrong fix" (forcing the projection only on the returned Result, after isSurfaceMap was
        // already computed from fromCtype): both produce a positive number. What differs is WHICH
        // number: forcing before isSurfaceMap routes CDELT1 (0.0897247426998 deg/px) through the
        // surface-map conversion to radians/px (CDELT1 / ARCSEC_PER_RAD ~= 0.0015659 rad/px),
        // while forcing only the Result field leaves isSurfaceMap false, so CDELT1 is scaled to
        // arcsec instead (raw deg-derived value, ~0.0897247427, unchanged from CDELT1). So the
        // correct value is ~57x smaller than the buggy one, and the buggy one is numerically
        // identical to the unforced TAN case above. Both assertions below fail under the wrong fix.
        double expectedUnitPerPixelX = 0.0015659; // rad/px: CDELT1 (deg) -> rad, surface-map branch
        double epsilon = 1e-6;
        assertTrue(Math.abs(forced.unitPerPixelX() - expectedUnitPerPixelX) < epsilon,
                "forced surface-map unitPerPixelX: expected ~" + expectedUnitPerPixelX
                        + " rad/px (CDELT1 routed through the surface-map branch before isSurfaceMap is read), got "
                        + forced.unitPerPixelX()
                        + " (a 'forced-after-the-branch' bug leaves this at the raw CDELT1-derived value, ~0.0897247427)");
        assertTrue(forced.unitPerPixelX() != unforced.unitPerPixelX(),
                "forced.unitPerPixelX() (" + forced.unitPerPixelX()
                        + ") must differ from unforced.unitPerPixelX() (" + unforced.unitPerPixelX()
                        + "); forcing CAR should route through the surface-map angular-scale conversion, "
                        + "not merely relabel the projection on the Result");

        System.out.println("WcsInterpreterForcedProjectionCheck: PASS");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }
}
