package org.helioviewer.jhv.plugins.pointcloud;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

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
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;

        JButton open = new JButton("Open…");
        open.setToolTipText("Load a PointCloud .json or .json.gz file");
        open.addActionListener(e -> {
            java.awt.FileDialog fd = new java.awt.FileDialog((java.awt.Frame) null, "Open point cloud", java.awt.FileDialog.LOAD);
            fd.setVisible(true);
            String file = fd.getFile();
            if (file != null) {
                layer.load(new File(fd.getDirectory(), file).toURI());
                refreshAlpha(layer);
            }
        });
        add(open, c);

        JCheckBox points = new JCheckBox("Points", layer.getShowPoints());
        points.addActionListener(e -> layer.setShowPoints(points.isSelected()));
        c.gridx = 1;
        add(points, c);

        c.gridx = 2;
        add(labeled("Size", makeSpinner(layer.getPointSize(), 0.005, 0.1, 0.005, layer::setPointSize)), c);

        JCheckBox byValue = new JCheckBox("Color by data", layer.getColorByValue());
        byValue.addActionListener(e -> layer.setColorByValue(byValue.isSelected()));
        c.gridx = 3;
        add(byValue, c);

        // Alpha row: the slider spans the full width of the panel. Double-click resets
        // it to the default (just below the convex hull, where the ripples resolve).
        JSlider alpha = new JSlider(0, 100, (int) Math.round(layer.getAlphaPct()));
        alpha.setToolTipText("Alpha-shape threshold (percentile of circumradii): 100 = convex hull. Double-click to reset.");
        alpha.addChangeListener(e -> {
            layer.setAlphaPct(alpha.getValue());
            refreshAlpha(layer);
        });
        alpha.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    alpha.setValue((int) Math.round(PointCloudLayer.DEFAULT_ALPHA_PCT));
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
