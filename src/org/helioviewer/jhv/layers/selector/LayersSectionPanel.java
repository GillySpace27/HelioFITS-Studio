package org.helioviewer.jhv.layers.selector;

import java.awt.BorderLayout;
import java.awt.EventQueue;

import javax.swing.BoxLayout;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.CadencePanel;
import org.helioviewer.jhv.gui.component.ImageSelectorPanel;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.timelines.draw.DrawController;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideSplitButton;

@SuppressWarnings("serial")
public final class LayersSectionPanel extends JPanel implements Interfaces.ObservationSelector {

    private final CadencePanel cadencePanel;
    private final ImageSelectorPanel imageSelectorPanel;
    private final JideSplitButton addLayerButton;

    public LayersSectionPanel() {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        // request cadence for the next layer, sourced against the master time range
        cadencePanel = new CadencePanel(MoviePanel.getInstance().getTimeSelectorPanel());
        imageSelectorPanel = new ImageSelectorPanel(this);

        addLayerButton = new JideSplitButton(Buttons.newLayer);
        addLayerButton.setAlwaysDropdown(true);
        addLayerButton.add(buildSourcePanel());
        addLayerButton.getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                EventQueue.invokeLater(() -> imageSelectorPanel.getFocused().grabFocus());
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        JPanel addLayerRow = new JPanel(new BorderLayout());
        addLayerRow.add(addLayerButton, BorderLayout.LINE_START);
        addLayerRow.add(cadencePanel, BorderLayout.CENTER);

        add(addLayerRow);
        add(MainFrame.getLayersPanel());
    }

    /**
     * The New Layer dropdown, with the format chosen first.
     *
     * <p>Format is the top-level choice rather than a detail inside each dialog because it decides
     * how much of the measurement you get, not merely where the bytes come from: a Helioviewer JP2
     * is an 8-bit browse product byte-scaled at ingest, while the native FITS behind it is 16-bit.
     * Same mission, same frame, two very different things to film.
     */
    private JPanel buildSourcePanel() {
        JPanel cards = new JPanel(new java.awt.CardLayout());
        cards.add(imageSelectorPanel, "JP2");
        cards.add(new org.helioviewer.jhv.gui.component.VsoSelectorPanel(this::getStartTime, this::getEndTime), "VSO");
        cards.add(buildNativePanel(), "NATIVE");

        JPanel chooser = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 2));
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        String[][] formats = {
                {"JP2", "JPEG 2000", "Helioviewer's browse product: fast, streamed, and 8-bit. Every mission it carries, and the quantization is already baked in."},
                {"VSO", "FITS (VSO)", "Calibrated FITS through the Virtual Solar Observatory, which federates most missions. Full bit depth, larger files, slower to load."},
                {"NATIVE", "FITS (native)", "Straight from a mission's own archive, for the ones VSO serves badly or not at all."},
        };
        for (String[] f : formats) {
            javax.swing.JToggleButton b = new javax.swing.JToggleButton(f[1]);
            b.setFont(org.helioviewer.jhv.gui.UIGlobals.uiFontSmall);
            b.setToolTipText(f[2]);
            b.setSelected("JP2".equals(f[0]));
            b.addActionListener(e -> ((java.awt.CardLayout) cards.getLayout()).show(cards, f[0]));
            group.add(b);
            chooser.add(b);
        }

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(chooser, BorderLayout.PAGE_START);
        panel.add(cards, BorderLayout.CENTER);
        return panel;
    }

    /** The archives with their own client, each keeping its own dialog rather than being flattened. */
    private void addLascoLayer(String detector) {
        long start = getStartTime();
        long end = getEndTime();
        org.helioviewer.jhv.layers.ImageLayer.create(null).load(new org.helioviewer.jhv.io.FitsRequest(
                org.helioviewer.jhv.io.FitsRequest.Archive.LASCO, "lz", detector, "",
                1000L * org.helioviewer.jhv.time.TimeUtils.defaultCadence(start, end), start, end));
    }

    private JPanel buildNativePanel() {
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 0, 3));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
        Object[][] archives = {
                {"PUNCH (SDAC)\u2026", (Runnable) () -> org.helioviewer.jhv.gui.dialog.PunchDialog.getInstance().showDialog()},
                {"Solar Orbiter (SOAR)\u2026", (Runnable) () -> org.helioviewer.jhv.gui.dialog.SoarDialog.getInstance().showDialog()},
                {"Proba-3 ASPIICS\u2026", (Runnable) () -> org.helioviewer.jhv.gui.dialog.AspiicsDialog.getInstance().showDialog()},
                // No dialog: the master range and its default cadence are the whole question, like
                // the VSO tree's Add button. NRL because the VSO's LASCO catalog stops in early
                // 2025 while the LZ archive is current; see LascoClient.
                {"LASCO C2 (NRL)", (Runnable) () -> addLascoLayer("C2")},
                {"LASCO C3 (NRL)", (Runnable) () -> addLascoLayer("C3")},
        };
        for (Object[] a : archives) {
            javax.swing.JButton b = new javax.swing.JButton((String) a[0]);
            b.addActionListener(e -> ((Runnable) a[1]).run());
            panel.add(b);
        }
        return panel;
    }

    // The Sync button lives next to the time range (in ImageLayersPane) and calls this.
    public void syncLayers() {
        syncLayersSpan(getStartTime(), getEndTime());
    }

    // Entry point for external callers that need to move the master range to a given
    // span and resync all layers to it (e.g. a layer's own "sync" button, or the
    // timeline widget snapping the movie to its locked selection).
    public void syncLayersSpan(long start, long end) {
        setTime(start, end);
        if (checkSanity()) {
            DrawController.setSelectedInterval(getStartTime(), getEndTime());
            ImageLayers.syncLayersSpan(getStartTime(), getEndTime(), getCadence());
        }
    }

    private boolean checkSanity() {
        long start = getStartTime();
        long end = getEndTime();
        if (start > end) {
            setTime(end, end);
            JOptionPane.showMessageDialog(null, "End date is before start date", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    @Override
    public int getCadence() {
        return cadencePanel.getCadence();
    }

    @Override
    public void setTime(long start, long end) {
        MoviePanel.getInstance().setTime(start, end);
    }

    @Override
    public long getStartTime() {
        return MoviePanel.getInstance().getStartTime();
    }

    @Override
    public long getEndTime() {
        return MoviePanel.getInstance().getEndTime();
    }

    @Override
    public void load(String server, int sourceId) {
        addLayerButton.doClickOnMenu();
        if (checkSanity()) {
            imageSelectorPanel.load(null, server, sourceId, getStartTime(), getEndTime(), getCadence());
        }
    }

    @Override
    public void setAvailabilityEnabled(boolean enable) {}
}
