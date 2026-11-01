package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.PortableScene;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.app.state.TemplateLibrary;
import org.helioviewer.jhv.app.state.TemplateTime;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.ExtensionFileFilter;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * The template manager (Window > Templates... on macOS, File > Templates... elsewhere;
 * docs/templates.md): every template in one table, and what can be done to one. Built-ins are
 * read-only: they can be opened, duplicated, shared and set as startup, never changed or retired.
 * Retire moves a template's files into Templates/attic/; nothing here deletes.
 */
public final class TemplateManager {

    /**
     * The "Refresh data" hook, for the Heliad integration to fill: install an action and the
     * manager's Refresh Data button calls it with the selected template. Until something is
     * installed the button stays disabled, with the tooltip "Needs Heliad". A final holder, so the
     * hook is not a non-final static field (guards.py ratchet).
     */
    public static final class RefreshData {
        @Nullable private volatile Consumer<TemplateLibrary.Entry> action;

        public void install(Consumer<TemplateLibrary.Entry> refresh) {
            action = refresh;
        }

        @Nullable
        public Consumer<TemplateLibrary.Entry> action() {
            return action;
        }
    }

    /** HOOK: TemplateManager.refreshDataHook.install(entry -> ...) adds Refresh Data (Heliad). */
    public static final RefreshData refreshDataHook = new RefreshData();

    @SuppressWarnings("serial")
    public static final class Open extends AbstractAction {
        public Open() {
            super("Templates...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            show();
        }
    }

    private static final AtomicReference<JDialog> showing = new AtomicReference<>();

    private static final String[] COLUMNS = {"", "Name", "Description", "Time", "Datasets", "Modified"};
    private static final int THUMB = 64;

    @SuppressWarnings("serial")
    private static final class Model extends AbstractTableModel {
        private List<TemplateLibrary.Entry> rows = new ArrayList<>();
        private final Map<TemplateLibrary.Entry, Icon> icons = new HashMap<>();

        void reload() {
            rows = TemplateLibrary.all();
            icons.clear();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Icon.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            TemplateLibrary.Entry e = rows.get(row);
            return switch (column) {
                case 0 -> icons.computeIfAbsent(e, k -> TemplateChooser.icon(k.thumbnail(), THUMB));
                case 1 -> e.meta().name();
                case 2 -> e.meta().description();
                case 3 -> TemplateTime.describe(e.meta());
                case 4 -> e.datasets();
                default -> e.builtIn() ? "Built in" : TimeUtils.formatDate(e.modified()) + " " + TimeUtils.formatTime(e.modified()).substring(0, 5);
            };
        }
    }

    public static void show() {
        JDialog open = showing.get();
        if (open != null) {
            open.toFront();
            return;
        }
        JDialog dialog = new JDialog(MainFrame.get(), "Templates", false);
        Model model = new Model();
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(THUMB + 6);
        table.getColumnModel().getColumn(0).setPreferredWidth(THUMB + 8);
        table.getColumnModel().getColumn(0).setMaxWidth(THUMB + 8);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(180);

        JButton openButton = button("Open", "Open the selected template; a session with unsaved changes stays, and the template opens in a new window");
        JButton duplicate = button("Duplicate...", "Copy the selected template under a new name, as one of yours");
        JButton rename = button("Rename...", "Give the selected template a new name");
        JButton edit = button("Edit...", "Change the selected template's description and time rule");
        JButton startup = button("Set as Startup", "Open the selected template at every launch, with its times worked out fresh each time");
        JButton reveal = button(Platform.isMacOS() ? "Reveal in Finder" : "Show in Folder", "Show the selected template's file");
        JButton share = button("Share Template...", "Save a copy to hand to someone else: home folder written as ~, thumbnail inside the file");
        JButton retire = button("Retire", "Move the selected template to the attic folder inside Templates; it is not deleted");
        JButton refresh = button("Refresh Data", "Needs Heliad");
        JButton close = button("Close", "");
        List<JButton> needSelection = List.of(openButton, duplicate, rename, edit, startup, reveal, share, retire);

        Runnable update = () -> {
            TemplateLibrary.Entry e = selected(table, model);
            for (JButton b : needSelection)
                b.setEnabled(e != null);
            boolean user = e != null && !e.builtIn();
            rename.setEnabled(user);
            edit.setEnabled(user);
            retire.setEnabled(user);
            reveal.setEnabled(user);
            String readOnly = "Built-in templates cannot be changed; duplicate one to make your own";
            boolean builtIn = e != null && e.builtIn();
            rename.setToolTipText(builtIn ? readOnly : "Give the selected template a new name");
            edit.setToolTipText(builtIn ? readOnly : "Change the selected template's description and time rule");
            retire.setToolTipText(builtIn ? readOnly : "Move the selected template to the attic folder inside Templates; it is not deleted");
            reveal.setToolTipText(builtIn ? "A built-in template is inside the application, not in a folder" : "Show the selected template's file");
            refresh.setEnabled(e != null && refreshDataHook.action() != null);
            refresh.setToolTipText(refreshDataHook.action() == null ? "Needs Heliad" : "Fetch the newest data for the selected template's datasets");
        };
        table.getSelectionModel().addListSelectionListener(ev -> update.run());

        openButton.addActionListener(ev -> act(table, model, update, e -> TemplateChooser.open(e)));
        duplicate.addActionListener(ev -> act(table, model, update, e -> {
            String name = ask(dialog, "Duplicate", "Name of the copy:", e.meta().name() + " copy");
            if (name != null)
                TemplateLibrary.duplicate(e, name);
        }));
        rename.addActionListener(ev -> act(table, model, update, e -> {
            String name = ask(dialog, "Rename", "New name:", e.meta().name());
            if (name != null && !name.equals(e.meta().name()))
                TemplateLibrary.rename(e, name);
        }));
        edit.addActionListener(ev -> act(table, model, update, e -> {
            TemplateTime.Meta meta = TemplateSave.form(dialog, "Edit Template", e.meta(), false);
            if (meta != null)
                TemplateLibrary.edit(e, meta);
        }));
        startup.addActionListener(ev -> act(table, model, update, e -> {
            File f = TemplateLibrary.startupFile(e);
            Settings.setProperty("startup.loadState", f.getPath()); // the pinned default session's setting (Actions.SetDefaultSession)
            Message.info("Set as Startup", "\"" + e.meta().name() + "\" opens at every launch, with its times worked out fresh each time. "
                    + "File > Clear Default Session undoes this.");
        }));
        reveal.addActionListener(ev -> act(table, model, update, e -> {
            if (e.file() != null)
                DesktopIntegration.reveal(e.file());
        }));
        share.addActionListener(ev -> act(table, model, update, e -> share(dialog, e)));
        retire.addActionListener(ev -> act(table, model, update, e -> {
            if (JOptionPane.showConfirmDialog(dialog, "Retire \"" + e.meta().name() + "\"? Its files move to the attic folder inside "
                    + TemplateLibrary.userDir().getPath() + "; nothing is deleted.", "Retire", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                TemplateLibrary.retire(e);
        }));
        refresh.addActionListener(ev -> act(table, model, update, e -> {
            Consumer<TemplateLibrary.Entry> hook = refreshDataHook.action();
            if (hook != null)
                hook.accept(e);
        }));
        close.addActionListener(ev -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : List.of(openButton, duplicate, rename, edit, startup, reveal, share, retire, refresh))
            buttons.add(b);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(close);
        south.add(right, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setPreferredSize(new Dimension(1000, 520));
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent ev) {
                showing.compareAndSet(dialog, null);
            }
        });
        model.reload();
        update.run();
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        showing.set(dialog);
        dialog.setVisible(true);
    }

    private static JButton button(String text, String tip) {
        JButton b = new JButton(text);
        if (!tip.isEmpty())
            b.setToolTipText(tip);
        return b;
    }

    @Nullable
    private static TemplateLibrary.Entry selected(JTable table, Model model) {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.rows.size() ? null : model.rows.get(table.convertRowIndexToModel(row));
    }

    private interface Step {
        void run(TemplateLibrary.Entry e) throws IOException;
    }

    // Runs one button's step on the selected template, says what went wrong, and reloads the table.
    private static void act(JTable table, Model model, Runnable update, Step step) {
        TemplateLibrary.Entry e = selected(table, model);
        if (e == null)
            return;
        String key = e.key();
        try {
            step.run(e);
        } catch (IOException | RuntimeException ex) {
            Log.warn("Template " + e.meta().name(), ex);
            Message.err("Templates", ex.getMessage() == null ? "That did not work; see the log." : ex.getMessage());
        }
        model.reload();
        for (int i = 0; i < model.rows.size(); i++)
            if (model.rows.get(i).key().equals(key))
                table.setRowSelectionInterval(i, i);
        update.run();
    }

    @Nullable
    private static String ask(Component parent, String title, String prompt, String initial) {
        Object answer = JOptionPane.showInputDialog(parent, prompt, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
        String name = answer == null ? null : answer.toString().strip();
        return name == null || name.isEmpty() ? null : name;
    }

    // As File > Share Scene: the local files the receiver will not have are listed before anything is written.
    private static void share(Component parent, TemplateLibrary.Entry e) throws IOException {
        PortableScene.Result portable = TemplateLibrary.portable(e);
        if (!portable.localFiles().isEmpty()) {
            List<String> shown = portable.localFiles().size() > 12 ? portable.localFiles().subList(0, 12) : portable.localFiles();
            if (JOptionPane.showConfirmDialog(parent, portable.localFiles().size()
                            + " local file(s) in this template are not in the cache; the person you share it with will not have them:\n\n"
                            + String.join("\n", shown) + (shown.size() < portable.localFiles().size() ? "\n..." : "") + "\n\nShare it anyway?",
                    "Share Template", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
                return;
        }
        FileDialog fileDialog = new FileDialog(MainFrame.get(), "Share Template as...", FileDialog.SAVE);
        fileDialog.setFilenameFilter(ExtensionFileFilter.JHV);
        fileDialog.setMultipleMode(false);
        fileDialog.setDirectory(Directories.EXPORTS.getPath());
        fileDialog.setFile(TemplateLibrary.safeName(e.meta().name()) + ".jhv");
        fileDialog.setVisible(true);
        String directory = fileDialog.getDirectory(), file = fileDialog.getFile();
        if (directory != null && file != null)
            State.write(portable.scene(), directory, file.endsWith(".jhv") ? file : file + ".jhv");
    }

    private TemplateManager() {}
}
