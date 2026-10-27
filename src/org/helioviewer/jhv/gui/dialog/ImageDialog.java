package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeSelectionModel;

import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.CadencePanel;
import org.helioviewer.jhv.gui.component.HTMLPane;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.gui.component.VsoSelectorPanel;
import org.helioviewer.jhv.gui.time.TimeSelectorPanel;
import org.helioviewer.jhv.io.DataSources;
import org.helioviewer.jhv.io.DataSourcesParser;
import org.helioviewer.jhv.io.DataSourcesTree;
import org.helioviewer.jhv.io.FitsRequest;
import org.helioviewer.jhv.io.FitsLibrary;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.time.TimeUtils;

import com.jidesoft.dialog.ButtonPanel;
import com.jidesoft.dialog.StandardDialog;

/**
 * New Layer: one window for every source, each a tab, all reading the range and time step at the
 * top of the window. Those copy the master controls beside New Layer each time the window opens,
 * one way: editing them here changes only what this window adds, never the master range.
 *
 * <ul>
 * <li>HV (JPEG 2000): Helioviewer's streamed browse images, the dataset tree.</li>
 * <li>VSO (FITS): calibrated FITS through the Virtual Solar Observatory.</li>
 * <li>Direct (FITS): a mission's own archive, each with its own dialog (seeded from the master
 * range), and LASCO from NRL, which needs nothing more than the range.</li>
 * <li>Local FITS Library: what the HelioFITS Cache Manager has already downloaded to this computer.</li>
 * </ul>
 *
 * <p>Every FITS route asks the FITS library first ({@link FitsLibraryFirst}), so data already on disk
 * is offered before it is downloaded again; Download Anyway keeps the old behaviour. Open Cache Manager
 * starts the HelioFITS Cache Manager itself, to manage that library and download into it.
 */
@SuppressWarnings("serial")
public final class ImageDialog extends StandardDialog implements DataSources.Listener {

    public interface Handler {
        void setDefaultTimeRange(long start, long end);

        void loadDatasets(List<DataSourcesTree.SourceItem> items, long start, long end, int cadence);
    }

    private final Handler handler;
    private final DataSourcesTree sourcesTree;
    private final HTMLPane datasetExtent = new HTMLPane();
    private final JButton availabilityButton = new JButton("Available data");
    private final AbstractAction load = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            loadSelectedDatasets();
        }
    };
    private final JButton actionButton = new JButton(load);
    // This window's own range and time step, copied from the master ones as it opens.
    private final TimeSelectorPanel timeSelector = new TimeSelectorPanel();
    private final CadencePanel cadencePanel = new CadencePanel(timeSelector);
    private final FitsLibraryTab libraryTab = new FitsLibraryTab(this::selectionChanged, this::loadSelectedDatasets,
            this::rangeStart, this::rangeEnd);
    private final VsoSelectorPanel vsoTab = new VsoSelectorPanel(this::rangeStart, this::rangeEnd,
            this::cadence, this::selectionChanged);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel range = new JLabel("Starts as the master range and time step; changes here apply only to the layer added.");
    private final JButton openCacheManager = new JButton("Open Cache Manager");

    public ImageDialog(Handler _handler) {
        super(MainFrame.get(), "New Image Layer", false);
        handler = _handler;
        sourcesTree = new DataSourcesTree(this::loadSelectedDatasets);
        sourcesTree.addTreeSelectionListener(e -> selectionChanged());
        selectionChanged();
        availabilityButton.addActionListener(e -> {
            List<DataSourcesTree.SourceItem> items = sourcesTree.getSelectedItems();
            String url = items.size() == 1 ? getAvailabilityURL(items.getFirst()) : null;
            if (url != null)
                DesktopIntegration.openURL(url);
        });
        openCacheManager.setToolTipText("Start the HelioFITS Cache Manager, to manage the local FITS library and download into it");
        openCacheManager.addActionListener(e -> {
            java.nio.file.Path app = FitsLibrary.app();
            if (app != null)
                DesktopIntegration.openURL(app.toUri().toString());
        });
        DataSources.setListener(this);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setResizable(false);
        setType(Window.Type.UTILITY);
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction close = ComponentUtils.hideAction(this);
        setDefaultCancelAction(close);
        setDefaultAction(load);
        setInitFocusedComponent(sourcesTree);

        JButton cancelButton = new JButton(close);
        cancelButton.setText("Cancel");
        getRootPane().setDefaultButton(actionButton);

        ButtonPanel panel = new ButtonPanel();
        panel.add(openCacheManager, ButtonPanel.OTHER_BUTTON);
        panel.add(availabilityButton, ButtonPanel.OTHER_BUTTON);
        panel.add(cancelButton, ButtonPanel.CANCEL_BUTTON);
        panel.add(actionButton, ButtonPanel.AFFIRMATIVE_BUTTON);
        return panel;
    }

    @Override
    public JComponent createContentPanel() {
        JScrollPane scrollPane = new JScrollPane(sourcesTree);
        scrollPane.setPreferredSize(new Dimension(250, 350));

        JPanel hv = new JPanel(new BorderLayout());
        hv.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        hv.add(scrollPane, BorderLayout.CENTER);
        hv.add(datasetExtent, BorderLayout.SOUTH);

        tabs.addTab("HV (JPEG 2000)", hv);
        tabs.setToolTipTextAt(0, "Helioviewer's browse product: fast, streamed, and 8-bit. Every mission it carries, and the quantization is already baked in.");
        tabs.addTab("VSO (FITS)", vsoTab);
        tabs.setToolTipTextAt(1, "Calibrated FITS through the Virtual Solar Observatory, which federates most missions. Full bit depth, larger files, slower to load.");
        tabs.addTab("Direct (FITS)", buildDirectTab());
        tabs.setToolTipTextAt(2, "Straight from a mission's own archive, for the ones VSO serves badly or not at all.");
        tabs.addTab("Local FITS Library", libraryTab);
        tabs.setToolTipTextAt(3, "FITS the HelioFITS Cache Manager has already downloaded to this computer. Nothing downloads.");
        tabs.addChangeListener(e -> {
            libraryTab.setFront(tabs.getSelectedComponent() == libraryTab);
            selectionChanged();
        });

        range.setFont(org.helioviewer.jhv.gui.UIGlobals.uiFontSmall);
        range.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        timeSelector.addListener((start, end) -> libraryTab.rangeChanged());
        JPanel time = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 8, 2));
        time.add(timeSelector);
        time.add(cadencePanel);
        JPanel top = new JPanel(new BorderLayout());
        top.add(time, BorderLayout.CENTER);
        top.add(range, BorderLayout.PAGE_END);
        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.PAGE_START);
        content.add(tabs, BorderLayout.CENTER);
        return content;
    }

    /** The archives with their own client: a dialog each, or for LASCO nothing past the master range. */
    private JPanel buildDirectTab() {
        JPanel buttons = new JPanel(new java.awt.GridLayout(0, 1, 0, 3));
        Object[][] archives = {
                // Each opens on this window's range (and PUNCH on its time step); ASPIICS goes by orbit.
                {"PUNCH (SDAC)\u2026", (Runnable) () -> PunchDialog.getInstance().showDialog(rangeStart(), rangeEnd(), cadence())},
                {"Solar Orbiter (SOAR)\u2026", (Runnable) () -> SoarDialog.getInstance().showDialog(rangeStart(), rangeEnd())},
                {"Proba-3 ASPIICS\u2026", (Runnable) () -> AspiicsDialog.getInstance().showDialog()},
                {"MLSO KCor and UCoMP (HAO)\u2026", (Runnable) () -> MlsoDialog.getInstance().showDialog(rangeStart(), rangeEnd())},
                // No dialog: the master range and cadence are the whole question, like the VSO
                // tab's Add. NRL because the VSO's LASCO catalog stops in early 2025 while the LZ
                // archive is current; see LascoClient.
                {"LASCO C2 (NRL)", (Runnable) () -> addLasco("C2")},
                {"LASCO C3 (NRL)", (Runnable) () -> addLasco("C3")},
        };
        for (Object[] a : archives) {
            JButton b = new JButton((String) a[0]);
            b.addActionListener(e -> {
                setVisible(false); // the archive's own dialog, or the new layer, takes over from here
                ((Runnable) a[1]).run();
            });
            buttons.add(b);
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        panel.add(buttons, BorderLayout.PAGE_START);
        return panel;
    }

    private void addLasco(String detector) {
        long start = rangeStart(), end = rangeEnd();
        if (FitsLibraryFirst.offer(MainFrame.get(), FitsLibrary.Wanted.of("soho", "lasco", detector, null), start, end)
                != FitsLibraryFirst.Choice.DOWNLOAD)
            return;
        ImageLayer.create(null).load(new FitsRequest(FitsRequest.Archive.LASCO, "lz", detector, "",
                FitsRequest.cadenceMillis(cadence()), start, end));
    }

    private long rangeStart() {
        return timeSelector.getStartTime();
    }

    /** One frame asked for collapses the range to its start, as the master controls do. */
    private long rangeEnd() {
        return cadencePanel.isSingleFrame() ? timeSelector.getStartTime() : timeSelector.getEndTime();
    }

    /** Seconds between frames asked for, 0 for every frame. */
    private int cadence() {
        return cadencePanel.getCadence();
    }

    @Nullable
    @Override
    public JComponent createBannerPanel() {
        return null;
    }

    public void showDialog(boolean changeMode) {
        setTitle(changeMode ? "Change Dataset" : "New Image Layer");
        actionButton.setText(changeMode ? "Change" : "Add");
        sourcesTree.getSelectionModel().setSelectionMode(
                changeMode ? TreeSelectionModel.SINGLE_TREE_SELECTION : TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        // Changing a layer's dataset is a Helioviewer request, so only that tab can do it.
        for (int i = 1; i < tabs.getTabCount(); i++)
            tabs.setEnabledAt(i, !changeMode);
        if (changeMode)
            tabs.setSelectedIndex(0);
        // One way, master to here, and only as the window opens: what is typed here stays here.
        timeSelector.setTime(MoviePanel.getInstance().getStartTime(), MoviePanel.getInstance().getEndTime());
        cadencePanel.copyFrom(MainFrame.getLayersSectionPanel().getCadencePanel());
        openCacheManager.setVisible(FitsLibrary.app() != null);
        libraryTab.update(changeMode);
        selectionChanged();
        if (!isVisible()) {
            pack();
            JPanel layersPanel = MainFrame.getLayersPanel();
            Point location = new Point(layersPanel.getWidth(), 0);
            SwingUtilities.convertPointToScreen(location, layersPanel);
            setLocation(location);
            setVisible(true);
        } else {
            toFront();
        }
    }

    public void selectDataset(String server, int sourceId) {
        sourcesTree.setSelectedItem(server, sourceId);
    }

    private void selectionChanged() {
        List<DataSourcesTree.SourceItem> items = sourcesTree.getSelectedItems();
        DataSourcesTree.SourceItem item = items.size() == 1 ? items.getFirst() : null;
        Object tab = tabs.getSelectedComponent();
        boolean hv = tab == null || tabs.getSelectedIndex() == 0;
        load.setEnabled(tab == libraryTab ? libraryTab.canLoad() : tab == vsoTab ? vsoTab.canAdd() : hv && !items.isEmpty());
        availabilityButton.setEnabled(hv && getAvailabilityURL(item) != null);
        String text = item != null ? "Extent: " + TimeUtils.formatShort(item.start) + " - " + TimeUtils.formatShort(item.end)
                : items.isEmpty() ? "" : items.size() + " datasets selected";
        datasetExtent.setText("<div style='text-align:center'>" + text + "</div>");
    }

    @Nullable
    private static String getAvailabilityURL(@Nullable DataSourcesTree.SourceItem item) {
        if (item == null) return null;

        DataSources.Server server = DataSources.getServer(item.server);
        String availability = server == null ? null : server.availabilityURL();
        return availability == null ? null : availability + "ID=" + item.sourceId;
    }

    @Override
    public void setupSources(DataSourcesParser parser) {
        DataSourcesTree.SourceItem item = sourcesTree.setParsedData(parser);
        if (item != null)
            handler.setDefaultTimeRange(item.end - 2 * TimeUtils.DAY_IN_MILLIS, item.end);
    }

    private void loadSelectedDatasets() {
        if (tabs.getSelectedComponent() == vsoTab) {
            if (vsoTab.addSelected())
                setVisible(false);
            return;
        }
        if (libraryTab.showing()) {
            if (libraryTab.load())
                setVisible(false);
            return;
        }
        List<DataSourcesTree.SourceItem> items = sourcesTree.getSelectedItems();
        if (items.isEmpty())
            return;
        setVisible(false);
        handler.loadDatasets(items, rangeStart(), rangeEnd(), cadence());
    }
}
