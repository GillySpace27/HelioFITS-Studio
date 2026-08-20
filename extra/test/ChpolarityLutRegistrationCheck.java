package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.io.Directories;

// Standalone self-check (no test framework in this repo). Confirms the new LUT resource parses,
// has exactly 256 entries, the colors.js rule matching detector=CHPOL resolves to it, and that
// specific legend indices unpack to the exact colors the category codes require (Finding 7: a
// future edit inserting one entry above the block would shift every category by one and, without
// these content checks, the count-only check above would still pass).
public final class ChpolarityLutRegistrationCheck {

    public static void main(String[] args) throws Exception {
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        initSpice();

        LUT byName = LUT.get("CH/Polarity Legend");
        assertTrue(byName != null, "LUT 'CH/Polarity Legend' should be registered from standard-luts.txt");
        int entries = byName.rgba().remaining() / 4;
        assertTrue(entries == 256, "expected 256 LUT entries, got " + entries);

        FitsMetaData indexed = FitsMetaDataChpolarityCheck.build("ptmc_compo_sm_20250909_041922_cr2302DO_l3");
        LUT byRule = LUT.get(indexed);
        assertTrue(byRule != null && "CH/Polarity Legend".equals(byRule.name()),
                "colors.js rule for detector=CHPOL should resolve to CH/Polarity Legend, got " + (byRule == null ? "null" : byRule.name()));

        int[] lut8 = byName.lut8(); // packed ARGB, alpha in bits 24-31
        assertRgb(lut8, 3, 0, 0, 255, "index 3 (+ Polarity Blue)");
        assertRgb(lut8, 9, 0, 255, 0, "index 9 (Filament Green)");
        assertRgb(lut8, 5, 255, 0, 255, "index 5 (- CH Boundary Magenta)");
        assertRgb(lut8, 200, 128, 0, 255, "index 200 (unused-fallback Electric Violet)");

        System.out.println("ChpolarityLutRegistrationCheck: PASS");
    }

    private static void assertRgb(int[] lut8, int index, int expectedR, int expectedG, int expectedB, String label) {
        int argb = lut8[index];
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        assertTrue(r == expectedR && g == expectedG && b == expectedB,
                label + ": expected RGB (" + expectedR + "," + expectedG + "," + expectedB + "), got (" + r + "," + g + "," + b + ")");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }

    private static void initSpice() throws Exception {
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();
    }
}
