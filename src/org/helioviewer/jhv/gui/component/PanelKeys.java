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
import org.helioviewer.jhv.gui.DesktopIntegration;
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

    /** Places per sidebar that get a key. */
    public static final int SLOTS = 8;

    private PanelKeys() {}

    /** The catalog id of place {@code n} (1-based) in the left or right sidebar. Ids are API. */
    public static String id(boolean right, int n) {
        return (right ? "foldRightPanel" : "foldLeftPanel") + n;
    }

    /** The default keystroke of place {@code n}: menu+N on the left, menu+Option+N (menu+Shift+N off a Mac) on the right. */
    static KeyStroke defaultKey(boolean right, int n) {
        int mods = DesktopIntegration.menuShortcutMask;
        if (right)
            mods |= Platform.isMacOS() ? InputEvent.ALT_DOWN_MASK : InputEvent.SHIFT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, mods);
    }

    /** The command for place {@code n}, for ActionCatalog to register. */
    public static Action action(boolean right, int n) {
        Action a = new AbstractAction((right ? "Right" : "Left") + " Sidebar Panel " + n) {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggle(right, n);
            }
        };
        a.putValue(Action.ACCELERATOR_KEY, defaultKey(right, n));
        return a;
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
