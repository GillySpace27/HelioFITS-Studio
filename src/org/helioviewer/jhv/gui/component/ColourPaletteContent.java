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
    private static JHVSlider gainSlider;
    private static JLabel gainValue;
    private static JComboBox<HdrGain.Mode> modeCombo;
    private static JHVSlider kneeSlider;
    private static JLabel kneeValue;
    private static JHVSlider inRangeSlider;
    private static JLabel inRangeValue;
    private static JCheckBox clipping;
    private static JLabel headroom;
    private static boolean built;
    private static boolean syncing; // mirroring the state into the widgets, not editing it

    // Brightness is a slider in hundredths of a photographic stop, 0 to 4 stops, so 1x to 16x.
    // The top is stored as "auto" rather than as 16: resolve() clamps a fixed stop to the headroom
    // the display is offering, so a fixed 16x and "follow the display" already behave identically,
    // and writing "auto" keeps the setting readable and keeps the old meaning exactly.
    private static final int MAX_STOPS = 400;

    private static double gainFromSlider(int v) {
        return Math.pow(2, v / 100.);
    }

    static Component build() {
        if (built) {
            refresh();
            return panel;
        }
        built = true;
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
        panel.setOpaque(false);

        gainSlider = new JHVSlider(0, MAX_STOPS, MAX_STOPS);
        gainValue = new JLabel();
        gainSlider.setToolTipText("<html>How far over the interface white the brightest data is allowed to go, in "
                + "photographic stops: each stop doubles it, so +2 stops is 4x white.<br><br>"
                + "The display is the ceiling. Whatever you ask for, the compositor gives what it has at the "
                + "current screen brightness, and the line at the bottom of this palette says what that is right "
                + "now. At the far right the setting becomes \"follow the display\", which is the same thing as "
                + "asking for more than it can give.<br><br>"
                + "This moves the picture, not the data: nothing here changes a pixel value, only how much room "
                + "above white it is drawn into. Double-click to return to the display maximum.</html>");
        gainSlider.addChangeListener(e -> {
            if (syncing)
                return;
            int v = gainSlider.getValue();
            HdrGain.aimSetting(v >= MAX_STOPS ? "auto" : String.valueOf(gainFromSlider(v)));
            gainValue.setText(gainText(v));
            DisplayController.display();
            if (!gainSlider.getValueIsAdjusting()) { // let go, or arrowed: now it is worth a file write
                HdrGain.commit();
                refresh();
            }
        });

        modeCombo = new JComboBox<>(HdrGain.Mode.values());
        modeCombo.setToolTipText("Linear scales the whole image into the headroom. The knee modes leave everything "
                + "below the knee as it is and expand only the highlights; soft rolls into it without a visible break.");
        modeCombo.addActionListener(e -> {
            HdrGain.setMode((HdrGain.Mode) modeCombo.getSelectedItem());
            DisplayController.display();
            refresh();
        });

        kneeSlider = new JHVSlider(5, 95, 75);
        kneeValue = new JLabel();
        kneeSlider.setToolTipText("<html>Where the knee modes stop leaving the picture alone and start spending the "
                + "headroom, as a position in the data range that feeds the colour table.<br><br>"
                + "At 75 the bottom three quarters of the range are drawn exactly as they would be with no "
                + "headroom at all and only the top quarter is expanded; drag left to expand more of the picture, "
                + "right to reserve the headroom for the brightest structure alone. The colorbar marks the "
                + "position with a line, so what is being expanded is visible rather than implied.<br><br>"
                + "Hard knee and soft knee only: the other mappings do not have a knee.</html>");
        kneeSlider.addChangeListener(e -> {
            if (syncing)
                return;
            HdrGain.aimKnee(kneeSlider.getValue() / 100.);
            kneeValue.setText("top " + (100 - kneeSlider.getValue()) + "%");
            DisplayController.display();
            if (!kneeSlider.getValueIsAdjusting())
                HdrGain.commit();
        });

        inRangeSlider = new JHVSlider(0, 100, (int) Math.round(HdrGain.DEFAULT_IN_RANGE * 100)); // double-click resets to the shipped default
        inRangeValue = new JLabel();
        inRangeSlider.setToolTipText("<html>How much of the headroom brightens the picture itself, rather than being "
                + "kept for data that exceeds the display range.<br><br>"
                + "At 0 the picture is exactly what it is with no headroom at all and every bit of the extra range "
                + "goes to over-range data, which is the honest choice when you want to see what exceeded the "
                + "range. At 100 the top of the range reaches the display's peak and there is nothing left above "
                + "it, so over-range data goes flat. In between, the top of the range lands part way up and the "
                + "climb carries on above it.<br><br>"
                + "Uniform only. With RHEF the rank never exceeds 1, so nothing can occupy the part above the "
                + "range and this is the slider that gives a RHEF picture its brightness.</html>");
        inRangeSlider.addChangeListener(e -> {
            if (syncing)
                return;
            HdrGain.aimInRange(inRangeSlider.getValue() / 100.);
            inRangeValue.setText(inRangeSlider.getValue() + "%");
            DisplayController.display();
            if (!inRangeSlider.getValueIsAdjusting())
                HdrGain.commit();
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

        for (JHVSlider slider : new JHVSlider[]{gainSlider, kneeSlider, inRangeSlider})
            slider.setPreferredSize(new Dimension(150, slider.getPreferredSize().height));

        headroom = new JLabel();
        headroom.setFont(headroom.getFont().deriveFont(Font.PLAIN, headroom.getFont().getSize2D() - 1));
        headroom.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));

        for (Component c : new Component[]{
                row("Brightness", gainSlider, gainValue), row("Mapping", modeCombo), row("Knee", kneeSlider, kneeValue),
                row("In range", inRangeSlider, inRangeValue), clipping, canvas, headroom}) {
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

    private static JPanel row(String label, Component c, Component value) {
        JPanel p = row(label, c);
        value.setPreferredSize(new java.awt.Dimension(84, value.getPreferredSize().height));
        p.add(value, BorderLayout.LINE_END);
        return p;
    }

    /** What the brightness slider is asking for, in the words the tooltip uses. */
    private static String gainText(int v) {
        if (v >= MAX_STOPS)
            return "display max";
        if (v == 0)
            return "off (1.0x)";
        return String.format("+%.2f (%.2fx)", v / 100., gainFromSlider(v));
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
        syncing = true;
        String setting = HdrGain.setting();
        int stops = MAX_STOPS;
        if (setting != null && !"auto".equals(setting)) {
            try {
                stops = (int) Math.round(100 * Math.log(Double.parseDouble(setting)) / Math.log(2));
            } catch (NumberFormatException ignore) {
            }
        }
        gainSlider.setValue(Math.clamp(stops, 0, MAX_STOPS));
        gainValue.setText(gainText(gainSlider.getValue()));
        modeCombo.setSelectedItem(HdrGain.mode());
        kneeSlider.setValue((int) Math.round(HdrGain.knee() * 100));
        kneeValue.setText("top " + (100 - kneeSlider.getValue()) + "%");
        syncing = false;
        clipping.setSelected(Display.showClipping);
        kneeSlider.setEnabled(HdrGain.mode() == HdrGain.Mode.HardKnee || HdrGain.mode() == HdrGain.Mode.SoftKnee);
        syncing = true;
        inRangeSlider.setValue((int) Math.round(HdrGain.inRange() * 100));
        inRangeValue.setText(inRangeSlider.getValue() + "%");
        syncing = false;
        inRangeSlider.setEnabled(HdrGain.mode() == HdrGain.Mode.Uniform);

        float gain = HdrGain.current(false);
        headroom.setText(gain > 1
                ? String.format("<html>The display is showing %.2fx interface white.<br>The colorbar's shaded section is that headroom.</html>", gain)
                : "<html>No headroom in use: everything is at or below interface white.</html>");
    }

    private ColourPaletteContent() {}

}
