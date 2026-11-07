package org.helioviewer.jhv.layers.fov;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Enumeration;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.gui.component.TerminatedFormatterFactory;

@SuppressWarnings("serial")
public final class FOVTreePane extends JScrollPane {

    private final DefaultMutableTreeNode root;
    private final JTree tree;

    public FOVTreePane(FOVCatalog catalog) {
        root = new DefaultMutableTreeNode("Root");
        catalog.platforms().forEach(platform -> {
            TreeNodeComponent platformNode = new TreeNodeComponent(platform, createPlatformComponent(platform));
            root.add(platformNode);

            for (FOVInstrument instrument : platform.instruments())
                platformNode.add(new TreeNodeComponent(instrument, createInstrumentComponent(instrument)));
        });
        tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setEditable(true);
        tree.setShowsRootHandles(true);
        tree.setSelectionModel(null);
        tree.setCellRenderer(new Renderer());
        tree.setCellEditor(new Editor());
        //tree.setRowHeight(0); // force calculation of nodes heights

        setViewportView(tree);
        setColumnHeaderView(createViewOptions(catalog));
        setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        UIGlobals.themed(this, c -> c.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIGlobals.separator())));
        setPreferredSize(new Dimension(-1, 120));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        tree.setEnabled(enabled);

        Enumeration<?> nodes = root.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object node = nodes.nextElement();
            if (node instanceof TreeNodeComponent componentNode)
                ComponentUtils.setEnabled(componentNode.component, enabled);
        }
    }

    /**
     * How the fields are drawn. Cones apply in a 3D view (Orthographic, Helioradial in 3D);
     * Coverage and Bodies in a flat one; Labels in both. The ones that do nothing in the current
     * projection stay visible but greyed, and say where they apply.
     */
    private static JPanel createViewOptions(FOVCatalog catalog) {
        JCheckBox cones = new JCheckBox("Cones", catalog.cones());
        JCheckBox labels = new JCheckBox("Labels", catalog.labels());
        labels.setToolTipText("Name each field on screen, at its far end.");
        JCheckBox coverage = new JCheckBox("Coverage", catalog.coverage());
        JCheckBox bodies = new JCheckBox("Bodies", catalog.bodies());
        cones.addActionListener(e -> {
            catalog.setCones(cones.isSelected());
            DisplayController.display();
        });
        labels.addActionListener(e -> {
            catalog.setLabels(labels.isSelected());
            DisplayController.display();
        });
        coverage.addActionListener(e -> {
            catalog.setCoverage(coverage.isSelected());
            DisplayController.display();
        });
        bodies.addActionListener(e -> {
            catalog.setBodies(bodies.isSelected());
            DisplayController.display();
        });
        Runnable follow = () -> {
            boolean in3D = ViewState.getProjection().rendersIn3D(); // the projection chosen, which a crossfade applies a moment later
            cones.setEnabled(in3D);
            cones.setToolTipText("Draw each field as a solid from its spacecraft, as HelioFleet does; off, outlines on the plane of the sky."
                    + (in3D ? "" : " Applies in Orthographic and 3D Helioradial."));
            coverage.setEnabled(!in3D);
            coverage.setToolTipText("Draw the edge of the solar surface each spacecraft with a field on can see."
                    + (in3D ? " Applies in the flat projections (HPC, Latitudinal, Observer Sky, flat Helioradial)." : ""));
            bodies.setEnabled(!in3D);
            bodies.setToolTipText("Mark the planets, and each spacecraft with a field on; one off screen gets an arrow at the edge with its elongation."
                    + (in3D ? " Applies in the flat projections; in 3D the Viewpoint layer shows them." : ""));
        };
        follow.run();
        ViewState.addModeListener(follow::run);
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        panel.add(cones);
        panel.add(labels);
        panel.add(coverage);
        panel.add(bodies);
        return panel;
    }

    private static Component createPlatformComponent(FOVPlatform platform) {
        JHVSpinner spinnerX = createSpinner(platform.getCenterX());
        spinnerX.addChangeListener(e -> platform.setCenterX((Double) spinnerX.getValue()));

        JHVSpinner spinnerY = createSpinner(platform.getCenterY());
        spinnerY.addChangeListener(e -> platform.setCenterY((Double) spinnerY.getValue()));

        JPanel spinnerXPanel = new JPanel(new BorderLayout());
        spinnerXPanel.setOpaque(false);
        spinnerXPanel.add(new JLabel("    δx ", JLabel.RIGHT), BorderLayout.LINE_START);
        spinnerXPanel.add(spinnerX, BorderLayout.LINE_END);

        JPanel spinnerYPanel = new JPanel(new BorderLayout());
        spinnerYPanel.setOpaque(false);
        spinnerYPanel.add(new JLabel("    δy ", JLabel.RIGHT), BorderLayout.LINE_START);
        spinnerYPanel.add(spinnerY, BorderLayout.LINE_END);

        JPanel panel = new JPanel(new GridLayout(1, 3, 0, 0));
        panel.setOpaque(false);
        panel.add(new JLabel(platform.toString()));
        panel.add(spinnerXPanel);
        panel.add(spinnerYPanel);
        return panel;
    }

    private static Component createInstrumentComponent(FOVInstrument instrument) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        JCheckBox checkBox = new JCheckBox(instrument.toString(), instrument.isEnabled());
        checkBox.addActionListener(e -> {
            instrument.setEnabled(checkBox.isSelected());
            DisplayController.display();
        });
        checkBox.setFocusPainted(false);
        checkBox.setOpaque(false);

        panel.add(checkBox, BorderLayout.LINE_START);
        panel.add(new JLabel("      "), BorderLayout.LINE_END); // avoid ellipsis on Windows
        return panel;
    }

    private static JHVSpinner createSpinner(double value) {
        JHVSpinner spinner = new JHVSpinner(value, FOVPlatform.MIN_CENTER_ARCMIN, FOVPlatform.MAX_CENTER_ARCMIN, 0.1);
        JFormattedTextField field = ((JHVSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        field.setFormatterFactory(new TerminatedFormatterFactory("%.2f", "′", FOVPlatform.MIN_CENTER_ARCMIN, FOVPlatform.MAX_CENTER_ARCMIN));
        return spinner;
    }

    private static final class Renderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (value instanceof TreeNodeComponent componentNode)
                return componentNode.component;
            return super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        }

    }

    private static final class Editor extends DefaultCellEditor {

        Editor() {
            super(new JCheckBox());
        }

        @Override
        public Component getTreeCellEditorComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row) {
            if (value instanceof TreeNodeComponent componentNode)
                return componentNode.component;
            return super.getTreeCellEditorComponent(tree, value, selected, expanded, leaf, row);
        }
    }

    private static final class TreeNodeComponent extends DefaultMutableTreeNode {

        private final Component component;

        TreeNodeComponent(Object userObject, Component _component) {
            super(userObject);
            component = _component;
        }
    }

}
