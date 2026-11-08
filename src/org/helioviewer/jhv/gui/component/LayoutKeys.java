package org.helioviewer.jhv.gui.component;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.DisplayScale;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * The window's layout from the keyboard (Gilly, 2026-11-08). One modifier, and the key's place says
 * where it points: Cmd+[ the left sidebar, Cmd+] the right one, Cmd+\ the timeline between them.
 * Cmd+Shift+= and Cmd+Shift+- scale the whole interface, at once, and Cmd+Shift+0 puts it back to
 * 100%; Cmd+= and Cmd+- stay the picture's zoom. Ctrl in place of Cmd off a Mac.
 *
 * <p>Each is a catalogued command, so it can be rebound in Settings > Keybindings. Ids are API.
 */
public final class LayoutKeys {

    private LayoutKeys() {}

    /** Every command here, in menu order: id, name, key, shift, what it does. */
    private record Spec(String id, String name, int key, boolean shift, Runnable run, String description) {}

    private static final Spec[] SPECS = {
            new Spec("toggleLeftSidebar", "Show or Hide Left Sidebar", KeyEvent.VK_OPEN_BRACKET, false, LayoutKeys::toggleLeft,
                    "Fold the left sidebar to its rail, or open it"),
            new Spec("toggleRightSidebar", "Show or Hide Right Sidebar", KeyEvent.VK_CLOSE_BRACKET, false, LayoutKeys::toggleRight,
                    "Fold the right sidebar to its rail, or open it"),
            new Spec("toggleTimeline", "Show or Hide Timeline", KeyEvent.VK_BACK_SLASH, false, LayoutKeys::toggleTimeline,
                    "Show or hide the timeline pane under the picture"),
            new Spec("interfaceScaleUp", "Larger Interface", KeyEvent.VK_EQUALS, true, () -> DisplayScale.stepUi(1),
                    "Scale the whole interface (text, spacing, icons) one step up, at once"),
            new Spec("interfaceScaleDown", "Smaller Interface", KeyEvent.VK_MINUS, true, () -> DisplayScale.stepUi(-1),
                    "Scale the whole interface one step down, at once"),
            new Spec("interfaceScaleReset", "Interface at 100%", KeyEvent.VK_0, true, DisplayScale::resetUi,
                    "Put the interface scale back to 100%"),
    };

    /** For ActionCatalog.register(): each command with its default key. */
    public static void register(ActionCatalog.Registrar put) {
        for (Spec s : SPECS) {
            Action a = new AbstractAction(s.name()) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    s.run().run();
                }
            };
            a.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(s.key(),
                    DesktopIntegration.menuShortcutMask | (s.shift() ? InputEvent.SHIFT_DOWN_MASK : 0)));
            put.put(s.id(), a, (s.id().startsWith("interface") ? "View/Interface Scale/" : "View/") + s.name(), s.description());
        }
    }

    /** The three layout items, added straight into the View menu. */
    public static void addLayoutItems(JMenu view) {
        for (String id : new String[]{"toggleLeftSidebar", "toggleRightSidebar", "toggleTimeline"})
            add(view, id);
    }

    /** View > Interface Scale. */
    public static JMenu scaleMenu() {
        JMenu menu = new JMenu("Interface Scale");
        for (String id : new String[]{"interfaceScaleUp", "interfaceScaleDown", "interfaceScaleReset"})
            add(menu, id);
        return menu;
    }

    private static void add(JMenu menu, String id) {
        ActionCatalog.Entry e = ActionCatalog.get(id);
        if (e != null)
            menu.add(e.action()).setName(id);
    }

    static void toggleLeft() {
        MainFrame.setSidebarCollapsed(!MainFrame.isSidebarCollapsed());
        ToolBar.syncSidebarToggles();
    }

    static void toggleRight() {
        if (!MainFrame.isEastVisible())
            return; // nothing docked on the right: there is no sidebar to show
        RightSidebar.getInstance().setCollapsed(!RightSidebar.getInstance().isCollapsed());
        ToolBar.syncSidebarToggles();
    }

    static void toggleTimeline() {
        MainContentPanel panel = MainFrame.getMainContentPanel();
        if (panel == null)
            return;
        panel.setPluginsShowing(!panel.isPluginsShowing());
        ToolBar.syncTimelinesToggle();
    }
}
