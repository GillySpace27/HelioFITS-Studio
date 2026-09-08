package org.helioviewer.jhv.app;

import java.awt.Color;
import java.util.Map;

/**
 * A theme generated from one or two chosen colours is still a theme you can read.
 *
 * <p>{@code Theme.derived} turns a parent theme's colour wheel: every stated token moves to the
 * hue of the chosen accent (or, for the panels and the lists, of a second chosen colour) and is
 * then put back at the luminance it had. The claim that makes it safe is the second half, and it
 * is not obvious: keeping HSB brightness across a hue change would NOT keep the colour as light,
 * because brightness is max(r,g,b) while luminance weights green nine times as heavily as blue.
 * A purple header carried to green at the same brightness comes out far lighter than it was, its
 * white title stops clearing 4.5:1, and the theme looks fine in the swatches while being
 * unreadable on screen. So this measures the derived themes rather than inspecting them.
 *
 * <p>Three things, over several hues including a grey (which means "take the colour out" rather
 * than "move to this hue"): that every stated token survives the hex round trip the themes file
 * stores it as, that the text and band pairs still clear 4.5:1 and 3:1, and that a token the
 * parent chose as a near-neutral is still a near-neutral. That last one is the failure a hue
 * rotation invites: body text and section titles are greys, and painting them bright green
 * because the accent is green destroys the theme while passing every contrast rule.
 *
 * <p>Pure arithmetic over {@code Theme.builtIns()}: no window, and nothing written to the themes
 * file, so it can run anywhere.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.app.ThemeDeriveCheck
 */
public final class ThemeDeriveCheck {

    // The two ratios Theme.derived enforces, and the one ThemeDialog reports: WCAG 2.1 AA for
    // text and the same standard's non-text minimum for a band or a hairline. Spelled out rather
    // than read back from Theme, so that a threshold quietly lowered there fails here.
    private static final double TEXT_MIN = 4.5;
    private static final double SURFACE_MIN = 3;

    // Theme's own definition of a near-neutral. Same reason.
    private static final float NEUTRAL_MAX = 0.18f;

    private static int failures;

    public static void main(String[] args) {
        Theme parent = Theme.byIdOrDefault(Theme.DEFAULT_ID);
        expect("the parent is " + Theme.DEFAULT_ID, parent.id().equals(Theme.DEFAULT_ID));

        derive(parent, "forest green", new Color(0x22, 0x8B, 0x22), null);
        derive(parent, "amber", new Color(0xD9, 0x8C, 0x29), null);
        derive(parent, "deep teal", new Color(0x0F, 0x6E, 0x6E), null);
        // Two colours: green highlights over a warm brown panel, the case the second chooser is for.
        derive(parent, "green on brown", new Color(0x3C, 0xB0, 0x43), new Color(0x6B, 0x4A, 0x2F));
        // A grey accent has no hue to move to. It must not be read as "hue zero" and turn the
        // whole interface red; it means the colour comes out.
        derive(parent, "grey", new Color(0x80, 0x80, 0x80), null);

        // The light built-in too, since the repair step pushes text the other way there.
        Theme light = Theme.byIdOrDefault("sunset-light");
        derive(light, "light, forest green", new Color(0x22, 0x8B, 0x22), null);

        System.out.println(failures == 0 ? "ThemeDeriveCheck: PASS" : "ThemeDeriveCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void derive(Theme parent, String what, Color accent, Color anchor) {
        Map<Theme.Token, Color> stated = Theme.derived(parent, accent, anchor);
        expect(what + ": states all eight tokens", stated.keySet().containsAll(Theme.STATED));

        // (a) The themes file holds these as hex and rebuilds the theme from them on the next
        // launch, so a colour that does not survive #RRGGBB is a theme that changes overnight.
        java.util.EnumMap<Theme.Token, Color> reparsed = new java.util.EnumMap<>(Theme.Token.class);
        for (Theme.Token token : Theme.STATED) {
            Color c = stated.get(token);
            Color back = c == null ? null : Color.decode(Theme.hex(c));
            expect(what + ": " + token + " round trips through " + (c == null ? "null" : Theme.hex(c)),
                    c != null && c.equals(back));
            if (back != null)
                reparsed.put(token, back);
        }
        if (reparsed.size() != Theme.STATED.size())
            return;

        Theme derived = Theme.userTheme("user-check", "Check", parent, reparsed);
        for (Theme.Token token : Theme.Token.values())
            expect(what + ": " + token + " resolves", derived.get(token) != null);

        Color panel = derived.get(Theme.Token.Background);
        Color list = derived.get(Theme.Token.Component);
        Color title = derived.get(Theme.Token.HeaderText);

        // (b) The pairs that decide whether the interface can be read.
        ratio(what, "body text on the panel", derived.get(Theme.Token.Foreground), panel, TEXT_MIN);
        ratio(what, "body text on a list", derived.get(Theme.Token.Foreground), list, TEXT_MIN);
        ratio(what, "header text on its band", title, derived.get(Theme.Token.HeaderFill), TEXT_MIN);
        ratio(what, "header text on a nested band", title, derived.get(Theme.Token.ChildHeaderFill), TEXT_MIN);
        ratio(what, "header band on the panel", derived.get(Theme.Token.HeaderFill), panel, SURFACE_MIN);
        ratio(what, "nested band on the panel", derived.get(Theme.Token.ChildHeaderFill), panel, SURFACE_MIN);
        ratio(what, "separator on the panel", derived.get(Theme.Token.Separator), panel, SURFACE_MIN);

        // (c) A grey the parent chose stays a grey.
        for (Theme.Token token : Theme.STATED) {
            float was = saturation(parent.get(token));
            float now = saturation(derived.get(token));
            if (was < NEUTRAL_MAX)
                expect(String.format("%s: %s was a near-neutral (%.3f) and still is (%.3f)", what, token, was, now),
                        now < NEUTRAL_MAX);
        }

        // The point of matching luminance rather than brightness: the parent's contrast survives.
        // Eight-bit quantisation is the only thing that moves it, so a few percent, not a rule.
        for (Theme.Token token : Theme.STATED) {
            double before = Theme.contrast(parent.get(token), parent.get(Theme.Token.Background));
            double after = Theme.contrast(derived.get(token), panel);
            expect(String.format("%s: %s keeps its ratio against the panel (%.2f -> %.2f)", what, token, before, after),
                    Math.abs(after - before) <= 0.05 * before + 0.02);
        }

        // And the derivation actually did something: the accent carries the chosen hue, unless the
        // chosen colour is a grey, in which case there was no hue to carry.
        float chosen = saturation(accent);
        if (chosen >= NEUTRAL_MAX) {
            float want = Color.RGBtoHSB(accent.getRed(), accent.getGreen(), accent.getBlue(), null)[0];
            float got = Color.RGBtoHSB(derived.get(Theme.Token.Accent).getRed(),
                    derived.get(Theme.Token.Accent).getGreen(), derived.get(Theme.Token.Accent).getBlue(), null)[0];
            expect(String.format("%s: the accent moved to the chosen hue (%.3f vs %.3f)", what, got, want),
                    Math.abs(got - want) < 0.02 || Math.abs(got - want) > 0.98);
        } else {
            expect(String.format("%s: a grey choice drains the accent instead of reddening it (%.3f)",
                    what, saturation(derived.get(Theme.Token.Accent))),
                    saturation(derived.get(Theme.Token.Accent)) < saturation(parent.get(Theme.Token.Accent)));
        }
    }

    private static float saturation(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[1];
    }

    private static void ratio(String what, String pair, Color a, Color b, double minimum) {
        double r = Theme.contrast(a, b);
        expect(String.format("%s: %s is %.2f:1, needs %.1f:1 (%s on %s)",
                what, pair, r, minimum, Theme.hex(a), Theme.hex(b)), r >= minimum);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private ThemeDeriveCheck() {}

}
