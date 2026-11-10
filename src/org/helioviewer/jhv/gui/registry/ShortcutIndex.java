package org.helioviewer.jhv.gui.registry;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.Action;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.display.interaction.CanvasKeys;
import org.helioviewer.jhv.gui.ActionCatalog;

/**
 * Every keyboard shortcut in force right now, as rows for the shortcuts overlay (Cmd+/). Read
 * from the live sources each time and never kept: the catalogued commands' accelerators (what
 * {@link KeyBindings} applies, so a rebinding shows at once) and the bare canvas keys
 * ({@link CanvasKeys}). A command with no shortcut has no row.
 */
public final class ShortcutIndex {

    /** One row: the section it sits in, the action's name and its key caps, one string per cap. */
    public record Row(String group, String name, List<String> caps) {}

    private static final Map<Integer, String> KEY_CAPS = Map.ofEntries(
            Map.entry(KeyEvent.VK_OPEN_BRACKET, "["), Map.entry(KeyEvent.VK_CLOSE_BRACKET, "]"),
            Map.entry(KeyEvent.VK_BACK_SLASH, "\\"), Map.entry(KeyEvent.VK_SLASH, "/"),
            Map.entry(KeyEvent.VK_EQUALS, "="), Map.entry(KeyEvent.VK_MINUS, "-"),
            Map.entry(KeyEvent.VK_COMMA, ","), Map.entry(KeyEvent.VK_PERIOD, "."),
            Map.entry(KeyEvent.VK_SEMICOLON, ";"), Map.entry(KeyEvent.VK_QUOTE, "'"),
            Map.entry(KeyEvent.VK_BACK_QUOTE, "`"), Map.entry(KeyEvent.VK_LEFT, "←"),
            Map.entry(KeyEvent.VK_RIGHT, "→"), Map.entry(KeyEvent.VK_UP, "↑"),
            Map.entry(KeyEvent.VK_DOWN, "↓"), Map.entry(KeyEvent.VK_ESCAPE, "Esc"));

    /** The rows for this platform. */
    public static List<Row> build() {
        return build(Platform.isMacOS());
    }

    public static List<Row> build(boolean mac) {
        List<Row> out = new ArrayList<>();
        for (ActionCatalog.Entry e : ActionCatalog.all()) {
            if (!(e.action().getValue(Action.ACCELERATOR_KEY) instanceof KeyStroke ks))
                continue;
            ControlRegistry.Entry reg = ControlRegistry.get(e.id());
            out.add(new Row(group(e.menuPath()), reg != null ? reg.title() : e.id(), caps(ks, mac)));
        }
        for (CanvasKeys.Cmd c : CanvasKeys.ALL) {
            int code = CanvasKeys.key(c.id());
            if (code < 0)
                continue;
            List<String> caps = new ArrayList<>();
            if (c.withCmd())
                caps.add(mac ? "⌘" : "Ctrl");
            caps.add(keyCap(code));
            out.add(new Row("Canvas", c.title().replace(" (with Cmd)", ""), caps));
        }
        return out;
    }

    /** The menu a command lives in names its section; a command with no menu goes under Other. */
    static String group(String menuPath) {
        int slash = menuPath.indexOf('/');
        return slash > 0 ? menuPath.substring(0, slash) : "Other";
    }

    /** Key caps in the platform's order and spelling: ⌃⌥⇧⌘ on a Mac, Ctrl Alt Shift Meta elsewhere. */
    public static List<String> caps(KeyStroke ks, boolean mac) {
        int m = ks.getModifiers();
        List<String> caps = new ArrayList<>();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0)
            caps.add(mac ? "⌃" : "Ctrl");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0)
            caps.add(mac ? "⌥" : "Alt");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0)
            caps.add(mac ? "⇧" : "Shift");
        if ((m & InputEvent.META_DOWN_MASK) != 0)
            caps.add(mac ? "⌘" : "Meta");
        caps.add(keyCap(ks.getKeyCode()));
        return caps;
    }

    static String keyCap(int code) {
        String cap = KEY_CAPS.get(code);
        return cap != null ? cap : KeyEvent.getKeyText(code).toUpperCase(Locale.ROOT);
    }

    /**
     * The rows whose name, section or keys contain every word of {@code query} (case-insensitive;
     * Cmd, Command, Option and Opt also match the symbols). An empty query keeps every row.
     */
    public static List<Row> filter(List<Row> rows, String query) {
        String[] words = query.strip().toLowerCase(Locale.ROOT).replace("command", "⌘").replace("cmd", "⌘")
                .replace("option", "⌥").replace("opt", "⌥").replace("control", "⌃").split("\\s+");
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            String hay = (r.name() + " " + r.group() + " " + String.join(" ", r.caps())).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words)
                all &= w.isEmpty() || hay.contains(w);
            if (all)
                out.add(r);
        }
        return out;
    }

    private ShortcutIndex() {}
}
