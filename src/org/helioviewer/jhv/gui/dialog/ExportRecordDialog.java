package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.TransferAccess;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FitsCoreIngest;
import org.helioviewer.jhv.movie.ExportReport;
import org.helioviewer.jhv.movie.ProcessingRecord;
import org.helioviewer.jhv.thread.Task;

/**
 * File &gt; Open Export Record: an export's processing record (its .hfrec sidecar, or the older .hfrec.json) as the
 * readable report ExportReport makes, read-only, with Copy and Save as text. The methods
 * paragraph, LaTeX and Python script come from HelioFITS Core ({@code hfcore record show
 * --format methods|tex|python}), so those buttons need a core; Studio does not repeat that prose.
 * With no sidecar beside the file, a core reads the record inside the PNG, EXR or movie instead.
 * A record's picture (its preview, else a PNG or JPEG export beside it) heads the dialog.
 */
public final class ExportRecordDialog {

    private static final Duration CORE_WAIT = Duration.ofMinutes(1);
    private static final String NEEDS_CORE = "Needs HelioFITS Core";

    private final File export; // the file the person picked: an export or its sidecar
    @Nullable
    private final Map<String, Object> record; // null: the core reads the record
    private final JTextArea area = new JTextArea();
    private final JLabel status = new JLabel(" ");
    @Nullable
    private final BufferedImage picture; // ProcessingRecord.previewImage, or null for none

    private ExportRecordDialog(File export, @Nullable Map<String, Object> record, @Nullable BufferedImage picture) {
        this.export = export;
        this.record = record;
        this.picture = picture;
    }

    private record Opened(File file, Map<String, Object> record, @Nullable String renamed, @Nullable BufferedImage picture) {
    }

    public static Action migrateAction() {
        return new AbstractAction("Migrate Sidecars in Folder...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                migrateFolder();
            }
        };
    }

    /** Pick a folder, count its .hfrec.json records, confirm, rename them to .hfrec, report. Ignores the rename setting: it is asked for. */
    static void migrateFolder() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(Directories.EXPORTS.getPath());
        chooser.setDialogTitle("Migrate Sidecars in Folder");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(MainFrame.get()) != javax.swing.JFileChooser.APPROVE_OPTION)
            return;
        Path dir = chooser.getSelectedFile().toPath();
        Task.submitBackground(() -> ProcessingRecord.migrateFolder(dir, false), plan -> {
            long todo = plan.stream().filter(r -> "would_rename".equals(r[2])).count();
            if (todo == 0) {
                Message.info("Migrate Sidecars", "Nothing to rename in " + dir.getFileName() + ": " + skipped(plan) + ".");
                return;
            }
            if (javax.swing.JOptionPane.showConfirmDialog(MainFrame.get(), "Rename " + todo + " .hfrec.json sidecar" + (todo == 1 ? "" : "s")
                    + " in " + dir.getFileName() + " (and its subfolders) to .hfrec? The content is not changed; an existing .hfrec is never replaced.",
                    "Migrate Sidecars", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION)
                return;
            Task.submitBackground(() -> ProcessingRecord.migrateFolder(dir, true), done -> Message.info("Migrate Sidecars",
                            done.stream().filter(r -> "renamed".equals(r[2])).count() + " renamed; " + skipped(done) + "."),
                    t -> Message.err("Migrate Sidecars", "Could not rename the sidecars in " + dir, t));
        }, t -> Message.err("Migrate Sidecars", "Could not read " + dir, t));
    }

    private static String skipped(java.util.List<String[]> rows) {
        long exists = rows.stream().filter(r -> "exists".equals(r[2])).count();
        long bad = rows.stream().filter(r -> "not_a_record".equals(r[2])).count();
        long err = rows.stream().filter(r -> r[2].startsWith("error")).count();
        return "skipped " + (exists + bad + err) + " (" + exists + " with a .hfrec already there, " + bad + " not a record, " + err + " unreadable)";
    }

    public static Action openAction() {
        return new AbstractAction("Open Export Record...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        };
    }

    /** Ask for an exported file or its .hfrec (or older .hfrec.json), then show its record. */
    public static void open() {
        FileDialog fd = new FileDialog(MainFrame.get(), "Open an exported file or its .hfrec record", FileDialog.LOAD);
        fd.setDirectory(Directories.EXPORTS.getPath());
        fd.setVisible(true);
        File[] picked = fd.getFiles();
        if (picked.length == 0 || !picked[0].isFile())
            return;
        File file = picked[0];
        Path side = ProcessingRecord.isSidecarName(file.getName()) ? file.toPath() : ProcessingRecord.existingSidecar(file.toPath());
        if (side != null) {
            Task.submitBackground(() -> {
                        Map<String, Object> rec = ExportReport.parse(Files.readString(side, StandardCharsets.UTF_8));
                        Path now = ProcessingRecord.migrateLegacy(side); // an older .hfrec.json becomes .hfrec once read
                        return new Opened(side.equals(file.toPath()) ? now.toFile() : file, rec,
                                now.equals(side) ? null : now.getFileName().toString(), ProcessingRecord.previewImage(rec, now));
                    },
                    o -> {
                        ExportRecordDialog d = new ExportRecordDialog(o.file(), o.record(), o.picture());
                        if (o.renamed() != null)
                            d.status.setText("Renamed to " + o.renamed());
                        d.show();
                    },
                    t -> Message.err("Export record unreadable", side.getFileName() + " could not be read as a processing record.", t));
        } else if (FitsCoreIngest.coreFound()) {
            ExportRecordDialog d = new ExportRecordDialog(file, null, null);
            d.core("text", false, d::show, "read the record inside " + file.getName());
        } else
            Message.warn("No export record", "There is no " + ProcessingRecord.sidecar(file.toPath()).getFileName() + " beside " + file.getName()
                    + ". A record inside a PNG, EXR or movie is read by HelioFITS Core, which was not found.");
    }

    private void show() {
        show(record == null ? null : ExportReport.text(record, false));
    }

    private void show(@Nullable String text) {
        String title = ProcessingRecord.exportName(export.getName());
        JDialog dialog = new JDialog(MainFrame.get(), "Export Record: " + title, false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setText(text);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        area.setColumns(102);
        area.setRows(36);

        JCheckBox full = new JCheckBox("Show every source file");
        full.addActionListener(e -> {
            if (record != null) {
                area.setText(ExportReport.text(record, full.isSelected()));
                area.setCaretPosition(0);
            } else
                core("text", full.isSelected(), this::replaceText, "list the source files");
        });

        boolean coreFound = FitsCoreIngest.coreFound();
        JButton copy = button("Copy", () -> {
            TransferAccess.writeClipboard(area.getText());
            status.setText("Copied the report.");
        });
        JButton save = button("Save as Text...", () -> save(area.getText(), title + ".report.txt", "Saved the report to "));
        JButton methods = coreButton("Copy as Methods Paragraph", coreFound, () -> core("methods", false, s -> {
            TransferAccess.writeClipboard(s);
            status.setText("Copied the methods paragraph.");
        }, "write the methods paragraph"));
        JButton tex = coreButton("Copy as LaTeX", coreFound, () -> core("tex", false, s -> {
            TransferAccess.writeClipboard(s);
            status.setText("Copied the LaTeX.");
        }, "write the LaTeX"));
        JButton python = coreButton("Save as Python Script...", coreFound, () -> core("python", false,
                s -> save(s, title + ".py", "Saved the script to "), "write the Python script"));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(full);
        buttons.add(copy);
        buttons.add(save);
        buttons.add(methods);
        buttons.add(tex);
        buttons.add(python);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
        south.add(status, BorderLayout.SOUTH);

        if (picture != null) { // top left, beside the export's name
            JLabel name = new JLabel(title);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setIcon(new ImageIcon(picture));
            name.setIconTextGap(12);
            name.setVerticalTextPosition(JLabel.TOP);
            name.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            north.add(name);
            dialog.getContentPane().add(north, BorderLayout.NORTH);
        }
        dialog.getContentPane().add(scroll, BorderLayout.CENTER);
        dialog.getContentPane().add(south, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        dialog.setVisible(true);
    }

    private void replaceText(String text) {
        area.setText(text);
        area.setCaretPosition(0);
    }

    private static JButton button(String label, Runnable run) {
        JButton b = new JButton(label);
        b.addActionListener(e -> run.run());
        return b;
    }

    private static JButton coreButton(String label, boolean coreFound, Runnable run) {
        JButton b = button(label, run);
        b.setEnabled(coreFound);
        if (!coreFound)
            b.setToolTipText(NEEDS_CORE);
        return b;
    }

    /** hfcore record show &lt;the picked file&gt; --format fmt [--full] off the event thread, then {@code then} on it. */
    private void core(String fmt, boolean fullList, Consumer<String> then, String what) {
        status.setText("Asking HelioFITS Core to " + what + "...");
        String[] args = fullList
                ? new String[]{"record", "show", export.getPath(), "--format", fmt, "--full"}
                : new String[]{"record", "show", export.getPath(), "--format", fmt};
        Callable<String> run = () -> FitsCoreIngest.runCommand(CORE_WAIT, args);
        Task.submitBackground(run, s -> {
            status.setText(" ");
            then.accept(s);
        }, t -> {
            status.setText(" ");
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("invalid choice: 'record'")) // a core from before record show (pyHelioFITS, 2026-11)
                msg = "The HelioFITS Core found here is older than its record command. Update the HelioFITS Cache Manager, "
                        + "or name a newer core in HFCORE_CORE.";
            Message.err("HelioFITS Core could not " + what, msg, t);
        });
    }

    private void save(String text, String suggested, String done) {
        FileDialog fd = new FileDialog(MainFrame.get(), "Save as...", FileDialog.SAVE);
        fd.setDirectory(export.getParent());
        fd.setFile(suggested);
        fd.setVisible(true);
        File[] picked = fd.getFiles();
        if (picked.length == 0)
            return;
        try {
            Files.writeString(picked[0].toPath(), text, StandardCharsets.UTF_8);
            status.setText(done + picked[0].getName() + ".");
        } catch (IOException e) {
            Message.err("Not saved", "Could not write " + picked[0].getPath(), e);
        }
    }

}
