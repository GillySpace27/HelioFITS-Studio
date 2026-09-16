package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;

// This panel acts as a container for the GUI elements which are shown in the
// main area of the application. Usually it contains the main image area. Below
// the main image area plug-ins are able to display their GUI components.
@SuppressWarnings("serial")
public final class MainContentPanel extends JPanel {

    private static final int DIVIDER_SIZE = 5;
    private static final double NORMAL_RESIZE_WEIGHT = 0.75;

    private final ArrayList<Interfaces.MainContentPanelPlugin> pluginList = new ArrayList<>();

    private final JSplitPane splitPane;
    /** How tall the plugins pane was when it was last hidden, so showing it gives that back. */
    private int savedDivider = -1;

    private final JPanel pluginContainer;
    private final CollapsiblePane collapsiblePane;
    private final JButton maximizeButton;

    private boolean pluginMaximized;
    private int normalDividerLocation;

    public MainContentPanel(Component mainComponent) {
        pluginContainer = new JPanel(new BorderLayout());
        collapsiblePane = new CollapsiblePane("Plugins", pluginContainer, !"false".equals(Settings.getProperty("display.plugins")));
        collapsiblePane.toggleButton.addActionListener(e -> updateLayout());

        maximizeButton = Buttons.flat(Buttons.maximizePanel);
        // Upstream pinned the old CollapsiblePaneButton to 28px so the glyph did not shift as the
        // chevron changed direction. A flat button with an Icon is already sized by the icon, and
        // both chevrons measure the same, so there is nothing left to pin.
        maximizeButton.addActionListener(e -> togglePluginMaximized());
        collapsiblePane.setAccessory(maximizeButton);
        updateMaximizeButton();

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerSize(0);
        splitPane.setBorder(null);
        splitPane.setResizeWeight(NORMAL_RESIZE_WEIGHT);

        splitPane.setTopComponent(mainComponent);

        setLayout(new BorderLayout());
        setMinimumSize(new Dimension());
        add(splitPane, BorderLayout.CENTER);
    }

    // Adds a plug-in and the associated GUI to the container. The GUI will be displayed below the main component.
    // Presentation mode folds the plugins pane (timelines, SWEK) away without touching the
    // user's own "display.plugins" preference, so leaving the mode restores what they had.
    public void setPluginsVisible(boolean visible) {
        // Maximized hides the render surface, which has to come back along with the canvas.
        if (!visible && pluginMaximized)
            restorePluginSize();
        boolean atBottom = splitPane.getBottomComponent() == collapsiblePane;
        // Remember how tall it was. Hiding the bottom component of a JSplitPane leaves the divider
        // where the now-zero-sized child drags it, which is the bottom, so showing it again gave
        // it back with no height at all: the pane returned as a title bar and looked like it had
        // come back collapsed. The fold state itself was never lost; the height was.
        if (!visible && atBottom && collapsiblePane.isVisible() && splitPane.getDividerLocation() > 0)
            savedDivider = splitPane.getDividerLocation();

        collapsiblePane.setVisible(visible);
        splitPane.setDividerSize(visible && atBottom ? DIVIDER_SIZE : 0);
        revalidate();
        repaint();

        // After the layout that revalidate schedules, or the split pane overwrites it: setting a
        // divider location on a component that has not been laid out yet is the classic way to
        // have it silently ignored.
        if (visible && atBottom && savedDivider > 0)
            javax.swing.SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(savedDivider));
    }

    /**
     * Unfolds the plugins pane, if it was folded, so something drawn in it can actually be seen.
     *
     * <p>setExpanded alone is not enough: the pane's own toggle runs updateLayout through an
     * action listener, and setSelected on the button does not fire one, so an expand from code
     * would leave the split pane still sized for a collapsed section.
     */
    public void revealPlugins() {
        if (!collapsiblePane.toggleButton.isSelected()) {
            collapsiblePane.setExpanded(true);
            updateLayout();
        }
    }

    /**
     * Whether the plugins pane is actually on screen: present AND unfolded.
     *
     * <p>Two separate things can hide it. Presentation mode takes the whole pane away with
     * {@link #setPluginsVisible}, and the user folds it with its own header. A toolbar toggle has
     * to mean "is the timeline showing", which is both of them at once.
     */
    public boolean isPluginsShowing() {
        return collapsiblePane.isVisible() && collapsiblePane.toggleButton.isSelected();
    }

    /** Show or hide the plugins pane outright, whichever of the two was hiding it. */
    public void setPluginsShowing(boolean showing) {
        setPluginsVisible(showing);
        if (showing)
            revealPlugins();
    }

    public void addPlugin(Interfaces.MainContentPanelPlugin plugin) {
        if (plugin == null || pluginList.contains(plugin) || plugin.getVisualInterfaces().isEmpty()) {
            return;
        }
        ComponentUtils.setVisible(plugin.getVisualInterfaces().getFirst(), collapsiblePane.toggleButton.isSelected());
        pluginList.add(plugin);
        updateLayout();
    }

    // Removes a plug-in and the associated GUI from the container
    public void removePlugin(Interfaces.MainContentPanelPlugin plugin) {
        if (pluginList.remove(plugin)) {
            if (!plugin.getVisualInterfaces().isEmpty())
                ComponentUtils.setVisible(plugin.getVisualInterfaces().getFirst(), false);
            updateLayout();
        }
    }

    // Updates the layout of the container and its subcomponents. Plug-ins will
    // be displayed, if available, in separated tabs below the main component
    // area. A split pane will be provided, if necessary, to readjust the
    // height of the components.
    private void updateLayout() {
        updateLayoutImpl();
        // Collapsing or expanding the timelines/plugins panel resizes the canvas the same way the
        // sidebar does, so the native surface needs the same synchronous re-sync.
        MainFrame.resyncRenderSurface();
    }

    private void updateLayoutImpl() {
        if ((pluginList.isEmpty() || !collapsiblePane.toggleButton.isSelected()) && pluginMaximized)
            restorePluginSize();

        splitPane.remove(collapsiblePane);
        remove(collapsiblePane);
        splitPane.setDividerSize(0);

        if (pluginList.isEmpty()) {
            pluginContainer.removeAll();
            revalidate();
            repaint();
            return;
        }

        boolean isSelected = collapsiblePane.toggleButton.isSelected();
        boolean onePlugin = pluginList.size() == 1 && pluginList.getFirst().getVisualInterfaces().size() == 1;
        collapsiblePane.setTitle(onePlugin ? pluginList.getFirst().getTabName() : "Plugins");

        if (isSelected) {
            pluginContainer.removeAll();

            if (onePlugin) {
                pluginContainer.add(pluginList.getFirst().getVisualInterfaces().getFirst(), BorderLayout.CENTER);
            } else {
                JTabbedPane tabbedPane = new JTabbedPane();
                for (Interfaces.MainContentPanelPlugin plugin : pluginList) {
                    for (JComponent component : plugin.getVisualInterfaces()) {
                        tabbedPane.addTab(plugin.getTabName(), component);
                    }
                }
                pluginContainer.add(tabbedPane, BorderLayout.CENTER);
            }
            splitPane.setBottomComponent(collapsiblePane);
            if (pluginMaximized) {
                splitPane.setDividerLocation(0);
            } else {
                splitPane.setDividerSize(DIVIDER_SIZE);
            }
        } else {
            add(collapsiblePane, BorderLayout.PAGE_END);
        }
        maximizeButton.setVisible(isSelected);
        Settings.setProperty("display.plugins", Boolean.toString(isSelected));

        revalidate();
        repaint();
    }

    private void togglePluginMaximized() {
        if (pluginMaximized) {
            restorePluginSize();
        } else {
            normalDividerLocation = splitPane.getDividerLocation();
            MainFrame.setRenderSurfaceVisible(false);
            splitPane.setResizeWeight(0);
            splitPane.setDividerSize(0);
            splitPane.setDividerLocation(0);
            pluginMaximized = true;
            updateMaximizeButton();
            revalidate();
            repaint();
        }
    }

    private void restorePluginSize() {
        splitPane.setResizeWeight(NORMAL_RESIZE_WEIGHT);
        splitPane.setDividerSize(DIVIDER_SIZE);
        splitPane.setDividerLocation(normalDividerLocation);
        pluginMaximized = false;
        updateMaximizeButton();
        revalidate();
        repaint();
        EventQueue.invokeLater(() -> {
            if (!pluginMaximized)
                MainFrame.setRenderSurfaceVisible(true);
        });
    }

    private void updateMaximizeButton() {
        maximizeButton.setIcon(pluginMaximized ? Buttons.restorePanel : Buttons.maximizePanel);
        maximizeButton.setToolTipText(pluginMaximized ? "Restore panel size" : "Maximize panel");
    }

}
