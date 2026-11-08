package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
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
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.study.JobQueue;
import org.helioviewer.jhv.study.JobQueue.Job;

/**
 * The Task Manager, opened from the footer's spinner or Managers > Task Manager: what is running right
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
    /** One line of "Right now": live work, or work that has ended and stays until dismissed. */
    private record Item(ActivityRows.Row row, String status, boolean ended) {
        Object key() {
            return ended ? this : ActivityRows.key(row);
        }
    }

    private List<Item> nowRows = List.of();
    private final java.util.Map<Object, ActivityRows.Row> seen = new java.util.LinkedHashMap<>(); // tracked work running at the last tick
    private final List<Item> endedItems = new java.util.ArrayList<>();
    private final java.util.Set<Object> stoppedByUser = new java.util.HashSet<>();
    private final AbstractTableModel nowModel = new AbstractTableModel() {
        private final String[] cols = {"What", "Status", "Progress", "Message"};

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
                return c == 0 ? "Nothing running" : c == 3 ? "Everything has landed." : c == 2 ? null : "";
            Item it = nowRows.get(r);
            ActivityRows.Row row = it.row();
            return switch (c) {
                case 0 -> (row.failed() ? "\u26a0 " : "") + row.name();
                case 1 -> it.status();
                case 2 -> it.ended() ? null : row;
                default -> row.detail();
            };
        }
    };
    private final JTable now = new JTable(nowModel);
    private final JButton stop = new JButton("Stop");
    private final JButton show = new JButton("Show layer");
    private final JButton dismiss = new JButton("Dismiss");
    private final JButton clear = new JButton("Clear finished");

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
        Item selItem = selectedItem();
        Object selKey = selItem == null ? null : selItem.key();
        java.util.Set<Object> liveKeys = new java.util.HashSet<>();
        for (ActivityRows.Row r : rows)
            liveKeys.add(ActivityRows.key(r));
        for (var it = seen.entrySet().iterator(); it.hasNext(); ) { // tracked work that is gone: keep it, with how it ended
            var e = it.next();
            if (liveKeys.contains(e.getKey()))
                continue;
            it.remove();
            Item done = new Item(e.getValue(), outcome(e.getKey(), e.getValue()), true);
            endedItems.add(done);
            if (Objects.equals(selKey, e.getKey()))
                selKey = done.key();
        }
        List<Item> all = new java.util.ArrayList<>();
        for (ActivityRows.Row r : rows) {
            if (tracked(r))
                seen.put(ActivityRows.key(r), r);
            all.add(new Item(r, r.failed() ? "failed" : "running", false));
        }
        all.addAll(endedItems);
        nowRows = all;
        nowModel.fireTableDataChanged(); // clears the selection
        for (int i = 0; i < all.size(); i++)
            if (selKey != null && selKey.equals(all.get(i).key()))
                now.setRowSelectionInterval(i, i);
        updateNowButtons();
    }

    private @javax.annotation.Nullable Item selectedItem() {
        int r = now.getSelectedRow();
        return r >= 0 && r < nowRows.size() ? nowRows.get(r) : null;
    }

    private @javax.annotation.Nullable ActivityRows.Row selectedRow() { // live work only: an ended line has nothing to stop
        Item it = selectedItem();
        return it == null || it.ended() ? null : it.row();
    }

    /** Work worth confirming when it ends: a layer's load, the recording, a Python send, a timeline band. */
    private static boolean tracked(ActivityRows.Row r) {
        return !r.failed() && (r.layer() != null || "Recording".equals(r.name()) || "Python".equals(r.name()) || r.name().startsWith("Timeline:"));
    }

    private String outcome(Object key, ActivityRows.Row r) {
        if (stoppedByUser.remove(key))
            return "cancelled";
        return "Recording".equals(r.name()) ? ExportMovie.lastOutcome() : "success";
    }

    private @javax.annotation.Nullable ImageLayer selectedLayer() {
        ActivityRows.Row r = selectedRow();
        return r == null ? null : r.layer();
    }

    private static boolean isRecording(@javax.annotation.Nullable ActivityRows.Row r) {
        return r != null && r.layer() == null && "Recording".equals(r.name()) && ExportMovie.isRecording();
    }

    private void updateNowButtons() {
        ImageLayer l = selectedLayer();
        stop.setEnabled(l != null && l.isDownloading() || isRecording(selectedRow()));
        show.setEnabled(l != null);
        dismiss.setEnabled(selectedItem() != null && selectedItem().ended());
        clear.setEnabled(!endedItems.isEmpty());
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
        super(MainFrame.get(), "Task Manager", false);
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
        now.getColumnModel().getColumn(1).setPreferredWidth(80);
        now.getColumnModel().getColumn(2).setPreferredWidth(160);
        now.getColumnModel().getColumn(3).setPreferredWidth(400);
        now.getColumnModel().getColumn(2).setCellRenderer(new Bar());
        now.setRowHeight(Math.max(now.getRowHeight(), 22));
        now.getSelectionModel().addListSelectionListener(e -> updateNowButtons());
        now.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2)
                    showLayer();
            }
        });
        stop.setToolTipText("Stop the selected download (the frames already here stay) or the recording (the frames so far become the movie)");
        stop.addActionListener(e -> {
            ImageLayer l = selectedLayer();
            if (l != null) {
                stoppedByUser.add(ActivityRows.key(selectedRow()));
                l.cancelDownloadTask();
            } else if (isRecording(selectedRow()))
                ExportMovie.shallStop();
        });
        show.setToolTipText("Select this layer in the Layers list (or double-click the row)");
        show.addActionListener(e -> showLayer());
        JPanel nowButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        nowButtons.add(stop);
        nowButtons.add(show);
        dismiss.setToolTipText("Remove the selected finished line from this list");
        dismiss.addActionListener(e -> {
            Item it = selectedItem();
            if (it != null && it.ended()) {
                endedItems.remove(it);
                refreshNow();
            }
        });
        clear.setToolTipText("Remove every finished line from this list");
        clear.addActionListener(e -> {
            endedItems.clear();
            refreshNow();
        });
        nowButtons.add(dismiss);
        nowButtons.add(clear);
        JScrollPane nowTable = new JScrollPane(now);
        nowTable.setPreferredSize(new Dimension(900, 170));
        JPanel nowScroll = new JPanel(new BorderLayout());
        nowScroll.add(nowTable, BorderLayout.CENTER);
        nowScroll.add(nowButtons, BorderLayout.SOUTH);
        nowScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Right now: downloads, loads and computing; finished work stays until dismissed"));
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Export queue: one job at a time, in order"));
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        javax.swing.JLabel hint = new javax.swing.JLabel("Managers > Study Manager... queues a set here; Screenshot and Record from the Movie panel run directly.");
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
