package org.helioviewer.jhv.plugins.pointcloud;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.net.URI;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.image.lut.LUT;

@SuppressWarnings("serial")
class PointCloudOptions extends JPanel {

    private final JLabel alphaLabel = new JLabel();

    PointCloudOptions(PointCloudLayer layer) {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;

        JButton open = new JButton("Open…");
        open.setToolTipText("Load a PointCloud .json or .json.gz file");
        open.addActionListener(e -> {
            java.awt.FileDialog fd = new java.awt.FileDialog((java.awt.Frame) null, "Open point cloud", java.awt.FileDialog.LOAD);
            fd.setVisible(true);
            String dir = fd.getDirectory();
            String file = fd.getFile();
            if (file != null) {
                layer.load(new File(dir, file).toURI());
                refreshAlpha(layer);
            }
        });
        add(open, c);

        JCheckBox points = new JCheckBox("Points", layer.getShowPoints());
        points.addActionListener(e -> layer.setShowPoints(points.isSelected()));
        c.gridx = 1;
        add(points, c);

        JHVSpinner size = new JHVSpinner(layer.getPointSize(), 0.005, 0.1, 0.005);
        size.addChangeListener(e -> layer.setPointSize((Double) size.getValue()));
        c.gridx = 2;
        add(labeled("Size", size), c);

        JSlider alpha = new JSlider(0, 100, (int) Math.round(layer.getAlphaPct()));
        alpha.setToolTipText("Alpha-shape threshold (percentile of circumradii): 100 = convex hull");
        alpha.addChangeListener(e -> {
            layer.setAlphaPct(alpha.getValue());
            refreshAlpha(layer);
        });
        c.gridy = 1;
        c.gridx = 0;
        add(new JLabel("Alpha"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        add(alpha, c);
        c.gridwidth = 1;
        c.gridx = 3;
        add(alphaLabel, c);

        JCheckBox wire = new JCheckBox("Wireframe", layer.getShowWire());
        wire.addActionListener(e -> layer.setShowWire(wire.isSelected()));
        c.gridy = 2;
        c.gridx = 0;
        add(wire, c);

        JCheckBox surface = new JCheckBox("Surface", layer.getShowSurface());
        surface.addActionListener(e -> layer.setShowSurface(surface.isSelected()));
        c.gridx = 1;
        add(surface, c);

        JHVSpinner opacity = new JHVSpinner(layer.getOpacity(), 0, 1, 0.05);
        opacity.addChangeListener(e -> layer.setOpacity((Double) opacity.getValue()));
        c.gridx = 2;
        add(labeled("Opacity", opacity), c);

        JComboBox<String> lut = new JComboBox<>(LUT.names());
        lut.setSelectedItem(layer.getLut());
        lut.addActionListener(e -> layer.setLut((String) lut.getSelectedItem()));
        c.gridy = 3;
        c.gridx = 0;
        add(new JLabel("Colormap"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        add(lut, c);
        c.gridwidth = 1;

        JCheckBox byValue = new JCheckBox("Color by data", layer.getColorByValue());
        byValue.addActionListener(e -> layer.setColorByValue(byValue.isSelected()));
        c.gridx = 3;
        add(byValue, c);

        refreshAlpha(layer);
    }

    private void refreshAlpha(PointCloudLayer layer) {
        String a = layer.resolvedAlpha();
        alphaLabel.setText(a == null ? "" : a);
    }

    private static JPanel labeled(String text, JHVSpinner spinner) {
        JPanel panel = new JPanel();
        panel.add(new JLabel(text, JLabel.RIGHT));
        panel.add(spinner);
        return panel;
    }

}
