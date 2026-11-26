package org.helioviewer.jhv.timelines.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableModel;

import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.TableValue;
import org.helioviewer.jhv.timelines.TimelineLayer;
import org.helioviewer.jhv.timelines.TimelineLayers;
import org.helioviewer.jhv.layers.grid.DataBuoys;
import org.helioviewer.jhv.timelines.band.Band;
import org.helioviewer.jhv.timelines.band.BandType;
import org.helioviewer.jhv.timelines.draw.DrawController;

@SuppressWarnings("serial")
public final class TimelinePanel extends JPanel {

    private static final int ICON_WIDTH = 12;
    private static final String PREDEFINED_PLACEHOLDER = "Predefined";


    private final TimelineTable grid;
    private final TimelineLayers layers;
    private final JPanel optionsPanelWrapper;
    private final JComboBox<String> predefinedCombo;
    private Map<String, List<BandType>> predefinedGroups = Map.of();
    private Map<String, org.json.JSONArray> userPresets = Map.of();
    private boolean updatingPredefinedGroup;

    private static class TimelineTable extends JTable implements Interfaces.LazyComponent {

        TimelineTable(TableModel tm) {
            super(tm);
            UITimer.register(this);
        }

        @Override
        public void changeSelection(int row, int col, boolean toggle, boolean extend) {
            if (col != TimelineLayers.ENABLED_COLUMN && col != TimelineLayers.REMOVE_COLUMN && col != TimelineLayers.APPEARANCE_COLUMN)
                super.changeSelection(row, col, toggle, extend);
            // otherwise prevent changing selection
        }

        @Override
        public void tableChanged(TableModelEvent e) {
            super.tableChanged(e);
            if (e.getType() == TableModelEvent.INSERT) {
                int row = e.getLastRow();
                setRowSelectionInterval(row, row);
            }
        }

        @Override
        public void repaint() {
            dirty().setBounds(0, 0, getWidth(), getHeight());
            dirtyValid = true;
        }

        @Override
        public void repaint(int x, int y, int width, int height) {
            if (!dirtyValid) {
                dirty().setBounds(x, y, width, height);
                dirtyValid = true;
            } else {
                dirty().add(x, y);
                dirty().add(x + width, y + height);
            }
        }

        private Rectangle dirty = null;
        private boolean dirtyValid = false;

        private Rectangle dirty() {
            if (dirty == null)
                dirty = new Rectangle();
            return dirty;
        }

        @Override
        public void lazyRepaint() {
            if (dirtyValid) {
                super.repaint(dirty.x, dirty.y, dirty.width, dirty.height);
                dirtyValid = false;
            }
        }

    }

    public TimelinePanel(TimelineLayers model) {
        layers = model;
        setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.weighty = 0;
        gc.fill = GridBagConstraints.BOTH;

        grid = new TimelineTable(model);

        jsp = new JScrollPane(grid, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        UIGlobals.themed(jsp, c -> c.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIGlobals.separator())));
        UIGlobals.themed(jsp.getViewport(), c -> c.setBackground(UIGlobals.timelineExportBackground() != null ? UIGlobals.timelineExportBackground() : grid.getBackground()));

        JButton addLayerButton = Buttons.flat(Buttons.newLayer);
        addLayerButton.setText("New Layer");
        addLayerButton.addActionListener(e -> new TimelineActions.NewLayer().actionPerformed(new ActionEvent(addLayerButton, 0, "")));

        predefinedCombo = new JComboBox<>();
        predefinedCombo.setToolTipText("Predefined plot");
        predefinedCombo.setMaximumSize(new Dimension(100, predefinedCombo.getPreferredSize().height));
        predefinedCombo.setPreferredSize(new Dimension(80, predefinedCombo.getPreferredSize().height));
        predefinedCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == null) {
                    label.setText(PREDEFINED_PLACEHOLDER);
                    label.setForeground(Color.GRAY);
                }
                return label;
            }
        });
        predefinedCombo.addActionListener(e -> {
            if (updatingPredefinedGroup)
                return;
            Object selected = predefinedCombo.getSelectedItem();
            if (selected instanceof String groupName) {
                updatingPredefinedGroup = true;
                loadPredefinedGroup(groupName);
                updatingPredefinedGroup = false;
            }
        });
        layers.addTableModelListener(e -> {
            boolean compositionChanged = e.getType() == TableModelEvent.INSERT
                    || e.getType() == TableModelEvent.DELETE
                    || e.getLastRow() == Integer.MAX_VALUE;
            if (compositionChanged && !updatingPredefinedGroup)
                predefinedCombo.setSelectedItem(null);
        });

        JButton upButton = new JButton("\u25B2");
        upButton.setToolTipText("Move the selected layer up (up the stacked plot)");
        upButton.addActionListener(e -> moveSelected(-1));
        JButton downButton = new JButton("\u25BC");
        downButton.setToolTipText("Move the selected layer down (down the stacked plot)");
        downButton.addActionListener(e -> moveSelected(1));

        JPanel leftButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        leftButtonPanel.add(addLayerButton);
        leftButtonPanel.add(upButton);
        leftButtonPanel.add(downButton);

        JPanel rightButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        rightButtonPanel.add(predefinedCombo);
        rightButtonPanel.add(DrawController.getOptionsPanel());

        JPanel addLayerButtonWrapper = new JPanel(new BorderLayout());
        addLayerButtonWrapper.add(leftButtonPanel, BorderLayout.LINE_START);
        addLayerButtonWrapper.add(rightButtonPanel, BorderLayout.LINE_END);

        // Minimum is preferred. The sidebar section can give this panel a pixel short of what it
        // asks for (seen live: 144 for 145), and GridBagLayout then lays every row out at its
        // minimum, which for a toolbar over a fixed-height table is a toolbar of negative height
        // and a table drawn above its own top: the section showed only row stripes, no buttons
        // and no names (Sarah Gibson's "timeline layers render nothing", 2026-10-19). Now a short
        // fall clips a pixel instead.
        @SuppressWarnings("serial")
        JPanel jspContainer = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        jspContainer.add(addLayerButtonWrapper, BorderLayout.CENTER);
        jspContainer.add(jsp, BorderLayout.PAGE_END);
        add(jspContainer, gc);

        grid.setTableHeader(null);
        grid.setShowHorizontalLines(true);
        grid.setRowSelectionAllowed(true);
        grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.setColumnSelectionAllowed(false);
        grid.setIntercellSpacing(new Dimension(0, 0));
        grid.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        // Drag a row by its grip to reorder, as in the layer lists; the order is the stacked-plot order
        grid.setDragEnabled(true);
        grid.setDropMode(javax.swing.DropMode.INSERT_ROWS);
        grid.setTransferHandler(new org.helioviewer.jhv.layers.selector.TableRowTransferHandler(grid, () -> pressedOnHandle));
        layers.setOnReorder(row -> grid.getSelectionModel().setSelectionInterval(row, row));

        grid.getColumnModel().getColumn(TimelineLayers.HANDLE_COLUMN).setCellRenderer(new org.helioviewer.jhv.layers.selector.CellRenderer.Handle());
        grid.getColumnModel().getColumn(TimelineLayers.HANDLE_COLUMN).setPreferredWidth(ICON_WIDTH + 12); // room for the selected row's arrow
        grid.getColumnModel().getColumn(TimelineLayers.HANDLE_COLUMN).setMaxWidth(ICON_WIDTH + 12);

        grid.getColumnModel().getColumn(TimelineLayers.ENABLED_COLUMN).setCellRenderer(new CellRenderer.Enabled());
        grid.getColumnModel().getColumn(TimelineLayers.ENABLED_COLUMN).setPreferredWidth(ICON_WIDTH + 8);
        grid.getColumnModel().getColumn(TimelineLayers.ENABLED_COLUMN).setMinWidth(ICON_WIDTH + 8);
        grid.getColumnModel().getColumn(TimelineLayers.ENABLED_COLUMN).setMaxWidth(ICON_WIDTH + 8);

        grid.getColumnModel().getColumn(TimelineLayers.TITLE_COLUMN).setCellRenderer(new CellRenderer.Name());

        grid.getColumnModel().getColumn(TimelineLayers.LOADING_COLUMN).setCellRenderer(new CellRenderer.Loading());
        grid.getColumnModel().getColumn(TimelineLayers.LOADING_COLUMN).setPreferredWidth(ICON_WIDTH + 2);
        grid.getColumnModel().getColumn(TimelineLayers.LOADING_COLUMN).setMinWidth(ICON_WIDTH + 2);
        grid.getColumnModel().getColumn(TimelineLayers.LOADING_COLUMN).setMaxWidth(ICON_WIDTH + 2);

        grid.getColumnModel().getColumn(TimelineLayers.APPEARANCE_COLUMN).setCellRenderer(new CellRenderer.LineColor());
        grid.getColumnModel().getColumn(TimelineLayers.APPEARANCE_COLUMN).setPreferredWidth(20);
        grid.getColumnModel().getColumn(TimelineLayers.APPEARANCE_COLUMN).setMinWidth(20);
        grid.getColumnModel().getColumn(TimelineLayers.APPEARANCE_COLUMN).setMaxWidth(20);

        grid.getColumnModel().getColumn(TimelineLayers.REMOVE_COLUMN).setCellRenderer(new CellRenderer.Remove());
        grid.getColumnModel().getColumn(TimelineLayers.REMOVE_COLUMN).setPreferredWidth(ICON_WIDTH + 2);
        grid.getColumnModel().getColumn(TimelineLayers.REMOVE_COLUMN).setMinWidth(ICON_WIDTH + 2);
        grid.getColumnModel().getColumn(TimelineLayers.REMOVE_COLUMN).setMaxWidth(ICON_WIDTH + 2);

        grid.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                refreshSelectedOptionsPanel();
        });

        // The 3D scene's buoys and these rows light each other: the row under the pointer names its spacecraft, a buoy under the pointer lights its rows
        DataBuoys.LISTENERS.add(grid::repaint);
        grid.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                TableValue v = TableValue.tableValueAtPoint(grid, e.getPoint());
                var craft = v != null && v.value instanceof Band band ? band.getSpacecraft() : null;
                setRowHover(craft == null ? null : craft.key());
            }
        });
        grid.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressedOnHandle = grid.columnAtPoint(e.getPoint()) == TimelineLayers.HANDLE_COLUMN;
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setRowHover(null);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                TableValue v = TableValue.tableValueAtPoint(grid, e.getPoint());
                if (v == null || !(v.value instanceof TimelineLayer timeline))
                    return;

                if (v.col == TimelineLayers.ENABLED_COLUMN) {
                    timeline.setEnabled(!timeline.isEnabled());
                    layers.updateCell(v.row, v.col);
                    layers.updateCell(v.row, TimelineLayers.REMOVE_COLUMN); // a built-in row's X follows its tick
                    if (grid.getSelectedRow() == v.row)
                        setOptionsPanel(timeline);
                    DrawController.layoutChanged();
                    if (timeline.isEnabled())
                        timeline.fetchData(DrawController.selectedAxis);
                } else if (v.col == TimelineLayers.APPEARANCE_COLUMN && timeline instanceof Band band && band.hasLevelColors()) {
                    band.setMulticolor(!band.isMulticolor());
                } else if (v.col == TimelineLayers.REMOVE_COLUMN && timeline.isDeletable()) {
                    timeline.deleted(); // the user's gesture, as against the teardown remove() below
                    layers.remove(timeline);
                    selectExistingRow(v.row);
                } else if (v.col == TimelineLayers.REMOVE_COLUMN && timeline.isEnabled()) {
                    timeline.setEnabled(false); // a built-in row: hide it, as its checkbox does
                    layers.updateCell(v.row, TimelineLayers.ENABLED_COLUMN);
                    layers.updateCell(v.row, v.col);
                    DrawController.layoutChanged();
                }
            }
        });

        // Every row shows, as in the layer lists: a list that scrolls hides layers behind a bar. The
        // section grows with the list; the sidebar's own scrollbar deals with a tall stack.
        fitRows();
        layers.addTableModelListener(e -> fitRows());

        gc.gridy = 1;
        optionsPanelWrapper = new JPanel(new BorderLayout());
        add(optionsPanelWrapper, gc);
    }

    @Nullable
    private TimelineLayer selectedLayer() {
        int row = grid.getSelectedRow();
        if (row < 0)
            return null;
        return grid.getValueAt(row, 0) instanceof TimelineLayer timeline ? timeline : null;
    }

    private void moveSelected(int delta) {
        TimelineLayer layer = selectedLayer();
        if (layer != null && layers.move(layer, delta)) {
            int row = grid.getSelectedRow() + delta;
            grid.getSelectionModel().setSelectionInterval(row, row);
        }
    }

    private void selectExistingRow(int preferredRow) {
        int rowCount = grid.getRowCount();
        if (rowCount == 0) {
            setOptionsPanel(null);
            return;
        }
        int row = Math.min(preferredRow, rowCount - 1);
        grid.getSelectionModel().setSelectionInterval(row, row);
        refreshSelectedOptionsPanel();
    }

    private JScrollPane jsp;
    private boolean pressedOnHandle; // a drag counts only if it began on the grip

    private void fitRows() {
        jsp.setPreferredSize(new Dimension(-1, grid.getRowHeight() * Math.max(grid.getRowCount(), 1) + 1));
        jsp.revalidate();
        revalidate();
    }

    private static void setRowHover(String key) {
        if (!java.util.Objects.equals(DataBuoys.ROW_HOVER.getAndSet(key), key))
            org.helioviewer.jhv.display.DisplayController.display();
    }

    private void refreshSelectedOptionsPanel() {
        setOptionsPanel(selectedLayer());
    }

    private void setOptionsPanel(@Nullable TimelineLayer timeline) {
        optionsPanelWrapper.removeAll();
        JPanel optionsPanel = timeline == null ? null : timeline.getOptionsPanel();
        if (optionsPanel != null) {
            ComponentUtils.setEnabled(optionsPanel, timeline.isEnabled());
            optionsPanelWrapper.add(optionsPanel, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Loads a preset by name: the catalogue's, or one the user saved with the Timeline Manager. */
    public void loadPredefinedGroup(String groupName) {
        List<BandType> bandTypes = predefinedGroups.get(groupName);
        if (bandTypes == null) {
            org.json.JSONArray saved = userPresets.get(groupName);
            if (saved == null)
                return;
            layers.replaceBandsWith(org.helioviewer.jhv.timelines.band.BandPresets.restore(saved));
            selectFirstBand();
            return;
        }

        layers.replaceBands(bandTypes);
        selectFirstBand();
    }

    /** The catalogue's own preset names, which the manager lists but does not change. */
    public List<String> builtInPresetNames() {
        return List.copyOf(predefinedGroups.keySet());
    }

    /** Reads the user's saved presets again and shows them in the Predefined list after the catalogue's. */
    public void refreshUserPresets() {
        userPresets = org.helioviewer.jhv.timelines.band.BandPresets.load();
        rebuildPresetCombo();
    }

    private void rebuildPresetCombo() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(predefinedGroups.keySet());
        names.addAll(userPresets.keySet());
        updatingPredefinedGroup = true;
        predefinedCombo.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)));
        predefinedCombo.setSelectedItem(null);
        updatingPredefinedGroup = false;
    }

    private void selectFirstBand() {
        for (int row = 0; row < grid.getRowCount(); row++) {
            if (grid.getValueAt(row, 0) instanceof Band) {
                selectExistingRow(row);
                return;
            }
        }
        selectExistingRow(0);
    }

    public void setPredefinedGroups(Map<String, List<BandType>> groups) {
        predefinedGroups = groups;
        userPresets = org.helioviewer.jhv.timelines.band.BandPresets.load();
        rebuildPresetCombo();
    }

}
