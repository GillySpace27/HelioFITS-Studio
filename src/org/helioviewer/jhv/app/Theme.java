package org.helioviewer.jhv.app;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.io.Directories;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * A named set of interface colours, and the table of the built-in ones.
 *
 * <p>This replaces a two-valued Dark/Light switch. The switch was not the problem; what was
 * missing is a place where the colours the application paints by hand live as data, so that they
 * can be checked, overridden and changed while the program runs. Everything the code used to
 * derive on the spot (a border from {@code getBackground().brighter()}, a header gradient from
 * two static finals) came out at one or two percent contrast against the surface behind it and
 * was invisible. Named tokens can be measured: {@code ThemeContrastCheck} does exactly that over
 * this table and refuses a theme whose section headers would disappear.
 *
 * <p>Eight tokens are stated by every theme; the seven timeline tokens are derived from those
 * unless a theme states them, which is how a user theme can restate one colour and have the
 * timeline follow it.
 *
 * <p>Two of the four built-ins sit on FlatLaf's own dark and light look-and-feels, where the
 * colours below are pushed in as {@code @background} and friends and cascade through every
 * component. The two Classic themes sit on the IntelliJ themes the application shipped with,
 * whose bundled JSON pins its colours as literal hex: overriding {@code @background} there
 * changes nothing. Their token values are therefore not instructions but a record of what those
 * themes actually produce, read out of UIManager, so that the hand-painted surfaces and the
 * contrast check agree with the screen.
 */
public final class Theme {

    /** Which look-and-feel a theme installs, and whether that look-and-feel is a dark one. */
    public enum Base {
        ClassicDark(true), ClassicLight(false), FlatDark(true), FlatLight(false);

        public final boolean dark;

        Base(boolean _dark) {
            dark = _dark;
        }

        /** Whether the tokens below reach the look-and-feel, or only the surfaces JHV paints itself. */
        boolean tokensReachLaf() {
            return this == FlatDark || this == FlatLight;
        }
    }

    /**
     * One colour with a job. The description is what the customizer shows, so it has to say what
     * the token does rather than repeat its name.
     */
    public enum Token {
        Background("Panel background", "Every panel, and the ground behind the controls."),
        Foreground("Text", "Body text on a panel."),
        Accent("Accent", "Selection and focus, and the tint of the selected timeline interval."),
        Component("List background", "Lists, tables, trees and text fields."),
        Separator("Separator", "The hairline between a list and the panel above it. Needs 3:1 against the panel or it is not there."),
        HeaderFill("Section header", "The band behind a collapsible section title. Needs 3:1 against the panel."),
        HeaderText("Section header text", "The section title itself. Needs 4.5:1 against the band it sits on."),
        ChildHeaderFill("Nested section header", "The band behind an indented sub-section. Same two rules as the parent."),

        TimelinePlot("Timeline plot", "The ground of the timeline's interval strip."),
        TimelineInterval("Timeline selected interval", "The span of the strip that is actually being plotted."),
        TimelineIntervalBorder("Timeline interval edge", "The grasp bars at each end of that span."),
        TimelineTick("Timeline tick", "Tick marks along the time axis."),
        TimelineLabel("Timeline label", "Text along the time axis."),
        TimelineMovie("Timeline movie line", "The line marking the current movie frame."),
        TimelineMovieBand("Timeline movie band", "The hatched band showing how much of the interval the movie covers.");

        public final String label;
        public final String description;

        Token(String _label, String _description) {
            label = _label;
            description = _description;
        }
    }

    /** The tokens every theme must state. The rest are derived from these. */
    public static final EnumSet<Token> STATED = EnumSet.of(
            Token.Background, Token.Foreground, Token.Accent, Token.Component,
            Token.Separator, Token.HeaderFill, Token.HeaderText, Token.ChildHeaderFill);

    private static final String FILE_NAME = "themes.json";
    private static final String SETTING = "display.theme"; // the theme in effect, whichever chose it
    private static final String MODE_SETTING = "display.themeMode";
    private static final String DARK_SETTING = "display.theme.dark";
    private static final String LIGHT_SETTING = "display.theme.light";

    /** Chosen when nothing is stored. Gilly's call: the purple one, not the grey one. */
    public static final String DEFAULT_ID = "sunset-dark";
    public static final String DEFAULT_LIGHT_ID = "sunset-light";

    /**
     * Which of the two chosen themes is in effect.
     *
     * <p>Dark and Light are a theme each, kept separately, so following the desktop is a real
     * choice of two rather than a switch between one theme and whatever the other built-in
     * happens to be. System asks the desktop and picks between them.
     */
    public enum Mode {
        Dark("Dark"), Light("Light"), System("Follow system");

        private final String label;

        Mode(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final String id;
    private final String name;
    private final Base base;
    @Nullable private final String parentId; // the built-in a user theme states its overrides against
    private final EnumMap<Token, Color> stated;
    private final EnumMap<Token, Color> colors;

    private Theme(String _id, String _name, Base _base, @Nullable String _parentId, EnumMap<Token, Color> _stated) {
        id = _id;
        name = _name;
        base = _base;
        parentId = _parentId;
        stated = _stated;
        colors = resolve(_stated);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Base base() {
        return base;
    }

    public boolean dark() {
        return base.dark;
    }

    public boolean builtIn() {
        return parentId == null;
    }

    @Nullable
    public String parentId() {
        return parentId;
    }

    public Color get(Token token) {
        return colors.get(token);
    }

    /** Every token this theme states outright, so the customizer can show what was changed. */
    public Map<Token, Color> stated() {
        return stated;
    }

    /**
     * The FlatLaf variables to install before the look-and-feel is set up.
     *
     * <p>Empty for the Classic themes on purpose: their IntelliJ base pins its colours as literal
     * hex, so these would be accepted and then ignored, which is worse than not offering them.
     */
    public Map<String, String> lafDefaults() {
        if (!base.tokensReachLaf())
            return Map.of();
        return Map.of(
                "@background", hex(get(Token.Background)),
                "@foreground", hex(get(Token.Foreground)),
                "@componentBackground", hex(get(Token.Component)),
                // Without this the platform accent wins on macOS and the theme's own selection
                // colour never appears.
                "@accentColor", hex(get(Token.Accent)));
    }

    // -- derivation ------------------------------------------------------------------------

    private static EnumMap<Token, Color> resolve(Map<Token, Color> stated) {
        for (Token t : STATED)
            if (!stated.containsKey(t))
                throw new IllegalArgumentException("theme does not state " + t);

        EnumMap<Token, Color> out = new EnumMap<>(stated);
        for (Token t : Token.values())
            if (!out.containsKey(t))
                out.put(t, derive(t, out));
        return out;
    }

    // Every derivation reads only stated tokens, so the order they are filled in cannot matter.
    private static Color derive(Token t, Map<Token, Color> c) {
        return switch (t) {
            // A nested header pulled back toward the panel, so it reads as subordinate. Pulled
            // only part of the way: past about a third it stops clearing 3:1 against the panel.
            case ChildHeaderFill -> mix(c.get(Token.HeaderFill), c.get(Token.Background), 0.22);
            case TimelinePlot -> c.get(Token.Component);
            // A tenth of the accent, not half of it. At 0.45 the plotted span became a lavender
            // wash across the whole strip, and the four coloured layer bands drawn on it lost most
            // of the contrast they had against the near-black ground they used to sit on.
            case TimelineInterval -> mix(c.get(Token.Component), c.get(Token.Accent), 0.14);
            case TimelineIntervalBorder, TimelineTick -> c.get(Token.Separator);
            case TimelineLabel, TimelineMovie -> c.get(Token.Foreground);
            case TimelineMovieBand -> mix(c.get(Token.Component), c.get(Token.Foreground), 0.55);
            default -> throw new IllegalArgumentException(t + " is stated, not derived");
        };
    }

    public static Color mix(Color a, Color b, double f) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * f),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f));
    }

    // -- contrast --------------------------------------------------------------------------

    /** Relative luminance, WCAG 2.1 definition. */
    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG 2.1 contrast ratio, 1 to 21. */
    public static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return la > lb ? (la + 0.05) / (lb + 0.05) : (lb + 0.05) / (la + 0.05);
    }

    // -- derivation from a chosen colour ---------------------------------------------------

    /**
     * WCAG 2.1 AA for text, and the same standard's non-text minimum for a band or a hairline.
     *
     * <p>4.5:1 and 3:1 are not picked for this feature; they are the numbers
     * {@code ThemeContrastCheck} already holds the built-ins to and {@code ThemeDialog} reports
     * live. Holding a derived theme to the same pair is the point: a theme generated in two
     * clicks must not be allowed to be less legible than one built by hand.
     */
    public static final double TEXT_MIN = 4.5;
    public static final double SURFACE_MIN = 3;

    /** Below this saturation a colour counts as a neutral, and the most tint a neutral may take. */
    private static final float NEUTRAL_MAX = 0.18f;
    private static final float NEUTRAL_TINT = 0.10f;

    /** How far a coloured token's saturation is pulled toward the chosen colour's. */
    private static final float SAT_PULL = 0.35f;

    /**
     * The tokens that follow the second chosen colour when one is given.
     *
     * <p>The panels and the lists, plus the text that sits on them: body text is a near-neutral in
     * every built-in, so it takes a whisper of whichever hue is behind it, and a warm panel wants
     * warm-tinted text rather than text tinted like the highlights. Everything else (the header
     * bands, the separator) is the highlight family and follows the accent. Background is in this
     * set for the one-colour case; when a second colour is given it is that colour outright, and
     * only the other three are retinted from it.
     */
    private static final EnumSet<Token> SURFACE = EnumSet.of(
            Token.Background, Token.Component, Token.Foreground, Token.HeaderText);

    /**
     * A parent theme's stated colours moved to one or two chosen ones.
     *
     * <p>The chosen colours are used literally on the tokens they were chosen for: the accent IS
     * {@link Token#Accent}, and a second colour IS {@link Token#Background}. Sampling their hue
     * onto the parent's own accent instead, which is what this did first, produced a theme that
     * nowhere contained the colour the person picked: forest green came back as the parent's
     * accent lightness in a green hue, and the complaint was exactly that.
     *
     * <p>Everything else still follows them by hue and something of their saturation, and is put
     * back at the parent token's luminance, so every contrast ratio the parent held between those
     * tokens survives. Only the two picked tokens carry a lightness of their own, and only they
     * can move a ratio measured against them.
     *
     * @param accent the Accent token itself, and the hue of the header bands and the separator
     * @param anchor the Background token itself, and the hue of the lists and the text, or null
     *               to give those the accent's hue and leave the panel at the parent's lightness
     * @param adjusted collects one line for any picked colour a contrast floor forced to move
     */
    public static EnumMap<Token, Color> derived(Theme parent, Color accent, @Nullable Color anchor, List<String> adjusted) {
        float[] high = hsb(accent);
        float[] surf = anchor == null ? high : hsb(anchor);

        EnumMap<Token, Color> out = new EnumMap<>(Token.class);
        for (Token t : STATED) {
            float[] src = SURFACE.contains(t) ? surf : high;
            out.put(t, retint(parent.get(t), src[0], src[1]));
        }
        // Last, over the retinted stand-ins: the two colours someone actually chose, unaltered.
        out.put(Token.Accent, accent);
        if (anchor != null)
            out.put(Token.Background, anchor);
        enforce(out, adjusted);
        return out;
    }

    /** As above, for a caller with nowhere to show what had to be adjusted. */
    public static EnumMap<Token, Color> derived(Theme parent, Color accent, @Nullable Color anchor) {
        return derived(parent, accent, anchor, new ArrayList<>());
    }

    private static float[] hsb(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
    }

    /** One token at a new hue, as light as it was. */
    private static Color retint(Color from, float targetHue, float targetSat) {
        float[] c = hsb(from);
        float s = c[1];
        float hue, sat;
        if (targetSat < NEUTRAL_MAX) {
            // The chosen colour is itself a grey, so there is no hue to move to and the request is
            // really "take the colour out". Every token keeps its own hue and gives up saturation.
            hue = c[0];
            sat = s + (targetSat - s) * SAT_PULL;
        } else if (s < NEUTRAL_MAX) {
            // A near-neutral takes the hue but not the colour: greys must not come back bright
            // green. Held to a tint, and given at least half of one so that a theme built out of
            // pure greys still shifts rather than ignoring the choice entirely.
            hue = targetHue;
            sat = Math.min(NEUTRAL_TINT, Math.max(s, NEUTRAL_TINT / 2));
        } else {
            hue = targetHue;
            sat = s + (targetSat - s) * SAT_PULL;
        }
        return atLuminance(hue, sat, luminance(from));
    }

    /**
     * The colour at this hue and saturation that is as light as {@code target}.
     *
     * <p>Not the parent's HSB brightness, which is what "keep the brightness" would mean and is
     * wrong: brightness is max(r,g,b), while luminance weights green nine times as heavily as
     * blue, so a purple carried over to green at the same brightness comes out far lighter and
     * whatever it was legible against stops being legible. Matching luminance instead makes every
     * contrast ratio in the parent theme survive the derivation intact, since contrast is a
     * function of luminance alone. That is the property the whole feature rests on.
     *
     * <p>Luminance rises monotonically with brightness at a fixed hue and saturation, so bisection
     * finds it. Where even a fully bright colour cannot reach the target (no saturated blue is as
     * light as near-white text) the saturation is bled out instead, a direction that always
     * reaches white.
     */
    private static Color atLuminance(float hue, float sat, double target) {
        boolean onSat = luminance(shade(hue, sat, 1, false)) < target;
        float lo = 0, hi = onSat ? sat : 1;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) / 2;
            // Less saturation is lighter, more brightness is lighter: opposite senses, one search.
            if (onSat == (luminance(shade(hue, sat, mid, onSat)) < target))
                hi = mid;
            else
                lo = mid;
        }
        // Luminance is a step function of the eight bits this ends up quantised to, so the
        // interval closes on the edge of a step rather than on the target, always from the same
        // side. Take whichever end lands closer, or every derived colour comes out half a step
        // dark: white text, asked for the luminance of white, came back #FEFEFE.
        Color a = shade(hue, sat, lo, onSat), b = shade(hue, sat, hi, onSat);
        return Math.abs(luminance(a) - target) <= Math.abs(luminance(b) - target) ? a : b;
    }

    /** The colour at whichever of saturation or brightness the search above is moving. */
    private static Color shade(float hue, float sat, float v, boolean onSat) {
        return onSat ? Color.getHSBColor(hue, v, 1) : Color.getHSBColor(hue, sat, v);
    }

    /**
     * The pairs that decide whether the interface can be read at all, walked in the order that
     * settles the grounds before the things drawn on them.
     *
     * <p>Preserving luminance should make this a no-op, and for the four built-ins it is. What it
     * is now for is the picked colours, which carry their own lightness and so can leave a parent
     * token stranded. A picked accent is a ground for nothing here and is never touched. A picked
     * panel is the ground everything else is measured against, so what gives way is a token nobody
     * chose: the band, the hairline, the text. Only where no such move exists does the panel
     * itself move, and then the caller is told rather than left to find out.
     */
    private static void enforce(EnumMap<Token, Color> m, List<String> adjusted) {
        // Body text and its two grounds first, because this is the one step that can move the
        // panel and every pair below is measured against it. The lists are settled first of the
        // two grounds: they are never a picked colour, so they are the cheaper one to bend.
        Color list = m.get(Token.Component);
        Color body = legible(m.get(Token.Foreground), list, TEXT_MIN);
        Color panel = m.get(Token.Background);
        if (contrast(body, panel) < TEXT_MIN) {
            Color moved = legible(body, panel, TEXT_MIN);
            if (contrast(moved, list) >= TEXT_MIN) {
                body = moved; // the text is what nobody chose, so the text is what moves
            } else {
                // The two grounds are on opposite sides of the text, which only a chosen panel can
                // arrange: no text colour is readable on both, and the panel is all that is left.
                panel = legible(panel, body, TEXT_MIN);
                m.put(Token.Background, panel);
                adjusted.add(String.format("%s moved to %s: body text clears %.2f:1 on it",
                        Token.Background.label, hex(panel), contrast(body, panel)));
            }
        }
        m.put(Token.Foreground, body);

        m.put(Token.HeaderFill, legible(m.get(Token.HeaderFill), panel, SURFACE_MIN));
        m.put(Token.ChildHeaderFill, legible(m.get(Token.ChildHeaderFill), panel, SURFACE_MIN));
        m.put(Token.Separator, legible(m.get(Token.Separator), panel, SURFACE_MIN));

        // Header text is fixed against the worse of the two bands it sits on, so that clearing one
        // cannot be what breaks the other. Twice, because a crossing swaps which band is the worse
        // one: white text sent dark to clear a light band then has the darker band to answer for.
        Color header = m.get(Token.HeaderText);
        header = legible(header, worse(header, m.get(Token.HeaderFill), m.get(Token.ChildHeaderFill)), TEXT_MIN);
        m.put(Token.HeaderText, legible(header, worse(header, m.get(Token.HeaderFill), m.get(Token.ChildHeaderFill)), TEXT_MIN));
    }

    /** Whichever of two grounds {@code fore} is harder to read on. */
    private static Color worse(Color fore, Color a, Color b) {
        return contrast(fore, a) <= contrast(fore, b) ? a : b;
    }

    /**
     * {@code fore} pushed away from {@code back} until it clears {@code min}, or as far as it goes.
     *
     * <p>The side the token is already on is tried first, which is what keeps the theme's
     * character: a light token goes lighter. That side runs out at white, and against a mid-tone
     * ground it runs out below 4.5:1, so the other side is tried before giving up. Crossing over
     * is still moving this token rather than the ground it sits on, which is what the caller wants
     * when that ground is a colour someone chose.
     */
    private static Color legible(Color fore, Color back, double min) {
        if (contrast(fore, back) >= min)
            return fore;
        boolean lighter = luminance(fore) >= luminance(back);
        Color out = toward(fore, back, min, lighter ? Color.WHITE : Color.BLACK);
        if (contrast(out, back) < min) {
            Color crossed = toward(fore, back, min, lighter ? Color.BLACK : Color.WHITE);
            if (contrast(crossed, back) >= min)
                out = crossed;
        }
        return out;
    }

    /** {@code fore} mixed toward {@code away} in steps until it clears, or the whole way. */
    private static Color toward(Color fore, Color back, double min, Color away) {
        for (double f = 0.05; f < 1; f += 0.05) {
            Color c = mix(fore, away, f);
            if (contrast(c, back) >= min)
                return c;
        }
        return away;
    }

    // -- the built-in table ----------------------------------------------------------------

    private static final List<Theme> BUILT_IN = List.of(
            // Royal-purple velvet rather than black. Header 3.52:1 on the panel, its white text
            // 4.58:1 on the header, body text 12.61:1. The window between those two rules is
            // narrow: a lighter header fails the text rule and a darker one fails the panel rule.
            builtIn("sunset-dark", "Sunset Dark", Base.FlatDark,
                    "#221E33", "#E6E1F2", "#8C79D9", "#2B2640", "#7E749E", "#7B6FA0", "#FFFFFF", "#736A99"),
            // The same idea warm: paper with an orange cast. Header 4.33:1, its text 4.94:1,
            // body 13.80:1.
            builtIn("sunset-light", "Sunset Light", Base.FlatLight,
                    "#F6EFE6", "#2B2118", "#B5541C", "#FFFBF5", "#8C7F66", "#B5541C", "#FFFFFF", "#8E4114"),
            // What the application looked like before there were themes. The first four values
            // are read out of the IntelliJ theme rather than imposed on it; see the class comment.
            // The header band is the one thing that could not be kept: on a #383838 panel no
            // colour darker than the panel reaches 3:1 (black itself is only 2.0), so a compliant
            // header has to be lighter than the panel and carry dark text.
            builtIn("classic-dark", "Classic Dark", Base.ClassicDark,
                    "#383838", "#CCCCCC", "#2A5285", "#303030", "#858585", "#9EA5B1", "#14161A", "#878E9B"),
            builtIn("classic-light", "Classic Light", Base.ClassicLight,
                    "#EEEEF2", "#333333", "#B8D9FF", "#F8F8F8", "#767676", "#4A5568", "#FFFFFF", "#69707E"));

    private static Theme builtIn(String id, String name, Base base, String background, String foreground,
                                 String accent, String component, String separator,
                                 String headerFill, String headerText, String childHeaderFill) {
        EnumMap<Token, Color> m = new EnumMap<>(Token.class);
        m.put(Token.Background, Color.decode(background));
        m.put(Token.Foreground, Color.decode(foreground));
        m.put(Token.Accent, Color.decode(accent));
        m.put(Token.Component, Color.decode(component));
        m.put(Token.Separator, Color.decode(separator));
        m.put(Token.HeaderFill, Color.decode(headerFill));
        m.put(Token.HeaderText, Color.decode(headerText));
        m.put(Token.ChildHeaderFill, Color.decode(childHeaderFill));
        return new Theme(id, name, base, null, m);
    }

    public static List<Theme> builtIns() {
        return BUILT_IN;
    }

    public static List<Theme> all() {
        load();
        List<Theme> out = new ArrayList<>(BUILT_IN);
        out.addAll(user.values());
        return out;
    }

    @Nullable
    public static Theme byId(String id) {
        for (Theme t : all())
            if (t.id.equals(id))
                return t;
        return null;
    }

    /** Falls back to the default rather than to nothing: an unknown id must still start the program. */
    public static Theme byIdOrDefault(String id) {
        Theme t = byId(id);
        if (t != null)
            return t;
        Theme fallback = byId(DEFAULT_ID);
        return fallback == null ? BUILT_IN.getFirst() : fallback;
    }

    // -- the current theme -----------------------------------------------------------------

    @Nullable private static Theme current;

    public static Theme current() {
        if (current == null)
            current = effective(); // whichever of the pair the mode asks for, on the first read
        return current;
    }

    /**
     * Whether the theme this launch will use is a dark one, decided before the settings are
     * loaded.
     *
     * <p>The macOS window appearance is read once, natively, when the Cocoa application starts,
     * so it has to be chosen before anything else in main() runs; {@link Settings#load} cannot
     * run that early because the data sources come first. Only the one key is peeked at, and an
     * unreadable or absent file means the default theme, which is what the app would use anyway.
     */
    public static boolean startupIsDark() {
        boolean storedDark = byIdOrDefault(migrateId(Settings.peekProperty(SETTING))).dark();
        Mode mode = parseMode(Settings.peekProperty(MODE_SETTING), storedDark);
        // Dark and Light say it outright. System has to ask the desktop, here rather than later,
        // because the window frame is fixed when NSApp starts and cannot be changed afterwards.
        return mode == Mode.System ? SystemAppearance.isDark(storedDark) : mode == Mode.Dark;
    }

    /** Records the choice. Installing it is {@code UIGlobals.switchTheme}, which calls this. */
    public static void setCurrent(Theme theme) {
        current = theme;
        Settings.setProperty(SETTING, theme.id);
    }

    public static Mode mode() {
        return parseMode(Settings.getProperty(MODE_SETTING), byIdOrDefault(migrateId(Settings.getProperty(SETTING))).dark());
    }

    /**
     * The mode a settings file states, or the one its single stored theme implies.
     *
     * <p>An install from before this control has no mode recorded, only the one theme it was set
     * to, and that theme is the whole of what the user asked for: a dark install stays dark rather
     * than being handed the desktop's preference on upgrade. Pure, for ThemeModeCheck.
     */
    static Mode parseMode(@Nullable String stored, boolean storedThemeIsDark) {
        if (stored != null)
            for (Mode m : Mode.values())
                if (m.name().equalsIgnoreCase(stored.trim()))
                    return m;
        return storedThemeIsDark ? Mode.Dark : Mode.Light;
    }

    public static void setMode(Mode mode) {
        Settings.setProperty(MODE_SETTING, mode.name());
    }

    /** The theme to use when the interface is dark. */
    public static Theme darkChoice() {
        return choice(DARK_SETTING, DEFAULT_ID, true);
    }

    /** The theme to use when the interface is light. */
    public static Theme lightChoice() {
        return choice(LIGHT_SETTING, DEFAULT_LIGHT_ID, false);
    }

    /**
     * One half of the pair, falling back twice.
     *
     * <p>An unset key falls back to the theme this install was already using, when that one is of
     * the right kind, so splitting the setting in two changes nothing for someone who had picked a
     * theme before it existed. Only then does it fall back to the built-in. A stored id of the
     * WRONG kind (a light theme recorded as the dark choice, from a hand-edited file) is refused
     * rather than honoured, because a Dark that is light is not a state any control here can undo.
     */
    private static Theme choice(String key, String fallbackId, boolean wantDark) {
        String id = Settings.getProperty(key);
        Theme stated = id == null ? null : byId(id);
        if (stated != null && stated.dark() == wantDark)
            return stated;
        if (stated == null) {
            Theme inUse = byIdOrDefault(migrateId(Settings.getProperty(SETTING)));
            if (inUse.dark() == wantDark)
                return inUse;
        }
        Theme fallback = byId(fallbackId);
        return fallback == null ? BUILT_IN.getFirst() : fallback;
    }

    /** Remember a theme as the choice for its own kind, without saying which kind is in effect. */
    public static void setChoice(Theme theme) {
        Settings.setProperty(theme.dark() ? DARK_SETTING : LIGHT_SETTING, theme.id);
    }

    /**
     * An explicit pick, from the Theme menu or the customizer: it is both the choice for its kind
     * and a statement that that kind is what the user wants now, so it leaves System. Otherwise
     * picking a light theme under a dark desktop would appear to do nothing, or would undo itself
     * on the next poll.
     */
    public static void choose(Theme theme) {
        setChoice(theme);
        setMode(theme.dark() ? Mode.Dark : Mode.Light);
    }

    /** The theme the mode implies right now. */
    public static Theme effective() {
        return switch (mode()) {
            case Dark -> darkChoice();
            case Light -> lightChoice();
            case System -> SystemAppearance.isDark(
                    byIdOrDefault(migrateId(Settings.getProperty(SETTING))).dark())
                    ? darkChoice() : lightChoice();
        };
    }

    /**
     * The theme id for whatever the settings file holds.
     *
     * <p>The same key used to hold the old two-valued switch, written only when someone actually
     * clicked the radio buttons. Those two values map to the Classic themes, so an install that
     * chose Light keeps a light interface instead of being handed the new purple default; an
     * install that never chose anything has no value here and gets the default.
     */
    static String migrateId(@Nullable String stored) {
        if (stored == null || stored.isBlank())
            return DEFAULT_ID;
        return switch (stored) {
            case "Dark" -> "classic-dark";
            case "Light" -> "classic-light";
            default -> stored;
        };
    }

    // -- user themes -----------------------------------------------------------------------

    @Nullable private static LinkedHashMap<String, Theme> user;

    /**
     * A user theme: a name, one of the built-ins to inherit from, and the tokens it disagrees on.
     * Storing the disagreement rather than all fifteen colours is what lets a built-in's other
     * tokens keep moving underneath it.
     */
    public static Theme userTheme(String id, String name, Theme parent, Map<Token, Color> overrides) {
        EnumMap<Token, Color> m = new EnumMap<>(parent.stated);
        m.putAll(overrides);
        return new Theme(id, name, parent.base, parent.id, m);
    }

    /** Overrides only, relative to the parent's resolved colours, for writing out and for the customizer. */
    public Map<Token, Color> overrides() {
        EnumMap<Token, Color> out = new EnumMap<>(Token.class);
        Theme parent = parentId == null ? null : byId(parentId);
        if (parent == null)
            return out;
        for (Map.Entry<Token, Color> e : stated.entrySet())
            if (!e.getValue().equals(parent.get(e.getKey())))
                out.put(e.getKey(), e.getValue());
        return out;
    }

    public static void save(Theme theme) {
        load();
        user.put(theme.id, theme);
        write();
    }

    public static void delete(String id) {
        load();
        if (user.remove(id) != null)
            write();
    }

    /** Ids come from a name typed by a person, so they have to survive spaces and punctuation. */
    public static String idFor(String name) {
        String slug = name.strip().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return "user-" + (slug.isEmpty() ? Long.toString(System.currentTimeMillis()) : slug);
    }

    private static File file() {
        return new File(Directories.SETTINGS.getFile(), FILE_NAME);
    }

    private static void load() {
        if (user != null)
            return;
        user = new LinkedHashMap<>();

        File f = file();
        if (!f.isFile())
            return;
        try (var reader = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            JSONArray arr = new JSONObject(new JSONTokener(reader)).optJSONArray("themes");
            if (arr == null)
                return;
            for (Object o : arr)
                if (o instanceof JSONObject jo) {
                    Theme t = fromJson(jo);
                    if (t != null)
                        user.put(t.id, t);
                }
        } catch (IOException | RuntimeException e) {
            Log.error("Could not read " + FILE_NAME, e);
        }
    }

    @Nullable
    private static Theme fromJson(JSONObject jo) {
        try {
            String id = jo.getString("id").strip();
            String name = jo.getString("name").strip();
            if (id.isEmpty() || name.isEmpty())
                return null;
            Theme parent = null;
            for (Theme t : BUILT_IN)
                if (t.id.equals(jo.getString("base")))
                    parent = t;
            if (parent == null)
                return null; // a user theme with no built-in under it has nothing to derive from

            EnumMap<Token, Color> overrides = new EnumMap<>(Token.class);
            JSONObject colors = jo.optJSONObject("colors");
            if (colors != null)
                for (String key : colors.keySet())
                    overrides.put(Token.valueOf(key), Color.decode(colors.getString(key)));
            return userTheme(id, name, parent, overrides);
        } catch (RuntimeException e) { // one bad entry must not cost the rest of the file
            Log.warn("Skipping unreadable theme: " + e.getMessage());
            return null;
        }
    }

    private JSONObject toJson() {
        JSONObject colors = new JSONObject();
        overrides().forEach((token, color) -> colors.put(token.name(), hex(color)));
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("base", parentId)
                .put("colors", colors);
    }

    private static void write() {
        JSONArray arr = new JSONArray();
        user.values().forEach(t -> arr.put(t.toJson()));
        // Written whole rather than appended, because that is the only way a delete takes effect.
        try {
            Files.writeString(file().toPath(), new JSONObject().put("themes", arr).toString(2),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Log.error("Could not write " + FILE_NAME, e);
        }
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    @Override
    public String toString() {
        return name;
    }

}
