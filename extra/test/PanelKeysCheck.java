package org.helioviewer.jhv.gui.component;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.Action;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.registry.KeyBindings;

/**
 * The sidebar panel keys: Cmd+N folds or shows the left sidebar's section N, Cmd+Option+N (Ctrl+Shift+N
 * off a Mac) the right one's, and N is the section's place on screen, so moving a section changes its
 * key (Gilly, 2026-11-07).
 *
 * <p>Pinned here: the sixteen commands exist with the keys stated and collide with nothing else in the
 * catalog; a place is counted the way the numbers in front of the titles are; pressing a key twice
 * folds and unfolds; a section in a collapsed sidebar is shown, not folded; and the place follows the
 * section when the sidebar's order changes. Uses the right sidebar, whose pane exists without a window.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PanelKeysCheck
 */
public final class PanelKeysCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // Settings is written (the right sidebar's order and fold state): never Gilly's own file.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-panel-keys").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs(); // else every Settings write logs a stack trace over the results

        int menu = DesktopIntegration.menuShortcutMask;
        int other = Platform.isMacOS() ? InputEvent.ALT_DOWN_MASK : InputEvent.SHIFT_DOWN_MASK;
        boolean keysOk = true, clashFree = true;
        for (boolean right : new boolean[]{false, true})
            for (int n = 1; n <= PanelKeys.SLOTS; n++) {
                ActionCatalog.Entry e = ActionCatalog.get(PanelKeys.id(right, n));
                KeyStroke want = KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, menu | (right ? other : 0));
                keysOk &= e != null && want.equals(e.action().getValue(Action.ACCELERATOR_KEY));
                if (e != null)
                    clashFree &= KeyBindings.set(e.id(), want) == null; // refused if another command or the system has it
            }
        expect("foldLeftPanel1..8 and foldRightPanel1..8 are catalogued with menu+N and menu+"
                + (Platform.isMacOS() ? "Option" : "Shift") + "+N", keysOk);
        expect("none of the sixteen keys is taken by another command or by the system", clashFree);
        expect("there is no ninth place: menu+9 stays Zoom to Fit", ActionCatalog.get("foldLeftPanel9") == null
                && KeyStroke.getKeyStroke(KeyEvent.VK_9, menu).equals(KeyBindings.current("zoomFit")));

        RightSidebar bar = RightSidebar.getInstance();
        bar.setCollapsed(false);
        for (String t : new String[]{"Alpha", "Beta", "Gamma"})
            bar.addSection(t, null, new JPanel(), () -> {});
        expect("places count the sections top to bottom",
                "Alpha".equals(PanelKeys.at(true, 1).getTitle()) && "Beta".equals(PanelKeys.at(true, 2).getTitle())
                        && "Gamma".equals(PanelKeys.at(true, 3).getTitle()));
        expect("an empty place has no section, and pressing its key does nothing", PanelKeys.at(true, 4) == null
                && PanelKeys.at(true, 0) == null && "R4. (no section)".equals(PanelKeys.label(true, 4)));
        PanelKeys.toggle(true, 4); // must not throw
        expect("the menu label names what the key reaches now", "R2. Beta".equals(PanelKeys.label(true, 2)));
        expect("the place agrees with the number drawn in front of the title",
                PanelKeys.at(true, 2).number() == 2 || !PanelVisibility.numbers());

        boolean before = bar.isUnfolded("Beta"), alpha = bar.isUnfolded("Alpha");
        PanelKeys.toggle(true, 2);
        boolean once = bar.isUnfolded("Beta");
        PanelKeys.toggle(true, 2);
        expect("the key flips its section, and pressing it again flips it back", once != before && bar.isUnfolded("Beta") == before);
        expect("and leaves the others alone", bar.isUnfolded("Alpha") == alpha && !bar.isCollapsed());

        if (bar.isUnfolded("Gamma"))
            PanelKeys.toggle(true, 3);
        PanelKeys.toggle(true, 3);
        expect("a folded section is unfolded", bar.isUnfolded("Gamma"));
        bar.setCollapsed(true);
        PanelKeys.toggle(true, 3);
        expect("in a collapsed sidebar the key shows the section rather than folding it out of sight",
                !bar.isCollapsed() && bar.isUnfolded("Gamma"));

        // Taken out and put back, Alpha goes to the end: the keys follow the order on screen.
        bar.removeSection("Alpha");
        bar.addSection("Alpha", null, new JPanel(), () -> {});
        expect("moving a section changes its place: Beta is now 1 and Alpha 3",
                "Beta".equals(PanelKeys.at(true, 1).getTitle()) && "Alpha".equals(PanelKeys.at(true, 3).getTitle()));
        String key = PanelKeys.keyText(PanelKeys.at(true, 3));
        expect("its tooltip key follows it there (" + key + ")", key != null && key.endsWith("3"));

        System.out.println(failures == 0 ? "PanelKeysCheck: PASS" : "PanelKeysCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PanelKeysCheck() {}

}
