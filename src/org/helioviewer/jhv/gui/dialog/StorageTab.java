package org.helioviewer.jhv.gui.dialog;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.StorageFolders;
import org.helioviewer.jhv.io.StorageMove;
import org.helioviewer.jhv.thread.Task;

/**
 * Settings > Storage: where the exports and the download cache are kept, how big they are, and a
 * button that moves what is already there. The choice lives in StorageFolders and applies at once.
 */
final class StorageTab extends JPanel {

    private final Row exports = new Row("Exports", "Movies, screenshots and their .hfrec records.",
            StorageFolders.EXPORTS, Directories.EXPORTS, true);
    private final Row cache = new Row("Download cache", "Downloaded data. Safe to delete: it downloads again when needed.",
            StorageFolders.FILECACHE, Directories.FILECACHE, false);
    private final JLabel note = new JLabel();

    StorageTab() {
        super(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(8, 8, 8, 8);
        c.gridy = 0;
        add(exports, c);
        c.gridy = 1;
        add(cache, c);
        c.gridy = 2;
        add(note, c);
        c.gridy = 3;
        add(otherInstalls(), c);
        c.gridy = 4;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        add(new JLabel(), c);
        refresh();
    }

    /** Other Studio installs on this computer: seen read-only in Open Recent and the cache lookup (io/Siblings). */
    private static JPanel otherInstalls() {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        javax.swing.JCheckBox on = new javax.swing.JCheckBox("Include other installs on this computer (read-only)",
                !"false".equalsIgnoreCase(org.helioviewer.jhv.app.Settings.getProperty(org.helioviewer.jhv.io.Siblings.ENABLED_KEY)));
        JLabel found = new JLabel();
        Runnable show = () -> found.setText(org.helioviewer.jhv.io.Siblings.homes().stream()
                .map(org.helioviewer.jhv.io.Siblings::name).collect(java.util.stream.Collectors.joining(", ")));
        on.addActionListener(e -> {
            org.helioviewer.jhv.app.Settings.setProperty(org.helioviewer.jhv.io.Siblings.ENABLED_KEY, String.valueOf(on.isSelected()));
            show.run();
        });
        JButton add = new JButton("Add Install Folder...");
        add.addActionListener(e -> {
            JFileChooser ch = new JFileChooser(System.getProperty("user.home"));
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (ch.showOpenDialog(MainFrame.get()) != JFileChooser.APPROVE_OPTION)
                return;
            String old = org.helioviewer.jhv.app.Settings.getProperty(org.helioviewer.jhv.io.Siblings.PATHS_KEY);
            org.helioviewer.jhv.app.Settings.setProperty(org.helioviewer.jhv.io.Siblings.PATHS_KEY,
                    (old == null || old.isBlank() ? "" : old + "\n") + ch.getSelectedFile().getAbsolutePath());
            show.run();
        });
        show.run();
        p.add(on);
        p.add(add);
        p.add(found);
        return p;
    }

    /** Re-read the folders and their sizes; the sizes are measured off the screen thread. */
    void refresh() {
        exports.refresh();
        cache.refresh();
        String fallback = StorageFolders.note();
        note.setText(fallback == null ? " " : "<html>" + fallback + "</html>");
    }

    private final class Row extends JPanel {
        private final String key;
        private final Directories dir;
        private final boolean sidecars;
        private final JLabel path = new JLabel();
        private final JLabel size = new JLabel(" ");
        private final JButton choose = new JButton("Choose...");
        private final JButton reset = new JButton("Use default");
        private final JButton move = new JButton("Move existing files...");
        private final JButton show = new JButton(org.helioviewer.jhv.gui.ExportsFolder.SHOW_LABEL);

        Row(String title, String blurb, String key, Directories dir, boolean sidecars) {
            super(new GridBagLayout());
            this.key = key;
            this.dir = dir;
            this.sidecars = sidecars;
            setBorder(javax.swing.BorderFactory.createTitledBorder(title));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = 5;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 6, 2, 6);
            add(new JLabel(blurb), c);
            c.gridy = 1;
            add(path, c);
            c.gridy = 2;
            add(size, c);
            c.gridy = 3;
            c.gridwidth = 1;
            add(choose, c);
            c.gridx = 1;
            add(reset, c);
            c.gridx = 2;
            add(move, c);
            c.gridx = 3;
            add(show, c);
            c.gridx = 4;
            c.weightx = 1;
            add(new JLabel(), c); // takes the slack, so the rows sit at the left

            choose.addActionListener(e -> chooseFolder());
            reset.addActionListener(e -> useDefault());
            move.addActionListener(e -> moveFiles());
            show.addActionListener(e -> {
                File f = dir.getFile();
                if (f.isDirectory())
                    DesktopIntegration.reveal(f);
            });
        }

        void refresh() {
            Path now = Path.of(dir.getPath());
            String chosen = StorageFolders.chosen(key);
            path.setText(now + (chosen == null ? "  (default)" : chosen.equals(StorageFolders.stripSeparator(now.toString())) ? "" : "  (default for now: " + chosen + " is not available)"));
            reset.setEnabled(chosen != null);
            size.setText("Measuring...");
            Task.submitBackground("storage-size", () -> StorageMove.size(now),
                    bytes -> size.setText(format(bytes) + " in use"),
                    (ctx, t) -> size.setText(" "));
        }

        private void chooseFolder() {
            JFileChooser chooser = new JFileChooser(dir.getPath());
            chooser.setDialogTitle("Choose the " + key + " folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(MainFrame.get()) != JFileChooser.APPROVE_OPTION)
                return;
            File picked = chooser.getSelectedFile();
            if (picked == null)
                return;
            try {
                if (!picked.isDirectory() && !picked.mkdirs())
                    throw new IOException("Could not create " + picked);
                if (!picked.canWrite())
                    throw new IOException(picked + " is not writable");
                String before = dir.getPath();
                StorageFolders.choose(key, picked.getAbsolutePath());
                afterChange(before);
            } catch (IOException ex) {
                Message.err("Storage folder", ex.getMessage());
            }
        }

        private void useDefault() {
            try {
                String before = dir.getPath();
                StorageFolders.choose(key, null);
                afterChange(before);
            } catch (IOException ex) {
                Message.err("Storage folder", ex.getMessage());
            }
        }

        /** After the folder changed: offer to bring the files that were in the old place. */
        private void afterChange(String before) {
            refresh();
            Path from = Path.of(before);
            Path to = Path.of(dir.getPath());
            if (from.equals(to) || StorageMove.size(from) == 0)
                return;
            int answer = JOptionPane.showConfirmDialog(this,
                    "Move what is in " + from + " to " + to + " now?\n\n"
                            + "Files keep their names and stay beside their records. Nothing is overwritten.",
                    "Move existing files", JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION)
                run(from, to);
        }

        private void moveFiles() {
            String def = dir.defaultPath();
            Path now = Path.of(dir.getPath());
            Path other = Path.of(def);
            if (now.equals(other)) {
                Message.info("Move existing files", "Choose a folder first; files in the default folder move to the folder you choose.");
                return;
            }
            Object[] options = {"Default folder to the chosen one", "Chosen folder back to the default", "Cancel"};
            int pick = JOptionPane.showOptionDialog(this, "Which way?\n\nDefault: " + other + "\nChosen: " + now,
                    "Move existing files", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (pick == 0)
                run(other, now);
            else if (pick == 1)
                run(now, other);
        }

        private void run(Path from, Path to) {
            move.setEnabled(false);
            choose.setEnabled(false);
            reset.setEnabled(false);
            size.setText("Moving...");
            Task.submitBackground("storage-move", () -> StorageMove.move(from, to, n -> {}),
                    r -> {
                        move.setEnabled(true);
                        choose.setEnabled(true);
                        refresh();
                        report(r);
                    },
                    (ctx, t) -> {
                        Log.error(ctx, t);
                        move.setEnabled(true);
                        choose.setEnabled(true);
                        refresh();
                        Message.err("Move existing files", t.getMessage() == null ? t.toString() : t.getMessage());
                    });
        }

        private void report(StorageMove.Result r) {
            StringBuilder text = new StringBuilder("Moved " + r.moved() + " files (" + format(r.bytes()) + ").");
            if (r.skipped() > 0)
                text.append(" Left ").append(r.skipped()).append(" in place because the destination already had a file with that name.");
            if (r.failed() > 0) {
                text.append(" ").append(r.failed()).append(" could not be moved and stay where they were.");
                r.problems().stream().limit(3).forEach(p -> Log.warn("Storage move: " + p));
            }
            if (sidecars && r.moved() > 0)
                text.append("\nRecords (.hfrec) moved together with their exports.");
            if (r.failed() > 0)
                Message.warn("Move existing files", text.toString());
            else
                Message.info("Move existing files", text.toString());
        }
    }

    static String format(long bytes) {
        return bytes >= 1L << 30 ? String.format("%.1f GB", bytes / (double) (1L << 30)) : (bytes >> 20) + " MB";
    }
}
