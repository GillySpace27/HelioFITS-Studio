package org.helioviewer.jhv.layers.filters;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionListener;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTComboBox;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

public final class LUTPanel implements FilterDetails {

    private final ImageDisplaySettings settings;
    private final LUTComboBox lutCombo;
    private final JToggleButton invertButton;
    private final JPanel buttonPanel = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel("Color ", JLabel.RIGHT);

    /**
     * Anything outside this panel that has to know a colour table changed: the Filters palette, which
     * greys its copy of RHEF for a categorical table just as the Image Layers row does. A list rather
     * than a Layers event, because a layerUpdated here would bring the Image Layers row back through
     * refresh(), and so through this listener, forever. Listeners must not touch a LUT either.
     */
    private static final java.util.List<Runnable> lutListeners = new java.util.ArrayList<>();

    public static void addLutListener(Runnable listener) {
        lutListeners.add(listener);
    }

    // onLutChanged: notified after a LUT/invert change lands on the layer. Whether a categorical
    // LUT is in play can flip here, which gates other controls elsewhere (see
    // ImageLayerRenderingPanel.applyIndexedGating()). The callback must not call back into this
    // panel's refresh()/combo -- that reopens the combo's own listener and loops forever.
    public LUTPanel(ImageLayer layer, Runnable onLutChanged) {
        settings = layer.getDisplaySettings();
        lutCombo = new LUTComboBox();
        invertButton = Buttons.flatToggle(Buttons.invert, settings.getInvertLUT());
        invertButton.setToolTipText("Invert color table");

        JToggleButton colorbarButton = Buttons.flatToggle(Buttons.colorbar, settings.getShowColorbar());
        colorbarButton.setToolTipText("Show the color table legend at the bottom of the view");

        ActionListener listener = e -> {
            LUT lut = lutCombo.getLUT();
            boolean inverted = invertButton.isSelected();
            boolean changed = !lut.equals(settings.getLUT()) || inverted != settings.getInvertLUT();
            if (changed) {
                // Only a real interaction may reach the whole selection. refresh() below drives
                // this same listener to sync the combo when a layer is selected, and fanning that
                // out would stamp the lead layer's colour table onto every other selected layer
                // just for clicking on them: selecting has to be free of side effects.
                if (syncing)
                    settings.setLUT(lut, inverted);
                else
                    Layers.applyToSelected(layer, s -> s.setLUT(lut, inverted));
            }
            // Run even when nothing changed, which is exactly what a sync produces: the gating
            // these drive (a categorical table greys the value-affecting rows) describes the layer
            // now shown, not the edit that was or was not made.
            onLutChanged.run();
            lutListeners.forEach(Runnable::run);
            if (changed)
                DisplayController.display();
        };
        lutCombo.addActionListener(listener);
        invertButton.addActionListener(listener);
        colorbarButton.addActionListener(e -> {
            Layers.applyToSelected(layer, s -> s.setShowColorbar(colorbarButton.isSelected()));
            DisplayController.display();
        });

        buttonPanel.add(colorbarButton, BorderLayout.LINE_START);
        buttonPanel.add(invertButton, BorderLayout.LINE_END);
    }

    // Programmatic sync of the widgets to the layer's own table. Fires the listener above, which
    // is why it is flagged: the resulting change belongs to this one layer, never to the selection.
    private boolean syncing;

    /** Mirror the layer's colour table and inversion into the widgets. */
    public void refresh() {
        syncing = true;
        try {
            invertButton.setSelected(settings.getInvertLUT());
            lutCombo.selectLUT(settings.getLUT());
        } finally {
            syncing = false;
        }
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return lutCombo;
    }

    @Override
    public Component getThird() {
        return buttonPanel;
    }

}
