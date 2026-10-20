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
    private static final double[] UI = {0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};
    private static final int TEXT_MIN = 50, TEXT_MAX = 175, TEXT_STEP = 5; // percent

    private DisplayScale() {}

    public static double text() {
        return read(Settings.getProperty(TEXT_KEY));
    }

    public static double ui() {
        return read(Settings.getProperty(UI_KEY));
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
        double textBefore = text();
        JSlider text = new JSlider(TEXT_MIN, TEXT_MAX, (int) Math.round(textBefore * 100));
        text.setMajorTickSpacing(25);
        text.setMinorTickSpacing(TEXT_STEP);
        text.setSnapToTicks(true);
        text.setPaintTicks(true);
        java.util.Hashtable<Integer, JLabel> labels = new java.util.Hashtable<>();
        for (int v = TEXT_MIN; v <= TEXT_MAX; v += 25)
            labels.put(v, new JLabel(v + "%"));
        text.setLabelTable(labels);
        text.setPaintLabels(true);
        JLabel textValue = new JLabel(text.getValue() + "%");
        // Each step applies as the slider lands on it. Coalesced: a drag crosses steps faster than
        // the whole window can be re-laid out, so the newest value wins and the rest are dropped.
        Timer apply = new Timer(60, e -> setText(text.getValue() / 100., SwingUtilities.getWindowAncestor(text)));
        apply.setRepeats(false);
        text.addChangeListener(e -> {
            textValue.setText(text.getValue() + "%");
            apply.restart();
        });
        JComboBox<String> ui = combo(UI, ui());

        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.LINE_START;
        c.gridy = 0;
        panel.add(new JLabel("Text size"), c);
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(text, c);
        c.fill = java.awt.GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(textValue, c);
        c.gridy = 1;
        panel.add(new JLabel("Interface scale"), c);
        panel.add(ui, c);
        JPanel box = new JPanel(new java.awt.BorderLayout(0, 10));
        box.add(panel, java.awt.BorderLayout.CENTER);
        box.add(new JLabel("<html><body style='width:360px'>Text size enlarges or shrinks the text and lets the layouts "
                + "follow it, as you move the slider. Interface scale changes everything together, text, spacing and "
                + "icons, and applies the next time HelioFITS Studio starts.</body></html>"), java.awt.BorderLayout.PAGE_END);

        boolean ok = JOptionPane.showConfirmDialog(parent, box, "Display Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
        apply.stop();
        if (!ok) { // Cancel puts the text size back as well
            if (text() != textBefore)
                setText(textBefore, null);
            return;
        }
        setText(text.getValue() / 100., null); // the last step, and the dialog's own window is gone now

        double newUi = UI[ui.getSelectedIndex()];
        boolean uiChanged = newUi != ui();
        Settings.setProperty(UI_KEY, Double.toString(newUi));
        if (uiChanged)
            JOptionPane.showMessageDialog(parent, "The interface scale changes the next time HelioFITS Studio starts.",
                    "Display Settings", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void setText(double scale, @javax.annotation.Nullable java.awt.Window skip) {
        if (scale == text())
            return;
        Settings.setProperty(TEXT_KEY, Double.toString(scale));
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
