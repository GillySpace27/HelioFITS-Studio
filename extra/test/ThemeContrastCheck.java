package org.helioviewer.jhv.app;

import java.awt.Color;
import java.util.List;

/**
 * Every built-in theme's section headers, measured.
 *
 * <p>The silent failure this catches is the one the theme system was built to fix, arriving again
 * from the other direction. A section header whose fill is within a few percent of the panel it
 * sits on is not a header: the two-half gradient this replaced measured 1.02 to 1.16 against the
 * panel in the shipped themes, and nothing said so. It looks like a design choice on screen, and
 * on a projector or a poorly-adjusted laptop it is simply not there. The same goes for the
 * separators that used to be {@code getBackground().brighter()}: about 1.15, which is no line.
 *
 * <p>So this asserts the two rules Gilly settled on, WCAG 2.1 non-text contrast for the band and
 * text contrast for the title, over the whole table, including the nested-header fill, which is
 * derived and therefore the one most likely to drift out of range when a colour above it moves.
 * It is pure arithmetic over {@code Theme.builtIns()} and touches no window and no settings file.
 *
 * <p>Also checks the settings migration, because it is a branch: the theme key used to hold the
 * old two-valued switch, and the two old values have to land on the Classic themes rather than on
 * the new default, or an install that chose Light comes back purple.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.app.ThemeContrastCheck
 */
public final class ThemeContrastCheck {

    private static final double HEADER_ON_PANEL = 3;
    private static final double TEXT_ON_HEADER = 4.5;
    private static final double SEPARATOR_ON_PANEL = 3;
    private static final double BODY_ON_PANEL = 4.5;

    private static int failures;

    public static void main(String[] args) {
        List<Theme> themes = Theme.builtIns();
        expect(themes.size() >= 4, "there are built-in themes");
        expect(Theme.byIdOrDefault(Theme.DEFAULT_ID).id().equals(Theme.DEFAULT_ID), "the default id names a real theme");

        for (Theme theme : themes) {
            Color panel = theme.get(Theme.Token.Background);
            Color text = theme.get(Theme.Token.HeaderText);

            ratio(theme, "section header on panel", theme.get(Theme.Token.HeaderFill), panel, HEADER_ON_PANEL);
            ratio(theme, "header text on section header", text, theme.get(Theme.Token.HeaderFill), TEXT_ON_HEADER);
            ratio(theme, "nested header on panel", theme.get(Theme.Token.ChildHeaderFill), panel, HEADER_ON_PANEL);
            ratio(theme, "header text on nested header", text, theme.get(Theme.Token.ChildHeaderFill), TEXT_ON_HEADER);
            ratio(theme, "separator on panel", theme.get(Theme.Token.Separator), panel, SEPARATOR_ON_PANEL);
            ratio(theme, "body text on panel", theme.get(Theme.Token.Foreground), panel, BODY_ON_PANEL);

            // A nested header that measures the same as its parent is compliant and useless.
            expect(!theme.get(Theme.Token.ChildHeaderFill).equals(theme.get(Theme.Token.HeaderFill)),
                    theme.name() + ": nested header is the same colour as the parent header");

            for (Theme.Token token : Theme.Token.values())
                expect(theme.get(token) != null, theme.name() + ": " + token + " has no colour");
        }

        // Two themes claiming the same id would make byId return whichever came first, and the
        // menu's radio would light up on the wrong row.
        for (int i = 0; i < themes.size(); i++)
            for (int j = i + 1; j < themes.size(); j++)
                expect(!themes.get(i).id().equals(themes.get(j).id()), "duplicate theme id: " + themes.get(i).id());

        expect(Theme.migrateId(null).equals(Theme.DEFAULT_ID), "no stored theme means the default");
        expect(Theme.migrateId("").equals(Theme.DEFAULT_ID), "a blank stored theme means the default");
        expect(Theme.migrateId("Dark").equals("classic-dark"), "the old Dark setting means Classic Dark");
        expect(Theme.migrateId("Light").equals("classic-light"), "the old Light setting means Classic Light");
        expect(Theme.migrateId("sunset-light").equals("sunset-light"), "a theme id is left alone");

        // The formula itself, against the two ends everyone knows.
        expect(Math.abs(Theme.contrast(Color.WHITE, Color.BLACK) - 21) < 0.01, "white on black is 21:1");
        expect(Math.abs(Theme.contrast(Color.GRAY, Color.GRAY) - 1) < 0.001, "a colour on itself is 1:1");

        if (failures != 0)
            throw new AssertionError(failures + " theme contrast failure(s)");
        System.out.println("ThemeContrastCheck: PASS");
    }

    private static void ratio(Theme theme, String what, Color a, Color b, double minimum) {
        double r = Theme.contrast(a, b);
        expect(r >= minimum, String.format("%s: %s is %.2f:1, needs %.1f:1 (%s on %s)",
                theme.name(), what, r, minimum, Theme.hex(a), Theme.hex(b)));
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
