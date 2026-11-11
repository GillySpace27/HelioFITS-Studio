package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.display.interaction.CanvasKeys;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.registry.ControlRegistry;
import org.helioviewer.jhv.gui.registry.KeyBindings;
import org.helioviewer.jhv.gui.registry.Shortcuts;
import org.helioviewer.jhv.gui.search.ControlIndex;

/**
 * Settings > Keybindings: every catalogued command with its keybinding. Search by name, change
 * a keybinding, remove it, or put it back to the default; a clash with another command or with the
 * system is refused with the reason. Changes apply at once (see {@link KeyBindings}).
 *
 * <p>A keybinding is chosen with the modifier boxes and a key list rather than by pressing it: on a
 * Mac the menu bar can take a key combination before this window sees it, and pressing Cmd+Q to
 * record it would quit.
 */
@SuppressWarnings("serial")
public final class KeybindingsPanel extends JPanel {

    private record Row(String id, String title, String where) {}

    private static final String[] KEYS = keyNames();

    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final JTextField filter = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final AbstractTableModel model = new AbstractTableModel() {
        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return switch (c) { case 0 -> "Command"; case 1 -> "Where"; default -> "Keybinding"; }; }
        @Override public Object getValueAt(int r, int c) {
            Row row = shown.get(r);
            return switch (c) {
                case 0 -> row.title();
                case 1 -> row.where();
                default -> text(row.id());
            };
        }
    };
    private final JTable table = new JTable(model);

    public KeybindingsPanel() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (ControlRegistry.Entry e : ControlRegistry.all())
            if (e.kind() == ControlRegistry.Kind.COMMAND && ActionCatalog.get(e.id()) != null && !e.command().needsArguments()) {
                String where = ActionCatalog.get(e.id()).menuPath();
                int slash = where.lastIndexOf('/');
                all.add(new Row(e.id(), e.title(), where.isEmpty() ? "" : slash > 0 ? where.substring(0, slash).replace("/", " > ") : "Menu"));
            }
        filter.setToolTipText("Type part of a command name, its menu or its keybinding");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter(); }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 2);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2)
                    change();
            }
        });

        JButton changeBtn = button("Change...", "Choose a new keybinding for the selected command", this::change);
        JButton removeBtn = button("Remove", "Give the selected command no keybinding", () -> apply(null, true));
        JButton resetBtn = button("Reset", "Back to the keybinding the program came with", () -> {
            Row r = selected();
            if (r != null) {
                KeyBindings.reset(r.id());
                done("Reset " + r.title() + ".");
            }
        });
        JButton resetAllBtn = button("Reset All", "Put every keybinding back to the default", () -> {
            if (JOptionPane.showConfirmDialog(this, "Put every keybinding back to the default?", "Reset All", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                KeyBindings.resetAll();
                done("Every keybinding is back to the default.");
            }
        });

        JButton canvasBtn = button("Canvas keys...", "The bare keys the picture answers to while it has focus: W A S D Q E turn the scene, Up and Down set the playback rate", this::canvasKeys);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("Search:"), BorderLayout.LINE_START);
        top.add(filter, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        buttons.add(changeBtn);
        buttons.add(removeBtn);
        buttons.add(resetBtn);
        buttons.add(resetAllBtn);
        buttons.add(canvasBtn);
        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 4));
        bottom.add(buttons);
        bottom.add(status);
        add(top, BorderLayout.PAGE_START);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.PAGE_END);
        setPreferredSize(new Dimension(640, 420));
        refilter();
    }

    /** The canvas keys: not accelerators (they are bare keys), so they have their own small table. */
    private void canvasKeys() {
        List<JComboBox<String>> combos = new ArrayList<>();
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 4));
        for (CanvasKeys.Cmd c : CanvasKeys.ALL) {
            JLabel label = new JLabel(c.title());
            label.setToolTipText(c.what());
            JComboBox<String> box = new JComboBox<>(prepend("none", c.withCmd() ? cmdKeys() : KEYS));
            box.setSelectedItem(CanvasKeys.text(CanvasKeys.key(c.id())));
            box.setToolTipText(c.what());
            grid.add(label);
            grid.add(box);
            combos.add(box);
        }
        JCheckBox invert = new JCheckBox("Invert pitch and yaw", CanvasKeys.invert());
        invert.setToolTipText("By default a key moves the near side of the scene the way a drag that way would: D to the right, W up");
        JSpinner rate = new JSpinner(new SpinnerNumberModel(CanvasKeys.degreesPerSecond(), 1, 360, 5));
        rate.setToolTipText("How fast a held key turns the scene; Option held makes it five times slower, Shift held five times faster");
        JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        extra.add(invert);
        extra.add(new JLabel("Degrees per second"));
        extra.add(rate);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(grid, BorderLayout.CENTER);
        p.add(extra, BorderLayout.PAGE_END);
        if (JOptionPane.showConfirmDialog(this, p, "Canvas keys", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        // Clear every binding first so a swap between two of them is not refused as a clash
        for (CanvasKeys.Cmd c : CanvasKeys.ALL)
            CanvasKeys.set(c.id(), -1);
        StringBuilder problems = new StringBuilder();
        for (int i = 0; i < CanvasKeys.ALL.size(); i++) {
            CanvasKeys.Cmd c = CanvasKeys.ALL.get(i);
            String name = (String) combos.get(i).getSelectedItem();
            int code = "none".equals(name) ? -1 : KeyBindings.keyCode(name);
            String problem = CanvasKeys.set(c.id(), code);
            if (problem != null) {
                CanvasKeys.set(c.id(), -1);
                problems.append(c.title()).append(": ").append(problem).append(' ');
            }
        }
        org.helioviewer.jhv.app.Settings.setProperty(CanvasKeys.INVERT, Boolean.toString(invert.isSelected()));
        org.helioviewer.jhv.app.Settings.setProperty(CanvasKeys.RATE, String.valueOf(((Number) rate.getValue()).doubleValue()));
        status.setText(problems.length() == 0 ? "Canvas keys saved." : problems.toString().strip());
    }

    /** The names a Cmd canvas key may take: the navigation keys only. */
    private static String[] cmdKeys() {
        List<String> out = new ArrayList<>();
        for (String k : KEYS)
            if (CanvasKeys.cmdKeyAllowed(KeyBindings.keyCode(k)))
                out.add(k);
        return out.toArray(new String[0]);
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static JButton button(String text, String tip, Runnable run) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(e -> run.run());
        return b;
    }

    private static String text(String id) {
        KeyStroke ks = KeyBindings.current(id);
        return (ks == null ? "" : Shortcuts.text(ks)) + (KeyBindings.isCustom(id) ? (ks == null ? "(removed)" : " *") : "");
    }

    @Nullable
    private Row selected() {
        int i = table.getSelectedRow();
        return i < 0 ? null : shown.get(i);
    }

    private void refilter() {
        String q = filter.getText();
        shown.clear();
        for (Row r : all)
            if (ControlIndex.matches(q, r.title(), r.where(), r.id()) || (!q.isBlank() && text(r.id()).toLowerCase().contains(q.strip().toLowerCase())))
                shown.add(r);
        model.fireTableDataChanged();
    }

    private void done(String message) {
        int keep = table.getSelectedRow();
        refilter();
        if (keep >= 0 && keep < shown.size())
            table.setRowSelectionInterval(keep, keep);
        status.setText(message);
    }

    private void change() {
        Row r = selected();
        if (r == null) {
            status.setText("Select a command first.");
            return;
        }
        KeyStroke now = KeyBindings.current(r.id());
        JCheckBox menu = new JCheckBox(Platform.isMacOS() ? "Cmd" : "Ctrl", now != null && (now.getModifiers() & DesktopIntegration.menuShortcutMask) != 0);
        JCheckBox alt = new JCheckBox(Platform.isMacOS() ? "Option" : "Alt", now != null && (now.getModifiers() & InputEvent.ALT_DOWN_MASK) != 0);
        JCheckBox shift = new JCheckBox("Shift", now != null && (now.getModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0);
        JCheckBox ctrl = new JCheckBox("Ctrl", now != null && (DesktopIntegration.menuShortcutMask & InputEvent.CTRL_DOWN_MASK) == 0
                && (now.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0);
        JComboBox<String> key = new JComboBox<>(KEYS);
        if (now != null)
            key.setSelectedItem(KeyBindings.keyName(now.getKeyCode()));
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        p.add(menu);
        if (Platform.isMacOS())
            p.add(ctrl);
        p.add(alt);
        p.add(shift);
        p.add(new JLabel("+"));
        p.add(key);
        if (JOptionPane.showConfirmDialog(this, p, "Keybinding for " + r.title(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        int mods = (menu.isSelected() ? DesktopIntegration.menuShortcutMask : 0) | (alt.isSelected() ? InputEvent.ALT_DOWN_MASK : 0)
                | (shift.isSelected() ? InputEvent.SHIFT_DOWN_MASK : 0) | (ctrl.isSelected() && Platform.isMacOS() ? InputEvent.CTRL_DOWN_MASK : 0);
        KeyStroke ks = KeyStroke.getKeyStroke(KeyBindings.keyCode((String) key.getSelectedItem()), mods);
        apply(ks, false);
    }

    private void apply(@Nullable KeyStroke ks, boolean remove) {
        Row r = selected();
        if (r == null) {
            status.setText("Select a command first.");
            return;
        }
        String problem = KeyBindings.set(r.id(), remove ? null : ks);
        if (problem != null)
            status.setText(problem);
        else
            done(remove ? r.title() + " has no keybinding now." : r.title() + " is " + Shortcuts.text(ks) + ".");
    }

    private static String[] keyNames() {
        List<String> out = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++)
            out.add(String.valueOf(c));
        for (char c = '0'; c <= '9'; c++)
            out.add(String.valueOf(c));
        for (int i = 1; i <= 12; i++)
            out.add("F" + i);
        out.addAll(List.of("LEFT", "RIGHT", "UP", "DOWN", "MINUS", "EQUALS", "COMMA", "PERIOD", "SLASH", "BACK_SLASH", "OPEN_BRACKET",
                "CLOSE_BRACKET", "SEMICOLON", "QUOTE", "BACK_QUOTE", "SPACE", "ENTER", "TAB", "ESCAPE", "DELETE", "BACK_SPACE", "HOME", "END",
                "PAGE_UP", "PAGE_DOWN"));
        return out.toArray(new String[0]);
    }
}
