package org.helioviewer.jhv.gui.component;

import java.awt.Color;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;

/**
 * Easier to find panels: how much louder than the theme's own values a top-level section is drawn.
 *
 * <p>Four independent options under one master switch, all on by default, all in {@link Settings}
 * rather than in a theme, so they follow whichever theme is chosen and a theme derived from an
 * accent colour keeps them. Everything below is computed from the current theme's tokens and
 * clamped so the header title keeps 4.5:1 on the band and the band and stripe keep 3:1, which is
 * what lets one set of rules serve built-in and user themes alike. With the master switch off
 * every value here is the theme's own and the look is exactly what it was before.
 */
public final class PanelVisibility {

    public static final String MASTER = "ui.visibility.enabled";
    public static final String HEADERS = "ui.visibility.headers";
    public static final String STRIPE = "ui.visibility.stripe";
    public static final String NUMBERS = "ui.visibility.numbers";
    public static final String HOVER = "ui.visibility.hover";

    /** How far above the 3:1 floor a stronger band tries to get, where the title can still be read on it. */
    static final double STRONG_BAND = 4.5;

    private PanelVisibility() {
    }

    private static boolean on(String key) {
        return !"false".equals(Settings.getProperty(MASTER)) && !"false".equals(Settings.getProperty(key));
    }

    public static boolean headers() {
        return on(HEADERS);
    }

    public static boolean stripe() {
        return on(STRIPE);
    }

    public static boolean numbers() {
        return on(NUMBERS);
    }

    public static boolean hover() {
        return on(HOVER);
    }

    public static boolean enabled() {
        return !"false".equals(Settings.getProperty(MASTER));
    }

    /** Thickness of the rule under a top-level section. */
    public static int separatorThickness() {
        return headers() ? 2 : 1;
    }

    /** Ground under a top-level section, between its last control and the next band. */
    public static int sectionGap() {
        return headers() ? 9 : 5;
    }

    /** Width of the accent stripe, 0 when there is none. */
    public static int stripeWidth() {
        return stripe() ? 4 : 0;
    }

    /** The top-level band, pushed away from the panel until it is clearly a band, if the title still reads on it. */
    public static Color headerFill(Theme theme, boolean child) {
        Color base = theme.get(Theme.Token.HeaderFill);
        Color band = base;
        if (headers()) {
            Color panel = theme.get(Theme.Token.Background);
            Color text = theme.get(Theme.Token.HeaderTextStrong);
            Color away = theme.get(Theme.Token.Foreground);
            for (double f = 0.05; f <= 0.6 && Theme.contrast(band, panel) < STRONG_BAND; f += 0.05) {
                Color c = Theme.mix(base, away, f);
                if (Theme.contrast(Theme.legibleOn(text, c, Theme.TEXT_MIN), c) < Theme.TEXT_MIN)
                    break; // any further and the title cannot be made to read, even brightened
                band = c;
            }
        }
        if (!child)
            return band;
        if (band.equals(base))
            return theme.get(Theme.Token.ChildHeaderFill);
        // The same step down from the stronger band that the theme takes from its own.
        Color panel = theme.get(Theme.Token.Background);
        Color nested = Theme.mix(band, panel, 0.22);
        return Theme.legibleOn(nested, panel, Theme.SURFACE_MIN);
    }

    /** The title colour for a band, held to 4.5:1 on whichever fill is in use: the strong one when headers are strong. */
    public static Color headerText(Theme theme, Color fill) {
        Theme.Token token = headers() ? Theme.Token.HeaderTextStrong : Theme.Token.HeaderText;
        return Theme.legibleOn(theme.get(token), fill, Theme.TEXT_MIN);
    }

    /** The accent stripe, held to 3:1 against the band it sits on. */
    public static Color stripeColor(Theme theme, Color fill) {
        return Theme.legibleOn(theme.get(Theme.Token.Accent), fill, Theme.SURFACE_MIN);
    }

    /** The band under the pointer: one step toward the text colour, if the title still reads on it. */
    public static Color hoverFill(Theme theme, Color fill) {
        Color text = theme.get(Theme.Token.HeaderText);
        for (double f : new double[]{0.16, 0.10, 0.06}) {
            Color c = Theme.mix(fill, theme.get(Theme.Token.Foreground), f);
            if (Theme.contrast(text, c) >= Theme.TEXT_MIN)
                return c;
        }
        return fill;
    }

    /** The "N. " in front of a title, or "" when this section has no number. */
    public static String numberPrefix(int number) {
        return numbers() && number > 0 ? number + ". " : "";
    }

}
