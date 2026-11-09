package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.registry.KeyBindings;
import org.helioviewer.jhv.gui.registry.Shortcuts;

/**
 * Fold or show a sidebar section from the keyboard, by its place in the sidebar: Ctrl+1 to Ctrl+9 for
 * the left one, Alt+1 to Alt+9 for the right one. On a Mac, Option+N for the right one and Ctrl+Option+N
 * for the left: bare Ctrl+digit is the system's switch-to-desktop.
 *
 * <p>Positional on purpose (Gilly, 2026-11-07): the key follows the number drawn in front of the
 * section's title, so moving a section changes which key reaches it, and what you see is what the
 * key does. Off a Mac, Ctrl+9 and Ctrl+0 are the zoom keys, so the left side has eight there.
 *
 * <p>Not Cmd+Shift+N for the right side on a Mac: Cmd+Shift+3, 4 and 5 are the system's screenshot
 * keys. Not Ctrl+Alt+N elsewhere: on many keyboard layouts Ctrl+Alt is AltGr, which types characters.
 *
 * <p>Each slot is an ordinary catalogued command ({@code foldLeftPanel1} to {@code foldRightPanel9}),
 * so it can be rebound in Settings > Keybindings, found with Cmd+K, and run by name. {@link CommandKeys}
 * hands the keys to them ahead of the menu bar.
 */
public final class PanelKeys {

    /** Places per sidebar that get a command. */
    public static final int SLOTS = 9;

    private PanelKeys() {}

    /** The catalog id of place {@code n} (1-based) in the left or right sidebar. Ids are API. */
    public static String id(boolean right, int n) {
        return (right ? "foldRightPanel" : "foldLeftPanel") + n;
    }

    /** The catalog id of the collapse-all command that starts with a side. Ids are API (they began as fold-all). */
    public static String idAll(boolean right) {
        return right ? "foldRightPanelAll" : "foldLeftPanelAll";
    }

    /**
     * The modifiers that reach a side. On a Mac (Gilly, 2026-11-08): Option on the right, Ctrl+Option
     * on the left; bare Ctrl+digit is the system's "switch to desktop", so the left side adds Option.
     * Option+digit types a symbol, which {@link CommandKeys} sees past by matching the key's code.
     * Elsewhere: Ctrl on the left, Alt on the right (Ctrl+Alt is AltGr on many layouts).
     */
    static int sideMask(boolean right) {
        if (right)
            return InputEvent.ALT_DOWN_MASK;
        return Platform.isMacOS() ? InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /**
     * The default keystroke of place {@code n} (0 for collapse-all), or null for none: Ctrl+N on the
     * left, Alt+N on the right (Ctrl+Option+N and Option+N on a Mac). Off a Mac, Ctrl+9 and Ctrl+0 are the zoom keys, so left 9 and
     * left collapse-all have none.
     */
    @Nullable
    static KeyStroke defaultKey(boolean right, int n) {
        if (!right && (n == 9 || n == 0) && !Platform.isMacOS())
            return null;
        return KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, sideMask(right));
    }

    /** The command for place {@code n}, for ActionCatalog to register. */
    public static Action action(boolean right, int n) {
        Action a = new AbstractAction((right ? "Right" : "Left") + " Sidebar Panel " + n) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (CommandKeys.fresh(id(right, n)))
                    toggle(right, n);
            }
        };
        KeyStroke ks = defaultKey(right, n);
        if (ks != null)
            a.putValue(Action.ACCELERATOR_KEY, ks);
        return a;
    }

    /** The collapse-all command that starts with a side, for ActionCatalog to register. */
    public static Action actionAll(boolean right) {
        Action a = new AbstractAction("Collapse All, " + (right ? "Right" : "Left") + " First") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (CommandKeys.fresh(idAll(right)))
                    collapseAll(right);
            }
        };
        KeyStroke ks = defaultKey(right, 0);
        if (ks != null)
            a.putValue(Action.ACCELERATOR_KEY, ks);
        return a;
    }

    /** How long after a press the next one still counts as the same sequence (Gilly, 2026-11-08). */
    static final long WINDOW_MS = 5000;
    /** Last press time, then the presses so far in this sequence; in a final holder (guards.py ratchet). */
    private static final long[] SEQUENCE = {0, 0};
    /** A check's own left sidebar content, where there is no main window; null in the application. A final holder (guards.py ratchet). */
    private static final SideContentPane[] LEFT_FOR_CHECK = {null};

    /** For a check only: the pane that stands for the left sidebar. */
    static void leftPaneForCheck(@Nullable SideContentPane pane) {
        LEFT_FOR_CHECK[0] = pane;
    }

    /** The side the sequence began with: true for the right. */
    private static final boolean[] FIRST = {false};

    /**
     * Collapse all, in three escalating presses within {@link #WINDOW_MS} of one another: the first
     * folds every section on one side (the right for Option+0, the left for Ctrl+Option+0), the
     * second folds the other side's too, the third collapses both sidebars and the timeline strip.
     * It never unfolds anything; a pause longer than the window starts over. Returns which press this
     * was (1 to 3; more presses stay at 3).
     */
    public static int collapseAll(boolean rightFirst) {
        return collapseAllAt(rightFirst, System.currentTimeMillis());
    }

    static int collapseAllAt(boolean rightFirst, long nowMs) {
        if (nowMs - SEQUENCE[0] > WINDOW_MS || SEQUENCE[1] == 0) {
            SEQUENCE[1] = 0;
            FIRST[0] = rightFirst;
        }
        SEQUENCE[0] = nowMs;
        int press = (int) Math.min(3, ++SEQUENCE[1]);
        SEQUENCE[1] = press;
        org.helioviewer.jhv.app.Log.info("Collapse All: press " + press + ", " + (FIRST[0] ? "right" : "left") + " first");
        switch (press) {
            case 1 -> foldAll(FIRST[0]);
            case 2 -> {
                foldAll(FIRST[0]);
                foldAll(!FIRST[0]);
            }
            default -> {
                foldAll(true);
                foldAll(false);
                LayoutKeys.collapseEverything();
            }
        }
        Palette.syncToggles();
        return press;
    }

    /** Fold every open, unpinned section of a side. */
    static void foldAll(boolean right) {
        for (CollapsiblePane p : shown(paneOf(right)))
            if (!p.isPinned() && p.toggleButton.isSelected())
                p.toggleButton.doClick();
    }

    @Nullable
    private static SideContentPane paneOf(boolean right) {
        return right ? RightSidebar.getInstance().pane() : LEFT_FOR_CHECK[0] != null ? LEFT_FOR_CHECK[0] : MainFrame.getLeftContentPane();
    }

    /**
     * The sections shown in {@code pane}, top to bottom: what the numbers in front of their titles
     * count (CollapsiblePane.renumberSiblings), so the keys and the numbers always agree.
     */
    static List<CollapsiblePane> shown(@Nullable SideContentPane pane) {
        List<CollapsiblePane> out = new ArrayList<>();
        if (pane != null)
            for (Component c : pane.getComponents())
                if (c instanceof CollapsiblePane p && p.isVisible())
                    out.add(p);
        return out;
    }

    /** The section at place {@code n} (1-based) of a sidebar, or null when there is none there. */
    @Nullable
    public static CollapsiblePane at(boolean right, int n) {
        List<CollapsiblePane> panes = shown(paneOf(right));
        return n >= 1 && n <= panes.size() ? panes.get(n - 1) : null;
    }

    /**
     * Fold the section at place {@code n}, or show it: unfold it, and open its sidebar if that is
     * collapsed. A section in a collapsed sidebar is always shown, never folded, because folding
     * something you cannot see would look like the key did nothing.
     */
    public static void toggle(boolean right, int n) {
        CollapsiblePane pane = at(right, n);
        if (pane == null)
            return;
        String title = pane.getTitle();
        SectionHost host = right ? RightSidebar.getInstance() : LeftSidebar.getInstance();
        boolean hidden = right ? RightSidebar.getInstance().isCollapsed() : MainFrame.isSidebarCollapsed();
        if (host.hasSection(title)) {
            if (hidden)
                host.reveal(title);
            else
                host.revealOrFold(title);
        } else { // a section put in the pane directly rather than through the host: fold it the way its header does
            if (hidden && !right)
                MainFrame.setSidebarCollapsed(false);
            else if (hidden)
                RightSidebar.getInstance().setCollapsed(false);
            if (!hidden || !pane.toggleButton.isSelected())
                pane.toggleButton.doClick();
        }
        Palette.syncToggles(); // the toolbar buttons of docked palettes show whether they are unfolded
    }

    /**
     * The key for a section, as a person reads it ("⌘2"), or null when its place has none. Null too
     * before the catalog is built: this is read for a tooltip, which Swing asks for while a section is
     * still being put together, and that must not be what builds every command in the application.
     */
    @Nullable
    static String keyText(CollapsiblePane pane) {
        if (!ActionCatalog.isBuilt())
            return null;
        for (boolean right : new boolean[]{false, true}) {
            SideContentPane host = paneOf(right);
            if (host == null || pane.getParent() != host)
                continue;
            int place = shown(host).indexOf(pane) + 1;
            if (place < 1 || place > SLOTS)
                return null;
            KeyStroke ks = KeyBindings.current(id(right, place));
            return ks == null ? null : Shortcuts.text(ks);
        }
        return null;
    }

    /**
     * View > Left Sidebar Panels (or Right): one item per place, named after whatever is there when
     * the menu opens. Every item stays in the menu and enabled even when its place is empty, because a
     * menu item that is hidden or disabled also loses its keyboard shortcut, and the place may fill
     * again without the menu ever being opened.
     */
    public static JMenu menu(boolean right) {
        JMenu menu = new JMenu(right ? "Right Sidebar Panels" : "Left Sidebar Panels");
        menu.setToolTipText("Fold or show a section by its number in the sidebar; moving a section changes its number");
        List<JMenuItem> items = new ArrayList<>();
        ActionCatalog.Entry all = ActionCatalog.get(idAll(right));
        if (all != null) {
            JMenuItem item = menu.add(all.action());
            item.setName(all.id());
            menu.addSeparator();
        }
        for (int n = 1; n <= SLOTS; n++) {
            ActionCatalog.Entry entry = ActionCatalog.get(id(right, n));
            if (entry == null)
                continue;
            JMenuItem item = menu.add(entry.action());
            item.setName(entry.id()); // ActionCatalog.find, help search and tours reach it by id
            items.add(item);
        }
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                for (int i = 0; i < items.size(); i++)
                    items.get(i).setText(label(right, i + 1));
            }

            @Override
            public void menuDeselected(MenuEvent e) {
                // nothing to undo: the labels are only read while the menu is open
            }

            @Override
            public void menuCanceled(MenuEvent e) {
                // as menuDeselected
            }
        });
        return menu;
    }

    /** "L2. Image Layers", or "R7. (no section)" for an empty place. */
    static String label(boolean right, int n) {
        CollapsiblePane pane = at(right, n);
        return (right ? "R" : "L") + n + ". " + (pane == null ? "(no section)" : pane.getTitle());
    }

}
