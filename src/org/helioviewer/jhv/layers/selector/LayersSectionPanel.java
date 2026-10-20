package org.helioviewer.jhv.layers.selector;

import java.awt.BorderLayout;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.CadencePanel;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.timelines.draw.DrawController;

@SuppressWarnings("serial")
public final class LayersSectionPanel extends JPanel {

    private final CadencePanel cadencePanel;
    private final JButton addLayerButton;

    public LayersSectionPanel() {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        // request cadence for the next layer, sourced against the master time range
        cadencePanel = new CadencePanel(MoviePanel.getInstance().getTimeSelectorPanel());

        // One window for every source (HV, VSO, Direct, Heliad), already on the master range and
        // cadence beside it; see ImageDialog. It used to be a dropdown that picked the format first.
        addLayerButton = new JButton("New Layer", Buttons.newLayer);
        addLayerButton.setToolTipText("Add data for the time range above: Helioviewer, VSO, a mission's own archive, or the Heliad library");
        addLayerButton.setName("imageLayersNew"); // tour target
        addLayerButton.addActionListener(e -> MoviePanel.getInstance().showNewLayerSelector());

        JPanel addLayerRow = new JPanel(new BorderLayout());
        addLayerRow.add(addLayerButton, BorderLayout.LINE_START);
        addLayerRow.add(cadencePanel, BorderLayout.CENTER);

        add(addLayerRow);
        add(MainFrame.getLayersPanel());
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
            Message.err("Invalid Date Range", "The end date must be after the start date."); // with Report this...
            return false;
        }
        return true;
    }

    public int getCadence() {
        return cadencePanel.getCadence();
    }

    public void setCadence(int seconds) {
        cadencePanel.setCadence(seconds);
    }

    /** Whether the cadence control is asking for a single frame; MoviePanel's loads honour it. */
    public boolean isSingleFrame() {
        return cadencePanel.isSingleFrame();
    }

    public void setTime(long start, long end) {
        MoviePanel.getInstance().setTime(start, end);
    }

    public long getStartTime() {
        return MoviePanel.getInstance().getStartTime();
    }

    public long getEndTime() {
        return MoviePanel.getInstance().getEndTime();
    }

}
