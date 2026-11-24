package org.helioviewer.jhv.layers.selector;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;

import org.helioviewer.jhv.layers.CometLayer;

/**
 * A comet layer's options: colour, the rings in each image, light time, Refresh, the orbit and the
 * position dot of the 3D modes with the orbit's span, and status lines saying where the path came
 * from and, per image layer, where its observer came from.
 */
@SuppressWarnings("serial")
public final class CometLayerOptions extends JPanel {

    private final JLabel status = new JLabel(" ");
    private final Timer refresh;

    public CometLayerOptions(CometLayer layer) {
        super(new GridBagLayout());

        JButton colour = new JButton("Colour…");
        colour.setToolTipText("The colour of this comet's rings");
        colour.addActionListener(e -> {
            int[] c = layer.rgb();
            Color chosen = JColorChooser.showDialog(this, layer.getName(), new Color(c[0], c[1], c[2]));
            if (chosen != null)
                layer.setRgb(new int[]{chosen.getRed(), chosen.getGreen(), chosen.getBlue()});
        });

        JCheckBox rings = new JCheckBox("In each image", layer.showRings());
        rings.setToolTipText("A ring in every image layer that shows the comet, where that image's spacecraft saw it at that image's time. Rings from different spacecraft sit in different places: that is parallax, not an error.");
        rings.addActionListener(e -> layer.setShowRings(rings.isSelected()));

        JCheckBox light = new JCheckBox("Light time", layer.lightTime());
        light.setToolTipText("Place the comet where it was when the light left it. Near perihelion this moves it by tenths of a solar radius.");
        light.addActionListener(e -> layer.setLightTime(light.isSelected()));

        JButton again = new JButton("Refresh");
        again.setToolTipText("Ask JPL Horizons again, ignoring the saved answer: for a comet whose orbit is still being refined");
        again.addActionListener(e -> layer.refresh());

        JCheckBox orbit = new JCheckBox("Orbit", layer.showOrbit());
        orbit.setToolTipText("In the 3D modes, the comet's path through the scene: brighter where it has been, fainter where it is going");
        orbit.addActionListener(e -> layer.setShowOrbit(orbit.isSelected()));

        JCheckBox position = new JCheckBox("Position", layer.showDot());
        position.setToolTipText("In the 3D modes, a dot where the comet is at the display time. It sits on a ring only when the comet is in that image's plane of sky: the images are perspective views.");
        position.addActionListener(e -> layer.setShowDot(position.isSelected()));

        JSpinner span = new JSpinner(new SpinnerNumberModel(layer.orbitSpanDays(), 1, CometLayer.MAX_SPAN_DAYS, 1));
        span.setToolTipText("Days of orbit drawn each side of perihelion, besides the movie; fetched from JPL Horizons when changed");
        span.addChangeListener(e -> layer.setOrbitSpanDays((Integer) span.getValue()));
        JLabel spanLabel = new JLabel("days");

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        row.add(colour);
        row.add(rings);
        row.add(light);
        row.add(again);

        JPanel row3d = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        row3d.add(orbit);
        row3d.add(span);
        row3d.add(spanLabel);
        row3d.add(position);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 4, 2, 4);
        c.gridy = 0;
        add(row, c);
        c.gridy = 1;
        add(row3d, c);
        c.gridy = 2;
        add(status, c);
        updateStatus(layer);

        // The status depends on the frame drawn (which layers, their observers), so it is polled
        // while the panel is on screen, as ModelLayerOptions does.
        refresh = new Timer(500, e -> updateStatus(layer));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0)
                return;
            if (isShowing())
                refresh.start();
            else
                refresh.stop();
        });
    }

    private void updateStatus(CometLayer layer) {
        String text = layer.status();
        status.setText("<html>" + (text.isEmpty() ? "&nbsp;" : text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")) + "</html>");
    }

}
