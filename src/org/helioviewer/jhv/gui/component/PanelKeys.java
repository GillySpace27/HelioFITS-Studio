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
 * Fold or show a sidebar section from the keyboard, by its place in the sidebar: Cmd+1 to Cmd+8 for
 * the left one, Cmd+Option+1 to Cmd+Option+8 for the right one (Ctrl+N and Ctrl+Shift+N elsewhere).
 *
 * <p>Positional on purpose (Gilly, 2026-11-07): the key follows the number drawn in front of the
 * section's title, so moving a section changes which key reaches it, and what you see is what the
 * key does. Eight a side, because Cmd+9 and Cmd+0 are Zoom to Fit and Actual Size.
 *
 * <p>Not Cmd+Shift+N for the right side on a Mac: Cmd+Shift+3, 4 and 5 are the system's screenshot
 * keys. Not Ctrl+Alt+N elsewhere: on many keyboard layouts Ctrl+Alt is AltGr, which types characters.
 *
 * <p>Each slot is an ordinary catalogued command ({@code foldLeftPanel1} to {@code foldRightPanel8}),
 * so it can be rebound in Settings > Keybindings, found with Cmd+K, and run by name.
 */
public final class PanelKeys {

    /** Places per sidebar that get a command. */
    public static final int SLOTS = 9;

    private PanelKeys() {}

    /** The catalog id of place {@code n} (1-based) in the left or right sidebar. Ids are API. */
    public static String id(boolean right, int n) {
        return (right ? "foldRightPanel" : "foldLeftPanel") + n;
    }

    /** The catalog id of the fold-all command of a side. Ids are API. */
    public static String idAll(boolean right) {
        return right ? "foldRightPanelAll" : "foldLeftPanelAll";
    }

    /** The modifier that reaches a side: Ctrl on the left, Option (Alt) on the right. */
    static int sideMask(boolean right) {
        return right ? InputEvent.ALT_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /**
     * The default keystroke of place {@code n} (0 for fold-all), or null for none: Ctrl+N on the
     * left, Option+N on the right. Off a Mac, Ctrl+9 and Ctrl+0 are the zoom keys, so left 9 and
     * left fold-all have none.
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
                toggle(right, n);
            }
        };
        KeyStroke ks = defaultKey(right, n);
        if (ks != null)
            a.putValue(Action.ACCELERATOR_KEY, ks);
        return a;
    }

    /** The fold-all command of a side, for ActionCatalog to register. */
    public static Action actionAll(boolean right) {
        Action a = new AbstractAction("Fold or Restore All " + (right ? "Right" : "Left") + " Sidebar Panels") {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleAll(right);
            }
        };
        KeyStroke ks = defaultKey(right, 0);
        if (ks != null)
            a.putValue(Action.ACCELERATOR_KEY, ks);
        return a;
    }

    /** The titles fold-all closed on each side, so the next press opens exactly those. Index 0 left, 1 right. */
    private static final List<java.util.Set<String>> REMEMBERED = List.of(new java.util.LinkedHashSet<>(), new java.util.LinkedHashSet<>());

    /**
     * Fold every section of a side if any is open, remembering which were; otherwise open the ones
     * remembered (all of them when nothing is remembered, or none of those is still there). A flip,
     * not a setting: press it twice and the sidebar is as it was.
     */
    public static void toggleAll(boolean right) {
        List<CollapsiblePane> panes = shown(paneOf(right));
        java.util.Set<String> remembered = REMEMBERED.get(right ? 1 : 0);
        List<CollapsiblePane> open = new ArrayList<>();
        for (CollapsiblePane p : panes)
            if (!p.isPinned() && p.toggleButton.isSelected())
                open.add(p);
        if (!open.isEmpty()) {
            remembered.clear();
            for (CollapsiblePane p : open) {
                remembered.add(p.getTitle());
                p.toggleButton.doClick();
            }
        } else {
            boolean any = false;
            for (CollapsiblePane p : panes)
                any |= remembered.contains(p.getTitle());
            for (CollapsiblePane p : panes)
                if (!p.isPinned() && (!any || remembered.contains(p.getTitle())))
                    p.toggleButton.doClick();
            remembered.clear();
            boolean hidden = right ? RightSidebar.getInstance().isCollapsed() : MainFrame.isSidebarCollapsed();
            if (hidden && right)
                RightSidebar.getInstance().setCollapsed(false);
            else if (hidden)
                MainFrame.setSidebarCollapsed(false);
        }
        Palette.syncToggles();
    }

    @Nullable
    private static SideContentPane paneOf(boolean right) {
        return right ? RightSidebar.getInstance().pane() : MainFrame.getLeftContentPane();
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
