package org.helioviewer.jhv.gui.dialog;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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

    /** The on/off switch for the whole identifier, which the Identify panel keeps above everything else. */
    public static JCheckBox masterSwitch() {
        JCheckBox enabled = box("Identify under cursor (footer)", IdentifySettings.enabled(), IdentifySettings.ENABLED);
        enabled.setFont(enabled.getFont().deriveFont(java.awt.Font.BOLD));
        IdentifySettings.addListener(() -> enabled.setSelected(IdentifySettings.enabled()));
        return enabled;
    }

    /** The whole form: the Identify panel in the sidebar shows this same one, so there is no second copy to drift. */
    public static JComponent build() {
        JCheckBox tooltip = box("Show the name beside the pointer", IdentifySettings.tooltip(), IdentifySettings.TOOLTIP);
        JCheckBox glow = box("Glow around what is identified", IdentifySettings.glow(), IdentifySettings.GLOW);
        JCheckBox pulse = box("Pulse when it changes", IdentifySettings.pulse(), IdentifySettings.PULSE);
        JCheckBox tipKind = box("Colour the name like its icon", IdentifySettings.tooltipByKind(), IdentifySettings.TOOLTIP_BY_KIND);
        JCheckBox symInstead = box("Planet symbols instead of rings", IdentifySettings.symbolsInstead(), IdentifySettings.SYMBOLS_INSTEAD);
        JCheckBox symAlways = box("Always show planet symbols", IdentifySettings.symbolsAlways(), IdentifySettings.SYMBOLS_ALWAYS);
        JCheckBox all = box("Highlight all named objects in view", IdentifySettings.highlightAll(), IdentifySettings.HIGHLIGHT_ALL);
        JCheckBox byKind = box("Colour the icon by kind of object", IdentifySettings.iconByKind(), IdentifySettings.ICON_BY_KIND);
        JButton colour = new JButton("Icon colour…");
        colour.setToolTipText("The one colour of every icon when it is not coloured by kind");
        colour.addActionListener(e -> {
            java.awt.Color c = javax.swing.JColorChooser.showDialog(colour, "Icon colour", new java.awt.Color(IdentifySettings.iconColor()));
            if (c != null)
                IdentifySettings.set(IdentifySettings.ICON_COLOR, String.format("#%06X", c.getRGB() & 0xFFFFFF));
        });
        Runnable paint = () -> {
            colour.setEnabled(!IdentifySettings.iconByKind());
            colour.setIcon(new ColourDot(new java.awt.Color(IdentifySettings.iconColor())));
        };
        paint.run();
        JCheckBox comets = box("Comets in the movie's field", IdentifySettings.dataComets(), IdentifySettings.DATA_COMETS);
        JCheckBox cmes = box("CMEs (CACTus events, needs the event layer)", IdentifySettings.dataCmes(), IdentifySettings.DATA_CMES);
        JCheckBox clusters = box("Open and globular clusters", IdentifySettings.dataClusters(), IdentifySettings.DATA_CLUSTERS);
        JCheckBox galaxies = box("Galaxies", IdentifySettings.dataGalaxies(), IdentifySettings.DATA_GALAXIES);
        JCheckBox nebulae = box("Nebulae and supernova remnants", IdentifySettings.dataNebulae(), IdentifySettings.DATA_NEBULAE);
        JSpinner deepMag = spinner(IdentifySettings.deepSkyMaxMagnitude(), -2, 20, 0.5, IdentifySettings.DEEP_SKY_MAX_MAG);
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
        magnitude.setToolTipText("The faintest star Identify will name. Separate from the Stars overlay's limit, which only sets what is drawn.");
        // Every control follows the settings, not only the three main switches: the menu, the
        // command and the panel's Reset to defaults all change them from outside this form.
        IdentifySettings.addListener(() -> {
            all.setSelected(IdentifySettings.highlightAll());
            tipKind.setSelected(IdentifySettings.tooltipByKind());
            symInstead.setSelected(IdentifySettings.symbolsInstead());
            symAlways.setSelected(IdentifySettings.symbolsAlways());
            byKind.setSelected(IdentifySettings.iconByKind());
            paint.run();
            comets.setSelected(IdentifySettings.dataComets());
            cmes.setSelected(IdentifySettings.dataCmes());
            clusters.setSelected(IdentifySettings.dataClusters());
            galaxies.setSelected(IdentifySettings.dataGalaxies());
            nebulae.setSelected(IdentifySettings.dataNebulae());
            follow(deepMag, IdentifySettings.deepSkyMaxMagnitude());
            tooltip.setSelected(IdentifySettings.tooltip());
            glow.setSelected(IdentifySettings.glow());
            pulse.setSelected(IdentifySettings.pulse());
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
        form.add(line(tooltip));
        form.add(line(glow));
        form.add(line(pulse));
        form.add(line(stars));
        form.add(line(all));
        form.add(line(tipKind));
        form.add(line(symInstead));
        form.add(line(symAlways));
        form.add(heading("Icon"));
        form.add(line(byKind));
        form.add(line(colour));
        form.add(heading("Datasets (optional)"));
        form.add(line(comets));
        form.add(line(cmes));
        form.add(line(clusters));
        form.add(line(galaxies));
        form.add(line(nebulae));
        form.add(line(new JLabel("Faintest deep-sky object (magnitude)"), deepMag));
        form.add(note("Objects with a Messier number or a common name are always listed. Comets need the network and use JPL's ephemeris for SOHO. "
                + org.helioviewer.jhv.identify.DeepSky.CREDIT));
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

    /** A small filled circle showing the colour chosen. */
    private record ColourDot(java.awt.Color colour) implements javax.swing.Icon {
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            g.setColor(colour);
            g.fillOval(x, y, 12, 12);
        }

        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 12;
        }
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
