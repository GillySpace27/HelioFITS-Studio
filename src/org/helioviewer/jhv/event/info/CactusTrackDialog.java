package org.helioviewer.jhv.event.info;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.event.JHVEvent;
import org.helioviewer.jhv.event.JHVEventCache;
import org.helioviewer.jhv.event.JHVEventListener;
import org.helioviewer.jhv.event.JHVEventParameter;
import org.helioviewer.jhv.event.JHVRelatedEvents;
import org.helioviewer.jhv.event.SWEKCatalog;
import org.helioviewer.jhv.event.SWEKSupplier;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeUtils;

// Browse the CACTus CME events loaded in the current movie range and pick one to track:
// double-click (or Track) jumps the playhead to the event onset, switches to RadialWarp, and
// engages CMETracker so the front holds a fixed screen radius. Reuses the loaded event cache;
// no runtime arc-catching. Kept in event.info (core) so the menu action need not import the
// SWEK plugin; the SWEK panel's Track button calls in from the plugin side.
@SuppressWarnings("serial")
public final class CactusTrackDialog extends JDialog implements JHVEventListener.Handle, JHVEventListener.Highlight {

    private static final String[] COLUMNS = {"Onset (UTC)", "Speed km/s", "Width°", "PA°", "Source"};

    private static CactusTrackDialog instance;

    // Show (creating on first use), refreshed from the current event cache each time.
    public static void open() {
        if (instance == null)
            instance = new CactusTrackDialog();
        instance.ensureCactusLoaded(); // pull CACTus events for the movie range if not already active
        instance.reload();
        instance.setVisible(true);
        instance.toFront();
    }

    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel status;
    private final List<JHVRelatedEvents> rows = new ArrayList<>(); // aligned with model rows
    private boolean syncingSelection; // guard against the table<->canvas highlight feedback loop

    private CactusTrackDialog() {
        super(MainFrame.get(), "Track CME");
        setType(Window.Type.UTILITY);
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE); // reused across opens

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 || c == 4 ? String.class : Integer.class; // numeric cols sort numerically
            }
        };
        table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    trackSelected(CMETracker.getMode()); // double-click reuses whichever mode was last used
            }
        });
        // Row selection -> canvas/timeline: highlight the picked wedge (skip while we are the ones
        // mirroring a canvas-driven highlight, so the two directions don't ping-pong).
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncingSelection)
                JHVEventCache.highlight(selected());
        });

        status = new JLabel(" ");
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));

        // One button per way of holding the front, rather than a mode selector plus a Track button.
        JButton trackWarpButton = new JButton("Track (Warp)");
        trackWarpButton.setToolTipText("Jump to this CME's onset and animate the Box-Cox warp (λ) so the front holds a fixed screen radius — the corona rubber-bands around a stationary front");
        trackWarpButton.addActionListener(e -> trackSelected(CMETracker.Mode.WARP));
        JButton trackEdgeButton = new JButton("Track (Edge)");
        trackEdgeButton.setToolTipText("Jump to this CME's onset and animate the outer edge crop instead, holding λ — the field of view widens to follow the front, like a zoom-out");
        trackEdgeButton.addActionListener(e -> trackSelected(CMETracker.Mode.EDGE));
        JButton detailsButton = new JButton("Details…");
        detailsButton.addActionListener(e -> detailsSelected());
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(detailsButton);
        buttons.add(trackWarpButton);
        buttons.add(trackEdgeButton);
        buttons.add(closeButton);

        setLayout(new BorderLayout());
        add(status, BorderLayout.PAGE_START);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons, BorderLayout.PAGE_END);
        setPreferredSize(new Dimension(540, 360));
        pack();
        setLocationRelativeTo(MainFrame.get());
    }

    // Listen only while shown: the cache handler repopulates the table when the async CACTus
    // download lands, and the highlight listener mirrors a canvas wedge selection into the table.
    @Override
    public void setVisible(boolean b) {
        if (b) {
            JHVEventCache.registerHandler(this);
            JHVEventCache.addHighlightListener(this);
        } else {
            JHVEventCache.unregisterHandler(this);
            JHVEventCache.removeHighlightListener(this);
        }
        super.setVisible(b);
    }

    // Make the dialog self-sufficient: if CACTus isn't an active supplier yet, activate it and
    // request the current movie range, rather than requiring the user to tick it in the SWEK tree
    // first. The download is async; the Handle callbacks below refresh the table when it arrives.
    private void ensureCactusLoaded() {
        SWEKSupplier cactus = SWEKCatalog.findCactus();
        if (cactus == null)
            return;
        if (!JHVEventCache.isSupplierActive(cactus))
            JHVEventCache.setSupplierActive(cactus, true);
        JHVEventCache.requestForInterval(Player.getStartTime(), Player.getEndTime(), this);
    }

    @Override
    public void cacheUpdated() {
        java.awt.EventQueue.invokeLater(this::reload);
    }

    @Override
    public void newEventsReceived() {
        java.awt.EventQueue.invokeLater(this::reload);
    }

    // Canvas/timeline -> table: when the global highlight changes (e.g. a wedge was clicked on the
    // canvas), select the matching row. Guarded so mirroring the highlight doesn't re-fire it.
    @Override
    public void highlightChanged() {
        java.awt.EventQueue.invokeLater(() -> {
            JHVRelatedEvents hl = JHVEventCache.getHighlighted();
            int modelRow = hl == null ? -1 : rows.indexOf(hl);
            syncingSelection = true;
            try {
                if (modelRow < 0)
                    table.clearSelection();
                else {
                    int viewRow = table.convertRowIndexToView(modelRow);
                    table.setRowSelectionInterval(viewRow, viewRow);
                    table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                }
            } finally {
                syncingSelection = false;
            }
        });
    }

    private void reload() {
        syncingSelection = true; // rebuilding the model churns the selection; don't treat that as a user pick
        model.setRowCount(0);
        rows.clear();

        List<JHVRelatedEvents> events = JHVEventCache.getEvents(Player.getStartTime(), Player.getEndTime());
        events.stream()
                .filter(re -> re.getSupplier().isCactus() && !re.getEvents().isEmpty())
                .sorted((a, b) -> Long.compare(representative(a).start, representative(b).start))
                .forEach(re -> {
                    JHVEvent evt = representative(re);
                    rows.add(re);
                    model.addRow(new Object[]{
                            TimeUtils.formatShort(evt.start),
                            intParam(evt, "cme_radiallinvel"),
                            intParam(evt, "cme_angularwidth"),
                            intParam(evt, "event_coord1"),
                            re.getSupplier().displayName()});
                });
        syncingSelection = false;
        highlightChanged(); // restore the row selection to whatever wedge is currently highlighted

        status.setText(rows.isEmpty()
                ? "No CACTus events in the loaded range — enable HEK → CME → CACTus and load a coronagraph movie."
                : rows.size() + " CACTus event(s) — double-click one to track.");
    }

    private JHVRelatedEvents selected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0)
            return null;
        return rows.get(table.convertRowIndexToModel(viewRow));
    }

    private void trackSelected(CMETracker.Mode mode) {
        JHVRelatedEvents re = selected();
        if (re == null)
            return;
        if (!Player.isAvailable()) { // setTime would silently no-op; don't switch projection / engage against a stale time
            status.setText("Load a coronagraph movie first — there is no movie to jump to.");
            return;
        }
        JHVEvent evt = representative(re);
        CMETracker.stop();                           // disengage first: setTime fires listeners synchronously,
        CMETracker.setMode(mode);                    // ...and pick the knob before engaging
        Player.setTime(new JHVTime(evt.start));      // so a still-registered tracker must not solve with stale params
        ViewState.setProjection(MapMode.RadialWarp); // no-op if already there; fits on entry
        CMETracker.track(speedOf(evt), evt.start, paOf(evt)); // re-engage with this CME's params
        JHVEventCache.highlight(re);
    }

    private void detailsSelected() {
        JHVRelatedEvents re = selected();
        if (re == null)
            return;
        new SWEKEventInformationDialog(re, representative(re)).setVisible(true);
    }

    // The time-earliest variant, so its start matches JHVRelatedEvents' interval start (get(0) is
    // merge/insertion order, which can differ after events associate) — used for onset + sorting.
    private static JHVEvent representative(JHVRelatedEvents re) {
        JHVEvent earliest = re.getEvents().get(0);
        for (JHVEvent e : re.getEvents())
            if (e.start < earliest.start)
                earliest = e;
        return earliest;
    }

    private static double speedOf(JHVEvent evt) {
        Integer s = intParam(evt, "cme_radiallinvel");
        return s == null ? 500 : s; // matches the arc renderer's fallback
    }

    private static double paOf(JHVEvent evt) {
        Integer pa = intParam(evt, "event_coord1"); // CACTus principal angle
        return pa == null ? 0 : pa;
    }

    private static Integer intParam(JHVEvent evt, String key) {
        JHVEventParameter p = evt.getParameter(key);
        if (p == null)
            return null;
        try {
            return (int) Math.round(Double.parseDouble(p.getParameterValue()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
