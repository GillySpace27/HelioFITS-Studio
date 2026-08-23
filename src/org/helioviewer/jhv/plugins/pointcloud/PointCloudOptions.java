package org.helioviewer.jhv.plugins.pointcloud;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.thread.Task;

import com.jidesoft.swing.JideButton;

@SuppressWarnings("serial")
class PointCloudOptions extends JPanel {

    private static final int ALPHA_STEPS = 1000; // fine slider resolution for the crowded high end
    private static final long DAY_MILLI = 86_400_000L;

    private final JLabel alphaLabel = new JLabel();

    PointCloudOptions(PointCloudLayer layer) {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;

        JCheckBox points = new JCheckBox("Points", layer.getShowPoints());
        points.addActionListener(e -> layer.setShowPoints(points.isSelected()));
        add(points, c);

        c.gridx = 1;
        add(labeled("Size", makeSpinner(layer.getPointSize(), 0.005, 0.1, 0.005, layer::setPointSize)), c);

        JCheckBox byValue = new JCheckBox("Color by data", layer.getColorByValue());
        byValue.addActionListener(e -> layer.setColorByValue(byValue.isSelected()));
        c.gridx = 2;
        add(byValue, c);

        // Sync the movie interval to this cloud's time span, so context imagery can be loaded over
        // the same range — mirrors the image layers' sync button (DifferencePanel).
        JideButton sync = new JideButton(Buttons.sync);
        sync.setToolTipText("Set the movie time interval to this point cloud's time span");
        sync.addActionListener(e -> {
            if (layer.hasClouds())
                MainFrame.getLayersSectionPanel().syncLayersSpan(layer.getStartTime(), layer.getEndTime());
        });
        c.gridx = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        add(sync, c);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        // Alpha row: the slider spans the full width of the panel. It runs in log(circumradius)
        // (see PointCloudLayer.sliderFracToAlphaPct) so the high end, where the fabric closes up
        // over orders of magnitude of radius, gets most of the travel. Double-click resets to the
        // default (just below the convex hull, where the ripples resolve).
        JSlider alpha = new JSlider(0, ALPHA_STEPS, alphaToSlider(layer, layer.getAlphaPct()));
        alpha.setToolTipText("Alpha-shape threshold on a log-radius scale: right = convex hull, fine control near the top. Double-click to reset.");
        alpha.addChangeListener(e -> {
            layer.setAlphaPct(layer.sliderFracToAlphaPct(alpha.getValue() / (double) ALPHA_STEPS));
            refreshAlpha(layer);
        });
        alpha.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    alpha.setValue(alphaToSlider(layer, PointCloudLayer.DEFAULT_ALPHA_PCT));
            }
        });
        JPanel alphaRow = new JPanel(new BorderLayout(4, 0));
        alphaRow.add(new JLabel("Alpha"), BorderLayout.WEST);
        alphaRow.add(alpha, BorderLayout.CENTER);
        alphaRow.add(alphaLabel, BorderLayout.EAST);
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = GridBagConstraints.REMAINDER;
        add(alphaRow, c);
        c.gridwidth = 1;

        JCheckBox wire = new JCheckBox("Wireframe", layer.getShowWire());
        wire.addActionListener(e -> layer.setShowWire(wire.isSelected()));
        c.gridy = 2;
        c.gridx = 0;
        add(wire, c);

        JCheckBox surface = new JCheckBox("Surface", layer.getShowSurface());
        surface.addActionListener(e -> layer.setShowSurface(surface.isSelected()));
        c.gridx = 1;
        add(surface, c);

        c.gridx = 2;
        add(labeled("Opacity", makeSpinner(layer.getOpacity(), 0, 1, 0.05, layer::setOpacity)), c);

        JComboBox<String> lut = new JComboBox<>(LUT.names());
        lut.setSelectedItem(layer.getLut());
        lut.addActionListener(e -> layer.setLut((String) lut.getSelectedItem()));
        c.gridy = 3;
        c.gridx = 0;
        add(new JLabel("Colormap"), c);
        c.gridx = 1;
        c.gridwidth = GridBagConstraints.REMAINDER;
        add(lut, c);
        c.gridwidth = 1;

        // Heliographic direction marker. Its own on/off, so it can be parked without losing the
        // angles you dialled in, and its own colour so it stays readable over any colormap.
        // Half-angle 0 draws an arrow; above 0 it becomes an ice-cream-cone CME model.
        JCheckBox arrow = new JCheckBox("Arrow", layer.getShowArrow());
        arrow.setToolTipText("Marker from Sun centre along a Stonyhurst longitude/latitude. Half-angle > 0 makes it a cone.");
        arrow.addActionListener(e -> layer.setShowArrow(arrow.isSelected()));
        c.gridy = 4;
        c.gridx = 0;
        add(arrow, c);

        JHVSpinner lon = makeSpinner(layer.getArrowLon(), -360, 360, 1, layer::setArrowLon);
        c.gridx = 1;
        add(labeled("Lon", lon), c);

        JHVSpinner lat = makeSpinner(layer.getArrowLat(), -90, 90, 1, layer::setArrowLat);
        c.gridx = 2;
        add(labeled("Lat", lat), c);

        JHVSpinner length = makeSpinner(layer.getArrowLength(), 1, 200, 1, layer::setArrowLength);
        JHVSpinner halfAngle = makeSpinner(layer.getArrowHalfAngle(), 0, 89, 1, layer::setArrowHalfAngle);

        JideButton donki = new JideButton("DONKI");
        donki.setToolTipText("Load a CME cone fit (lon/lat/half-angle) from CCMC DONKI for this cloud's date");
        donki.addActionListener(e -> pickDonkiFit(layer, arrow, lon, lat, halfAngle, length));
        c.gridx = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        add(donki, c);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 5;
        c.gridx = 0;
        add(labeled("Length", length), c);

        c.gridx = 1;
        add(labeled("Width", makeSpinner(layer.getArrowWidth(), 0.5, 20, 0.5, layer::setArrowWidth)), c);

        c.gridx = 2;
        add(labeled("Half-angle", halfAngle), c);

        JComboBox<Colors.NamedColor> arrowColor = new JComboBox<>(Colors.NamedColor.values());
        arrowColor.setSelectedItem(layer.getArrowColor());
        arrowColor.addActionListener(e -> {
            Colors.NamedColor picked = (Colors.NamedColor) arrowColor.getSelectedItem();
            if (picked != null)
                layer.setArrowColor(picked);
        });
        c.gridx = 3;
        add(arrowColor, c);

        addOrbitRows(c, layer, lon, lat);

        refreshAlpha(layer);
    }

    // Orbit mode. Lives on this panel because that is where the axis presets have something to
    // point at, but it drives the camera globally and works with no cloud loaded.
    private void addOrbitRows(GridBagConstraints c, PointCloudLayer layer, JHVSpinner arrowLon, JHVSpinner arrowLat) {
        OrbitMode orbit = OrbitMode.get();

        JCheckBox on = new JCheckBox("Orbit", orbit.getEnabled());
        on.setToolTipText("Rotate the camera about a heliographic axis; movie export follows automatically");
        on.addActionListener(e -> orbit.setEnabled(on.isSelected()));
        c.gridy = 6;
        c.gridx = 0;
        add(on, c);

        JHVSpinner perRev = makeSpinner(orbit.getFramesPerRev(), 2, 3600, 10, v -> orbit.setFramesPerRev((int) v));
        JHVSpinner rate = makeSpinner(orbit.getDegPerSec(), -360, 360, 1, orbit::setDegPerSec);

        // Turntable supplies its own frames, playback rides the movie clock, so exactly one of
        // the two numbers is live at a time. Greying the other keeps that obvious.
        JComboBox<OrbitMode.Driver> driver = new JComboBox<>(OrbitMode.Driver.values());
        driver.setSelectedItem(orbit.getDriver());
        driver.setToolTipText("Turntable: the orbit generates the frames, data held still. Playback: the orbit rides the movie clock.");
        driver.addActionListener(e -> {
            OrbitMode.Driver picked = (OrbitMode.Driver) driver.getSelectedItem();
            if (picked == null)
                return;
            orbit.setDriver(picked);
            perRev.setEnabled(picked == OrbitMode.Driver.TURNTABLE);
            rate.setEnabled(picked == OrbitMode.Driver.PLAYBACK);
        });
        perRev.setEnabled(orbit.getDriver() == OrbitMode.Driver.TURNTABLE);
        rate.setEnabled(orbit.getDriver() == OrbitMode.Driver.PLAYBACK);
        c.gridx = 1;
        c.gridwidth = 2;
        add(driver, c);
        c.gridwidth = 1;

        JHVSpinner orbitLon = makeSpinner(orbit.getAxisLon(), -360, 360, 1, orbit::setAxisLon);
        JHVSpinner orbitLat = makeSpinner(orbit.getAxisLat(), -90, 90, 1, orbit::setAxisLat);

        // Presets, so the two axes worth having are one click rather than remembered numbers.
        // "CME axis" reads the arrow's own lon/lat, which is whatever DONKI or Yara's GCS put
        // there, so the camera can circle the propagation direction itself.
        JComboBox<String> preset = new JComboBox<>(new String[]{"Axis…", "Solar north", "CME axis", "Sub-Earth"});
        preset.setToolTipText("Fill the orbit axis from a known direction");
        preset.addActionListener(e -> {
            switch (String.valueOf(preset.getSelectedItem())) {
                case "Solar north" -> {
                    orbitLon.setValue(0.);
                    orbitLat.setValue(90.);
                }
                case "CME axis" -> {
                    orbitLon.setValue(((Number) arrowLon.getValue()).doubleValue());
                    orbitLat.setValue(((Number) arrowLat.getValue()).doubleValue());
                }
                case "Sub-Earth" -> {
                    orbitLon.setValue(0.);
                    orbitLat.setValue(0.);
                }
                default -> {
                }
            }
            preset.setSelectedIndex(0); // a menu of actions, not a persistent selection
        });
        c.gridx = 3;
        add(preset, c);

        c.gridy = 7;
        c.gridx = 0;
        add(labeled("Frames/rev", perRev), c);

        c.gridx = 1;
        add(labeled("Deg/s", rate), c);

        c.gridx = 2;
        add(labeled("Axis lon", orbitLon), c);

        c.gridx = 3;
        add(labeled("Axis lat", orbitLat), c);
    }

    // DONKI publishes fits per UTC day, so the query is padded a day either side of the cloud's
    // span to catch a CME that started before the frame being viewed. Several CMEs are usually
    // in flight at once, hence a chooser rather than "take the first" — on 2021-10-28 the
    // loudest fit that day is a different CME from the one these clouds reconstruct.
    private static void pickDonkiFit(PointCloudLayer layer, JCheckBox arrow, JHVSpinner lon,
                                     JHVSpinner lat, JHVSpinner halfAngle, JHVSpinner length) {
        if (!layer.hasClouds()) {
            JOptionPane.showMessageDialog(MainFrame.get(),
                    "Load a point cloud first: its timestamps set the date range to query.",
                    "DONKI", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        long start = layer.getStartTime(), end = layer.getEndTime();
        Task.submit("donki", DonkiCone.query(start - DAY_MILLI, end + DAY_MILLI), fits -> {
            if (fits.isEmpty()) {
                JOptionPane.showMessageDialog(MainFrame.get(),
                        "No DONKI cone fits with a direction and half-angle in that window.",
                        "DONKI", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Object choice = JOptionPane.showInputDialog(MainFrame.get(),
                    "CME cone fits (SWPC_CAT) near this cloud:", "DONKI",
                    JOptionPane.QUESTION_MESSAGE, null, fits.toArray(), fits.getFirst());
            if (choice == null)
                return;
            DonkiCone.Fit fit = (DonkiCone.Fit) choice;
            lon.setValue(fit.longitude());
            lat.setValue(fit.latitude());
            halfAngle.setValue(fit.halfAngle());
            // Only a starting value: below 21.5 R☉ this back-extrapolates through the
            // acceleration phase at constant speed, so it runs high. Clamped to the spinner's
            // range, and left for the user to trim against the cloud.
            double h = fit.heightAt(start);
            if (fit.time215Milli() != 0 && h > 1 && h < 200)
                length.setValue(h);
            if (!arrow.isSelected())
                arrow.doClick(); // shows the cone; doClick keeps the checkbox and layer in step
        }, "Error querying DONKI");
    }

    private void refreshAlpha(PointCloudLayer layer) {
        String a = layer.resolvedAlpha();
        alphaLabel.setText(a == null ? "" : a);
    }

    private static int alphaToSlider(PointCloudLayer layer, double alphaPct) {
        return (int) Math.round(Math.clamp(layer.alphaPctToSliderFrac(alphaPct), 0, 1) * ALPHA_STEPS);
    }

    private static JHVSpinner makeSpinner(double value, double min, double max, double step,
                                          java.util.function.DoubleConsumer setter) {
        JHVSpinner s = new JHVSpinner(value, min, max, step);
        s.addChangeListener(e -> setter.accept((Double) s.getValue()));
        return s;
    }

    private static JPanel labeled(String text, JHVSpinner spinner) {
        JPanel panel = new JPanel();
        panel.add(new JLabel(text, JLabel.RIGHT));
        panel.add(spinner);
        return panel;
    }

}
