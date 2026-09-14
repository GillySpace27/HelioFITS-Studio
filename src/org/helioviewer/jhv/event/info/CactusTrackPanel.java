package org.helioviewer.jhv.event.info;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
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
import org.helioviewer.jhv.gui.component.RightSidebar;
import org.helioviewer.jhv.gui.component.Palette;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * Browse the CACTus CME events in the current movie range and pick one to lock onto: double-click
 * (or a Track button) jumps the playhead to the event onset, switches to Helioradial and engages
 * CMETracker, so the front holds a fixed screen radius.
 *
 * <p>A palette rather than the dialog it was. Picking a CME is not a question with one answer you
 * submit and are done with: you try one, watch the corona rubber-band around the front, and want
 * the next one, or the other mode, or the same one again after nudging the view. A dialog made
 * every one of those a reopen. This is the same reason Annotation stopped being a menu, and the
 * same reason the projection controls were a palette to begin with.
 *
 * <p>Kept in event.info (core) so the menu action need not import the SWEK plugin; the SWEK
 * panel's Track button calls in from the plugin side.
 */
@SuppressWarnings("serial")
public final class CactusTrackPanel extends JPanel implements JHVEventListener.Handle, JHVEventListener.Highlight {

    private static final String[] COLUMNS = {"Onset (UTC)", "Speed km/s", "Width°", "PA°", "Source"};

    private static CactusTrackPanel instance;
    private static Palette palette;

    /**
     * The palette, built on first call and docked where it lives (the right sidebar unless the user
     * moved it). The toolbar calls this while it is being built, which is what puts the panel on
     * screen at launch; before, it only existed once the menu or the SWEK row had opened it.
     */
    public static Palette palette() {
        if (instance == null)
            instance = new CactusTrackPanel();
        if (palette == null) {
            palette = new Palette("Track CME", () -> instance, () -> {
                instance.ensureCactusLoaded();
                instance.reload();
            });
            palette.restoreHome(RightSidebar.getInstance());
        }
        return palette;
    }

    /** Show the palette wherever it lives and refresh it from the current event cache. */
    public static void open() {
        palette();
        instance.ensureCactusLoaded(); // pull CACTus events for the movie range if not already active
        instance.reload();
        palette.open();
    }

    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel status;
    private final List<JHVRelatedEvents> rows = new ArrayList<>(); // aligned with model rows
    private boolean syncingSelection; // guard against the table<->canvas highlight feedback loop

    private CactusTrackPanel() {
        super(new BorderLayout());

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
        JButton trackCropButton = new JButton("Track (Crop)");
        trackCropButton.setToolTipText("Jump to this CME's onset and animate the outer radial crop instead, holding λ: the field of view widens to follow the front, like a zoom-out");
        trackCropButton.addActionListener(e -> trackSelected(CMETracker.Mode.CROP));
        JButton detailsButton = new JButton("Details…");
        detailsButton.addActionListener(e -> detailsSelected());
        // No Close button: the palette's own header closes it, and in a sidebar there is nothing
        // to close. A dialog needed one; a panel that lives somewhere does not.
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(detailsButton);
        buttons.add(trackWarpButton);
        buttons.add(trackCropButton);

        add(status, BorderLayout.PAGE_START);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons, BorderLayout.PAGE_END);
        // No fixed size: that was the dialog's, 540 x 360, and in a sidebar it hid every row past the
        // fourteenth behind a scrollbar. The height is the rows'; see fitRows.
        fitRows();

        // Listen only while on screen. This used to hang off setVisible, which a dialog gets told
        // about and a panel in a folded section does not; an ancestor listener sees both being
        // added to a window and the section around it being folded away.
        addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                JHVEventCache.registerHandler(CactusTrackPanel.this);
                JHVEventCache.addHighlightListener(CactusTrackPanel.this);
                // Here as well as on show: docked at launch, the palette is shown while the toolbar is
                // built, before the SWEK plugin has loaded the catalog, so that request finds no CACTus
                // and quietly does nothing. The window going on screen comes after the plugins.
                ensureCactusLoaded();
                reload();
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent e) {
                JHVEventCache.unregisterHandler(CactusTrackPanel.this);
                JHVEventCache.removeHighlightListener(CactusTrackPanel.this);
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
        });
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
        fitRows();
    }

    /**
     * Ask for exactly the height of every row, so a sidebar shows the whole list.
     *
     * <p>A JTable asks its scroll pane for 450 x 400 whatever it holds, and a scroll pane's own minimum
     * is about one header tall. The sidebar lays sections out in a GridBag, which drops every child to
     * its minimum as soon as it cannot give them all their preferred size, so the first launch that
     * docked this palette showed "23 CACTus event(s)" over a header with no rows under it and nothing
     * to double-click. Same fault, same container, as the layer lists before it. A popped-out window
     * smaller than the list still scrolls: the scroll pane stays, it just no longer decides the height.
     */
    void fitRows() {
        int height = Math.max(table.getRowHeight(), table.getPreferredSize().height); // one row's room when empty
        table.setPreferredScrollableViewportSize(new Dimension(table.getPreferredSize().width, height));
        revalidate();
    }

    /** Height only, as LayersPanel: never less than every row, and width left for SqueezeView to squeeze. */
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
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
        ViewState.setProjection(MapMode.Helioradial); // no-op if already there; fits on entry
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
