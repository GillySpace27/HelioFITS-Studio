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
                // Ctrl+N left (Shift+Option+N on a Mac), Option+N right; off a Mac, Ctrl+9 and Ctrl+0 stay the zoom keys
                KeyStroke want = !right && (n == 9 || n == 0) && !mac ? null
                        : KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, (right ? InputEvent.ALT_DOWN_MASK : mac ? InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK : InputEvent.CTRL_DOWN_MASK)); // a Mac's left side is Shift+Option: bare Ctrl+digit is the system's desktop switch
                Object got = e == null ? "missing" : e.action().getValue(Action.ACCELERATOR_KEY);
                keysOk &= e != null && java.util.Objects.equals(want, got);
                if (e != null && want != null)
                    clashFree &= KeyBindings.set(e.id(), want) == null; // refused if another command or the system has it
            }
        expect("foldLeftPanel1..9 / foldRightPanel1..9 and the two fold-alls are catalogued with Ctrl+N and Option+N (Shift+Option+N and Option+N on a Mac)", keysOk);
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

        // CommandKeys: the key is matched by its code, with the shifted symbols a Mac names read as their keys
        java.awt.Component src = new JPanel();
        int shift = InputEvent.SHIFT_DOWN_MASK;
        expect("a key press finds the command that owns it", CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, menu, KeyEvent.VK_OPEN_BRACKET, '['))
                == ActionCatalog.get("toggleLeftSidebar").action());
        expect("Cmd+Shift+= arriving as '+' still scales the interface up", CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, menu | shift, KeyEvent.VK_PLUS, '+'))
                == ActionCatalog.get("interfaceScaleUp").action());
        expect("Cmd+Shift+- arriving as '_' scales it down", CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, menu | shift, KeyEvent.VK_UNDERSCORE, '_'))
                == ActionCatalog.get("interfaceScaleDown").action());
        expect("Cmd+Shift+0 arriving as ')' puts it back", CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, menu | shift, KeyEvent.VK_RIGHT_PARENTHESIS, ')'))
                == ActionCatalog.get("interfaceScaleReset").action());
        expect("a key with no keycode is read from its character", CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, menu | shift, KeyEvent.VK_UNDEFINED, '+'))
                == ActionCatalog.get("interfaceScaleUp").action());
        expect("Cmd+= without Shift stays the picture's zoom, not the interface scale", CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, menu, KeyEvent.VK_EQUALS, '=')) == null);
        int rightMask = PanelKeys.sideMask(true), leftMask = PanelKeys.sideMask(false);
        expect("the section keys are found by code whatever character the modifier makes (Option+2 types a symbol; left mask " + leftMask + ", right " + rightMask + ")",
                CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, rightMask, KeyEvent.VK_2, '\u2122')) == ActionCatalog.get("foldRightPanel2").action()
                        && CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, leftMask, KeyEvent.VK_2, '2')) == ActionCatalog.get("foldLeftPanel2").action());
        expect("the two sides need different modifiers, and only exactly those", leftMask != rightMask
                && CommandKeys.commandFor(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, leftMask | rightMask | InputEvent.META_DOWN_MASK, KeyEvent.VK_2, '2')) == null);
        expect("holding exactly a side's modifiers lights that side", PanelKeyHint.sideOf(leftMask) == 'L' && PanelKeyHint.sideOf(rightMask) == 'R'
                && PanelKeyHint.sideOf(InputEvent.SHIFT_DOWN_MASK) == 0);

        // System keys are never taken, and one press that arrives twice runs a command once
        for (int code : new int[]{KeyEvent.VK_TAB, KeyEvent.VK_Q, KeyEvent.VK_H, KeyEvent.VK_M, KeyEvent.VK_SPACE, KeyEvent.VK_BACK_QUOTE})
            expect("Cmd+" + KeyEvent.getKeyText(code) + " is a system key, passed over untouched",
                    CommandKeys.isSystemKey(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, InputEvent.META_DOWN_MASK, code, KeyEvent.CHAR_UNDEFINED))
                            && !CommandKeys.onKey(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, InputEvent.META_DOWN_MASK, code, KeyEvent.CHAR_UNDEFINED)));
        expect("Cmd+Shift+Tab too", CommandKeys.isSystemKey(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_TAB, '\t')));
        expect("a key that is not a system key is not one", !CommandKeys.isSystemKey(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, InputEvent.META_DOWN_MASK, KeyEvent.VK_OPEN_BRACKET, '[')));
        String tabRefusal = KeyBindings.set("duplicateLayer", KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.META_DOWN_MASK));
        KeyBindings.reset("duplicateLayer");
        expect("Cmd+Tab cannot be given to a command on a Mac", tabRefusal != null || !mac);
        long t0Press = 5_000_000;
        expect("the same command twice within a tap is one press (the system menu and the router both delivering it)",
                CommandKeys.fresh("foldRightPanelAll", t0Press) && !CommandKeys.fresh("foldRightPanelAll", t0Press + 30));
        expect("but a second press after that is a press, as is another command at once", CommandKeys.fresh("foldRightPanelAll", t0Press + 30 + CommandKeys.DOUBLE_MS + 1)
                && CommandKeys.fresh("toggleTimeline", t0Press + 30 + CommandKeys.DOUBLE_MS + 2));

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

        // Collapse all: three escalating presses, never an unfold
        if (!bar.isUnfolded("Alpha"))
            PanelKeys.toggle(true, 1);
        if (!bar.isUnfolded("Beta"))
            PanelKeys.toggle(true, 2);
        long t0 = 1_000_000;
        int first = PanelKeys.collapseAllAt(true, t0);
        boolean allFolded = !bar.isUnfolded("Alpha") && !bar.isUnfolded("Beta") && !bar.isUnfolded("Gamma");
        expect("the first press folds every section of the right sidebar", first == 1 && allFolded);
        PanelKeys.toggle(true, 1); // open one by hand between presses
        int second = PanelKeys.collapseAllAt(true, t0 + 4000);
        expect("a second press within 5 seconds folds both sides (and counts 2)", second == 2 && !bar.isUnfolded("Alpha"));
        int third = PanelKeys.collapseAllAt(true, t0 + 8000); // 4 s after the second: the window slides
        expect("a third press is the full collapse (the sidebars and timeline need a window, so only the count is checked here)", third == 3);
        expect("further presses stay at the third and still never unfold", PanelKeys.collapseAllAt(true, t0 + 9000) == 3 && !bar.isUnfolded("Alpha"));
        bar.setCollapsed(false);
        int again = PanelKeys.collapseAllAt(true, t0 + 9000 + PanelKeys.WINDOW_MS + 1);
        expect("after more than 5 seconds it starts over with the first press, which leaves the sidebar where it is", again == 1 && !bar.isCollapsed());
        PanelKeys.collapseAllAt(false, t0 + 20000);
        expect("started from the left key, the left sidebar is the first target",
                PanelKeys.collapseAllAt(false, t0 + 20001) == 2);
        PanelKeys.collapseAllAt(false, t0 + 40000); // let it lapse and reopen for what follows
        bar.setCollapsed(false);
        for (String t : new String[]{"Alpha", "Beta", "Gamma"})
            if (!bar.isUnfolded(t))
                PanelKeys.toggle(true, "Alpha".equals(t) ? 1 : "Beta".equals(t) ? 2 : 3);

        CollapsiblePaneButton btn = PanelKeys.at(true, 2).toggleButton;
        btn.setSize(260, 24);
        String textBefore = btn.getText();
        java.awt.Dimension sizeBefore = btn.getPreferredSize();
        java.awt.image.BufferedImage plain = new java.awt.image.BufferedImage(260, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        btn.paint(plain.getGraphics());
        java.awt.image.BufferedImage plain2 = new java.awt.image.BufferedImage(260, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        btn.paint(plain2.getGraphics());
        // Both real panes: a left sidebar's content beside the right sidebar, folded left first
        SideContentPane left = new SideContentPane();
        left.add("One", new JPanel(), true);
        left.add("Two", new JPanel(), true);
        PanelKeys.leftPaneForCheck(left);
        for (String tt : new String[]{"Alpha", "Beta", "Gamma"})
            if (!bar.isUnfolded(tt))
                PanelKeys.toggle(true, "Alpha".equals(tt) ? 1 : "Beta".equals(tt) ? 2 : 3);
        long u0 = 90_000_000;
        PanelKeys.collapseAllAt(false, u0); // Shift+Option+0: the left first
        boolean leftFolded = PanelKeys.shown(left).stream().noneMatch(p -> p.toggleButton.isSelected());
        expect("started from the left key, the first press folds the left sections and leaves the right ones", leftFolded
                && (bar.isUnfolded("Alpha") || bar.isUnfolded("Beta") || bar.isUnfolded("Gamma")));
        PanelKeys.collapseAllAt(false, u0 + 1000);
        expect("and the second folds the right ones as well", !bar.isUnfolded("Alpha") && !bar.isUnfolded("Beta") && !bar.isUnfolded("Gamma"));
        PanelKeys.collapseAllAt(false, u0 + 20000); // lapse
        PanelKeys.leftPaneForCheck(null);
        for (String tt : new String[]{"Alpha", "Beta", "Gamma"})
            if (!bar.isUnfolded(tt))
                PanelKeys.toggle(true, "Alpha".equals(tt) ? 1 : "Beta".equals(tt) ? 2 : 3);

        // The sections were folded and opened since the first paint, and the Mac look and feel draws a
        // header by its state: the unlit picture to compare with is taken now, in the state the lit one is
        btn.paint(plain.getGraphics());
        btn.paint(plain2.getGraphics());
        PanelKeyHint.lightForCheck('R');
        java.awt.image.BufferedImage lit = new java.awt.image.BufferedImage(260, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        btn.paint(lit.getGraphics());
        expect("holding Option leaves the header's text and size exactly as they were (no jitter)",
                btn.getText().equals(textBefore) && btn.getPreferredSize().equals(sizeBefore));
        java.awt.Rectangle box = btn.badgeBox(btn.getFontMetrics(btn.getFont()), btn.getText().startsWith("R2"));
        expect("and boxes the number: the box is where the number is, and painted", box != null && box.width > 4
                && lit.getRGB(box.x + 2, box.y + box.height / 2) != plain.getRGB(box.x + 2, box.y + box.height / 2));
        // Glyph edges next to the box may antialias differently under a clip (seen on the Mac's look and feel): leave a margin
        int firstX = box == null ? 0 : box.x + box.width + 4;
        int diffCount = 0, noise = 0, diffX = -1, diffY = -1;
        for (int x = firstX; x < 260; x++)
            for (int y = 0; y < 24; y++) {
                if (plain.getRGB(x, y) != plain2.getRGB(x, y))
                    noise++; // two paints of the same state differ: the look and feel is not deterministic here
                if (lit.getRGB(x, y) != plain.getRGB(x, y) && diffCount++ == 0) {
                    diffX = x;
                    diffY = y;
                }
            }
        boolean diffElsewhere = diffCount > noise;
        expect("nothing else on the header changes, so the title does not move" + (diffElsewhere ? " [" + diffCount + " pixels, first at " + diffX + "," + diffY + ", noise " + noise + ", box " + box + ", text \"" + btn.getText() + "\", L&F " + javax.swing.UIManager.getLookAndFeel().getName() + "]" : ""), !diffElsewhere);
        PanelKeyHint.cancel();
        expect("letting go takes the box away", btn.badgeBox(btn.getFontMetrics(btn.getFont()), true) == null && btn.getText().equals(textBefore));

        // The box's colours: 4.5:1 for the number in every theme, and clear of the band behind it
        double worstText = 99, worstBand = 99;
        String worstTheme = "";
        for (org.helioviewer.jhv.app.Theme t : org.helioviewer.jhv.app.Theme.all())
            for (boolean child : new boolean[]{false, true}) {
                java.awt.Color band = PanelVisibility.headerFill(t, child);
                java.awt.Color[] c = PanelVisibility.badgeColors(t, band);
                worstText = Math.min(worstText, org.helioviewer.jhv.app.Theme.contrast(c[1], c[0]));
                double vsBand = org.helioviewer.jhv.app.Theme.contrast(c[0], band);
                if (vsBand < worstBand) {
                    worstBand = vsBand;
                    worstTheme = t.name();
                }
            }
        expect(String.format("the number reads on its box in every theme (worst %.1f:1, needs 4.5)", worstText), worstText >= 4.5);
        expect(String.format("and the box stands out from its band (worst %.1f:1 in %s, needs 2)", worstBand, worstTheme), worstBand >= 2);

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
