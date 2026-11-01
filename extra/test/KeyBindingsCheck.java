package org.helioviewer.jhv.gui.registry;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;

import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;

/**
 * The keyboard shortcut manager: a shortcut is stored portably, refused when it clashes or cannot be
 * used, applied live to the menu items that carry the Action, removable, and put back by Reset. Runs
 * in a throwaway home, as every check that touches Settings must; building the catalog needs a
 * display, so run-checks.sh reruns this with one.
 */
public final class KeyBindingsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-keys-check").toString()); // before Settings or Directories load
        Files.createDirectories(java.nio.file.Path.of(org.helioviewer.jhv.io.Directories.SETTINGS.getPath()));
        org.helioviewer.jhv.app.Platform.init();
        int menu = DesktopIntegration.menuShortcutMask;

        // Stored form
        KeyStroke p = KeyStroke.getKeyStroke(KeyEvent.VK_P, menu | InputEvent.SHIFT_DOWN_MASK);
        expect("encode writes the menu key as 'menu': " + KeyBindings.encode(p), KeyBindings.encode(p).equals("menu shift P"));
        expect("decode reads it back on this platform", p.equals(KeyBindings.decode("menu shift P")));
        for (KeyStroke ks : new KeyStroke[] { p, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, menu | InputEvent.ALT_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu), KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SLASH, InputEvent.ALT_DOWN_MASK) })
            expect("round trip " + KeyBindings.encode(ks), ks.equals(KeyBindings.decode(KeyBindings.encode(ks))));
        expect("decode refuses nonsense", KeyBindings.decode("menu shift") == null && KeyBindings.decode("!!") == null);

        // Refusals
        String free = "duplicateLayer"; // catalogued; has no default shortcut
        expect("the command used below has no default shortcut", KeyBindings.defaultFor(free) == null && ActionCatalog.get(free) != null);
        expect("a bare letter is refused (it would shadow the canvas keys)", KeyBindings.refusal(free, KeyStroke.getKeyStroke(KeyEvent.VK_J, 0)) != null);
        expect("Shift alone is refused", KeyBindings.refusal(free, KeyStroke.getKeyStroke(KeyEvent.VK_J, InputEvent.SHIFT_DOWN_MASK)) != null);
        expect("a function key alone is allowed", KeyBindings.refusal(free, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0)) == null);
        expect("menu+C (copy) is refused", KeyBindings.refusal(free, KeyStroke.getKeyStroke(KeyEvent.VK_C, menu)) != null);
        expect("menu+V and menu+Z are reserved for another command", KeyBindings.refusal(free, KeyStroke.getKeyStroke(KeyEvent.VK_V, menu)) != null
                && KeyBindings.refusal(free, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu)) != null);
        expect("but a command may keep its own reserved default (Paste has menu+V)", KeyBindings.refusal("paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, menu)) == null);
        for (var f : new java.util.Map.Entry[] { java.util.Map.entry("menu alt N", new org.helioviewer.jhv.timelines.gui.TimelineActions.NewLayer()),
                java.util.Map.entry("menu alt O", new org.helioviewer.jhv.timelines.gui.TimelineActions.OpenLocalFile()) }) {
            Action a = (Action) f.getValue();
            expect("FIXED lists " + f.getKey() + " as " + a.getValue(Action.NAME), KeyBindings.FIXED.containsKey(f.getKey())
                    && KeyBindings.encode((KeyStroke) a.getValue(Action.ACCELERATOR_KEY)).equals(f.getKey()));
            String why = KeyBindings.refusal(free, (KeyStroke) a.getValue(Action.ACCELERATOR_KEY));
            expect("a hard-coded accelerator outside the catalog cannot be taken: " + why, why != null && why.contains("cannot be changed"));
        }
        KeyStroke zoomIn = KeyBindings.current("zoomIn");
        String clash = KeyBindings.refusal(free, zoomIn);
        expect("a clash names the other command: " + clash, clash != null && clash.contains("Zoom In"));
        expect("a command may keep its own shortcut", KeyBindings.refusal("zoomIn", zoomIn) == null);
        expect("a refused set changes nothing", KeyBindings.set(free, zoomIn) != null && KeyBindings.current(free) == null && !KeyBindings.isCustom(free));

        // Set, live, stored, reset
        Action action = ActionCatalog.get(free).action();
        JMenuItem item = new JMenuItem(action);
        KeyStroke mine = KeyStroke.getKeyStroke(KeyEvent.VK_D, menu | InputEvent.ALT_DOWN_MASK);
        expect("set accepts a free combination", KeyBindings.set(free, mine) == null);
        expect("the Action carries it", mine.equals(action.getValue(Action.ACCELERATOR_KEY)) && mine.equals(KeyBindings.current(free)));
        expect("a menu item built before the change follows it: " + item.getAccelerator(), mine.equals(item.getAccelerator()));
        expect("it is stored: " + Settings.getProperty("keys." + free), "menu alt D".equals(Settings.getProperty("keys." + free)));
        expect("it is custom", KeyBindings.isCustom(free));
        expect("another command now cannot take it", KeyBindings.refusal("zoomOut", mine) != null);

        KeyBindings.reset(free);
        expect("reset puts the default back (none)", KeyBindings.current(free) == null && item.getAccelerator() == null && !KeyBindings.isCustom(free));

        // Remove a default, and survive a restart
        String withDefault = "zoomIn";
        KeyStroke def = KeyBindings.defaultFor(withDefault);
        expect("zoomIn has a default", def != null);
        expect("remove (null) is allowed", KeyBindings.set(withDefault, null) == null && KeyBindings.current(withDefault) == null);
        expect("removed is stored as none: " + Settings.getProperty("keys." + withDefault), "none".equals(Settings.getProperty("keys." + withDefault)));
        expect("the default is still known", def.equals(KeyBindings.defaultFor(withDefault)));
        ActionCatalog.get(withDefault).action().putValue(Action.ACCELERATOR_KEY, def); // a fresh start: the default is on the Action again
        KeyBindings.apply();
        expect("apply() re-applies the stored removal (restart)", KeyBindings.current(withDefault) == null);
        Settings.setProperty("keys." + free, "menu alt J");
        KeyBindings.apply();
        expect("apply() re-applies a stored shortcut (restart): " + KeyBindings.current(free),
                KeyStroke.getKeyStroke(KeyEvent.VK_J, menu | InputEvent.ALT_DOWN_MASK).equals(KeyBindings.current(free)));
        Settings.setProperty("keys." + free, "garbage ((");
        ActionCatalog.get(free).action().putValue(Action.ACCELERATOR_KEY, null);
        KeyBindings.apply();
        expect("an unreadable stored value keeps the default (none)", KeyBindings.current(free) == null);

        KeyBindings.resetAll();
        expect("resetAll puts every shortcut back", def.equals(KeyBindings.current(withDefault)) && KeyBindings.current(free) == null
                && !KeyBindings.isCustom(withDefault) && !KeyBindings.isCustom(free));

        // The panel builds and filters
        org.helioviewer.jhv.gui.dialog.ShortcutsPanel panel = new org.helioviewer.jhv.gui.dialog.ShortcutsPanel();
        expect("the Shortcuts panel builds", panel.getComponentCount() > 0);

        System.out.println(failures == 0 ? "KeyBindingsCheck: ok" : "KeyBindingsCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
