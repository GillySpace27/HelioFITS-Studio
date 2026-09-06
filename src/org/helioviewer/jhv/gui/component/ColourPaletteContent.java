package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.HdrGain;

/**
 * The controls that decide how every frame is coloured, in one palette.
 *
 * <p>These were spread down the View menu, one submenu each, which is the wrong shape for them:
 * they are not commands, they are settings you adjust while watching the picture change, and a
 * menu closes the moment you pick one. They also differ in kind from what the layer rows carry.
 * A layer row is about one layer's own pixels; everything here applies to the whole view and to
 * every frame in it, so a movie looks the same at frame 1 and frame 245.
 *
 * <p>The menu items stay where they are. They cost nothing, some people go looking in menus, and
 * both routes read the same settings, so neither can drift from the other.
 */
final class ColourPaletteContent {

    private static final JPanel panel = new JPanel();
    private static JComboBox<String> gainCombo;
    private static JComboBox<HdrGain.Mode> modeCombo;
    private static JComboBox<String> kneeCombo;
    private static JComboBox<String> inRangeCombo;
    private static JCheckBox clipping;
    private static JLabel headroom;
    private static boolean built;

    private static final String[][] STOPS = {{"Off (1x)", "1"}, {"+1/2 stop (1.4x)", "1.41"}, {"+1 stop (2x)", "2"},
            {"+1 1/2 stops (2.8x)", "2.83"}, {"+2 stops (4x)", "4"}, {"Display maximum", "auto"}};
    private static final double[] KNEES = {0.5, 0.75, 0.9};
    private static final double[] IN_RANGE = {0, 0.35, 0.5, 0.75, 1};

    static Component build() {
        if (built) {
            refresh();
            return panel;
        }
        built = true;
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
        panel.setOpaque(false);

        gainCombo = new JComboBox<>(new String[]{STOPS[0][0], STOPS[1][0], STOPS[2][0], STOPS[3][0], STOPS[4][0], STOPS[5][0]});
        gainCombo.setToolTipText("How far over the interface white the brightest data goes, in photographic stops. "
                + "Never more than the display offers at its current brightness; Display maximum uses all of it.");
        gainCombo.addActionListener(e -> {
            HdrGain.setSetting(STOPS[gainCombo.getSelectedIndex()][1]);
            DisplayController.display();
            refresh();
        });

        modeCombo = new JComboBox<>(HdrGain.Mode.values());
        modeCombo.setToolTipText("Linear scales the whole image into the headroom. The knee modes leave everything "
                + "below the knee as it is and expand only the highlights; soft rolls into it without a visible break.");
        modeCombo.addActionListener(e -> {
            HdrGain.setMode((HdrGain.Mode) modeCombo.getSelectedItem());
            DisplayController.display();
            refresh();
        });

        String[] kneeLabels = new String[KNEES.length];
        for (int i = 0; i < KNEES.length; i++)
            kneeLabels[i] = "top " + Math.round((1 - KNEES[i]) * 100) + "% of the data";
        kneeCombo = new JComboBox<>(kneeLabels);
        kneeCombo.setToolTipText("Where the knee modes start expanding, as a fraction of the data range that feeds "
                + "the colour table. The colorbar marks it with a line, so what is expanded is visible rather than implied.");
        kneeCombo.addActionListener(e -> {
            HdrGain.setKnee(KNEES[kneeCombo.getSelectedIndex()]);
            DisplayController.display();
        });

        String[] shares = new String[IN_RANGE.length];
        for (int i = 0; i < IN_RANGE.length; i++)
            shares[i] = switch ((int) Math.round(IN_RANGE[i] * 100)) {
                case 0 -> "none: all of it above the range";
                case 100 -> "all of it: nothing left above the range";
                default -> Math.round(IN_RANGE[i] * 100) + "% inside the range";
            };
        inRangeCombo = new JComboBox<>(shares);
        inRangeCombo.setToolTipText("Uniform only. How much of the headroom brightens the picture itself rather than "
                + "being kept for data that exceeds the display range. None leaves the picture exactly as it is "
                + "without headroom; all of it takes the range to the display's peak and flattens everything above it.");
        inRangeCombo.addActionListener(e -> {
            HdrGain.setInRange(IN_RANGE[inRangeCombo.getSelectedIndex()]);
            DisplayController.display();
        });

        // The same switch as View > Show Clipped Pixels, not a second one beside it. The colorbar's
        // over-range section is not optional and is not this: it shows whenever the display carries
        // headroom, because a legend that stops at 1 misrepresents an image that does not.
        clipping = new JCheckBox("Show clipped pixels");
        clipping.setToolTipText("Magenta where the display range is exceeded, green where it bottoms out. "
                + "Flat regions that stay unflagged were already flat in the data.");
        clipping.addActionListener(e -> {
            Display.setShowClipping(clipping.isSelected());
            MenuBar.syncClippingItem();
            DisplayController.display();
        });

        JCheckBox canvas = new JCheckBox("HDR canvas", HdrGain.canvasEnabled());
        canvas.setToolTipText("Render image layers into the display's extended range, so the corona can be brighter "
                + "than the window. Needs an EDR display; the canvas itself is created at the next start, but "
                + "turning this off takes the brightness to 1x now, and turning it on brings the old brightness back.");
        canvas.addActionListener(e -> {
            HdrGain.setCanvasEnabled(canvas.isSelected()); // also parks the brightness at 1x, or restores it
            DisplayController.display();
            refresh();
        });

        headroom = new JLabel();
        headroom.setFont(headroom.getFont().deriveFont(Font.PLAIN, headroom.getFont().getSize2D() - 1));
        headroom.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));

        for (Component c : new Component[]{
                row("Brightness", gainCombo), row("Mapping", modeCombo), row("Knee", kneeCombo),
                row("In range", inRangeCombo), clipping, canvas, headroom}) {
            ((JPanel) (c instanceof JPanel p ? p : wrap(c))).setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(c instanceof JPanel ? c : wrap(c));
        }
        refresh();
        return panel;
    }

    private static JPanel wrap(Component c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        p.setOpaque(false);
        p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel row(String label, Component c) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(90, l.getPreferredSize().height));
        p.add(l, BorderLayout.LINE_START);
        p.add(c, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Mirror the settings, and say what the display is actually giving us right now. */
    static void refresh() {
        if (!built)
            return;
        for (int i = 0; i < STOPS.length; i++)
            if (STOPS[i][1].equals(HdrGain.setting()))
                gainCombo.setSelectedIndex(i);
        modeCombo.setSelectedItem(HdrGain.mode());
        for (int i = 0; i < KNEES.length; i++)
            if (Math.abs(KNEES[i] - HdrGain.knee()) < 1e-3)
                kneeCombo.setSelectedIndex(i);
        clipping.setSelected(Display.showClipping);
        kneeCombo.setEnabled(HdrGain.mode() == HdrGain.Mode.HardKnee || HdrGain.mode() == HdrGain.Mode.SoftKnee);
        for (int i = 0; i < IN_RANGE.length; i++)
            if (Math.abs(IN_RANGE[i] - HdrGain.inRange()) < 1e-3)
                inRangeCombo.setSelectedIndex(i);
        inRangeCombo.setEnabled(HdrGain.mode() == HdrGain.Mode.Uniform);

        float gain = HdrGain.current(false);
        headroom.setText(gain > 1
                ? String.format("<html>The display is showing %.2fx interface white.<br>The colorbar's shaded section is that headroom.</html>", gain)
                : "<html>No headroom in use: everything is at or below interface white.</html>");
    }

    private ColourPaletteContent() {}

}
