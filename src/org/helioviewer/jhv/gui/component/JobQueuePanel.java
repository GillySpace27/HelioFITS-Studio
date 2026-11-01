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

/**
 * The Activity window, opened from the footer's spinner or Window > Activity: what is running right
 * now (downloads, loads, filters, event fetches, the export in progress), what did not land, and the
 * export queue (study runs, Screenshot, Record), which runs one job at a time with cancel, re-run and
 * open folder. Downloads are listed, not queued: they run side by side and are managed from their layer.
 */
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
    private final javax.swing.DefaultListModel<String> nowModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> now = new javax.swing.JList<>(nowModel);

    /** The footer spinner's own lines, so the two never disagree. */
    private void refreshNow() {
        java.util.List<String> lines = new java.util.ArrayList<>(org.helioviewer.jhv.gui.status.ActivityStatusPanel.running());
        for (String f : org.helioviewer.jhv.gui.status.ActivityStatusPanel.failed())
            lines.add("Did not land: " + f);
        if (lines.isEmpty())
            lines.add("Nothing running: everything has landed.");
        if (!lines.equals(java.util.Collections.list(nowModel.elements()))) {
            nowModel.clear();
            nowModel.addAll(lines);
        }
    }
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
        super(MainFrame.get(), "Activity", false);
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
        scroll.setPreferredSize(new Dimension(900, 200));
        now.setVisibleRowCount(5);
        JScrollPane nowScroll = new JScrollPane(now);
        nowScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Right now: downloads, loads and computing (managed from their layers)"));
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Export queue: one job at a time, in order"));
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        javax.swing.JLabel hint = new javax.swing.JLabel("Movie > Study... queues a set here; Screenshot and Record from the Movie panel run directly.");
        hint.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 6, 8));
        south.add(hint, BorderLayout.SOUTH);
        getContentPane().add(nowScroll, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        javax.swing.Timer tick = new javax.swing.Timer(1000, e -> refreshNow());
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refreshNow();
                tick.start();
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                tick.stop();
            }
        });
        refreshNow();

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
