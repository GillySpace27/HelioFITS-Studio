package org.helioviewer.jhv.layers.filters;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionListener;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTComboBox;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

import com.jidesoft.swing.JideToggleButton;

public class LUTPanel implements FilterDetails {

    private final LUTComboBox lutCombo;
    private final JPanel buttonPanel = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel("Color ", JLabel.RIGHT);

    // onLutChanged: notified after a LUT/invert change lands on the layer. Whether a categorical
    // LUT is in play can flip here, which gates other controls elsewhere (see
    // ImageLayerRenderingPanel.applyIndexedGating()). The callback must not call back into this
    // panel's setLUT()/combo -- that reopens the combo's own listener and loops forever.
    public LUTPanel(ImageLayer layer, Runnable onLutChanged) {
        lutCombo = new LUTComboBox();
        JideToggleButton invertButton = new JideToggleButton(Buttons.invert, layer.getGLImage().getInvertLUT());
        invertButton.setToolTipText("Invert color table");

        JideToggleButton colorbarButton = new JideToggleButton(Buttons.colorbar, layer.getGLImage().getShowColorbar());
        colorbarButton.setToolTipText("Show the color table legend at the bottom of the view");

        ActionListener listener = e -> {
            // Only a real interaction may reach the whole selection. setLUT() below drives this
            // same listener to sync the combo when a layer is selected, and fanning that out
            // would stamp the lead layer's colour table onto every other selected layer just
            // for clicking on them: selecting has to be free of side effects.
            if (syncing)
                layer.getGLImage().setLUT(lutCombo.getLUT(), invertButton.isSelected());
            else
                Layers.applyToSelected(layer, gl -> gl.setLUT(lutCombo.getLUT(), invertButton.isSelected()));
            onLutChanged.run();
            DisplayController.display();
        };
        lutCombo.addActionListener(listener);
        invertButton.addActionListener(listener);
        colorbarButton.addActionListener(e -> {
            Layers.applyToSelected(layer, gl -> gl.setShowColorbar(colorbarButton.isSelected()));
            DisplayController.display();
        });

        buttonPanel.add(colorbarButton, BorderLayout.LINE_START);
        buttonPanel.add(invertButton, BorderLayout.LINE_END);
    }

    // Programmatic sync of the combo to a layer's own table. Fires the listener above, which is
    // why it is flagged: the resulting change belongs to this one layer, never to the selection.
    private boolean syncing;

    public void setLUT(LUT lut) {
        syncing = true;
        try {
            lutCombo.setLUT(lut);
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
