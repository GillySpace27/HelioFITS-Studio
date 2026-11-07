package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.layers.StarLayer;
import org.helioviewer.jhv.layers.stars.StarCatalog;

/** The Stars row's settings: magnitude limit, colour and saturation, size, planets, and where the catalogue came from. */
@SuppressWarnings("serial")
final class StarLayerOptions extends JPanel {

    private int row;

    StarLayerOptions(StarLayer layer) {
        super(new GridBagLayout());

        JHVSlider mag = new JHVSlider((int) StarLayer.MIN_MAG, (int) StarCatalog.MAX_MAGNITUDE, (int) Math.round(layer.getMagLimit()));
        mag.setToolTipText("Faintest star drawn, as a V magnitude. About 6 is what the eye sees on a dark night; 9 is the deepest the catalogue goes here.");
        mag.addChangeListener(e -> layer.setMagLimit(mag.getValue()));
        addRow("Faintest", mag);

        JCheckBox colour = new JCheckBox("Star colours", layer.isColour());
        colour.setToolTipText("Tint each star by its B-V colour index, hot stars blue-white and cool ones orange. A display aid, not a measured colour.");
        JHVSlider saturation = new JHVSlider(0, (int) (100 * StarLayer.MAX_SATURATION), (int) Math.round(100 * layer.getSaturation()));
        saturation.setToolTipText("0 is white, 100 the blackbody colour, above 100 exaggerated.");
        saturation.setEnabled(layer.isColour());
        colour.addActionListener(e -> {
            layer.setColour(colour.isSelected());
            saturation.setEnabled(colour.isSelected());
        });
        saturation.addChangeListener(e -> layer.setSaturation(saturation.getValue() / 100.));
        addRow(null, colour);
        addRow("Saturation", saturation);

        JHVSlider size = new JHVSlider((int) (100 * StarLayer.MIN_SIZE), (int) (100 * StarLayer.MAX_SIZE), (int) Math.round(100 * layer.getSizeScale()));
        size.setToolTipText("Point size, in percent of the default.");
        size.addChangeListener(e -> layer.setSizeScale(size.getValue() / 100.));
        addRow("Size", size);

        JCheckBox planets = new JCheckBox("Show planets", layer.isPlanets());
        planets.setToolTipText("Mercury to Neptune, except the one the view is from, at their true directions from the observer at the frame time.");
        JCheckBox moon = new JCheckBox("Show the Moon", layer.isMoon());
        moon.setEnabled(layer.isPlanets());
        planets.addActionListener(e -> {
            layer.setPlanets(planets.isSelected());
            moon.setEnabled(planets.isSelected());
        });
        moon.addActionListener(e -> layer.setMoon(moon.isSelected()));
        addRow(null, planets);
        addRow(null, moon);

        JCheckBox onTop = new JCheckBox("Draw over the imagery", layer.isOnTop());
        onTop.setToolTipText("Off: a background the imagery covers. On: markers over the data, to tell which specks in a coronagraph frame are stars or planets.");
        onTop.addActionListener(e -> layer.setOnTop(onTop.isSelected()));
        addRow(null, onTop);

        JLabel status = new JLabel(layer.getStatus());
        layer.setStatusListener(s -> EventQueue.invokeLater(() -> status.setText(s)));
        addRow(null, status);
        JLabel credit = new JLabel("<html><small>" + StarCatalog.CREDIT + "</small></html>");
        credit.setToolTipText(StarCatalog.LICENCE_URL);
        addRow(null, credit);
    }

    private void addRow(String label, Component comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row++;
        c.insets = new Insets(1, 2, 1, 2);
        c.weightx = 0;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        if (label != null)
            add(new JLabel(label, JLabel.RIGHT), c);
        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(comp, c);
    }

}
