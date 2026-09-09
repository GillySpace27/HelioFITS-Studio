package org.helioviewer.jhv.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.InputStream;
import java.util.function.Consumer;
//import java.util.Enumeration;
//import java.util.Map;
//import java.util.TreeSet;

import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
//import javax.swing.plaf.FontUIResource;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.IconBank.JHVIcon;
import org.helioviewer.jhv.gui.component.TimeSlider;
import org.helioviewer.jhv.io.FileUtils;

public final class UIGlobals {

    public static void setLaf() {
        applyTheme();
        // listFontKeys();
        // listColorKeys();

        if (!Platform.isMacOS()) {
            ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
            JPopupMenu.setDefaultLightWeightPopupEnabled(false);
            UIManager.put("Popup.forceHeavyWeight", true);
        }

        if (Platform.isMacOS()) {
            ImageIcon cursor = IconBank.getIcon(JHVIcon.CLOSED_HAND_MAC);
            cursor = cursor == null ? IconBank.getBlank() : cursor;
            closedHandCursor = Toolkit.getDefaultToolkit().createCustomCursor(cursor.getImage(), new Point(5, 1), cursor.toString());
        } else {
            ImageIcon cursor = IconBank.getIcon(JHVIcon.CLOSED_HAND);
            cursor = cursor == null ? IconBank.getBlank() : cursor;
            closedHandCursor = Toolkit.getDefaultToolkit().createCustomCursor(cursor.getImage(), new Point(16, 8), cursor.toString());
        }
        ImageIcon openHand = IconBank.getIcon(JHVIcon.OPEN_HAND);
        openHandCursor = openHand != null && openHand.getIconWidth() > 0
                ? Toolkit.getDefaultToolkit().createCustomCursor(openHand.getImage(), new Point(openHand.getIconWidth() / 2, openHand.getIconHeight() / 2), "openHand")
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); // flat open palm, falling back to the pointing hand

        try (InputStream is = FileUtils.getResource("/fonts/materialdesignicons-webfont.ttf")) {
            uiFontMDI = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(uiFontMDI);
        } catch (Exception e) {
            Log.warn("Font not loaded correctly, fallback to default", e);
            uiFontMDI = sansFont;
        }
    }

    /**
     * Install the current theme's look-and-feel and re-derive every colour and font the
     * application captured from it.
     *
     * <p>Separate from {@link #setLaf} because it runs again on every theme switch, and because
     * everything it does has to be idempotent. The cursors and the icon font above it are
     * one-time work: registering the same font twice is wasted, and nothing about a cursor
     * depends on the theme.
     */
    public static void applyTheme() {
        Theme theme = Theme.current();
        try {
            // Must precede setup(): FlatLaf reads the extra defaults while building the table.
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(theme.lafDefaults());
            switch (theme.base()) {
                case ClassicDark -> com.formdev.flatlaf.intellijthemes.FlatDarkFlatIJTheme.setup();
                case ClassicLight -> com.formdev.flatlaf.intellijthemes.FlatLightFlatIJTheme.setup();
                case FlatDark -> com.formdev.flatlaf.FlatDarkLaf.setup();
                case FlatLight -> com.formdev.flatlaf.FlatLightLaf.setup();
            }
            com.jidesoft.plaf.LookAndFeelFactory.installJideExtension();
        } catch (Exception e) {
            Log.error(e);
        }

        // Everything below is written into a defaults table that setup() has just replaced, so it
        // belongs here rather than in setLaf: after a switch it would otherwise be gone.
        if (Platform.isMacOS())
            UIManager.getLookAndFeelDefaults().put("defaultFont", UIManager.getFont("medium.font")); // smaller, FlatLaf 2

        int arc = 6;
        UIManager.put("Button.arc", arc);
        UIManager.put("CheckBox.arc", arc);
        UIManager.put("Component.arc", arc);
        UIManager.put("ProgressBar.arc", arc);
        UIManager.put("TextComponent.arc", arc);
        // UIManager.put("Component.arrowType", "triangle");

        uiFont = UIManager.getFont("defaultFont");
        uiFont = uiFont == null ? UIManager.getFont("Label.font") : uiFont; // when not FlatLaf
        float defaultSize = uiFont.getSize();

        uiFontBold = uiFont.deriveFont(Font.BOLD);
        uiFontSmall = uiFont.deriveFont(defaultSize - 2);
        uiFontSmallBold = uiFontSmall.deriveFont(Font.BOLD);

        uiFontMono = UIManager.getFont("monospaced.font");
        uiFontMono = uiFontMono == null ? new Font("Monospaced", Font.PLAIN, (int) defaultSize) : uiFontMono; // when not FlatLaf
        uiFontMonoSmall = uiFontMono.deriveFont(defaultSize - 2);

        sansFont = new Font("SansSerif", Font.PLAIN, (int) defaultSize);

        foreColor = theme.get(Theme.Token.Foreground);
        backColor = theme.get(Theme.Token.Background);

        TL_AVAILABLE_INTERVAL_BACKGROUND_COLOR = theme.get(Theme.Token.TimelinePlot);
        TL_SELECTED_INTERVAL_BACKGROUND_COLOR = theme.get(Theme.Token.TimelineInterval);
        TL_INTERVAL_BORDER_COLOR = theme.get(Theme.Token.TimelineIntervalBorder);
        // Was a near-black line on a near-black ground, i.e. not a border at all. The separator
        // token is the one colour in the theme that is guaranteed to be visible on the panel.
        TL_BORDER_COLOR = theme.get(Theme.Token.Separator);

        TL_TICK_LINE_COLOR = theme.get(Theme.Token.TimelineTick);
        TL_LABEL_TEXT_COLOR = theme.get(Theme.Token.TimelineLabel);
        // The event tooltip is a filled chip with text on it: the same pair of colours, and the
        // same two contrast rules, as a section header.
        TL_TEXT_COLOR = theme.get(Theme.Token.HeaderText);
        TL_TEXT_BACKGROUND_COLOR = theme.get(Theme.Token.HeaderFill);

        TL_MOVIE_FRAME_COLOR = theme.get(Theme.Token.TimelineMovie);
        TL_MOVIE_INTERVAL_COLOR = theme.get(Theme.Token.TimelineMovieBand);
    }

    /**
     * Change theme with the window open.
     *
     * <p>{@code updateComponentTreeUI} only replaces values whose type says they came from the
     * look-and-feel, so every colour the application captured itself survives a switch and has to
     * be re-derived by hand. Those are: the statics above, the slider's palette
     * ({@link TimeSlider#refreshColors}), and the plain borders and fills registered through
     * {@link #themed}. The section headers need nothing: they read the theme while painting.
     */
    public static void switchTheme(Theme theme) {
        Theme.choose(theme); // an explicit pick is also a statement of which kind is wanted
        installTheme(theme);
        syncSystemWatch();
    }

    /** Milliseconds between asks of the desktop while the mode is Follow system. */
    private static final int SYSTEM_POLL_MS = 4000;

    @Nullable
    private static javax.swing.Timer systemWatch;

    /**
     * Put on whatever the theme mode now asks for, and keep watching the desktop while it is
     * Follow system.
     *
     * <p>Polled rather than notified: catching the appearance change as it happens needs a
     * distributed notification and therefore native code, where asking costs about thirty
     * milliseconds every few seconds. Idempotent, so this is both how the watch starts and what it
     * does on each tick.
     */
    public static void applyThemeMode() {
        Theme want = Theme.effective();
        if (!want.id().equals(Theme.current().id()))
            installTheme(want);
        syncSystemWatch();
    }

    private static void syncSystemWatch() {
        boolean follow = Theme.mode() == Theme.Mode.System;
        if (follow && systemWatch == null) {
            systemWatch = new javax.swing.Timer(SYSTEM_POLL_MS, e -> applyThemeMode());
            systemWatch.start();
        } else if (!follow && systemWatch != null) {
            systemWatch.stop();
            systemWatch = null;
        }
    }

    /** Install a theme without deciding anything: what the mode asks for, and what a pick installs. */
    private static void installTheme(Theme theme) {
        Theme.setCurrent(theme);
        applyTheme();
        TimeSlider.refreshColors();
        com.formdev.flatlaf.FlatLaf.updateUI();
        refreshThemed(); // after updateUI, which puts the look-and-feel's own borders back
        for (Window w : Window.getWindows()) {
            w.invalidate();
            w.validate();
            w.repaint();
        }
    }

    /** The theme's separator, the one colour guaranteed to clear 3:1 against a panel. */
    public static Color separator() {
        return Theme.current().get(Theme.Token.Separator);
    }

    private static final String THEMED = "jhv.themed";

    /**
     * Paint something with a plain (non-look-and-feel) colour and remember how, so a theme switch
     * can do it again.
     *
     * <p>A {@code MatteBorder} or a {@code setBackground} holding an ordinary Color is invisible
     * to {@code updateComponentTreeUI}: it stays exactly as it was, in the old theme's colours,
     * for the rest of the session. Keeping the recipe on the component itself rather than in a
     * listener list means a panel that goes away takes its entry with it.
     */
    public static void themed(JComponent c, Consumer<JComponent> apply) {
        c.putClientProperty(THEMED, apply);
        apply.accept(c);
    }

    public static void refreshThemed() {
        for (Window w : Window.getWindows())
            refreshThemed(w);
    }

    @SuppressWarnings("unchecked")
    private static void refreshThemed(Component c) {
        if (c instanceof JComponent jc && jc.getClientProperty(THEMED) instanceof Consumer<?> apply)
            ((Consumer<JComponent>) apply).accept(jc);
        if (c instanceof Container container)
            for (Component child : container.getComponents())
                refreshThemed(child);
    }
/*
    private static void setUIFont(Font font) {
        FontUIResource f = new FontUIResource(font);
        Enumeration<?> keys = UIManager.getLookAndFeelDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource)
                UIManager.put(key, f);
        }
    }

    private static void listFontKeys() {
        TreeSet<String> keys = new TreeSet<>();
        for (Map.Entry<Object, Object> entry : UIManager.getLookAndFeelDefaults().entrySet()) {
            if (entry.getValue() instanceof Font) {
                keys.add((String) entry.getKey());
            }
        }
        keys.forEach(System.out::println);
    }

    private static void listColorKeys() {
        TreeSet<String> keys = new TreeSet<>();
        for (Map.Entry<Object, Object> entry : UIManager.getLookAndFeelDefaults().entrySet()) {
            if (entry.getValue() instanceof Color) {
                keys.add((String) entry.getKey());
            }
        }
        keys.forEach(System.out::println);
    }
*/

    public static Font sansFont;

    public static Font uiFont;
    public static Font uiFontBold;

    public static Font uiFontSmall;
    public static Font uiFontSmallBold;

    public static Font uiFontMono;
    public static Font uiFontMonoSmall;

    public static Font uiFontMDI;

    public static Cursor openHandCursor;
    public static Cursor closedHandCursor;

    public static Color foreColor;
    public static Color backColor;

    // Timelines panel colors
    public static Color TL_AVAILABLE_INTERVAL_BACKGROUND_COLOR;
    public static Color TL_SELECTED_INTERVAL_BACKGROUND_COLOR;
    public static Color TL_INTERVAL_BORDER_COLOR;
    public static Color TL_BORDER_COLOR;

    public static Color TL_TICK_LINE_COLOR;
    public static Color TL_LABEL_TEXT_COLOR;
    public static Color TL_TEXT_COLOR;
    public static Color TL_TEXT_BACKGROUND_COLOR;

    public static Color TL_MOVIE_FRAME_COLOR;
    public static Color TL_MOVIE_INTERVAL_COLOR;

    private UIGlobals() {}
}
