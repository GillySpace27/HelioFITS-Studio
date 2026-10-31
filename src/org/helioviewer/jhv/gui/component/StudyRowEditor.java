package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;

import org.helioviewer.jhv.display.MapMode;
import org.json.JSONObject;

/** The Study table's renderer (grey inherited values, red refused cells) and its Projection and Layers editors. */
final class StudyRowEditor {

    private StudyRowEditor() {
    }

    /** Inherited cells in grey with the scene's value; a refused cell outlined in red, its reason as the tooltip. */
    @SuppressWarnings("serial")
    static final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            StudyTableModel m = (StudyTableModel) table.getModel();
            int mr = table.convertRowIndexToModel(row), mc = table.convertColumnIndexToModel(col);
            boolean inherited = m.isInherited(mr, mc);
            super.getTableCellRendererComponent(table, inherited ? m.inheritedText(mc) : value, selected, focus, row, col);
            if (inherited) {
                Color grey = UIManager.getColor("Label.disabledForeground");
                setForeground(grey == null ? Color.GRAY : grey);
            }
            String why = m.reason(mr, mc);
            if (why != null)
                setBorder(BorderFactory.createLineBorder(Color.RED, 2));
            setToolTipText(why != null ? why : inherited ? "Inherited from the scene; type to override" : null);
            return this;
        }
    }

    /** Projection: inherit, or one of the MapModes. */
    static TableCellEditor projectionEditor() {
        JComboBox<Object> box = new JComboBox<>();
        box.addItem(StudyCells.INHERIT);
        for (MapMode m : MapMode.values())
            box.addItem(m);
        return new DefaultCellEditor(box) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int col) {
                Object pick = StudyCells.INHERIT;
                String s = value == null ? "" : value.toString();
                for (MapMode m : MapMode.values())
                    if (m.toString().equals(s) || m.name().equals(s))
                        pick = m;
                box.setSelectedItem(pick);
                return box;
            }

            @Override
            public Object getCellEditorValue() {
                Object o = box.getSelectedItem();
                return o instanceof MapMode m ? m : "";
            }
        };
    }

    /**
     * Layers: one chip per scene layer, each cycling inherit, on, off. Opens on edit; OK writes the
     * chips back as text ("AIA 171: on, LASCO C2: off"), all-inherit as blank.
     */
    @SuppressWarnings("serial")
    static final class LayersEditor extends AbstractCellEditor implements TableCellEditor {

        private static final String[] STATES = {StudyCells.INHERIT, "on", "off"};
        private final JLabel label = new JLabel();
        private String value = "";

        @Override
        public Object getCellEditorValue() {
            return value;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object v, boolean selected, int row, int col) {
            value = v == null ? "" : v.toString();
            label.setText(value);
            StudyTableModel m = (StudyTableModel) table.getModel();
            int mr = table.convertRowIndexToModel(row);
            SwingUtilities.invokeLater(() -> choose(table, m, mr));
            return label;
        }

        private void choose(JTable table, StudyTableModel m, int row) {
            JSONObject own = m.variant(row).override().optJSONObject("layers");
            Map<String, String> state = new LinkedHashMap<>();
            for (String name : m.layerNames())
                state.put(name, own != null && own.has(name) ? (own.optBoolean(name, true) ? "on" : "off") : StudyCells.INHERIT);
            if (own != null) // a layer the row names that the scene lacks stays visible, so it can be cleared
                for (String name : own.keySet())
                    state.putIfAbsent(name, own.optBoolean(name, true) ? "on" : "off");
            if (state.isEmpty()) {
                JOptionPane.showMessageDialog(table, "The scene has no image layers.", "Layers", JOptionPane.INFORMATION_MESSAGE);
                cancelCellEditing();
                return;
            }
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT));
            for (String name : state.keySet()) {
                JButton chip = new JButton(name + ": " + state.get(name));
                chip.setToolTipText("Click to cycle inherit, on, off");
                chip.addActionListener(e -> {
                    String now = state.get(name);
                    int i = 0;
                    while (!STATES[i].equals(now))
                        i++;
                    state.put(name, STATES[(i + 1) % STATES.length]);
                    chip.setText(name + ": " + state.get(name));
                });
                chips.add(chip);
            }
            int ok = JOptionPane.showConfirmDialog(table, chips, "Layers for this row", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                cancelCellEditing();
                return;
            }
            Map<String, Boolean> set = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : state.entrySet())
                if (!e.getValue().equals(StudyCells.INHERIT))
                    set.put(e.getKey(), e.getValue().equals("on"));
            value = StudyCells.formatLayers(set);
            stopCellEditing();
        }
    }
}
