package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.timelines.Timelines;
import org.helioviewer.jhv.timelines.band.BandPresets;
import org.json.JSONArray;

/**
 * The Timeline Manager (Managers menu): the presets of the timeline stack. The catalogue's own are listed
 * first and are only loaded; the ones saved here from the current stack (each row with its colour,
 * colour levels and axis) can be loaded, saved over, renamed and deleted. A deleted preset is kept in
 * presets-retired.json beside the saved ones.
 */
@SuppressWarnings("serial")
public final class TimelineManager {

    private static final java.util.concurrent.atomic.AtomicReference<JDialog> DIALOG = new java.util.concurrent.atomic.AtomicReference<>();

    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private JDialog owner;
    private final JLabel status = new JLabel(" ");
    private Map<String, JSONArray> saved = new LinkedHashMap<>();
    private List<String> builtIn = List.of();

    public static final class Open extends AbstractAction {
        public Open() {
            super("Timeline Manager...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            show();
        }
    }

    public static void show() {
        JDialog dialog = DIALOG.get();
        if (dialog == null) {
            TimelineManager m = new TimelineManager();
            dialog = new JDialog(MainFrame.get(), "Timeline Manager", false);
            m.owner = dialog;
            dialog.setContentPane(m.build());
            dialog.pack();
            dialog.setLocationRelativeTo(MainFrame.get());
            DIALOG.set(dialog);
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    private JPanel build() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
                String name = (String) value;
                return super.getListCellRendererComponent(l, builtIn.contains(name) ? name + "  (built in)" : name, index, sel, focus);
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(380, 260));

        JButton load = new JButton("Load");
        load.setToolTipText("Replace the timeline rows with this preset");
        load.addActionListener(e -> {
            String name = list.getSelectedValue();
            if (name != null)
                Timelines.getPanel().loadPredefinedGroup(name);
        });
        JButton save = new JButton("Save current as...");
        save.setToolTipText("Save the timeline rows now (with their colours, colour levels and axes) as a preset");
        save.addActionListener(e -> saveCurrent());
        JButton rename = new JButton("Rename...");
        rename.addActionListener(e -> rename());
        JButton delete = new JButton("Delete");
        delete.setToolTipText("Remove a saved preset from the list (a copy is kept in presets-retired.json)");
        delete.addActionListener(e -> delete());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        buttons.add(load);
        buttons.add(save);
        buttons.add(rename);
        buttons.add(delete);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(9, 9, 9, 9));
        panel.add(new JLabel("<html>Presets of the timeline stack. The ones marked built in come with the catalogue and can only be loaded.</html>"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(buttons, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);
        reload();
        return panel;
    }

    private void reload() {
        saved = BandPresets.load();
        builtIn = Timelines.getPanel().builtInPresetNames();
        model.clear();
        builtIn.forEach(model::addElement);
        saved.keySet().stream().filter(n -> !builtIn.contains(n)).forEach(model::addElement);
        Timelines.getPanel().refreshUserPresets();
    }

    private void saveCurrent() {
        String name = JOptionPane.showInputDialog(owner, "Name for this preset:", "Save timeline preset", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank())
            return;
        name = name.trim();
        if (builtIn.contains(name)) {
            status.setText("That name is a built in preset's: pick another");
            return;
        }
        if (saved.containsKey(name) && JOptionPane.showConfirmDialog(owner, "Replace the saved preset \"" + name + "\"?", "Save timeline preset",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        JSONArray rows = BandPresets.capture(org.helioviewer.jhv.timelines.TimelineLayers.get());
        if (rows.isEmpty()) {
            status.setText("There are no timeline rows to save");
            return;
        }
        Map<String, JSONArray> next = new LinkedHashMap<>(saved);
        next.put(name, rows);
        status.setText(BandPresets.save(next) ? "Saved \"" + name + "\" (" + rows.length() + " rows)" : "Could not save the preset");
        reload();
        list.setSelectedValue(name, true);
    }

    private void rename() {
        String name = list.getSelectedValue();
        if (name == null || builtIn.contains(name) || !saved.containsKey(name)) {
            status.setText("Select a saved preset to rename");
            return;
        }
        String to = JOptionPane.showInputDialog(owner, "New name:", name);
        if (to == null || to.isBlank() || to.trim().equals(name))
            return;
        to = to.trim();
        if (builtIn.contains(to) || saved.containsKey(to)) {
            status.setText("That name is taken");
            return;
        }
        Map<String, JSONArray> next = new LinkedHashMap<>();
        for (Map.Entry<String, JSONArray> e : saved.entrySet())
            next.put(e.getKey().equals(name) ? to : e.getKey(), e.getValue());
        status.setText(BandPresets.save(next) ? "Renamed to \"" + to + "\"" : "Could not save the preset");
        reload();
        list.setSelectedValue(to, true);
    }

    private void delete() {
        String name = list.getSelectedValue();
        if (name == null || builtIn.contains(name) || !saved.containsKey(name)) {
            status.setText("Select a saved preset to delete");
            return;
        }
        if (JOptionPane.showConfirmDialog(owner, "Delete the saved preset \"" + name + "\"? A copy is kept in presets-retired.json.", "Delete timeline preset",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        BandPresets.retire(name, saved.get(name));
        Map<String, JSONArray> next = new LinkedHashMap<>(saved);
        next.remove(name);
        status.setText(BandPresets.save(next) ? "Deleted \"" + name + "\"" : "Could not save the preset list");
        reload();
    }

}
