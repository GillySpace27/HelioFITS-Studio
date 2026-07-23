package org.helioviewer.jhv.plugins.pointcloud;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.image.lut.LUT;

import com.jidesoft.swing.JideButton;

@SuppressWarnings("serial")
class PointCloudOptions extends JPanel {

    private static final int ALPHA_STEPS = 1000; // fine slider resolution for the crowded high end

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

        refreshAlpha(layer);
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
