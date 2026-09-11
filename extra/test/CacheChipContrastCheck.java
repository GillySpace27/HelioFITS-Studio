package org.helioviewer.jhv.gui.dialog;

import java.awt.Color;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.io.CacheIndex;

/**
 * The three range chips are readable in every theme.
 *
 * <p>They are the one place in the dialog that does not take its colour from the theme, and that
 * is deliberate: good, caution and nothing are a separate axis from the interface's accent, so a
 * purple theme and an orange one both still need green to mean "you already have this". Colours
 * chosen outside the theme are colours the theme cannot fix, which is why they are measured here.
 *
 * <p>Against {@code Component}, the list background, rather than the panel: these are table cells.
 * At the text threshold rather than the graphic one, because a chip IS its text.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.dialog.CacheChipContrastCheck
 */
public final class CacheChipContrastCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws java.io.IOException {
        // Theme.setCurrent records the choice, so this runs against a throwaway home rather than
        // rewriting the settings of the application it is checking.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("jhv-cache-chips").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();

        for (Theme theme : Theme.builtIns()) {
            Theme.setCurrent(theme);
            Color list = theme.get(Theme.Token.Component);
            for (CacheIndex.Overlap overlap : CacheIndex.Overlap.values()) {
                Color chip = CacheDialog.StatusCell.chip(overlap);
                double ratio = Theme.contrast(chip, list);
                expect(String.format("%s: the %s chip is %.2f:1 on the list, needs %.1f (%s on %s)",
                                theme.id(), overlap.name().toLowerCase(), ratio, Theme.TEXT_MIN,
                                Theme.hex(chip), Theme.hex(list)),
                        ratio >= Theme.TEXT_MIN);
            }
        }

        // Three states that look alike are one state, and the first version of this measured it
        // with the contrast ratio, which was the wrong instrument: contrast is a LUMINANCE
        // difference, and a semantic palette deliberately keeps its good and caution colours at
        // similar lightness so neither shouts over the other. Asking for contrast between them
        // would have forced green and amber apart in brightness for no reason a reader benefits
        // from. What actually separates them is hue, and what separates both from the third is
        // that the third has almost no colour at all.
        for (boolean dark : new boolean[]{true, false}) {
            Theme.setCurrent(Theme.byIdOrDefault(dark ? "sunset-dark" : "sunset-light"));
            float[] covers = hsb(CacheDialog.StatusCell.chip(CacheIndex.Overlap.COVERS));
            float[] partial = hsb(CacheDialog.StatusCell.chip(CacheIndex.Overlap.PARTIAL));
            float[] none = hsb(CacheDialog.StatusCell.chip(CacheIndex.Overlap.NONE));
            String where = dark ? "dark" : "light";
            expect(String.format("%s: covers and partial are %.0f degrees apart in hue, needs 60",
                            where, hueGap(covers[0], partial[0])), hueGap(covers[0], partial[0]) >= 60);
            expect(String.format("%s: covers carries real colour (saturation %.2f)", where, covers[1]),
                    covers[1] >= 0.35);
            expect(String.format("%s: so does partial (saturation %.2f)", where, partial[1]),
                    partial[1] >= 0.35);
            expect(String.format("%s: and no-overlap is nearly colourless (saturation %.2f)", where, none[1]),
                    none[1] <= 0.25);
        }

        System.out.println(failures == 0 ? "CacheChipContrastCheck: PASS" : "CacheChipContrastCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static float[] hsb(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
    }

    /** Degrees between two hues the short way round, so 350 and 10 are twenty apart, not 340. */
    private static double hueGap(float a, float b) {
        double gap = Math.abs(a - b) * 360;
        return Math.min(gap, 360 - gap);
    }

    private CacheChipContrastCheck() {}

}
