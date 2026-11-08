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
        boolean mac = Platform.isMacOS();
        boolean keysOk = true, clashFree = true;
        for (boolean right : new boolean[]{false, true})
            for (int n = 0; n <= PanelKeys.SLOTS; n++) {
                ActionCatalog.Entry e = ActionCatalog.get(n == 0 ? PanelKeys.idAll(right) : PanelKeys.id(right, n));
                // Ctrl+N left, Option+N right; off a Mac, Ctrl+9 and Ctrl+0 stay the zoom keys
                KeyStroke want = !right && (n == 9 || n == 0) && !mac ? null
                        : KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, right ? InputEvent.ALT_DOWN_MASK : InputEvent.CTRL_DOWN_MASK);
                Object got = e == null ? "missing" : e.action().getValue(Action.ACCELERATOR_KEY);
                keysOk &= e != null && java.util.Objects.equals(want, got);
                if (e != null && want != null)
                    clashFree &= KeyBindings.set(e.id(), want) == null; // refused if another command or the system has it
            }
        expect("foldLeftPanel1..9 / foldRightPanel1..9 and the two fold-alls are catalogued with Ctrl+N and Option+N", keysOk);
        expect("none of those keys is taken by another command or by the system", clashFree);
        expect("Cmd+9 and Cmd+0 stay Zoom to Fit and Actual Size", KeyStroke.getKeyStroke(KeyEvent.VK_9, menu).equals(KeyBindings.current("zoomFit"))
                && KeyStroke.getKeyStroke(KeyEvent.VK_0, menu).equals(KeyBindings.current("zoomOne")));
        boolean layoutOk = true;
        String[][] layout = {{"toggleLeftSidebar", "" + KeyEvent.VK_OPEN_BRACKET, "0"}, {"toggleRightSidebar", "" + KeyEvent.VK_CLOSE_BRACKET, "0"},
                {"toggleTimeline", "" + KeyEvent.VK_BACK_SLASH, "0"}, {"interfaceScaleUp", "" + KeyEvent.VK_EQUALS, "1"},
                {"interfaceScaleDown", "" + KeyEvent.VK_MINUS, "1"}, {"interfaceScaleReset", "" + KeyEvent.VK_0, "1"}};
        for (String[] l : layout) {
            KeyStroke want = KeyStroke.getKeyStroke(Integer.parseInt(l[1]), menu | ("1".equals(l[2]) ? InputEvent.SHIFT_DOWN_MASK : 0));
            ActionCatalog.Entry e = ActionCatalog.get(l[0]);
            layoutOk &= e != null && want.equals(KeyBindings.current(l[0]));
        }
        expect("Cmd+[ Cmd+] Cmd+\\ toggle the sidebars and timeline; Cmd+Shift+= - 0 scale the interface", layoutOk);
        expect("Cmd+= and Cmd+- stay the picture's zoom", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu).equals(KeyBindings.current("zoomIn"))
                && KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu).equals(KeyBindings.current("zoomOut")));

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

        // Fold-all: a flip that remembers what was open
        if (!bar.isUnfolded("Alpha"))
            PanelKeys.toggle(true, 1);
        if (bar.isUnfolded("Beta"))
            PanelKeys.toggle(true, 2);
        PanelKeys.toggleAll(true);
        boolean allFolded = !bar.isUnfolded("Alpha") && !bar.isUnfolded("Beta") && !bar.isUnfolded("Gamma");
        PanelKeys.toggleAll(true);
        expect("fold-all folds every section", allFolded);
        expect("and the next press opens exactly the ones that were open", bar.isUnfolded("Alpha") && !bar.isUnfolded("Beta") && bar.isUnfolded("Gamma"));
        PanelKeys.toggle(true, 1); // fold the two open ones by hand: everything folded, nothing remembered
        PanelKeys.toggle(true, 3);
        PanelKeys.toggleAll(true);
        expect("with nothing remembered it opens them all", bar.isUnfolded("Alpha") && bar.isUnfolded("Beta") && bar.isUnfolded("Gamma"));

        PanelKeyHint.lightForCheck('R');
        expect("holding Option bolds the right numbers", PanelKeys.at(true, 2).toggleButton.getText().startsWith("<html><b>R2.</b>"));
        PanelKeyHint.cancel();
        expect("and letting go puts them back", !PanelKeys.at(true, 2).toggleButton.getText().startsWith("<html>"));

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
