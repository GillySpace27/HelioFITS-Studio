package org.helioviewer.jhv.gui.registry;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.Action;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;

/**
 * The keyboard shortcut of every catalogued command: its default (the one its Action states) and
 * the person's own, kept in the settings as {@code keys.<command id>}. A changed shortcut is
 * applied live by setting the Action's accelerator, which the menu items that carry the Action
 * follow, so nothing restarts.
 *
 * <p>Stored value: empty (or not set) is the default; {@code none} is deliberately unbound; anything
 * else is a keystroke in the form of {@link KeyStroke#getKeyStroke(String)} with {@code menu}
 * standing for the platform's menu shortcut key (Cmd on a Mac, Ctrl elsewhere), so a settings file
 * moved between computers keeps its meaning.
 *
 * <p>Only commands in the {@link ActionCatalog} are managed. The keys a focused control handles
 * itself (J, K and L on the canvas, I and O on the time slider, arrows, Escape in dialogs) are not
 * commands and stay fixed; a shortcut therefore must carry a modifier, or be a function key, so it
 * can never shadow them.
 */
public final class KeyBindings {

    public static final String PREFIX = "keys.";
    private static final String NONE = "none";
    private static final int MODIFIERS = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;

    /** Combinations the system or text editing owns, written as stored: never offered to a command. */
    private static final Set<String> RESERVED_ALL = Set.of("menu C", "menu X", "menu A", "menu V", "menu Z");
    private static final Set<String> RESERVED_MAC = Set.of("meta H", "meta SPACE", "meta TAB", "meta shift TAB");
    private static final Set<String> RESERVED_OTHER = Set.of("alt F4", "alt TAB", "ctrl alt DELETE", "ctrl shift ESCAPE");

    /**
     * Accelerators set in code outside the catalog, so not changeable here and not free to take (stored
     * form to what uses it). KeyBindingsCheck holds each to the Action that states it; a new one is
     * added here when it is added there.
     */
    static final Map<String, String> FIXED = Map.of("menu alt N", "New Timeline", "menu alt O", "Open Timeline");

    private static final Map<String, KeyStroke> defaults = new LinkedHashMap<>(); // the map is the only state (guards.py ratchet)

    /**
     * Records every default, then applies the person's own shortcuts. Called once as the catalog is
     * built; a later call applies again and keeps the defaults it recorded.
     */
    public static synchronized void apply() {
        for (ActionCatalog.Entry e : ActionCatalog.all()) {
            if (!defaults.containsKey(e.id()))
                defaults.put(e.id(), e.action().getValue(Action.ACCELERATOR_KEY) instanceof KeyStroke ks ? ks : null);
            String stored = Settings.getProperty(PREFIX + e.id());
            if (stored == null || stored.isBlank())
                continue;
            KeyStroke ks = stored.equals(NONE) ? null : decode(stored);
            if (!stored.equals(NONE) && ks == null)
                continue; // an unreadable value keeps the default
            e.action().putValue(Action.ACCELERATOR_KEY, ks);
        }
    }

    /** The shortcut the command had before any of the person's changes, or null. */
    @Nullable
    public static synchronized KeyStroke defaultFor(String id) {
        apply();
        return defaults.get(id);
    }

    /** The shortcut now in force, or null for none. */
    @Nullable
    public static KeyStroke current(String id) {
        ActionCatalog.Entry e = ActionCatalog.get(id);
        return e != null && e.action().getValue(Action.ACCELERATOR_KEY) instanceof KeyStroke ks ? ks : null;
    }

    public static boolean isCustom(String id) {
        String stored = Settings.getProperty(PREFIX + id);
        return stored != null && !stored.isBlank();
    }

    /**
     * Gives {@code id} the shortcut {@code ks} (null unbinds it). Returns why it was refused, or null
     * when it was applied and kept.
     */
    @Nullable
    public static synchronized String set(String id, @Nullable KeyStroke ks) {
        apply();
        ActionCatalog.Entry e = ActionCatalog.get(id);
        if (e == null)
            return "no command '" + id + "'";
        if (ks != null) {
            String problem = refusal(id, ks);
            if (problem != null)
                return problem;
        }
        e.action().putValue(Action.ACCELERATOR_KEY, ks);
        KeyStroke def = defaults.get(id);
        boolean isDefault = ks == null ? def == null : ks.equals(def);
        Settings.setProperty(PREFIX + id, isDefault ? "" : ks == null ? NONE : encode(ks));
        return null;
    }

    /** Back to the default shortcut. */
    public static synchronized void reset(String id) {
        apply();
        ActionCatalog.Entry e = ActionCatalog.get(id);
        if (e == null)
            return;
        e.action().putValue(Action.ACCELERATOR_KEY, defaults.get(id));
        Settings.setProperty(PREFIX + id, "");
    }

    public static synchronized void resetAll() {
        apply();
        for (String id : defaults.keySet())
            if (isCustom(id))
                reset(id);
    }

    /** Why {@code ks} cannot be the shortcut of {@code id}, or null when it can. */
    @Nullable
    static String refusal(String id, KeyStroke ks) {
        boolean functionKey = ks.getKeyCode() >= KeyEvent.VK_F1 && ks.getKeyCode() <= KeyEvent.VK_F12;
        if ((ks.getModifiers() & MODIFIERS) == 0 && !functionKey)
            return "A shortcut needs Ctrl, Alt or " + (Platform.isMacOS() ? "Cmd" : "Meta") + ", or to be a function key, so it cannot clash with the keys the canvas and the time slider use.";
        if (ks.getKeyCode() == KeyEvent.VK_UNDEFINED || ks.isOnKeyRelease() || isModifierKey(ks.getKeyCode()))
            return "Press a key together with the modifier.";
        String stored = encode(ks);
        Set<String> reserved = Platform.isMacOS() ? RESERVED_MAC : RESERVED_OTHER;
        if ((RESERVED_ALL.contains(stored) || reserved.contains(stored)) && !ks.equals(defaultFor(id)))
            return Shortcuts.text(ks) + " is used by the system or by text editing.";
        if (FIXED.containsKey(stored))
            return Shortcuts.text(ks) + " is used by " + FIXED.get(stored) + ", which cannot be changed.";
        for (ActionCatalog.Entry other : ActionCatalog.all())
            if (!other.id().equals(id) && ks.equals(other.action().getValue(Action.ACCELERATOR_KEY)))
                return Shortcuts.text(ks) + " is already used by " + ControlRegistry.get(other.id()).title() + ".";
        return null;
    }

    private static boolean isModifierKey(int code) {
        return code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META
                || code == KeyEvent.VK_ALT_GRAPH;
    }

    /** A keystroke as stored, the menu shortcut key written as {@code menu}. */
    public static String encode(KeyStroke ks) {
        StringBuilder sb = new StringBuilder();
        int m = ks.getModifiers();
        int menu = DesktopIntegration.menuShortcutMask;
        if ((m & menu) != 0)
            sb.append("menu ");
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0 && (menu & InputEvent.CTRL_DOWN_MASK) == 0)
            sb.append("ctrl ");
        if ((m & InputEvent.META_DOWN_MASK) != 0 && (menu & InputEvent.META_DOWN_MASK) == 0)
            sb.append("meta ");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0)
            sb.append("alt ");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0)
            sb.append("shift ");
        return sb.append(keyName(ks.getKeyCode())).toString();
    }

    /** The inverse of {@link #encode}; null when the text is not a keystroke. */
    @Nullable
    public static KeyStroke decode(String text) {
        String s = text.strip();
        String menu = (DesktopIntegration.menuShortcutMask & InputEvent.META_DOWN_MASK) != 0 ? "meta" : "ctrl";
        KeyStroke ks = KeyStroke.getKeyStroke(s.replaceAll("\\bmenu\\b", menu));
        return ks == null || ks.isOnKeyRelease() ? null : ks;
    }

    /** The name KeyStroke.getKeyStroke(String) reads back: the VK_ constant without its prefix. */
    public static String keyName(int code) {
        for (java.lang.reflect.Field f : KeyEvent.class.getFields()) {
            if (!f.getName().startsWith("VK_") || f.getType() != int.class)
                continue;
            try {
                if (f.getInt(null) == code)
                    return f.getName().substring(3);
            } catch (IllegalAccessException ignored) {
                // public static fields: not reachable
            }
        }
        return Integer.toString(code);
    }

    /** The VK_ code of a name from {@link #keyName}; VK_UNDEFINED when there is none. */
    public static int keyCode(String name) {
        try {
            return KeyEvent.class.getField("VK_" + name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return KeyEvent.VK_UNDEFINED;
        }
    }

    private KeyBindings() {}
}
