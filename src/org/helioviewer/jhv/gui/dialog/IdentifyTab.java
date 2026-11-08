package org.helioviewer.jhv.gui.dialog;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.helioviewer.jhv.identify.IdentifySettings;

/**
 * The cursor identifier's numbers and its finer switches, shown in the Identify panel in the sidebar. The three main
 * switches are in the View menu under Show Status Bar; they are repeated here so this tab is the
 * whole picture.
 */
public final class IdentifyTab {

    /** The whole form: the Identify panel in the sidebar shows this same one, so there is no second copy to drift. */
    public static JComponent build() {
        JCheckBox enabled = box("Identify under cursor (footer)", IdentifySettings.enabled(), IdentifySettings.ENABLED);
        JCheckBox tooltip = box("Show the name beside the pointer", IdentifySettings.tooltip(), IdentifySettings.TOOLTIP);
        JCheckBox stars = box("Include stars", IdentifySettings.stars(), IdentifySettings.STARS);
        JCheckBox iau = box("IAU proper names (Sirius)", IdentifySettings.starNamesIau(), IdentifySettings.STAR_NAMES_IAU);
        JCheckBox bayer = box("Bayer designations (α CMa)", IdentifySettings.starNamesBayer(), IdentifySettings.STAR_NAMES_BAYER);
        JCheckBox pluto = box("Include Pluto", IdentifySettings.pluto(), IdentifySettings.PLUTO);
        JCheckBox light = box("Correct for light travel time",
                IdentifySettings.lightTime(), IdentifySettings.LIGHT_TIME);
        JSpinner tolerance = spinner(IdentifySettings.tolerancePixels(), 0, 100, 1, IdentifySettings.TOLERANCE_PX);
        JSpinner dwell = spinner(IdentifySettings.dwellMillis(), 0, 5000, 50, IdentifySettings.DWELL_MS);
        JSpinner earth = spinner(IdentifySettings.earthUncertaintyKm(), 0, 1e6, 100, IdentifySettings.EARTH_UNCERTAINTY_KM);
        JSpinner magnitude = spinner(IdentifySettings.starMaxMagnitude(), -2, 12, 0.5, IdentifySettings.STAR_MAX_MAG);
        // Every control follows the settings, not only the three main switches: the menu, the
        // command and the panel's Reset to defaults all change them from outside this form.
        IdentifySettings.addListener(() -> {
            enabled.setSelected(IdentifySettings.enabled());
            tooltip.setSelected(IdentifySettings.tooltip());
            stars.setSelected(IdentifySettings.stars());
            iau.setSelected(IdentifySettings.starNamesIau());
            bayer.setSelected(IdentifySettings.starNamesBayer());
            pluto.setSelected(IdentifySettings.pluto());
            light.setSelected(IdentifySettings.lightTime());
            follow(tolerance, IdentifySettings.tolerancePixels());
            follow(dwell, IdentifySettings.dwellMillis());
            follow(earth, IdentifySettings.earthUncertaintyKm());
            follow(magnitude, IdentifySettings.starMaxMagnitude());
        });

        // A narrow column, one control per line, because this lives in a sidebar about 300 px wide:
        // a label-beside-control grid made the panel 786 px wide.
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(6, 9, 9, 9));
        form.add(heading("Switches"));
        form.add(line(enabled));
        form.add(line(tooltip));
        form.add(line(stars));
        form.add(heading("Pointer"));
        form.add(line(new JLabel("Tolerance (screen pixels)"), tolerance));
        form.add(line(new JLabel("Label delay (ms)"), dwell));
        form.add(heading("Observer"));
        form.add(line(new JLabel("Earth uncertainty (km)"), earth));
        form.add(note("PUNCH mosaics place the observer at Earth's centre"));
        form.add(heading("Bodies"));
        form.add(line(pluto));
        form.add(line(light));
        form.add(note("Where the body was when its light left it; about 0.005° for the Moon from Earth"));
        form.add(heading("Stars"));
        form.add(line(iau));
        form.add(line(bayer));
        form.add(line(new JLabel("Faintest star (magnitude)"), magnitude));
        form.add(note("Stars without a name or designation are named by Hipparcos number. Both tables are fetched on first use. "
                + org.helioviewer.jhv.layers.stars.StarCatalog.CREDIT + " " + org.helioviewer.jhv.layers.stars.StarNames.CREDIT));
        return form;
    }

    private static JComponent heading(String text) {
        JLabel l = new JLabel("<html><b>" + text + "</b>");
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent note(String text) {
        JLabel l = new JLabel("<html><body style='width:240px'><small>" + text);
        l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent line(JComponent... parts) {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 4, 0));
        row.setOpaque(false);
        for (JComponent c : parts)
            row.add(c);
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return row;
    }

    private static JCheckBox box(String text, boolean value, String key) {
        JCheckBox b = new JCheckBox(text, value);
        b.addActionListener(e -> IdentifySettings.set(key, Boolean.toString(b.isSelected())));
        return b;
    }

    /** Show a setting's value without writing it back: the spinner's own listener would answer with another change. */
    private static void follow(JSpinner s, double value) {
        if (((Number) s.getValue()).doubleValue() != value)
            s.setValue(value);
    }

    private static JSpinner spinner(double value, double min, double max, double step, String key) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        s.addChangeListener(e -> IdentifySettings.set(key, String.valueOf(((Number) s.getValue()).doubleValue())));
        return s;
    }

    private IdentifyTab() {}
}
