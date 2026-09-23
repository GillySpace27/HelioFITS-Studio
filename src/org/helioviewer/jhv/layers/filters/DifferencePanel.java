package org.helioviewer.jhv.layers.filters;

import java.awt.Component;
import java.awt.FlowLayout;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

public final class DifferencePanel implements FilterDetails {

    private final JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JPanel buttonPanel = new JPanel(); // the sync button moved to the layer's action icons
    private final JLabel title = new JLabel(" Difference ", JLabel.RIGHT);

    public DifferencePanel(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        ButtonGroup modeGroup = new ButtonGroup();
        for (ImageDisplaySettings.DifferenceMode mode : ImageDisplaySettings.DifferenceMode.values()) {
            JRadioButton item = new JRadioButton(mode.toString());
            if (mode == settings.getDifferenceMode())
                item.setSelected(true);
            item.addActionListener(e -> {
                Layers.applyToSelected(layer, s -> s.setDifferenceMode(mode));
                DisplayController.display();
            });
            modeGroup.add(item);
            modePanel.add(item);
        }

    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return modePanel;
    }

    @Override
    public Component getThird() {
        return buttonPanel;
    }

}
