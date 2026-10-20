package org.helioviewer.jhv.gui;

import java.awt.Component;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Settings;

/**
 * View > Display Settings: text size and interface scale, the one place to make the app bigger.
 *
 * <p>Two knobs because they do different things. Text size scales the base font every other UI
 * font is derived from (UIGlobals.applyTheme), and the layouts grow around the text; it applies at
 * once through the same path a theme switch takes, though the few controls that pinned a font of
 * their own pick it up at the next launch. Interface scale is FlatLaf's flatlaf.uiScale, which
 * scales everything together (text, insets, icons, borders), so nothing changes proportion; FlatLaf
 * reads it once as the look and feel loads, so it applies at the next launch. The image canvas is
 * drawn by GL and is not scaled by either.
 */
public final class DisplayScale {

    static final String TEXT_KEY = "display.textScale";
    static final String UI_KEY = "display.uiScale";
    // The three text levels, each relative to its default and multiplied by Text size: section
    // headings, body text, and detail (the small labels and readouts). Gilly, 2026-10-20: headings
    // large without the small text growing with them.
    static final String HEADING_KEY = "display.headingScale";
    static final String BODY_KEY = "display.bodyScale";
    static final String DETAIL_KEY = "display.detailScale";
    private static final double[] UI = {0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};
    private static final int MIN = 50, MAX = 300, STEP = 5; // percent, the same for every text slider

    private DisplayScale() {}

    public static double text() {
        return read(Settings.getProperty(TEXT_KEY));
    }

    public static double ui() {
        return read(Settings.getProperty(UI_KEY));
    }

    public static double heading() {
        return read(Settings.getProperty(HEADING_KEY));
    }

    public static double body() {
        return read(Settings.getProperty(BODY_KEY));
    }

    public static double detail() {
        return read(Settings.getProperty(DETAIL_KEY));
    }

    /** A stored factor, or 1 when it is absent, unreadable or outside 0.5 to 3. */
    static double read(@javax.annotation.Nullable String stored) {
        if (stored == null)
            return 1;
        try {
            double v = Double.parseDouble(stored);
            return v >= 0.5 && v <= 3 ? v : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Before anything loads the look and feel: FlatLaf reads its scale once. An explicit -D wins. */
    public static void applyUiScaleProperty() {
        double scale = ui();
        if (scale != 1 && System.getProperty("flatlaf.uiScale") == null)
            System.setProperty("flatlaf.uiScale", Double.toString(scale));
    }

    public static void showDialog(Component parent) {
        String[] keys = {TEXT_KEY, HEADING_KEY, BODY_KEY, DETAIL_KEY};
        String[] names = {"Text size", "Headings", "Body", "Detail"};
        double[] before = new double[keys.length];
        JSlider[] sliders = new JSlider[keys.length];
        // Each step applies as a slider lands on it. Coalesced: a drag crosses steps faster than the
        // whole window can be re-laid out, so the newest values win and the rest are dropped.
        Timer apply = new Timer(60, null);
        apply.setRepeats(false);

        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.LINE_START;
        for (int i = 0; i < keys.length; i++) {
            before[i] = read(Settings.getProperty(keys[i]));
            // One range for all four, so they share an axis and only the last needs to print it.
            JSlider slider = slider(before[i], i == keys.length - 1);
            JLabel value = new JLabel(slider.getValue() + "%");
            slider.addChangeListener(e -> {
                value.setText(slider.getValue() + "%");
                apply.restart();
            });
            sliders[i] = slider;
            c.gridy = i;
            c.fill = java.awt.GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(new JLabel(i == 0 ? names[i] : "    " + names[i]), c); // the levels sit under the overall size
            c.fill = java.awt.GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            panel.add(slider, c);
            c.fill = java.awt.GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(value, c);
        }
        apply.addActionListener(e -> setScales(keys, values(sliders), SwingUtilities.getWindowAncestor(sliders[0])));

        JComboBox<String> ui = combo(UI, ui());
        c.gridy = keys.length;
        panel.add(new JLabel("Interface scale"), c);
        panel.add(ui, c);
        JPanel box = new JPanel(new java.awt.BorderLayout(0, 10));
        box.add(panel, java.awt.BorderLayout.CENTER);
        JLabel note = new JLabel("<html><body style='width:340px'>Text size sets all text; Headings, Body and Detail "
                + "each adjust one level against it. Interface scale also sizes spacing and icons, after a restart.</body></html>");
        note.setFont(UIGlobals.uiFontSmall);
        box.add(note, java.awt.BorderLayout.PAGE_END);

        boolean ok = JOptionPane.showConfirmDialog(parent, box, "Display Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
        apply.stop();
        if (!ok) { // Cancel puts the text sizes back as well
            setScales(keys, before, null);
            return;
        }
        setScales(keys, values(sliders), null); // the last step, and the dialog's own window is gone now

        double newUi = UI[ui.getSelectedIndex()];
        boolean uiChanged = newUi != ui();
        Settings.setProperty(UI_KEY, Double.toString(newUi));
        if (uiChanged) {
            String[] options = {"Restart Now", "Later"};
            if (JOptionPane.showOptionDialog(parent, "The interface scale changes when HelioFITS Studio starts again.\n"
                            + "Restart now? Your session is saved and reopens.", "Display Settings",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]) == 0)
                org.helioviewer.jhv.app.Session.restart();
        }
    }

    private static JSlider slider(double current, boolean axis) {
        JSlider slider = new JSlider(MIN, MAX, (int) Math.clamp(Math.round(current * 100), MIN, MAX));
        slider.setMinorTickSpacing(STEP);
        slider.setSnapToTicks(true); // snapping needs the spacing, not painted ticks
        if (axis) { // the shared axis, under the last slider: the columns line them all up with it
            java.util.Hashtable<Integer, JLabel> labels = new java.util.Hashtable<>();
            for (int v = MIN; v <= MAX; v += 50) {
                JLabel label = new JLabel(v + "%");
                label.setFont(UIGlobals.uiFontSmall);
                labels.put(v, label);
            }
            slider.setLabelTable(labels);
            slider.setPaintLabels(true);
        }
        return slider;
    }

    private static double[] values(JSlider[] sliders) {
        double[] out = new double[sliders.length];
        for (int i = 0; i < sliders.length; i++)
            out[i] = sliders[i].getValue() / 100.;
        return out;
    }

    /** Store the sizes that changed and re-apply once, leaving {@code skip} (the dialog, mid-drag) alone. */
    private static void setScales(String[] keys, double[] scales, @javax.annotation.Nullable java.awt.Window skip) {
        boolean changed = false;
        for (int i = 0; i < keys.length; i++)
            if (scales[i] != read(Settings.getProperty(keys[i]))) {
                Settings.setProperty(keys[i], Double.toString(scales[i]));
                changed = true;
            }
        if (changed)
            UIGlobals.reapplyTheme(skip);
    }

    private static JComboBox<String> combo(double[] steps, double current) {
        JComboBox<String> box = new JComboBox<>();
        int best = 0;
        for (int i = 0; i < steps.length; i++) {
            box.addItem(Math.round(steps[i] * 100) + "%");
            if (Math.abs(steps[i] - current) < Math.abs(steps[best] - current))
                best = i;
        }
        box.setSelectedIndex(best);
        return box;
    }

}
