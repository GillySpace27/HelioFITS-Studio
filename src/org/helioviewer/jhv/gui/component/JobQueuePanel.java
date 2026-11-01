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
import org.helioviewer.jhv.gui.status.ActivityRows;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
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
    private List<ActivityRows.Row> nowRows = List.of();
    private final AbstractTableModel nowModel = new AbstractTableModel() {
        private final String[] cols = {"What", "Progress", "Status"};

        @Override
        public int getRowCount() {
            return Math.max(1, nowRows.size());
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (nowRows.isEmpty())
                return c == 0 ? "Nothing running" : c == 1 ? null : "Everything has landed.";
            ActivityRows.Row row = nowRows.get(r);
            return c == 0 ? (row.failed() ? "\u26a0 " : "") + row.name() : c == 1 ? row : row.detail();
        }
    };
    private final JTable now = new JTable(nowModel);
    private final JButton stop = new JButton("Stop download");
    private final JButton show = new JButton("Show layer");

    /** A bar where the amount is known, a moving bar where it is not, nothing for a finished or failed row. */
    private static final class Bar extends javax.swing.JProgressBar implements javax.swing.table.TableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            if (!(v instanceof ActivityRows.Row row) || row.failed()) {
                setIndeterminate(false);
                setValue(0);
                setStringPainted(false);
                return this;
            }
            setIndeterminate(row.fraction() < 0);
            setStringPainted(row.fraction() >= 0);
            setValue(row.fraction() < 0 ? 0 : (int) Math.round(row.fraction() * 100));
            return this;
        }
    }

    private void refreshNow() {
        List<ActivityRows.Row> rows = ActivityRows.rows();
        ImageLayer sel = selectedLayer();
        nowRows = rows;
        nowModel.fireTableDataChanged();
        for (int i = 0; i < rows.size(); i++)
            if (sel != null && rows.get(i).layer() == sel)
                now.setRowSelectionInterval(i, i);
        updateNowButtons();
    }

    private @javax.annotation.Nullable ImageLayer selectedLayer() {
        int r = now.getSelectedRow();
        return r >= 0 && r < nowRows.size() ? nowRows.get(r).layer() : null;
    }

    private void updateNowButtons() {
        ImageLayer l = selectedLayer();
        stop.setEnabled(l != null && l.isDownloading());
        show.setEnabled(l != null);
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
        now.setFillsViewportHeight(true);
        now.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        now.getColumnModel().getColumn(0).setPreferredWidth(220);
        now.getColumnModel().getColumn(1).setPreferredWidth(160);
        now.getColumnModel().getColumn(2).setPreferredWidth(480);
        now.getColumnModel().getColumn(1).setCellRenderer(new Bar());
        now.setRowHeight(Math.max(now.getRowHeight(), 22));
        now.getSelectionModel().addListSelectionListener(e -> updateNowButtons());
        now.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2)
                    showLayer();
            }
        });
        stop.setToolTipText("Stop this layer's download; the frames already here stay");
        stop.addActionListener(e -> {
            ImageLayer l = selectedLayer();
            if (l != null)
                l.cancelDownloadTask();
        });
        show.setToolTipText("Select this layer in the Layers list (or double-click the row)");
        show.addActionListener(e -> showLayer());
        JPanel nowButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        nowButtons.add(stop);
        nowButtons.add(show);
        JScrollPane nowTable = new JScrollPane(now);
        nowTable.setPreferredSize(new Dimension(900, 170));
        JPanel nowScroll = new JPanel(new BorderLayout());
        nowScroll.add(nowTable, BorderLayout.CENTER);
        nowScroll.add(nowButtons, BorderLayout.SOUTH);
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
        javax.swing.Timer tick = new javax.swing.Timer(500, e -> refreshNow());
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

    private void showLayer() {
        ImageLayer l = selectedLayer();
        if (l != null) {
            Layers.setActiveImageLayer(l);
            MainFrame.get().toFront();
        }
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
