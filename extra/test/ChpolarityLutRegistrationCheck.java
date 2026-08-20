package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.io.Directories;

// Standalone self-check (no test framework in this repo). Confirms the new LUT resource parses,
// has exactly 256 entries, and the colors.js rule matching detector=CHPOL resolves to it.
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

        System.out.println("ChpolarityLutRegistrationCheck: PASS");
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
