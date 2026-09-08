package org.helioviewer.jhv.app;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A theme generated from one or two chosen colours contains those colours, and is still a theme
 * you can read.
 *
 * <p>{@code Theme.derived} puts the chosen accent on the Accent token and a chosen second colour
 * on the Background token, both exactly as they were picked, and turns the parent theme's colour
 * wheel around them: every other stated token moves to the accent's hue (or, for the lists and the
 * text, to the second colour's) and is then put back at the luminance it had. Both halves are
 * asserted here, and the second is not obvious: keeping HSB brightness across a hue change would
 * NOT keep the colour as light, because brightness is max(r,g,b) while luminance weights green
 * nine times as heavily as blue. A purple header carried to green at the same brightness comes out
 * far lighter than it was, its white title stops clearing 4.5:1, and the theme looks fine in the
 * swatches while being unreadable on screen. So this measures the derived themes rather than
 * inspecting them.
 *
 * <p>Over several hues including a grey (which means "take the colour out" rather than "move to
 * this hue"): that the picked colours come back verbatim, that every stated token survives the hex
 * round trip the themes file stores it as, that the text and band pairs still clear 4.5:1 and 3:1,
 * and that a token the parent chose as a near-neutral is still a near-neutral. That last one is the
 * failure a hue rotation invites: body text and section titles are greys, and painting them bright
 * green because the accent is green destroys the theme while passing every contrast rule.
 *
 * <p>Then the repair order, which is what a picked colour costs: where a chosen panel leaves a
 * parent token unreadable, the token nobody chose is the one that moves, and the two cases below
 * pin down both halves of that: the header band giving way to a chosen panel, and the one case
 * where nothing but the chosen colour is left to move, which the dialog has to report.
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
        derive(parent, "mid teal", new Color(0x33, 0x99, 0x99), null);
        // Darker than the panel it will sit on, so nothing about it is convenient. It is still the
        // colour that was asked for: the accent is a ground for no text and a floor for nothing,
        // and the alternative, dragging the panel up to it, would rewrite the whole theme.
        derive(parent, "very dark navy", new Color(0x0A, 0x1F, 0x44), null);
        // Two colours: green highlights over a warm brown panel, the case the second chooser is for.
        derive(parent, "green on brown", new Color(0x3C, 0xB0, 0x43), new Color(0x6B, 0x4A, 0x2F));
        // A grey accent has no hue to move to. It must not be read as "hue zero" and turn the
        // whole interface red; it means the colour comes out.
        derive(parent, "grey", new Color(0x80, 0x80, 0x80), null);

        // The light built-in too, since the repair step pushes text the other way there.
        Theme light = Theme.byIdOrDefault("sunset-light");
        derive(light, "light, forest green", new Color(0x22, 0x8B, 0x22), null);

        partnerMoves(parent);
        pickMoves(parent);

        System.out.println(failures == 0 ? "ThemeDeriveCheck: PASS" : "ThemeDeriveCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void derive(Theme parent, String what, Color accent, Color anchor) {
        List<String> adjusted = new ArrayList<>();
        Map<Theme.Token, Color> stated = Theme.derived(parent, accent, anchor, adjusted);
        expect(what + ": states all eight tokens", stated.keySet().containsAll(Theme.STATED));

        // (a) The colours that were picked are the colours that come back. This is the whole
        // complaint the feature had: a hue sample of forest green is not forest green, and a theme
        // derived from a colour that does not contain that colour anywhere is not what was asked
        // for. None of these need a repair, so none of them may be moved at all.
        expect(what + ": nothing had to be adjusted (" + String.join("; ", adjusted) + ")", adjusted.isEmpty());
        expect(what + ": the Accent token is exactly the colour picked, " + Theme.hex(accent),
                accent.equals(stated.get(Theme.Token.Accent)));
        if (anchor != null)
            expect(what + ": the Background token is exactly the second colour picked, " + Theme.hex(anchor),
                    anchor.equals(stated.get(Theme.Token.Background)));

        // (b) The themes file holds these as hex and rebuilds the theme from them on the next
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

        // (c) The pairs that decide whether the interface can be read.
        floors(what, reparsed);

        // (d) A grey the parent chose stays a grey. Not asked of a picked colour: that one is
        // whatever was picked, grey or not.
        for (Theme.Token token : Theme.STATED) {
            if (picked(token, anchor))
                continue;
            float was = saturation(parent.get(token));
            float now = saturation(derived.get(token));
            if (was < NEUTRAL_MAX)
                expect(String.format("%s: %s was a near-neutral (%.3f) and still is (%.3f)", what, token, was, now),
                        now < NEUTRAL_MAX);
        }

        // The point of matching luminance rather than brightness: the parent's contrast survives.
        // Eight-bit quantisation is the only thing that moves it, so a few percent, not a rule.
        // Two exemptions, and both are the feature working rather than failing: a picked colour
        // carries its own lightness, and a picked panel is the thing every one of these ratios is
        // measured against, so it moves all of them at once. What has to hold there is the floors
        // above, which are checked for every case here.
        if (anchor == null)
            for (Theme.Token token : Theme.STATED) {
                if (picked(token, null))
                    continue;
                double before = Theme.contrast(parent.get(token), parent.get(Theme.Token.Background));
                double after = Theme.contrast(derived.get(token), derived.get(Theme.Token.Background));
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

    /**
     * The repair seen from outside: a chosen panel the parent's header band cannot sit on.
     *
     * <p>Sunset Dark's band is 3.52:1 above a near-black panel; on this brown one it measures
     * 1.75:1 and cannot stay where the derivation put it. The band is the token nobody chose, so
     * the band is what moves, and both picked colours come back untouched.
     */
    private static void partnerMoves(Theme parent) {
        String what = "green on brown, repair";
        Color accent = new Color(0x3C, 0xB0, 0x43);
        Color anchor = new Color(0x6B, 0x4A, 0x2F);
        List<String> adjusted = new ArrayList<>();
        Map<Theme.Token, Color> m = Theme.derived(parent, accent, anchor, adjusted);

        expect(what + ": the panel is still the colour that was picked", anchor.equals(m.get(Theme.Token.Background)));
        expect(what + ": the accent is still the colour that was picked", accent.equals(m.get(Theme.Token.Accent)));
        expect(what + ": no picked colour was adjusted (" + String.join("; ", adjusted) + ")", adjusted.isEmpty());

        double was = luminance(parent.get(Theme.Token.HeaderFill));
        double now = luminance(m.get(Theme.Token.HeaderFill));
        expect(String.format("%s: the header band is what moved (luminance %.3f -> %.3f)", what, was, now),
                Math.abs(now - was) > 0.02);
        expect(String.format("%s: it had to, the parent's band is %.2f:1 on this panel",
                what, Theme.contrast(parent.get(Theme.Token.HeaderFill), anchor)),
                Theme.contrast(parent.get(Theme.Token.HeaderFill), anchor) < SURFACE_MIN);
        floors(what, m);
    }

    /**
     * The one case where the chosen colour itself has to move, and the reason the dialog has a line
     * to say so.
     *
     * <p>A mid-grey panel: white text only reaches 3.95:1 on it, so no light body text clears
     * 4.5:1, and dark body text cannot be read on the lists, which keep the parent's near-black.
     * The two grounds are on opposite sides of the text and nothing that was not picked is left to
     * move, so the panel gives way, as little as it takes, and the derivation reports it.
     */
    private static void pickMoves(Theme parent) {
        String what = "mid-grey panel";
        Color accent = new Color(0x22, 0x8B, 0x22);
        Color anchor = new Color(0x80, 0x80, 0x80);
        List<String> adjusted = new ArrayList<>();
        Map<Theme.Token, Color> m = Theme.derived(parent, accent, anchor, adjusted);

        expect(what + ": the panel could not be kept and the derivation says so " + adjusted,
                adjusted.size() == 1 && adjusted.getFirst().contains(Theme.Token.Background.label));
        expect(what + ": the panel moved, " + Theme.hex(anchor) + " -> " + Theme.hex(m.get(Theme.Token.Background)),
                !anchor.equals(m.get(Theme.Token.Background)));
        // As little as it takes: the pick is darkened past the 4.5:1 line, not replaced. The line
        // itself is 0.08 of luminance below the pick, so anything under 0.15 is a step and not a
        // second opinion about what colour the panel should be.
        expect(String.format("%s: it moved a step, not a theme (luminance %.3f -> %.3f)",
                what, luminance(anchor), luminance(m.get(Theme.Token.Background))),
                Math.abs(luminance(anchor) - luminance(m.get(Theme.Token.Background))) < 0.15);
        // And it is the picked colour darkened, not some other colour: mixing toward black scales
        // the channels together, so the hue and the saturation of the pick come through.
        expect(what + ": what moved is the picked colour itself, taken down",
                Math.abs(saturation(m.get(Theme.Token.Background)) - saturation(anchor)) < 0.02);
        // The accent was not the problem, so the accent is untouched.
        expect(what + ": the accent is still the colour that was picked", accent.equals(m.get(Theme.Token.Accent)));
        floors(what, m);
    }

    /** The pairs that decide whether the interface can be read at all. */
    private static void floors(String what, Map<Theme.Token, Color> m) {
        Color panel = m.get(Theme.Token.Background);
        Color list = m.get(Theme.Token.Component);
        Color title = m.get(Theme.Token.HeaderText);

        ratio(what, "body text on the panel", m.get(Theme.Token.Foreground), panel, TEXT_MIN);
        ratio(what, "body text on a list", m.get(Theme.Token.Foreground), list, TEXT_MIN);
        ratio(what, "header text on its band", title, m.get(Theme.Token.HeaderFill), TEXT_MIN);
        ratio(what, "header text on a nested band", title, m.get(Theme.Token.ChildHeaderFill), TEXT_MIN);
        ratio(what, "header band on the panel", m.get(Theme.Token.HeaderFill), panel, SURFACE_MIN);
        ratio(what, "nested band on the panel", m.get(Theme.Token.ChildHeaderFill), panel, SURFACE_MIN);
        ratio(what, "separator on the panel", m.get(Theme.Token.Separator), panel, SURFACE_MIN);
    }

    /** The tokens a derivation takes literally from what was chosen. */
    private static boolean picked(Theme.Token token, Color anchor) {
        return token == Theme.Token.Accent || (anchor != null && token == Theme.Token.Background);
    }

    /** WCAG relative luminance, read back out of the contrast formula against black. */
    private static double luminance(Color c) {
        return Theme.contrast(c, Color.BLACK) * 0.05 - 0.05;
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
