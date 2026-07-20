package org.helioviewer.jhv.plugins.swek;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.Timer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.helioviewer.jhv.event.JHVEventCache;
import org.helioviewer.jhv.event.SWEKCatalog;
import org.helioviewer.jhv.event.SWEKDownloader;
import org.helioviewer.jhv.event.SWEKGroup;
import org.helioviewer.jhv.event.SWEKSupplier;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.event.filter.FilterDialog;
import org.helioviewer.jhv.event.info.CactusTrackDialog;
import org.helioviewer.jhv.gui.component.BusyIndicator;

import com.jidesoft.swing.JideButton;

@SuppressWarnings("serial")
final class SWEKTreePane extends JPanel {

    private static final int RIGHT_ALIGNMENT = 300;
    private static final Color TRACK_ACTIVE = new Color(255, 140, 0); // "Tracking" indicator

    private final java.util.List<Runnable> trackerListeners = new java.util.ArrayList<>(); // CMETracker listeners to release on teardown

    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final IdentityHashMap<SWEKGroup, Component> groupComponents = new IdentityHashMap<>();
    private final IdentityHashMap<SWEKGroup, DefaultMutableTreeNode> groupNodes = new IdentityHashMap<>();
    private final IdentityHashMap<SWEKSupplier, Component> supplierComponents = new IdentityHashMap<>();
    private final Timer loadingTimer;

    SWEKTreePane(List<SWEKGroup> groups) {
        super(new BorderLayout());
        SWEKIconBank.init();
        treeModel = createTreeModel(groups);

        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setEditable(true);
        tree.setShowsRootHandles(true);
        tree.setSelectionModel(null);
        tree.setCellRenderer(new Renderer());
        tree.setCellEditor(new Editor());
        // tree.setRowHeight(0); // force calculation of nodes heights
        for (int i = 0; i < tree.getRowCount(); i++)
            tree.expandRow(i);

        loadingTimer = new Timer(500, e -> repaintBusyGroups());
        SWEKDownloader.setGroupChangedCallback(this::groupBusyChanged);

        setBorder(BorderFactory.createEmptyBorder());
        add(tree, BorderLayout.CENTER);
    }

    private DefaultTreeModel createTreeModel(List<SWEKGroup> groups) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        DefaultTreeModel model = new DefaultTreeModel(root);
        for (SWEKGroup group : groups) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(group);
            groupNodes.put(group, groupNode);
            for (SWEKSupplier supplier : SWEKCatalog.getSuppliers(group)) {
                groupNode.add(new DefaultMutableTreeNode(supplier));
            }
            root.add(groupNode);
        }
        return model;
    }

    private void groupBusyChanged(SWEKGroup group) {
        DefaultMutableTreeNode groupNode = groupNodes.get(group);
        if (groupNode != null)
            treeModel.nodeChanged(groupNode);
    }

    private void repaintBusyGroups() {
        boolean anyBusy = false;
        Enumeration<?> children = ((DefaultMutableTreeNode) treeModel.getRoot()).children();
        while (children.hasMoreElements()) {
            Object child = children.nextElement();
            if (child instanceof DefaultMutableTreeNode groupNode && groupNode.getUserObject() instanceof SWEKGroup group) {
                if (SWEKDownloader.isGroupBusy(group)) {
                    anyBusy = true;
                    repaintGroup(groupNode);
                }
            }
        }

        if (!anyBusy)
            loadingTimer.stop();
    }

    private void repaintGroup(DefaultMutableTreeNode groupNode) {
        Rectangle bounds = tree.getPathBounds(new TreePath(groupNode.getPath()));
        if (bounds != null)
            tree.repaint(bounds);
    }

    private Component componentFor(Object value) {
        Component component = null;
        if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof SWEKGroup group) {
            boolean busy = SWEKDownloader.isGroupBusy(group);
            if (busy && !loadingTimer.isRunning())
                loadingTimer.start();
            component = groupComponents.computeIfAbsent(group, SWEKTreePane::createGroupComponent);
            if (component instanceof JPanel panel && panel.getComponentCount() > 1)
                panel.getComponent(1).setVisible(busy);
        } else if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof SWEKSupplier supplier) {
            component = supplierComponents.computeIfAbsent(supplier, this::createSupplierComponent);
            if (component instanceof JPanel panel && panel.getComponent(0) instanceof JCheckBox checkBox)
                checkBox.setSelected(JHVEventCache.isSupplierActive(supplier));
        }

        if (component != null)
            setEnabledRecursively(component, tree.isEnabled());
        return component;
    }

    private static Component createGroupComponent(SWEKGroup group) {
        JLabel label = new JLabel(group.getName());
        int size = label.getPreferredSize().height;
        ImageIcon icon = SWEKIconBank.getIcon(group.getIconKey());
        label.setIcon(new ImageIcon(icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH)));

        BusyIndicator busyIndicator = new BusyIndicator();
        busyIndicator.setOpaque(false);
        busyIndicator.setVisible(SWEKDownloader.isGroupBusy(group));
        busyIndicator.setPreferredSize(new Dimension(size, size));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(label, BorderLayout.LINE_START);
        panel.add(busyIndicator, BorderLayout.LINE_END);
        panel.setPreferredSize(new Dimension(RIGHT_ALIGNMENT, size));
        return panel;
    }

    private Component createSupplierComponent(SWEKSupplier supplier) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        JCheckBox checkBox = new JCheckBox(supplier.displayName(), JHVEventCache.isSupplierActive(supplier));
        checkBox.addActionListener(e -> JHVEventCache.setSupplierActive(supplier, checkBox.isSelected()));
        checkBox.setFocusPainted(false);
        checkBox.setOpaque(false);
        panel.add(checkBox, BorderLayout.LINE_START);

        // Right-aligned action strip: Track (CACTus only) sits to the left of Filter.
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        actions.setOpaque(false);
        int rowHeight = checkBox.getPreferredSize().height;

        if (supplier.isCactus()) {
            JideButton trackButton = new JideButton("Track");
            trackButton.setToolTipText("Browse the loaded CACTus CMEs and track one through the corona");
            Color defaultFg = trackButton.getForeground();
            Font baseFont = trackButton.getFont();
            // Reflect the live tracking state: orange bold "Tracking" while engaged. Components are
            // cached per supplier for the panel's life, so this one listener registration is bounded.
            Runnable sync = () -> {
                boolean t = CMETracker.isTracking();
                trackButton.setText(t ? "Tracking" : "Track");
                trackButton.setForeground(t ? TRACK_ACTIVE : defaultFg);
                trackButton.setFont(baseFont.deriveFont(t ? Font.BOLD : Font.PLAIN));
                tree.repaint(); // the cell component is an orphan renderer stamp; force the JTree to re-stamp
            };
            sync.run();
            CMETracker.addChangeListener(sync);
            trackerListeners.add(sync); // released in removeNotify()
            trackButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    CactusTrackDialog.open();
                }
            });
            actions.add(trackButton);
            rowHeight = trackButton.getPreferredSize().height;
        }

        if (supplier.containsFilter()) {
            FilterDialog filterDialog = new FilterDialog(supplier);
            JideButton filterButton = new JideButton("Filter");
            filterButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Point pressedLocation = e.getLocationOnScreen();
                    Point windowLocation = new Point(pressedLocation.x, pressedLocation.y - filterDialog.getSize().height);
                    filterDialog.setLocation(windowLocation);
                    filterDialog.setVisible(true);
                }
            });
            actions.add(filterButton);
            rowHeight = filterButton.getPreferredSize().height;
        }

        if (actions.getComponentCount() > 0) {
            panel.setPreferredSize(new Dimension(RIGHT_ALIGNMENT, rowHeight));
            panel.add(actions, BorderLayout.LINE_END);
        }
        return panel;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        tree.setEnabled(enabled);
        groupComponents.values().forEach(component -> setEnabledRecursively(component, enabled));
        supplierComponents.values().forEach(component -> setEnabledRecursively(component, enabled));
    }

    @Override
    public void removeNotify() {
        SWEKDownloader.clearGroupChangedCallback();
        loadingTimer.stop();
        trackerListeners.forEach(CMETracker::removeChangeListener);
        trackerListeners.clear();
        super.removeNotify();
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents())
                setEnabledRecursively(child, enabled);
        }
    }

    private final class Renderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Component component = componentFor(value);
            if (component != null)
                return component;
            return super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        }

    }

    private final class Editor extends DefaultCellEditor {

        Editor() {
            super(new JCheckBox());
        }

        @Override
        public Component getTreeCellEditorComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row) {
            Component component = componentFor(value);
            if (component != null)
                return component;
            return super.getTreeCellEditorComponent(tree, value, selected, expanded, leaf, row);
        }

    }

}
