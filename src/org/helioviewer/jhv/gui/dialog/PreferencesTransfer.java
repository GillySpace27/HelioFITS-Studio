package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;

import javax.swing.AbstractAction;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.helioviewer.jhv.app.GlobalPreferences;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * File &gt; Save Global Preferences and Load Global Preferences, and the same two buttons in
 * Settings &gt; General. The work is in {@link GlobalPreferences}; this is the questions: whether
 * to include folder paths, and the list of what a load would change, before it does.
 */
public final class PreferencesTransfer {

    private PreferencesTransfer() {}

    public static class Save extends AbstractAction {
        public Save() {
            super("Save Global Preferences...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            save();
        }
    }

    public static class Load extends AbstractAction {
        public Load() {
            super("Load Global Preferences...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            load();
        }
    }

    public static void save() {
        JCheckBox paths = new JCheckBox("Include folder paths (download, session and export folders, recent sessions)");
        paths.setToolTipText("Off by default: a folder on this computer is rarely one on the next. Passwords, tokens and window positions are never saved.");
        Object[] message = {"Save your settings, keybindings, Canvas keys, interface scale, panel layout, Identify and load-guard limits\n"
                + "to one file you can load on another computer or after a reinstall.\n"
                + "Passwords, tokens (api.json) and window positions are never saved.", paths};
        if (JOptionPane.showConfirmDialog(MainFrame.get(), message, "Save Global Preferences", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        FileDialog dialog = new FileDialog(MainFrame.get(), "Save global preferences", FileDialog.SAVE);
        dialog.setDirectory(Settings.getProperty("path.local"));
        dialog.setFile("studio-preferences-" + LocalDate.now() + ".json");
        dialog.setVisible(true);
        if (dialog.getFile() == null)
            return;
        File file = new File(dialog.getDirectory(), dialog.getFile());
        try {
            GlobalPreferences.save(file.toPath(), paths.isSelected());
            Message.info("Global preferences saved", file.getPath());
        } catch (java.io.IOException ex) {
            Message.err("Could not save the preferences", ex.getMessage(), ex);
        }
    }

    public static void load() {
        FileDialog dialog = new FileDialog(MainFrame.get(), "Load global preferences", FileDialog.LOAD);
        dialog.setDirectory(Settings.getProperty("path.local"));
        dialog.setFilenameFilter((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
        dialog.setVisible(true);
        if (dialog.getFile() == null)
            return;
        Path file = new File(dialog.getDirectory(), dialog.getFile()).toPath();
        try {
            // Read once without paths to learn whether the file holds any; ask only if it does.
            GlobalPreferences.Plan plan = GlobalPreferences.planFile(file, false);
            boolean withPaths = false;
            if (!plan.held().isEmpty()) {
                JCheckBox box = new JCheckBox("Also apply the " + plan.held().size() + " folder path(s) in the file");
                Object[] ask = {"The file holds folder paths from the computer it was saved on.\nThey are left out unless you tick this.", box};
                JOptionPane.showMessageDialog(MainFrame.get(), ask, "Folder paths in the file", JOptionPane.QUESTION_MESSAGE);
                withPaths = box.isSelected();
                if (withPaths)
                    plan = GlobalPreferences.planFile(file, true);
            }
            if (plan.isEmpty()) {
                Message.info("Load Global Preferences", "Nothing to change.\n\n" + plan.summary());
                return;
            }
            JTextArea text = new JTextArea(plan.summary(), 22, 80);
            text.setEditable(false);
            JPanel body = new JPanel(new BorderLayout(0, 6));
            body.add(new JScrollPane(text), BorderLayout.CENTER);
            body.add(new javax.swing.JLabel("Apply these? Restart Studio afterwards so every panel reads them."), BorderLayout.SOUTH);
            body.setPreferredSize(new Dimension(760, 460));
            if (JOptionPane.showConfirmDialog(MainFrame.get(), body, "Load Global Preferences", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
                return;
            GlobalPreferences.apply(plan);
            Message.info("Global preferences loaded", plan.changes().size() + " setting(s) applied. Restart Studio so every panel reads them.");
        } catch (IllegalArgumentException | java.io.IOException ex) {
            Message.err("Could not load the preferences", ex.getMessage(), ex);
        }
    }
}
