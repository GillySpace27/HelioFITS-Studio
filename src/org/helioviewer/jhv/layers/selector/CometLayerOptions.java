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
import javax.swing.Timer;

import org.helioviewer.jhv.layers.CometLayer;

/**
 * A comet layer's options: colour, the rings in each image, light time, Refresh, and status lines
 * saying where the path came from and, per image layer, where its observer came from.
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

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        row.add(colour);
        row.add(rings);
        row.add(light);
        row.add(again);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 4, 2, 4);
        c.gridy = 0;
        add(row, c);
        c.gridy = 1;
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
