package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.study.JobQueue;
import org.helioviewer.jhv.study.JobQueue.Job;

/** The export queue: every job with its state and progress, and cancel, re-run and open folder. */
@SuppressWarnings({"serial", "this-escape"})
public final class JobQueuePanel extends JDialog {

    private static final AtomicReference<JobQueuePanel> PANEL = new AtomicReference<>();

    public static JobQueuePanel open() {
        JobQueuePanel p = PANEL.get();
        if (p == null) {
            p = new JobQueuePanel();
            PANEL.set(p);
        }
        if (!p.isVisible()) {
            p.pack();
            p.setLocationRelativeTo(MainFrame.get());
            p.setVisible(true);
        } else
            p.toFront();
        return p;
    }

    private static final String[] COLUMNS = {"Job", "State", "Progress", "Message"};

    private final JTable table = new JTable();
    private final JobQueue.Listener listener;

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return JobQueue.INSTANCE.jobs().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Job j = JobQueue.INSTANCE.jobs().get(r);
            return switch (c) {
                case 0 -> "#" + j.id + " " + j.label;
                case 1 -> j.state().name().toLowerCase(java.util.Locale.ROOT);
                case 2 -> j.total() == 0 ? "" : j.done() + " / " + j.total();
                default -> j.message();
            };
        }
    }

    private JobQueuePanel() {
        super(MainFrame.get(), "Export queue", false);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        table.setModel(new Model());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(3).setPreferredWidth(320);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> withSelected(JobQueue.INSTANCE::cancel));
        JButton rerun = new JButton("Re-run");
        rerun.addActionListener(e -> withSelected(JobQueue.INSTANCE::rerun));
        JButton open = new JButton("Open folder");
        open.addActionListener(e -> withSelected(JobQueuePanel::openFolder));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(cancel);
        buttons.add(rerun);
        buttons.add(open);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(900, 220));
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        listener = () -> {
            int sel = table.getSelectedRow();
            ((AbstractTableModel) table.getModel()).fireTableDataChanged();
            if (sel >= 0 && sel < table.getRowCount())
                table.setRowSelectionInterval(sel, sel);
        };
        JobQueue.INSTANCE.addListener(listener);
    }

    private void withSelected(java.util.function.Consumer<Job> action) {
        List<Job> jobs = JobQueue.INSTANCE.jobs();
        int r = table.getSelectedRow();
        if (r >= 0 && r < jobs.size())
            action.accept(jobs.get(r));
    }

    private static void openFolder(Job j) {
        if (j.folder() == null || !Desktop.isDesktopSupported())
            return;
        try {
            Desktop.getDesktop().open(j.folder().toFile());
        } catch (IOException | UnsupportedOperationException e) {
            org.helioviewer.jhv.app.Log.warn("Could not open " + j.folder(), e);
        }
    }
}
