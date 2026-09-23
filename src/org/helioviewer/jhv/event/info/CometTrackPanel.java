package org.helioviewer.jhv.event.info;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.gui.component.Palette;
import org.helioviewer.jhv.gui.component.RightSidebar;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeListener;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * Browse the near-Sun comets JPL knows about in the current movie range and pick one to lock onto,
 * exactly as {@link CactusTrackPanel} does for CACTus fronts: double-click, or a Track button, and
 * the warp or the crop is animated so the comet holds a fixed screen radius while the corona moves
 * around it.
 *
 * <p>The one difference is where the radius comes from. A CME is a constant-speed extrapolation the
 * catalog hands over in a single row; a comet's plane-of-sky distance from Sun centre falls and
 * then rises, and nothing short of an ephemeris says by how much. So picking a comet costs a
 * Horizons round trip that picking a CME does not, which is why the fetch is a background task with
 * a status line rather than a click that either works instantly or does nothing.
 */
@SuppressWarnings("serial")
public final class CometTrackPanel extends JPanel implements TimeListener.Range {

    private static final String[] COLUMNS = {"Comet", "Closest (R☉)", "When (UTC)", "q (R☉)"};
    private static final double ASSUMED_FIELD_RSUN = 32; // before a layer reports one: about C3

    private static CometTrackPanel instance;
    private static Palette palette;

    public static Palette palette() {
        if (instance == null)
            instance = new CometTrackPanel();
        if (palette == null) {
            palette = new Palette("Track comet", () -> instance, () -> instance.search());
            palette.restoreHome(RightSidebar.getInstance());
        }
        return palette;
    }

    public static void open() {
        palette();
        instance.search();
        palette.open();
    }

    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel status;
    private final List<Comets.Sighting> rows = new ArrayList<>();

    private long searchedStart;
    private long searchedEnd;
    private boolean searching;

    private Comets.Track cachedTrack; // the last comet expanded, so the other mode's button is free
    private String cachedKey;

    private CometTrackPanel() {
        super(new BorderLayout());

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return c == 1 || c == 3 ? Double.class : String.class; // so the distances sort as numbers
            }
        };
        table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    trackSelected(CMETracker.getMode());
            }
        });

        status = new JLabel(" ");
        status.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Ask JPL again for the comets in the loaded movie range");
        refreshButton.addActionListener(e -> {
            searchedStart = searchedEnd = 0;
            search();
        });
        JButton trackWarpButton = new JButton("Track (Warp)");
        trackWarpButton.setToolTipText("Animate the Box-Cox warp (λ) so this comet holds a fixed screen radius: the corona rubber-bands around it");
        trackWarpButton.addActionListener(e -> trackSelected(CMETracker.Mode.WARP));
        JButton trackCropButton = new JButton("Track (Crop)");
        trackCropButton.setToolTipText("Animate the outer radial crop instead, holding λ: the field of view opens and closes to follow the comet");
        trackCropButton.addActionListener(e -> trackSelected(CMETracker.Mode.CROP));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(refreshButton);
        buttons.add(trackWarpButton);
        buttons.add(trackCropButton);

        add(status, BorderLayout.PAGE_START);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons, BorderLayout.PAGE_END);
        fitRows();

        // Follow the movie, but only while on screen. Without this the palette searched once, when
        // it was first shown, and then sat there: docked in the sidebar it is always visible, so
        // loading a different movie never re-triggered it and the list went on describing the
        // previous one. A single stale row over a movie it has nothing to do with is worse than an
        // empty list, because it looks like an answer.
        //
        // Registered from the ancestor listener rather than the constructor, as CactusTrackPanel
        // does: addTimeRangeListener calls straight back, and a panel being built by the toolbar
        // should not be starting network searches before it is anywhere.
        addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                Player.addTimeRangeListener(CometTrackPanel.this);
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent e) {
                Player.removeTimeRangeListener(CometTrackPanel.this);
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
        });
    }

    @Override
    public void timeRangeChanged(long start, long end) {
        if (start == searchedStart && end == searchedEnd)
            return;
        model.setRowCount(0); // drop the previous movie's answer before asking about this one
        rows.clear();
        cachedKey = null;
        cachedTrack = null;
        fitRows();
        search();
    }

    /** Ask JPL for the movie's range, unless that is the range already listed. */
    private void search() {
        if (!Player.isAvailable()) {
            status.setText("Load a movie first: the catalog is searched over its time range.");
            return;
        }
        long start = Player.getStartTime(), end = Player.getEndTime();
        if (searching || (start == searchedStart && end == searchedEnd))
            return;
        double field = ImageLayers.getLargestRadialSize();
        double fieldRsun = field > 1 ? field : ASSUMED_FIELD_RSUN;
        searching = true;
        searchedStart = start;
        searchedEnd = end;
        status.setText("Asking JPL which comets crossed this field…");
        // The progress messages arrive on the worker; hop them to the EDT, where the label lives.
        Task.submitBackground("comet-search",
                () -> Comets.search(start, end, fieldRsun, message -> java.awt.EventQueue.invokeLater(() -> {
                    if (searching)
                        status.setText(message);
                })),
                this::listed,
                (logContext, t) -> {
                    searching = false;
                    searchedStart = searchedEnd = 0; // a failed search is not a searched range
                    status.setText("Comet search failed: " + t.getMessage());
                });
    }

    private void listed(List<Comets.Sighting> sightings) {
        searching = false;
        model.setRowCount(0);
        rows.clear();
        for (Comets.Sighting sighting : sightings) {
            rows.add(sighting);
            model.addRow(new Object[]{sighting.comet().label(), round(sighting.minRadius()),
                    TimeUtils.formatShort(sighting.closest()), round(sighting.comet().perihelionRsun())});
        }
        status.setText(rows.isEmpty()
                ? "No catalogued comet crossed this field. JPL carries the SOHO comets thickly for 1996-2008 and barely at all after, so an uncatalogued sungrazer will not appear here."
                : rows.size() + (rows.size() == 1 ? " comet in the field. Double-click it to track." : " comets in the field. Double-click one to track."));
        fitRows();
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.;
    }

    @Nullable
    private Comets.Comet selected() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : rows.get(table.convertRowIndexToModel(viewRow)).comet();
    }

    private void trackSelected(CMETracker.Mode mode) {
        Comets.Comet comet = selected();
        if (comet == null)
            return;
        if (!Player.isAvailable()) {
            status.setText("Load a coronagraph movie first: there is no movie to jump to.");
            return;
        }
        long start = Player.getStartTime(), end = Player.getEndTime();
        String key = comet.designation() + '@' + start + '-' + end;
        if (key.equals(cachedKey) && cachedTrack != null) {
            engage(comet, cachedTrack, mode);
            return;
        }
        status.setText("Fetching the ephemeris for " + comet.label() + "…");
        Task.submitBackground("comet-ephemeris", () -> Comets.ephemeris(comet, start, end), track -> {
            cachedKey = key;
            cachedTrack = track;
            engage(comet, track, mode);
        }, (logContext, t) -> status.setText("No track for " + comet.label() + ": " + t.getMessage()));
    }

    private void engage(Comets.Comet comet, Comets.Track track, CMETracker.Mode mode) {
        double fov = ImageLayers.getLargestRadialSize();
        CMETracker.stop();                            // as the CME panel: disengage before setTime, whose listeners
        CMETracker.setMode(mode);                     // fire synchronously, and pick the knob before engaging
        Player.setTime(new JHVTime(track.entry(fov > 1 ? fov : 30)));
        ViewState.setProjection(MapMode.Helioradial); // no-op if already there; fits on entry
        CMETracker.track(track::radius, track::positionAngle);
        status.setText(comet.label() + ": closest approach " + Math.round(track.minRadius() * 10) / 10. + " R☉ on the sky" +
                (track.minRadius() > fov && fov > 1 ? ", outside the loaded field of view" : ""));
    }

    /** As {@link CactusTrackPanel#fitRows}: a sidebar GridBag must not squeeze the rows away. */
    void fitRows() {
        int height = Math.max(table.getRowHeight(), table.getPreferredSize().height);
        table.setPreferredScrollableViewportSize(new Dimension(table.getPreferredSize().width, height));
        revalidate();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

}
