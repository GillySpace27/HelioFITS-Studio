package org.helioviewer.jhv.gui.component;

import java.awt.Color;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;

/**
 * Easier to find panels: how much louder than the theme's own values a top-level section is drawn.
 *
 * <p>Independent options (the stronger headers are four of them: band, rule, space, title colour) under one master switch, all on by default, all in {@link Settings}
 * rather than in a theme, so they follow whichever theme is chosen and a theme derived from an
 * accent colour keeps them. Everything below is computed from the current theme's tokens and
 * clamped so the header title keeps 4.5:1 (3.5:1 for the strong title) on the band and the band and stripe keep 3:1, which is
 * what lets one set of rules serve built-in and user themes alike. With the master switch off
 * every value here is the theme's own and the look is exactly what it was before.
 */
public final class PanelVisibility {

    public static final String MASTER = "ui.visibility.enabled";
    /**
     * The old single switch for stronger section headers. It did four things at once, so each is now its
     * own option below; a setting left unset follows this key, so a stored "false" keeps all four off.
     */
    public static final String HEADERS = "ui.visibility.headers";
    public static final String HEADER_BAND = "ui.visibility.headerBand";
    public static final String HEADER_RULE = "ui.visibility.headerRule";
    public static final String HEADER_GAP = "ui.visibility.headerGap";
    public static final String HEADER_TEXT = "ui.visibility.headerText";
    public static final String STRIPE = "ui.visibility.stripe";
    public static final String NUMBERS = "ui.visibility.numbers";
    public static final String HOVER = "ui.visibility.hover";
    public static final String SCROLLBAR = "ui.visibility.scrollbar";

    /** The scroll bar width Studio has always had, and the narrowest the slider offers. */
    public static final int SCROLLBAR_DEFAULT = 10;
    public static final int SCROLLBAR_MAX = 30;

    private static final java.util.Set<java.awt.Component> scrollBars =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** How far above the 3:1 floor a stronger band tries to get, where the title can still be read on it. */
    static final double STRONG_BAND = 4.5;

    private PanelVisibility() {
    }

    private static boolean on(String key) {
        return !"false".equals(Settings.getProperty(MASTER)) && !"false".equals(Settings.getProperty(key));
    }

    /** The four stronger-header options: an unset one follows the old combined switch, which is on unless stored as false. */
    private static boolean header(String key) {
        if (!enabled())
            return false;
        String v = Settings.getProperty(key);
        return v == null ? !"false".equals(Settings.getProperty(HEADERS)) : !"false".equals(v);
    }

    /** The stored state of one option for the settings window, ignoring the master switch: an unset header option follows the old combined one. */
    public static boolean optionValue(String key) {
        String v = Settings.getProperty(key);
        boolean headerKey = HEADER_BAND.equals(key) || HEADER_RULE.equals(key) || HEADER_GAP.equals(key) || HEADER_TEXT.equals(key);
        if (v == null && headerKey)
            v = Settings.getProperty(HEADERS);
        return !"false".equals(v);
    }

    /** A bolder band: pushed further from the panel colour until it is clearly a band. */
    public static boolean headerBand() {
        return header(HEADER_BAND);
    }

    /** A thicker rule under each top-level section. */
    public static boolean headerRule() {
        return header(HEADER_RULE);
    }

    /** More space between top-level sections. */
    public static boolean headerGap() {
        return header(HEADER_GAP);
    }

    /** The Section header text (strong) colour for the title, held to 3.5:1. */
    public static boolean headerText() {
        return header(HEADER_TEXT);
    }

    /** Any of the four stronger-header options is on. */
    public static boolean headers() {
        return headerBand() || headerRule() || headerGap() || headerText();
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

    /** Width of the sidebars' and palettes' scroll bars in pixels: wide enough to grab for those who scroll by dragging. */
    public static int scrollBarWidth() {
        if (!enabled())
            return SCROLLBAR_DEFAULT;
        try {
            return Math.clamp(Integer.parseInt(Settings.getProperty(SCROLLBAR)), SCROLLBAR_DEFAULT, SCROLLBAR_MAX);
        } catch (NumberFormatException e) {
            return SCROLLBAR_DEFAULT; // unset, or not a number
        }
    }

    /** Give a sidebar or palette scroll bar the chosen width, and keep it following the setting. */
    public static void styleScrollBar(javax.swing.JScrollBar bar) {
        scrollBars.add(bar);
        applyScrollBar(bar);
    }

    private static void applyScrollBar(javax.swing.JScrollBar bar) {
        int w = scrollBarWidth();
        bar.setPreferredSize(bar.getOrientation() == javax.swing.JScrollBar.VERTICAL
                ? new java.awt.Dimension(w, 0) : new java.awt.Dimension(0, w));
        bar.revalidate();
    }

    /** After the setting changes: every scroll bar styled so far takes the new width. */
    public static void refreshScrollBars() {
        for (java.awt.Component bar : new java.util.ArrayList<>(scrollBars))
            applyScrollBar((javax.swing.JScrollBar) bar);
    }

    public static boolean enabled() {
        return !"false".equals(Settings.getProperty(MASTER));
    }

    /** Thickness of the rule under a top-level section. */
    public static int separatorThickness() {
        return headerRule() ? 2 : 1;
    }

    /** Ground under a top-level section, between its last control and the next band. */
    public static int sectionGap() {
        return headerGap() ? 9 : 5;
    }

    /** Width of the accent stripe, 0 when there is none. */
    public static int stripeWidth() {
        return stripe() ? 4 : 0;
    }

    /** The top-level band, pushed away from the panel until it is clearly a band, if the title still reads on it. */
    public static Color headerFill(Theme theme, boolean child) {
        Color base = theme.get(Theme.Token.HeaderFill);
        Color band = base;
        if (headerBand()) {
            Color panel = theme.get(Theme.Token.Background);
            Color text = theme.get(headerText() ? Theme.Token.HeaderTextStrong : Theme.Token.HeaderText);
            double textMin = headerText() ? Theme.STRONG_TEXT_MIN : Theme.TEXT_MIN;
            Color away = theme.get(Theme.Token.Foreground);
            for (double f = 0.05; f <= 0.6 && Theme.contrast(band, panel) < STRONG_BAND; f += 0.05) {
                Color c = Theme.mix(base, away, f);
                if (Theme.contrast(Theme.legibleOn(text, c, textMin), c) < textMin)
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

    /** The title colour for a band, held to 4.5:1 (3.5:1 for the strong one) on whichever fill is in use: the strong one when headers are strong. */
    public static Color headerText(Theme theme, Color fill) {
        if (headerText())
            return Theme.legibleOn(theme.get(Theme.Token.HeaderTextStrong), fill, Theme.STRONG_TEXT_MIN);
        return Theme.legibleOn(theme.get(Theme.Token.HeaderText), fill, Theme.TEXT_MIN);
    }

    /**
     * The box behind a section's key number while its modifier is held: the theme's accent, pushed
     * toward white or toward black until the number's text has 4.5:1 and the box has 3:1 against the
     * band, whichever of the two ends up standing out most from the band it is on. Returns {fill, text}. The hue stays the
     * accent's, so it belongs to the theme, but the box is a pastel or a deep tone rather than the
     * raw accent, which keeps it clear of garish.
     */
    public static Color[] badgeColors(Theme theme, Color band) {
        Color accent = theme.get(Theme.Token.Accent);
        Color[] best = null;
        double bestScore = -1;
        for (Color text : new Color[]{Color.WHITE, Color.BLACK}) {
            Color away = text.equals(Color.WHITE) ? Color.BLACK : Color.WHITE; // push the fill away from the text colour
            Color fill = accent;
            // far enough for the number (4.5:1) and for the box to stand out from the band (3:1), if the hue allows
            for (double f = 0.04; (Theme.contrast(text, fill) < Theme.TEXT_MIN || Theme.contrast(fill, band) < Theme.SURFACE_MIN) && f <= 1.0001; f += 0.04)
                fill = Theme.mix(accent, away, Math.min(1, f));
            double score = Theme.contrast(fill, band);
            if (Theme.contrast(text, fill) >= Theme.TEXT_MIN && score > bestScore) {
                bestScore = score;
                best = new Color[]{fill, text};
            }
        }
        return best;
    }

    /** The accent stripe, held to 3:1 against the band it sits on. */
    public static Color stripeColor(Theme theme, Color fill) {
        return Theme.legibleOn(theme.get(Theme.Token.Accent), fill, Theme.SURFACE_MIN);
    }

    /** The band under the pointer: one step toward the text colour, if the title still reads on it. */
    public static Color hoverFill(Theme theme, Color fill) {
        double min = headerText() ? Theme.STRONG_TEXT_MIN : Theme.TEXT_MIN;
        Color text = headerText(theme, fill);
        for (double f : new double[]{0.16, 0.10, 0.06}) {
            Color c = Theme.mix(fill, theme.get(Theme.Token.Foreground), f);
            if (Theme.contrast(text, c) >= min)
                return c;
        }
        return fill;
    }

    /** The "N. " in front of a title, or "" when this section has no number. */
    public static String numberPrefix(int number) {
        return numbers() && number > 0 ? number + ". " : "";
    }

    /** "L2. " or "R2. ": the number and the side it counts on, which is also what the keys say (Gilly, 2026-11-08). */
    public static String numberPrefix(int number, char side) {
        return numbers() && number > 0 ? side + "" + number + ". " : "";
    }

}
