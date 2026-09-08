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
    private static final String SETTING = "display.theme";

    /** Chosen when nothing is stored. Gilly's call: the purple one, not the grey one. */
    public static final String DEFAULT_ID = "sunset-dark";

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
            case TimelineInterval -> mix(c.get(Token.Component), c.get(Token.Accent), 0.45);
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

    // -- the built-in table ----------------------------------------------------------------

    private static final List<Theme> BUILT_IN = List.of(
            // Royal-purple velvet rather than black. Header 3.52:1 on the panel, its white text
            // 4.58:1 on the header, body text 12.61:1. The window between those two rules is
            // narrow: a lighter header fails the text rule and a darker one fails the panel rule.
            builtIn("sunset-dark", "Sunset Dark", Base.FlatDark,
                    "#221E33", "#E6E1F2", "#8C79D9", "#2B2640", "#7E749E", "#7A6BB3", "#FFFFFF", "#72639F"),
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
            current = byIdOrDefault(migrateId(Settings.getProperty(SETTING)));
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
        return byIdOrDefault(migrateId(Settings.peekProperty(SETTING))).dark();
    }

    /** Records the choice. Installing it is {@code UIGlobals.switchTheme}, which calls this. */
    public static void setCurrent(Theme theme) {
        current = theme;
        Settings.setProperty(SETTING, theme.id);
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
