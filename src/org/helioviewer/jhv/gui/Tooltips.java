package org.helioviewer.jhv.gui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ContainerEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONObject;

/**
 * Tooltips kept as data, in resources/data/tooltips.json, keyed by {@link #key}: a named
 * component by its name (a toolbar id, a catalogued menu item's id, or a literal setName), any
 * other control by "Owner/label", the title of the section it sits in, or the class of the panel that
 * built it, or the title of its dialog, and the text on it or on the label just before it ("Grid/Radial grid", "MoviePanel/Loop").
 *
 * <p>The code's own setToolTipText wins: a file entry only fills a control that has none, because
 * many controls rewrite their tooltip as their state changes (the toolbar lock, the sidebar
 * toggles). A named control with no entry gets the description of the catalogued command of that
 * id. Every component added anywhere is filled as it is added, so palettes and dialogs built
 * later are covered with no call of their own.
 *
 * <p>Help > Report Controls Without Tooltips, and {@code -Djhv.probeTooltips=true} at launch,
 * write every control on screen that still has none to the log, each with the key to add to the
 * file. TooltipsCheck holds the file readable and free of em dashes. docs/tips-and-tooltips.md
 * says where each kind of help text lives.
 */
public final class Tooltips {

    private static final Map<String, String> byKey = Collections.synchronizedMap(new LinkedHashMap<>()); // the map is the only state (guards.py ratchet)

    static Map<String, String> parse(JSONObject jo) {
        JSONObject tips = jo.getJSONObject("tooltips");
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : tips.keySet())
            out.put(k, tips.getString(k));
        return out;
    }

    static Map<String, String> load() {
        try (InputStream is = FileUtils.getResource("/data/tooltips.json")) {
            return parse(JSONUtils.get(is));
        } catch (IOException | RuntimeException e) { // TooltipsCheck keeps the file readable; never a crash here
            Log.warn("Tooltips could not be read", e);
            return Map.of();
        }
    }

    @Nullable
    static String lookup(@Nullable String key) {
        if (key == null)
            return null;
        if (byKey.isEmpty())
            byKey.putAll(load());
        return byKey.get(key);
    }

    /** On the EDT, once: fill every window open now, and every component added from now on. */
    public static void install() {
        for (Window w : Window.getWindows())
            fill(w);
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() == ContainerEvent.COMPONENT_ADDED && e instanceof ContainerEvent ce)
                fill(ce.getChild());
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    /** Give {@code root} and everything under it the tooltips it lacks. */
    public static void fill(Component root) {
        if (root instanceof JComponent j && j.getToolTipText() == null && !(j instanceof JMenuItem)) { // a menu item's label already says it
            String k = key(j);
            String tip = lookup(k);
            if (tip == null && k != null && k.indexOf('/') > 0)
                tip = lookup("*" + k.substring(k.indexOf('/'))); // "*/Close": the same button anywhere
            if (tip == null && j.getName() != null) {
                ActionCatalog.Entry entry = ActionCatalog.get(j.getName());
                if (entry != null)
                    tip = entry.description();
            }
            if (tip != null)
                j.setToolTipText(tip);
        }
        if (root instanceof Container c)
            for (Component child : c.getComponents())
                fill(child);
    }

    /**
     * The file key for {@code c}: its name when it has one, else "Owner/label", else null for a
     * control with neither a name nor any text to go by (give it a setName, or a setToolTipText).
     */
    @Nullable
    public static String key(Component c) {
        if (c.getName() != null && !c.getName().startsWith("TextField.")) // FlatLaf's own parts name themselves
            return c.getName();
        String label = label(c);
        String owner = owner(c);
        return label == null || owner == null ? null : owner + '/' + label;
    }

    /** The text on the control, or on the label just before it in its panel, without HTML or a trailing colon. */
    @Nullable
    static String label(Component c) {
        String text = c instanceof AbstractButton b ? b.getText() : null;
        if ((text == null || text.isBlank()) && c.getParent() != null) {
            Component[] siblings = c.getParent().getComponents();
            for (int i = 1; i < siblings.length; i++)
                if (siblings[i] == c && siblings[i - 1] instanceof JLabel l)
                    text = l.getText();
        }
        if (text == null)
            return null;
        text = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
        if (text.endsWith(":"))
            text = text.substring(0, text.length() - 1).strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Where the control sits: the title of the nearest section around it, or the nearest enclosing
     * panel whose class is this program's own, by its outer class's simple name, whichever is closer.
     */
    @Nullable
    static String owner(Component c) {
        for (Container p = c.getParent(); p != null; p = p.getParent()) {
            if (p instanceof org.helioviewer.jhv.gui.component.CollapsiblePane section)
                return section.getTitle().split("  \u25cf  ")[0]; // LayerSection appends "  ●  " and a live summary
            String name = p.getClass().getName();
            if (p instanceof org.helioviewer.jhv.gui.component.SplitButton)
                continue; // a part of a control, not a panel that says where
            if (p instanceof java.awt.Dialog d && d.getTitle() != null && !d.getTitle().isBlank())
                return d.getTitle(); // a dialog built from plain Swing parts goes by its title
            if (name.startsWith("org.helioviewer.")) {
                name = name.substring(name.lastIndexOf('.') + 1);
                int inner = name.indexOf('$');
                return inner > 0 ? name.substring(0, inner) : name;
            }
        }
        return null;
    }

    /** A control a person operates: a button, a box, a list to pick from, a slider, a field. Menus and scroll bars are not. */
    static boolean isControl(Component c) {
        if (c instanceof JMenuItem || c.getParent() instanceof JScrollBar || c.getParent() instanceof JComboBox<?>)
            return false;
        if (c.getName() != null && c.getName().startsWith("TextField.")) // FlatLaf's clear button inside a field
            return false;
        if (c instanceof JComponent j && j.getParent() instanceof JComponent p && p.getParent() instanceof JSpinner)
            return false; // a spinner's text field; the spinner is reported instead
        return c instanceof AbstractButton || c instanceof JComboBox<?> || c instanceof JSlider || c instanceof JSpinner
                || (c instanceof JTextComponent t && t.isEditable());
    }

    /** Every control under {@code root} with no tooltip, as "key  (Type in Owner)"; a missing key reads "(no key)". */
    public static List<String> missing(Component root) {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Component c, List<String> out) {
        if (isControl(c) && ((JComponent) c).getToolTipText() == null) {
            String k = key(c);
            out.add((k == null ? "(no key)" : k) + "  (" + c.getClass().getSimpleName() + " in " + owner(c) + ")");
        }
        if (c instanceof Container container && !(c instanceof JComboBox<?>) && !(c instanceof JSpinner))
            for (Component child : container.getComponents())
                walk(child, out);
    }

    /** Help > Report Controls Without Tooltips: every open window's controls that have none, into the log. */
    public static void logReport() {
        int n = 0;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing())
                continue;
            for (String line : missing(w)) {
                Log.info("Tooltips: missing: " + line);
                n++;
            }
        }
        Log.info("Tooltips: " + n + " control(s) without a tooltip in the open windows; add each key to resources/data/tooltips.json");
    }

    private Tooltips() {}

}
