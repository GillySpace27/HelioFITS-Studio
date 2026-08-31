package org.helioviewer.jhv.layers.selector;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.DoubleConsumer;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.layers.ObserverLayer;

@SuppressWarnings("serial")
final class ObserverLayerOptions extends JPanel {

    ObserverLayerOptions(ObserverLayer layer) {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(1, 2, 1, 2);
        c.weightx = 1;

        JHVSpinner perRev = spinner(layer.getFramesPerRev(), 2, 3600, 10, v -> layer.setFramesPerRev((int) v));
        JHVSpinner rate = spinner(layer.getDegPerSec(), -360, 360, 1, layer::setDegPerSec);

        // Turntable supplies its own frames, playback rides the movie clock, so exactly one of the
        // two numbers is live at a time. Greying the other keeps that obvious.
        JComboBox<ObserverLayer.Driver> driver = new JComboBox<>(ObserverLayer.Driver.values());
        driver.setSelectedItem(layer.getDriver());
        driver.setToolTipText("Turntable: the orbit generates the frames, data held still. Playback: the orbit rides the movie clock.");
        driver.addActionListener(e -> {
            ObserverLayer.Driver picked = (ObserverLayer.Driver) driver.getSelectedItem();
            if (picked == null)
                return;
            layer.setDriver(picked);
            perRev.setEnabled(picked == ObserverLayer.Driver.TURNTABLE);
            rate.setEnabled(picked == ObserverLayer.Driver.PLAYBACK);
        });
        perRev.setEnabled(layer.getDriver() == ObserverLayer.Driver.TURNTABLE);
        rate.setEnabled(layer.getDriver() == ObserverLayer.Driver.PLAYBACK);

        c.gridy = 0;
        c.gridx = 0;
        add(new JLabel("Orbit", JLabel.RIGHT), c);
        c.gridx = 1;
        c.gridwidth = 3;
        add(driver, c);
        c.gridwidth = 1;

        c.gridy = 1;
        c.gridx = 0;
        add(new JLabel("Frames/rev", JLabel.RIGHT), c);
        c.gridx = 1;
        add(perRev, c);
        c.gridx = 2;
        add(new JLabel("°/s", JLabel.RIGHT), c);
        c.gridx = 3;
        add(rate, c);

        JHVSpinner lon = spinner(layer.getAxisLon(), -360, 360, 1, layer::setAxisLon);
        JHVSpinner lat = spinner(layer.getAxisLat(), -90, 90, 1, layer::setAxisLat);

        // Presets, so the axes worth having are one click rather than remembered numbers. The
        // point-cloud version also offered "CME axis", which read the arrow's own lon/lat; that
        // one stayed behind with the plugin, since core has nothing to read it from.
        JComboBox<String> preset = new JComboBox<>(new String[]{"Axis…", "Solar north", "Sub-Earth"});
        preset.setToolTipText("Fill the orbit axis from a known direction");
        preset.addActionListener(e -> {
            switch (String.valueOf(preset.getSelectedItem())) {
                case "Solar north" -> {
                    lon.setValue(0.);
                    lat.setValue(90.);
                }
                case "Sub-Earth" -> {
                    lon.setValue(0.);
                    lat.setValue(0.);
                }
                default -> {
                }
            }
            preset.setSelectedIndex(0); // a menu of actions, not a persistent selection
        });

        c.gridy = 2;
        c.gridx = 0;
        add(new JLabel("Axis lon", JLabel.RIGHT), c);
        c.gridx = 1;
        add(lon, c);
        c.gridx = 2;
        add(new JLabel("lat", JLabel.RIGHT), c);
        c.gridx = 3;
        add(lat, c);

        c.gridy = 3;
        c.gridx = 1;
        c.gridwidth = 3;
        add(preset, c);
    }

    private static JHVSpinner spinner(double value, double min, double max, double step, DoubleConsumer setter) {
        JHVSpinner s = new JHVSpinner(value, min, max, step);
        s.addChangeListener(e -> setter.accept((Double) s.getValue()));
        return s;
    }

}
