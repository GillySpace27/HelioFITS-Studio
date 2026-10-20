package org.helioviewer.jhv.gui;

import java.awt.Component;
import java.awt.GridLayout;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

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
    private static final double[] TEXT = {1, 1.15, 1.3, 1.5, 1.75};
    private static final double[] UI = {1, 1.25, 1.5, 1.75, 2};

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
        JComboBox<String> text = combo(TEXT, text());
        JComboBox<String> ui = combo(UI, ui());
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 6));
        panel.add(new JLabel("Text size"));
        panel.add(text);
        panel.add(new JLabel("Interface scale"));
        panel.add(ui);
        JPanel box = new JPanel(new java.awt.BorderLayout(0, 10));
        box.add(panel, java.awt.BorderLayout.CENTER);
        box.add(new JLabel("<html><body style='width:320px'>Text size enlarges the text and lets the layouts grow around it; "
                + "it applies now. Interface scale enlarges everything together, text, spacing and icons, "
                + "and applies the next time HelioFITS Studio starts.</body></html>"), java.awt.BorderLayout.PAGE_END);
        if (JOptionPane.showConfirmDialog(parent, box, "Display Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;

        double newText = TEXT[text.getSelectedIndex()], newUi = UI[ui.getSelectedIndex()];
        boolean uiChanged = newUi != ui();
        Settings.setProperty(UI_KEY, Double.toString(newUi));
        if (newText != text()) {
            Settings.setProperty(TEXT_KEY, Double.toString(newText));
            UIGlobals.reapplyTheme();
        }
        if (uiChanged)
            JOptionPane.showMessageDialog(parent, "The interface scale changes the next time HelioFITS Studio starts.",
                    "Display Settings", JOptionPane.INFORMATION_MESSAGE);
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
