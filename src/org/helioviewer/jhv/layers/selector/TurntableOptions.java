package org.helioviewer.jhv.layers.selector;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.DoubleConsumer;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.Turntable;

@SuppressWarnings("serial")
final class TurntableOptions extends JPanel {

    // Turntable's own clock only holds while nothing else claims it, so with imagery loaded the
    // data evolves as the camera turns. That is stated in the panel rather than left in a source
    // comment, because from the outside it looks like the frames-per-revolution setting misfiring.
    private final JLabel clockNote = new JLabel("<html>With an image layer loaded the camera turns over that movie's own frames, "
            + "so the data evolves as it turns instead of standing still.");

    private final Turntable turntable;

    TurntableOptions(Turntable _turntable) {
        turntable = _turntable;
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(1, 2, 1, 2);
        c.weightx = 1;

        JHVSpinner perRev = spinner(turntable.getFramesPerRev(), 2, 3600, 10, v -> turntable.setFramesPerRev((int) v));
        JHVSpinner rate = spinner(turntable.getDegPerSec(), -360, 360, 1, turntable::setDegPerSec);

        // Own clock supplies its own frames, movie clock rides the player, so exactly one of the
        // two numbers is live at a time. Greying the other keeps that obvious.
        JComboBox<Turntable.Driver> driver = new JComboBox<>(Turntable.Driver.values());
        driver.setSelectedItem(turntable.getDriver());
        driver.setToolTipText("Own clock: the camera generates the frames and the data is held still. Movie clock: the camera turns as the movie plays.");
        driver.addActionListener(e -> {
            Turntable.Driver picked = (Turntable.Driver) driver.getSelectedItem();
            if (picked == null)
                return;
            turntable.setDriver(picked);
            perRev.setEnabled(picked == Turntable.Driver.TURNTABLE);
            rate.setEnabled(picked == Turntable.Driver.PLAYBACK);
            refresh();
        });
        perRev.setEnabled(turntable.getDriver() == Turntable.Driver.TURNTABLE);
        rate.setEnabled(turntable.getDriver() == Turntable.Driver.PLAYBACK);

        c.gridy = 0;
        c.gridx = 0;
        add(new JLabel("Frames", JLabel.RIGHT), c);
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

        JHVSpinner lon = spinner(turntable.getAxisLon(), -360, 360, 1, turntable::setAxisLon);
        JHVSpinner lat = spinner(turntable.getAxisLat(), -90, 90, 1, turntable::setAxisLat);

        // Presets, so the axes worth having are one click rather than remembered numbers. The
        // point-cloud version also offered "CME axis", which read the arrow's own lon/lat; that
        // one stayed behind with the plugin, since core has nothing to read it from.
        JComboBox<String> preset = new JComboBox<>(new String[]{"Axis…", "Solar north", "Sub-Earth"});
        preset.setToolTipText("Fill the axis from a known direction");
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

        c.gridy = 4;
        c.gridx = 0;
        c.gridwidth = 4;
        add(clockNote, c);

        refresh();
    }

    /** Only the own-clock driver claims the master clock, so only it can lose the claim. */
    void refresh() {
        clockNote.setVisible(turntable.getDriver() == Turntable.Driver.TURNTABLE && Layers.getActiveImageLayer() != null);
    }

    private static JHVSpinner spinner(double value, double min, double max, double step, DoubleConsumer setter) {
        JHVSpinner s = new JHVSpinner(value, min, max, step);
        s.addChangeListener(e -> setter.accept((Double) s.getValue()));
        return s;
    }

}
