package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.io.CacheIndex;
import org.helioviewer.jhv.io.HeliadLibrary;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * The "Heliad library" tab of the New Image Layer dialog: datasets Heliad has already downloaded,
 * and how much of the master time range each one reaches, with Add loading them as layers.
 *
 * <p>One of the dialog's four tabs; {@link ImageDialog} owns the tabs and says when this one is in
 * front. With no Heliad library on the machine the tab says so instead of listing anything; a
 * library that appears or goes away while Studio runs is picked up the next time the dialog
 * opens. The tab is greyed out when the dialog is changing a layer's dataset, which only the
 * Helioviewer list can do.
 *
 * <p>Add loads each selected dataset as one layer, from the observations inside the master range.
 * A dataset with nothing in the range loads whole, which is how the range moves to it, as in
 * {@link CacheDialog}; past {@link #CONFIRM_FRAMES} frames that asks first. The range chips are
 * {@link CacheDialog}'s, so the two lists mean the same thing by green.
 *
 * <p>"Open in Heliad" hands the master range (and the selected dataset's instrument and
 * wavelength) to the Heliad app as a heliad:// link, so a search for more starts where this left
 * off. It is shown only when the app is installed.
 */
@SuppressWarnings("serial")
final class HeliadTab extends JPanel {

    /** A whole dataset this large asks before loading; Heliad libraries can hold thousands of frames. */
    static final int CONFIRM_FRAMES = 1000;

    private final Runnable onChange;
    private final Runnable onActivate;
    private final JLabel status = new JLabel(" ");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton openInHeliad = new JButton("Open in Heliad");

    @Nullable
    private HeliadLibrary.Location library;
    private boolean front; // this tab is the one showing
    // The New Image Layer window's range, which starts as the master range and can be changed there.
    private final java.util.function.LongSupplier rangeStart;
    private final java.util.function.LongSupplier rangeEnd;
    private boolean changeMode;
    private int reads; // so a slow read that lands after a newer one is dropped

    /**
     * @param _onChange   called when what Add would do changes: the tab, or the selection in it
     * @param _onActivate called on a double-click, to add the row as the dialog's Add would
     */
    HeliadTab(Runnable _onChange, Runnable _onActivate, java.util.function.LongSupplier _rangeStart,
              java.util.function.LongSupplier _rangeEnd) {
        super(new BorderLayout(0, 4));
        rangeStart = _rangeStart;
        rangeEnd = _rangeEnd;
        onChange = _onChange;
        onActivate = _onActivate;

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.setFillsViewportHeight(true);
        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(Model.NAME, SortOrder.ASCENDING)));
        table.setRowSorter(sorter);
        table.getColumnModel().getColumn(Model.STATUS).setCellRenderer(new StatusCell());
        table.getColumnModel().getColumn(Model.STATUS).setPreferredWidth(55);
        table.getColumnModel().getColumn(Model.NAME).setPreferredWidth(200);
        table.getColumnModel().getColumn(Model.IN_RANGE).setPreferredWidth(65);
        table.getColumnModel().getColumn(Model.FRAMES).setPreferredWidth(55);
        table.getColumnModel().getColumn(Model.SPAN).setPreferredWidth(205);
        table.getColumnModel().getColumn(Model.SIZE).setPreferredWidth(60);
        table.getColumnModel().getColumn(Model.SPAN).setCellRenderer(new SpanCell());
        table.getColumnModel().getColumn(Model.SIZE).setCellRenderer(new SizeCell());
        table.getSelectionModel().addListSelectionListener(e -> onChange.run());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0)
                    onActivate.run();
            }
        });

        JScrollPane scroller = new JScrollPane(table);
        scroller.setPreferredSize(new Dimension(660, 330));
        UIGlobals.themed(status, c -> c.setForeground(UIGlobals.foreColor));
        openInHeliad.setToolTipText("Search for more in the Heliad app, starting from the master time range");
        openInHeliad.addActionListener(e -> DesktopIntegration.openURL(HeliadLibrary.link(rangeStart(), rangeEnd(), single())));
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        bottom.add(openInHeliad);

        setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        add(status, BorderLayout.PAGE_START);
        add(scroller, BorderLayout.CENTER);
        add(bottom, BorderLayout.PAGE_END);
    }

    /** The dialog brought this tab to the front, or moved off it. */
    void setFront(boolean _front) {
        front = _front;
        if (front)
            refresh();
    }

    /** Called as the dialog opens: look for the library again and re-read it. */
    void update(boolean _changeMode) {
        changeMode = _changeMode;
        library = HeliadLibrary.find();
        openInHeliad.setVisible(library != null && HeliadLibrary.app() != null);
        if (library == null) {
            model.setRows(List.of(), rangeStart(), rangeEnd());
            status.setText(HeliadLibrary.app() == null
                    ? "No Heliad library on this computer. Heliad downloads FITS into a local library that Studio reads."
                    : "No Heliad library yet. Open Heliad to download into one; Studio reads it from here.");
        } else if (front)
            refresh();
    }

    /** Whether the Heliad tab is the one in front, so Add means "add from Heliad". */
    boolean showing() {
        return front;
    }

    boolean canLoad() {
        return !changeMode && !selected().isEmpty();
    }

    /**
     * Load each selected dataset as one layer.
     *
     * @return whether anything was loaded, so the dialog knows to close
     */
    boolean load() {
        HeliadLibrary.Location lib = library;
        List<HeliadLibrary.Dataset> sets = selected();
        if (lib == null || sets.isEmpty())
            return false;
        long start = rangeStart(), end = rangeEnd();
        boolean loaded = false;
        try {
            for (HeliadLibrary.Dataset set : sets) {
                // Asked again rather than taken from the row: the range may have moved since the list was read.
                List<File> files = HeliadLibrary.files(lib, set.key(), start, end);
                if (files.isEmpty()) {
                    if (set.frames() > CONFIRM_FRAMES && JOptionPane.showConfirmDialog(this,
                            set.label() + " has nothing in the master time range.\nLoad all " + String.format("%,d", set.frames())
                                    + " frames (" + diskSize(set.bytes()) + ")?", "Load Whole Dataset",
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION)
                        continue;
                    files = HeliadLibrary.files(lib, set.key());
                }
                List<URI> uris = new ArrayList<>();
                for (File file : files)
                    if (file.isFile()) // a delete in Heliad since the list was read
                        uris.add(file.toURI());
                if (uris.isEmpty()) {
                    Message.warn("Nothing left to load", "The files of " + set.label() + " are no longer in the Heliad library.");
                    refresh();
                    continue;
                }
                Commands.loadImage(uris);
                loaded = true;
            }
        } catch (SQLException | HeliadLibrary.TooNew e) {
            Message.err("Could not read the Heliad library", e.getMessage(), e);
        }
        return loaded;
    }

    /** Read the datasets off the event thread; the table fills when the read lands. */
    private void refresh() {
        HeliadLibrary.Location lib = library;
        if (lib == null)
            return;
        int read = ++reads;
        long start = rangeStart(), end = rangeEnd();
        status.setText("Reading the Heliad library...");
        new SwingWorker<List<HeliadLibrary.Dataset>, Void>() {
            @Override
            protected List<HeliadLibrary.Dataset> doInBackground() throws SQLException, HeliadLibrary.TooNew {
                return HeliadLibrary.datasets(lib, start, end);
            }

            @Override
            protected void done() {
                if (read != reads)
                    return;
                List<HeliadLibrary.Dataset> sets;
                try {
                    sets = get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    model.setRows(List.of(), start, end);
                    status.setText(cause instanceof HeliadLibrary.TooNew ? cause.getMessage()
                            : "Could not read the Heliad library: " + (cause.getMessage() == null ? "see the log for details." : cause.getMessage()));
                    onChange.run();
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                model.setRows(sets, start, end);
                status.setText(summary(lib, sets));
                status.setToolTipText(lib.root().toString());
                onChange.run();
            }
        }.execute();
    }

    private static String summary(HeliadLibrary.Location lib, List<HeliadLibrary.Dataset> sets) {
        int frames = 0;
        long bytes = 0;
        for (HeliadLibrary.Dataset set : sets) {
            frames += set.frames();
            bytes += set.bytes();
        }
        return String.format("%,d", frames) + (frames == 1 ? " frame" : " frames") + " · " + diskSize(bytes) + " · "
                + sets.size() + (sets.size() == 1 ? " dataset" : " datasets") + " in " + folder(lib.root());
    }

    /** The library folder as a person would type it, so the line fits; the tooltip has the full path. */
    private static String folder(Path root) {
        Path home = Path.of(System.getProperty("user.home"));
        return root.startsWith(home) && !root.equals(home) ? "~/" + home.relativize(root) : root.toString();
    }

    private List<HeliadLibrary.Dataset> selected() {
        List<HeliadLibrary.Dataset> out = new ArrayList<>();
        if (!showing())
            return out;
        for (int view : table.getSelectedRows()) {
            Row row = model.row(table.convertRowIndexToModel(view));
            if (row != null)
                out.add(row.set());
        }
        return out;
    }

    @Nullable
    private HeliadLibrary.Dataset single() {
        List<HeliadLibrary.Dataset> sets = selected();
        return sets.size() == 1 ? sets.getFirst() : null;
    }

    private long rangeStart() {
        return rangeStart.getAsLong();
    }

    private long rangeEnd() {
        return rangeEnd.getAsLong();
    }

    /** The window's range moved: re-read what falls inside it. */
    void rangeChanged() {
        if (front && library != null)
            refresh();
    }

    private static String diskSize(long bytes) {
        if (bytes >= 1L << 30)
            return String.format("%.1f GB", bytes / (double) (1L << 30));
        return bytes >= 1L << 20 ? (bytes >> 20) + " MB" : "< 1 MB";
    }

    /** A dataset with what it means for the range it was read against. */
    private record Row(HeliadLibrary.Dataset set, CacheIndex.Overlap overlap, double coverage) {}

    private static final class Model extends AbstractTableModel {

        static final int STATUS = 0, NAME = 1, IN_RANGE = 2, FRAMES = 3, SPAN = 4, SIZE = 5;
        private static final String[] TITLES = {"Range", "Dataset", "In range", "Frames", "Observed", "On disk"};

        private List<Row> rows = List.of();

        void setRows(List<HeliadLibrary.Dataset> sets, long start, long end) {
            List<Row> out = new ArrayList<>();
            for (HeliadLibrary.Dataset set : sets)
                out.add(new Row(set, CacheIndex.overlap(set.start(), set.end(), start, end),
                        CacheIndex.coverage(set.start(), set.end(), start, end)));
            rows = out;
            fireTableDataChanged();
        }

        @Nullable
        Row row(int index) {
            return index >= 0 && index < rows.size() ? rows.get(index) : null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return TITLES.length;
        }

        @Override
        public String getColumnName(int col) {
            return TITLES[col];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case STATUS -> Double.class;
                case IN_RANGE, FRAMES, SPAN, SIZE -> Long.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int index, int col) {
            Row row = rows.get(index);
            HeliadLibrary.Dataset set = row.set();
            return switch (col) {
                case STATUS -> row.coverage();
                case NAME -> set.label();
                case IN_RANGE -> (long) set.inRange();
                case FRAMES -> (long) set.frames();
                case SPAN -> set.start();
                default -> set.bytes();
            };
        }
    }

    /** The range chip, in {@link CacheDialog}'s colours and words. */
    private static final class StatusCell extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int viewRow, int col) {
            super.getTableCellRendererComponent(t, value, selected, focused, viewRow, col);
            setHorizontalAlignment(SwingConstants.CENTER);
            Row row = ((Model) t.getModel()).row(t.convertRowIndexToModel(viewRow));
            CacheIndex.Overlap overlap = row == null ? CacheIndex.Overlap.NONE : row.overlap();
            long percent = row == null ? 0 : Math.round(row.coverage() * 100);
            setText(switch (overlap) {
                case COVERS -> "covers";
                case PARTIAL -> percent + "%";
                case NONE -> "--";
            });
            setToolTipText(switch (overlap) {
                case COVERS -> "Spans the whole master time range";
                case PARTIAL -> "Overlaps the master time range by " + percent + '%';
                case NONE -> "Outside the master time range. Adding it loads the whole dataset, which moves the range to it";
            });
            if (!selected)
                setForeground(CacheDialog.StatusCell.chip(overlap));
            return this;
        }
    }

    private static final DateTimeFormatter SPAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** First to last observation, to the minute, the date once when both are on one day; the tooltip has the rest. */
    private static final class SpanCell extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int viewRow, int col) {
            super.getTableCellRendererComponent(t, value, selected, focused, viewRow, col);
            Row row = ((Model) t.getModel()).row(t.convertRowIndexToModel(viewRow));
            HeliadLibrary.Dataset set = row == null ? null : row.set();
            if (set == null || set.start() == Long.MIN_VALUE) {
                setText(set == null ? "" : "unknown");
                setToolTipText(null);
            } else {
                boolean oneDay = TimeUtils.formatDate(set.start()).equals(TimeUtils.formatDate(set.end()));
                setText(set.start() == set.end() ? TimeUtils.format(SPAN, set.start())
                        : TimeUtils.format(SPAN, set.start()) + " to " + TimeUtils.format(oneDay ? CLOCK : SPAN, set.end()));
                setToolTipText(TimeUtils.format(set.start()) + " to " + TimeUtils.format(set.end()));
            }
            return this;
        }
    }

    private static final class SizeCell extends DefaultTableCellRenderer {

        SizeCell() {
            setHorizontalAlignment(SwingConstants.TRAILING);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof Number n ? diskSize(n.longValue()) : "");
        }
    }

}
