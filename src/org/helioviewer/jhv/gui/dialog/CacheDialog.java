package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.io.CacheIndex;
import org.helioviewer.jhv.time.TimeUtils;

import com.jidesoft.dialog.ButtonPanel;
import com.jidesoft.dialog.StandardDialog;

/**
 * What is already on disk, and how it stands against the master time range.
 *
 * <p>The cache never evicts and nothing in the application has ever shown it, so a dataset that
 * has already been downloaded gets downloaded again the moment a parameter moves: PUNCH's file
 * version is the usual culprit, since {@code 0k} and {@code 0l} are two cache entries for one
 * observation. This lists the frames that are there, grouped into the datasets they came from, so
 * that answer is available before the download starts.
 *
 * <p>Read-only. The actions that change something (load one as a layer, delete one to get the disk
 * back) come next, deliberately after the half that only reads has been looked at on screen.
 *
 * <p>The scan is not instant the first time. Measured on a real cache, 754 files and 13 GB take
 * about two and a half seconds of header reading, so it runs on a worker with the count showing
 * and the table fills in when it lands. Afterwards only changed files are re-read and reopening is
 * immediate; see {@link CacheIndex}.
 */
@SuppressWarnings("serial")
public final class CacheDialog extends StandardDialog implements Interfaces.ShowableDialog {

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JTextField search = new JTextField(18);
    private final JCheckBox showCovers = new JCheckBox("covers", true);
    private final JCheckBox showPartial = new JCheckBox("partial", true);
    private final JCheckBox showNone = new JCheckBox("no overlap", true);

    private List<CacheIndex.Dataset> all = List.of();

    public CacheDialog() {
        super(MainFrame.get(), "Load from Cache");
        setResizable(true);
    }

    @Override
    public JComponent createBannerPanel() {
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(8, 9, 4, 9));

        JLabel lede = new JLabel("Datasets already on disk, and how much of the master time range each one covers.");
        top.add(lede, BorderLayout.PAGE_START);

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        filters.add(new JLabel("Find:"));
        search.setToolTipText("Match on mission, instrument, level, product type or version");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter(); }
        });
        filters.add(search);
        for (JCheckBox box : new JCheckBox[]{showCovers, showPartial, showNone}) {
            box.addActionListener(e -> refilter());
            filters.add(box);
        }
        top.add(filters, BorderLayout.CENTER);

        status.setForeground(UIGlobals.foreColor);
        top.add(status, BorderLayout.PAGE_END);
        return top;
    }

    @Override
    public JComponent createContentPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.setFillsViewportHeight(true);

        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        // Biggest first, because the only reason to look at a 13 GB cache by size is to find what
        // is worth reusing or worth deleting, and both of those are at the top of that order.
        sorter.setSortKeys(List.of(new RowSorter.SortKey(Model.SIZE, SortOrder.DESCENDING)));
        table.setRowSorter(sorter);

        table.getColumnModel().getColumn(Model.STATUS).setCellRenderer(new StatusCell());
        table.getColumnModel().getColumn(Model.STATUS).setPreferredWidth(90);
        table.getColumnModel().getColumn(Model.STATUS).setMaxWidth(120);
        table.getColumnModel().getColumn(Model.NAME).setPreferredWidth(240);
        table.getColumnModel().getColumn(Model.FRAMES).setPreferredWidth(60);
        table.getColumnModel().getColumn(Model.SPAN).setPreferredWidth(230);
        table.getColumnModel().getColumn(Model.CADENCE).setPreferredWidth(70);
        table.getColumnModel().getColumn(Model.SIZE).setPreferredWidth(70);
        table.getColumnModel().getColumn(Model.LEVEL).setPreferredWidth(45);
        table.getColumnModel().getColumn(Model.TYPE).setPreferredWidth(45);
        table.getColumnModel().getColumn(Model.VERSION).setPreferredWidth(55);

        // The model holds raw longs so the sorter can order them; these renderers are what
        // stops the table from showing epoch milliseconds and eleven-digit byte counts.
        table.getColumnModel().getColumn(Model.FRAMES).setCellRenderer(new NumberCell(v -> String.format("%,d", v)));
        table.getColumnModel().getColumn(Model.CADENCE).setCellRenderer(new NumberCell(CacheDialog::cadence));
        table.getColumnModel().getColumn(Model.SIZE).setCellRenderer(new NumberCell(CacheDialog::size));
        table.getColumnModel().getColumn(Model.SPAN).setCellRenderer(new SpanCell());

        JScrollPane scroller = new JScrollPane(table);
        scroller.setPreferredSize(new Dimension(880, 330));
        panel.add(scroller, BorderLayout.CENTER);
        return panel;
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction close = new AbstractAction("Close") {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        };
        setDefaultCancelAction(close);
        ButtonPanel panel = new ButtonPanel();
        javax.swing.JButton closeButton = new javax.swing.JButton(close);
        panel.add(closeButton, ButtonPanel.CANCEL_BUTTON);
        getRootPane().setDefaultButton(closeButton);
        return panel;
    }

    @Override
    public void showDialog() {
        pack();
        setLocationRelativeTo(MainFrame.get());
        rescan();
        setVisible(true);
    }

    /**
     * Read the cache on a worker and fill the table when it lands.
     *
     * <p>On the event thread this is two and a half seconds of frozen window the first time, which
     * is exactly long enough for the feature to be blamed for the wrong problem. The count is
     * published as it goes so the wait says what it is doing.
     */
    private void rescan() {
        status.setText("Reading the cache...");
        new SwingWorker<List<CacheIndex.Dataset>, Integer>() {
            @Override
            protected List<CacheIndex.Dataset> doInBackground() {
                return CacheIndex.group(CacheIndex.scan(this::publish));
            }

            @Override
            protected void process(List<Integer> counts) {
                status.setText("Reading the cache... " + counts.getLast() + " frames");
            }

            @Override
            protected void done() {
                try {
                    all = get();
                } catch (Exception e) {
                    status.setText("Could not read the cache: " + e.getMessage());
                    return;
                }
                refilter();
            }
        }.execute();
    }

    /** The master range the chips are measured against: whatever the movie is set to right now. */
    private long rangeStart() {
        return MoviePanel.getInstance().getTimeSelectorPanel().getStartTime();
    }

    private long rangeEnd() {
        return MoviePanel.getInstance().getTimeSelectorPanel().getEndTime();
    }

    private void refilter() {
        String needle = search.getText().trim().toLowerCase();
        List<Row> rows = new ArrayList<>();
        long bytes = 0;
        for (CacheIndex.Dataset set : all) {
            CacheIndex.Overlap overlap = CacheIndex.overlap(set.start(), set.end(), rangeStart(), rangeEnd());
            bytes += set.bytes();
            if (!showing(overlap))
                continue;
            if (!needle.isEmpty() && !set.key().toLowerCase().contains(needle))
                continue;
            rows.add(new Row(set, overlap, CacheIndex.coverage(set.start(), set.end(), rangeStart(), rangeEnd())));
        }
        model.setRows(rows);
        status.setText(summary(rows.size(), bytes));
    }

    private String summary(int shown, long bytes) {
        int frames = 0;
        for (CacheIndex.Dataset set : all)
            frames += set.frameCount();
        String held = frames + (frames == 1 ? " frame" : " frames") + " · " + size(bytes)
                + " · " + all.size() + (all.size() == 1 ? " dataset" : " datasets");
        return shown == all.size() ? held : held + ", showing " + shown;
    }

    private boolean showing(CacheIndex.Overlap overlap) {
        return switch (overlap) {
            case COVERS -> showCovers.isSelected();
            case PARTIAL -> showPartial.isSelected();
            case NONE -> showNone.isSelected();
        };
    }

    /** A dataset with what it means for the range in force when the table was last filled. */
    private record Row(CacheIndex.Dataset set, CacheIndex.Overlap overlap, double coverage) {}

    private static String size(long bytes) {
        return bytes >= 1L << 30
                ? String.format("%.1f GB", bytes / (double) (1L << 30))
                : (bytes >> 20) + " MB";
    }

    /** Median frame spacing, given in milliseconds, as a duration a person reads at a glance. */
    private static String cadence(long millis) {
        if (millis <= 0)
            return "";
        double seconds = millis / 1e3;
        if (seconds < 1)
            return String.format("%d ms", millis);
        if (seconds < 90)
            return seconds == Math.rint(seconds) ? String.format("%.0f s", seconds) : String.format("%.1f s", seconds);
        double minutes = seconds / 60;
        if (minutes < 90)
            return minutes == Math.rint(minutes) ? String.format("%.0f min", minutes) : String.format("%.1f min", minutes);
        double hours = minutes / 60;
        return hours < 48
                ? String.format("%.1f h", hours)
                : String.format("%.1f d", hours / 24);
    }

    /** Right-aligned cell whose text comes from the raw long the model still sorts on. */
    private static final class NumberCell extends DefaultTableCellRenderer {

        private final java.util.function.LongFunction<String> format;

        NumberCell(java.util.function.LongFunction<String> _format) {
            format = _format;
            setHorizontalAlignment(SwingConstants.TRAILING);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof Number n ? format.apply(n.longValue()) : "");
        }
    }

    /** The observed range. The model sorts on the start instant; this shows start to end. */
    private static final class SpanCell extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int viewRow, int col) {
            super.getTableCellRendererComponent(t, value, selected, focused, viewRow, col);
            Row row = ((Model) t.getModel()).row(t.convertRowIndexToModel(viewRow));
            setText(row == null ? "" : span(row.set()));
            return this;
        }
    }

    private static String span(CacheIndex.Dataset set) {
        return set.start() == set.end()
                ? TimeUtils.format(set.start())
                : TimeUtils.format(set.start()) + "  to  " + TimeUtils.format(set.end());
    }

    private static final class Model extends AbstractTableModel {

        static final int STATUS = 0, NAME = 1, FRAMES = 2, SPAN = 3, CADENCE = 4, SIZE = 5,
                LEVEL = 6, TYPE = 7, VERSION = 8;
        private static final String[] TITLES =
                {"Range", "Dataset", "Frames", "Observed", "Cadence", "On disk", "Level", "Type", "Version"};

        private List<Row> rows = List.of();

        void setRows(List<Row> _rows) {
            rows = _rows;
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
            // So the sorter sorts frames and bytes as numbers. Everything the cell renders is still
            // a string; these are the values behind them.
            return switch (col) {
                case STATUS -> Double.class; // sorts by how much of the range is covered
                case FRAMES, SIZE, CADENCE, SPAN -> Long.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int index, int col) {
            Row row = rows.get(index);
            CacheIndex.Dataset set = row.set();
            return switch (col) {
                case STATUS -> row.coverage();
                case NAME -> set.key();
                case FRAMES -> (long) set.frameCount();
                case SPAN -> set.start();
                case CADENCE -> set.cadence();
                case SIZE -> set.bytes();
                case LEVEL -> set.level();
                case TYPE -> set.typeCode();
                default -> set.version();
            };
        }
    }

    /**
     * The three chips, and the only colours in this dialog that are not the theme's.
     *
     * <p>Good, caution and nothing are a separate axis from the interface's accent: a theme is
     * free to be purple or orange, and green still has to mean "you already have this". They are
     * chosen per theme brightness rather than fixed, because a green that reads on a near-black
     * list is not the green that reads on paper. {@code CacheChipContrastCheck} holds all six
     * against the list background of every built-in theme.
     */
    static final class StatusCell extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int viewRow, int col) {
            super.getTableCellRendererComponent(t, value, selected, focused, viewRow, col);
            setHorizontalAlignment(SwingConstants.CENTER);
            double coverage = value instanceof Double d ? d : 0;
            Model model = (Model) t.getModel();
            Row row = model.row(t.convertRowIndexToModel(viewRow));
            CacheIndex.Overlap overlap = row == null ? CacheIndex.Overlap.NONE : row.overlap();
            setText(switch (overlap) {
                case COVERS -> "covers";
                case PARTIAL -> Math.round(coverage * 100) + "%";
                case NONE -> "--";
            });
            setToolTipText(switch (overlap) {
                case COVERS -> "Spans the whole master time range: load this and nothing is missing";
                case PARTIAL -> "Overlaps the master time range by " + Math.round(coverage * 100) + '%';
                case NONE -> "Outside the master time range. Loading it is also how you move the range to it";
            });
            if (!selected)
                setForeground(chip(overlap));
            return this;
        }

        static Color chip(CacheIndex.Overlap overlap) {
            boolean dark = Theme.current().dark();
            return switch (overlap) {
                case COVERS -> dark ? new Color(0x6F, 0xD0, 0x96) : new Color(0x1E, 0x6B, 0x3C);
                case PARTIAL -> dark ? new Color(0xE3, 0xB2, 0x5C) : new Color(0x8A, 0x59, 0x0C);
                // Lightened from #8F87A8, which read 4.26:1 on the sunset list and 3.90 on the
                // classic one: grey enough to recede is not the same as grey enough to disappear,
                // and a row that says nothing about the range still has to say it legibly.
                case NONE -> dark ? new Color(0xA1, 0x97, 0xBC) : new Color(0x6E, 0x66, 0x5C);
            };
        }
    }

}
