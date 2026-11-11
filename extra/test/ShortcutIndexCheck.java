package org.helioviewer.jhv.gui.registry;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.util.List;

import javax.swing.Action;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.display.interaction.CanvasKeys;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;

/**
 * The shortcuts overlay's data: every registered binding has a row, a rebinding shows at once, a
 * removed one drops out, search narrows the rows, caps are spelled for the platform, and Cmd+/ is
 * the overlay's own and clashes with nothing. Runs in a throwaway home; needs a display (run-checks.sh).
 */
public final class ShortcutIndexCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean has(List<ShortcutIndex.Row> rows, String name, String... caps) {
        return rows.stream().anyMatch(r -> r.name().equals(name) && r.caps().equals(List.of(caps)));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-shortcuts-check").toString());
        Files.createDirectories(java.nio.file.Path.of(org.helioviewer.jhv.io.Directories.SETTINGS.getPath()));
        org.helioviewer.jhv.app.Platform.init();
        int menu = DesktopIntegration.menuShortcutMask;
        boolean mac = menu == InputEvent.META_DOWN_MASK;
        String menuCap = mac ? "⌘" : "Ctrl";

        // Every catalogued accelerator and every canvas key is a row
        List<ShortcutIndex.Row> rows = ShortcutIndex.build(true);
        int accelerators = 0;
        for (ActionCatalog.Entry e : ActionCatalog.all())
            if (e.action().getValue(Action.ACCELERATOR_KEY) instanceof KeyStroke)
                accelerators++;
        int canvas = (int) CanvasKeys.ALL.stream().filter(c -> CanvasKeys.key(c.id()) >= 0).count();
        expect("one row per binding (" + accelerators + " commands + " + canvas + " canvas keys + " + ShortcutIndex.FIXED.size() + " fixed keys)",
                rows.size() == accelerators + canvas + ShortcutIndex.FIXED.size() + 2); // + Shift faster, Option slower
        expect("F and V zoom and G shows or hides the grid, on the canvas", has(rows, "Zoom In", "F") && has(rows, "Zoom Out", "V")
                && has(rows, "Show or Hide Grid", "G"));
        expect("Shift faster and Option slower are listed", has(rows, "Hold for Faster Camera Keys (canvas)", "Shift")
                && has(rows, "Hold for Slower Camera Keys (canvas)", "Option"));
        expect("the fixed keys are listed, J K L among them", has(rows, "Shuttle Reverse, Stop, Forward (anywhere but a text field)", "J", "K", "L")
                && rows.stream().anyMatch(r -> r.group().equals("Fixed keys") && r.caps().equals(List.of("Space"))));
        expect("sidebar toggle is there with its symbols", has(rows, "Show or Hide Left Sidebar", menuCap, "["));
        expect("W is on the canvas, bare", has(rows, "Pitch Up", "W"));
        expect("the overlay lists itself on the slash key", rows.stream().anyMatch(r -> r.name().equals("Keyboard Shortcuts") && r.caps().get(r.caps().size() - 1).equals("/")));

        // Platform spelling
        KeyStroke k = KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK);
        expect("Mac caps", ShortcutIndex.caps(k, true).equals(List.of("Ctrl", "Option", "Shift", "⌘", "=")));
        expect("Windows and Linux caps", ShortcutIndex.caps(k, false).equals(List.of("Ctrl", "Alt", "Shift", "Meta", "=")));

        // Search
        expect("empty search keeps every row", ShortcutIndex.filter(rows, "  ").size() == rows.size());
        List<ShortcutIndex.Row> pitch = ShortcutIndex.filter(rows, "pitch");
        expect("'pitch' narrows to the pitch rows", !pitch.isEmpty() && pitch.size() < rows.size() && pitch.stream().allMatch(r -> r.name().toLowerCase().contains("pitch")));
        expect("'cmd [' finds the left sidebar by its keys", has(ShortcutIndex.filter(rows, mac ? "cmd [" : "ctrl ["), "Show or Hide Left Sidebar", menuCap, "["));
        expect("nonsense matches nothing", ShortcutIndex.filter(rows, "zzzqqq").isEmpty());

        // Rebinding is live; removal drops the row
        String id = "toggleTimeline";
        expect("rebinding is accepted", KeyBindings.set(id, KeyStroke.getKeyStroke(KeyEvent.VK_T, menu | InputEvent.ALT_DOWN_MASK)) == null);
        List<ShortcutIndex.Row> after = ShortcutIndex.build(true);
        expect("the new key shows", mac ? has(after, "Show or Hide Timeline", "Option", "⌘", "T") : has(after, "Show or Hide Timeline", "Ctrl", "Option", "T"));
        expect("the old key is gone", !ShortcutIndex.filter(after, "timeline").stream().anyMatch(r -> r.caps().contains("\\")));
        KeyBindings.set(id, null);
        expect("an unbound command has no row", ShortcutIndex.filter(ShortcutIndex.build(true), "timeline").stream().noneMatch(r -> r.name().equals("Show or Hide Timeline")));
        KeyBindings.reset(id);
        expect("reset brings it back", has(ShortcutIndex.build(true), "Show or Hide Timeline", menuCap, "\\"));

        // Cmd+/ is the overlay's alone
        KeyStroke slash = KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, menu);
        int users = 0;
        for (ActionCatalog.Entry e : ActionCatalog.all())
            if (slash.equals(e.action().getValue(Action.ACCELERATOR_KEY)))
                users++;
        expect("menu+/ belongs to exactly one command", users == 1 && slash.equals(KeyBindings.current("keyboardShortcuts")));

        System.out.println(failures == 0 ? "ShortcutIndexCheck: all ok" : "ShortcutIndexCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
