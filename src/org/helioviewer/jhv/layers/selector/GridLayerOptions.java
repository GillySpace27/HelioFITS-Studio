package org.helioviewer.jhv.layers.selector;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.gui.component.CollapsiblePane;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.gui.component.TerminatedFormatterFactory;
import org.helioviewer.jhv.layers.GridLayer;


@SuppressWarnings("serial")
final class GridLayerOptions extends JPanel {

    /**
     * Four collapsed sections rather than one flat list.
     *
     * <p>The grid, the Thomson sphere, the ecliptic plane and the planets are four separate things
     * that happen to be drawn by one layer, and flattening them produced a column of eleven
     * unlabelled sliders where "Line width" and "Thomson line width" sat five rows apart with
     * nothing saying which belonged to what. Each section now carries its own colour, opacity,
     * width and density, so a slider's meaning comes from the header above it.
     */
    GridLayerOptions(GridLayer layer) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        add(new CollapsiblePane("Grid", gridSection(layer), true, true));
        add(new CollapsiblePane("Thomson sphere", thomsonSection(layer), false, true));
        add(new CollapsiblePane("Celestial sphere", celestialSection(layer), false, true));
        add(new CollapsiblePane("Ecliptic plane", eclipticSection(layer), false, true));
        add(new CollapsiblePane("Planets", planetSection(layer), false, true));
    }

    private JPanel gridSection(GridLayer layer) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        c.gridy = 0;
        c.gridx = 1;
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(createToggle("Solar axis", layer.isShowAxis(), layer::setShowAxis), c);
        c.gridx = 3;
        panel.add(createToggle("Grid labels", layer.isShowLabels(), layer::setShowLabels), c);

        c.gridy = 1;
        c.gridx = 1;
        panel.add(createToggle("Radial grid", layer.isShowRadial(), layer::setShowRadial), c);
        c.gridx = 2;
        panel.add(new JLabel("Grid type ", JLabel.RIGHT), c);
        c.gridx = 3;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(createGridTypeBox(layer), c);

        c.gridy = 2;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(new JLabel("Longitude ", JLabel.RIGHT), c);
        c.gridx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(createGridResolutionSpinner(layer.getLonStep(), layer::setLonStep), c);
        c.gridx = 2;
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(new JLabel("Latitude ", JLabel.RIGHT), c);
        c.gridx = 3;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(createGridResolutionSpinner(layer.getLatStep(), layer::setLatStep), c);

        JPanel rows = new JPanel(new GridBagLayout());
        addAdjustmentRow(rows, "Color ", createColorBox(layer), 0);
        addAdjustmentRow(rows, "Line width ", createLineWidthSlider(layer), 1);
        addAdjustmentRow(rows, "Line opacity ", createOpacitySlider(layer.getGridAlpha(), layer::setGridAlpha), 2);
        addAdjustmentRow(rows, "Label opacity ", createOpacitySlider(layer.getLabelAlpha(), layer::setLabelAlpha), 3);
        addAdjustmentRow(rows, "Label size ", createLabelSizeSlider(layer), 4);
        addAdjustmentRow(rows, "Ring label angle ", createLabelAngleSlider(layer), 5);

        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 4;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(rows, c);
        return panel;
    }

    private JPanel thomsonSection(GridLayer layer) {
        JCheckBox toggle = createToggle("Show", layer.isShowThomson(), layer::setShowThomson);
        toggle.setToolTipText("Wireframe of the surface where scattering is at 90 degrees, which is where a coronagraph's line of sight is assumed to have originated. Drawn whichever surface model the imagery uses, so the two can be compared.");
        JPanel panel = surfacePanel(toggle,
                createSurfaceColorBox(layer.getThomsonColor(), layer::setThomsonColor),
                createOpacitySlider(layer.getThomsonAlpha(), layer::setThomsonAlpha),
                createScaleSlider(layer.getThomsonLineScale(), layer::setThomsonLineScale),
                createScaleSlider(layer.getThomsonDensity(), layer::setThomsonDensity));
        return panel;
    }

    private JPanel celestialSection(GridLayer layer) {
        JCheckBox toggle = createToggle("Show", layer.isShowCelestial(), layer::setShowCelestial);
        toggle.setToolTipText("Wireframe of the celestial sphere: twice the Thomson sphere's radius, sharing its pole at the Sun but centred on the observer instead of the Sun-observer midpoint, with its far pole twice as far beyond the Sun.");
        JHVSlider extent = createExtentSlider(layer.getCelestialExtent(), layer::setCelestialExtent);
        extent.setToolTipText("How far around the sky to draw, as an elongation from the Sun: 90 degrees is the hemisphere facing the Sun, 180 the whole sky out to the anti-solar point. PUNCH's WFI reaches 45.");
        return surfacePanel(toggle,
                createSurfaceColorBox(layer.getCelestialColor(), layer::setCelestialColor),
                createOpacitySlider(layer.getCelestialAlpha(), layer::setCelestialAlpha),
                createScaleSlider(layer.getCelestialLineScale(), layer::setCelestialLineScale),
                createScaleSlider(layer.getCelestialDensity(), layer::setCelestialDensity),
                "Extent ", extent);
    }

    private JPanel eclipticSection(GridLayer layer) {
        JCheckBox toggle = createToggle("Show", layer.isShowEcliptic(), layer::setShowEcliptic);
        toggle.setToolTipText("The plane the planets orbit in, taken from Earth's own orbit rather than a tabulated inclination, so it passes through the Earth marker by construction.");
        return surfacePanel(toggle,
                createSurfaceColorBox(layer.getEclipticColor(), layer::setEclipticColor),
                createOpacitySlider(layer.getEclipticAlpha(), layer::setEclipticAlpha),
                createScaleSlider(layer.getEclipticLineScale(), layer::setEclipticLineScale),
                createScaleSlider(layer.getEclipticDensity(), layer::setEclipticDensity));
    }

    /** The same four controls for every reference surface, so the sections read alike. */
    private static JPanel surfacePanel(JCheckBox toggle, Component color, Component opacity,
                                       Component lineWidth, Component density) {
        return surfacePanel(toggle, color, opacity, lineWidth, density, null, null);
    }

    /** The four, plus one control only one surface has (the celestial sphere's extent). */
    private static JPanel surfacePanel(JCheckBox toggle, Component color, Component opacity,
                                       Component lineWidth, Component density, String extraLabel, Component extra) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridy = 0;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(toggle, c);
        c.gridx = 1;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(color, c);

        JPanel rows = new JPanel(new GridBagLayout());
        addAdjustmentRow(rows, "Opacity ", opacity, 0);
        addAdjustmentRow(rows, "Line width ", lineWidth, 1);
        addAdjustmentRow(rows, "Density ", density, 2);
        if (extra != null)
            addAdjustmentRow(rows, extraLabel, extra, 3);

        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 4;
        panel.add(rows, c);
        return panel;
    }

    /**
     * Planets, drawn by this layer rather than by ViewpointLayer.
     *
     * <p>The earlier version put a button here that enabled the Viewpoint layer and forced it into
     * Heliosphere mode, because that layer only draws planets in that one camera mode. Handing the
     * camera over to see a marker is too high a price, and it moved the view out from under the
     * user. These are plain toggles now, with no dependency on any other layer.
     */
    private JPanel planetSection(GridLayer layer) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        c.gridy = 0;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        JCheckBox show = createToggle("Show", layer.isShowPlanets(), layer::setShowPlanets);
        show.setToolTipText("Mercury through Neptune, positioned from the shipped ephemeris, in the same frame as the Earth marker.");
        panel.add(show, c);

        c.gridx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        JCheckBox names = createToggle("Names", layer.isShowPlanetNames(), layer::setShowPlanetNames);
        panel.add(names, c);

        c.gridx = 2;
        c.anchor = GridBagConstraints.LINE_END;
        JCheckBox orbits = createToggle("Orbits", layer.isShowPlanetOrbits(), layer::setShowPlanetOrbits);
        orbits.setToolTipText("One full orbit each, sampled from the ephemeris rather than drawn as a circle, so an eccentric orbit like Mercury's is the shape it really is.");
        panel.add(orbits, c);

        c.gridy = 1;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        JCheckBox follow = createToggle("Follow solar rotation", layer.isPlanetsFollowRotation(), layer::setPlanetsFollowRotation);
        follow.setToolTipText("On: Earth sits on the observer marker and the planets register with the imagery, so a planet bright in a coronagraph frame lands on its marker. Off: an inertial layout where each planet moves at its own orbital rate, good for watching Mercury lap Earth, but nothing lines up with the picture.");
        c.gridwidth = 2;
        panel.add(follow, c);
        c.gridwidth = 1;

        JPanel rows = new JPanel(new GridBagLayout());
        addAdjustmentRow(rows, "Orbit opacity ",
                createOpacitySlider(layer.getPlanetOrbitAlpha(), layer::setPlanetOrbitAlpha), 0);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(rows, c);
        return panel;
    }

    private static void addAdjustmentRow(JPanel panel, String text, Component component, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(new JLabel(text, JLabel.RIGHT), c);

        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = y;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(component, c);
    }

    private static JComboBox<Colors.NamedColor> createColorBox(GridLayer layer) {
        JComboBox<Colors.NamedColor> comboBox = new JComboBox<>(Colors.NamedColor.values());
        comboBox.setSelectedItem(layer.getGridColor());
        comboBox.setRenderer(new ColorRenderer());
        comboBox.addActionListener(e -> {
            Colors.NamedColor color = (Colors.NamedColor) Objects.requireNonNull(comboBox.getSelectedItem());
            layer.setGridColor(color);
        });
        return comboBox;
    }

    /** A colour box for a reference surface, which owns its own colour rather than the grid's. */
    private static JComboBox<Colors.NamedColor> createSurfaceColorBox(Colors.NamedColor initial, Consumer<Colors.NamedColor> setter) {
        JComboBox<Colors.NamedColor> comboBox = new JComboBox<>(Colors.NamedColor.values());
        comboBox.setSelectedItem(initial);
        comboBox.setRenderer(new ColorRenderer());
        comboBox.addActionListener(e ->
                setter.accept((Colors.NamedColor) Objects.requireNonNull(comboBox.getSelectedItem())));
        return comboBox;
    }

    /** A 0.25x to 4x multiplier, for line widths and mesh density alike. */
    private static JHVSlider createScaleSlider(double initialValue, DoubleConsumer valueSetter) {
        JHVSlider slider = new JHVSlider(25, 400, (int) Math.round(initialValue * 100));
        slider.addChangeListener(e -> valueSetter.accept(slider.getValue() / 100.));
        return slider;
    }

    /** Degrees of elongation for the celestial sphere, whose extent is an angle on the sky rather than a radius. */
    private static JHVSlider createExtentSlider(double initialDegrees, DoubleConsumer valueSetter) {
        JHVSlider slider = new JHVSlider((int) GridLayer.CELESTIAL_EXTENT_MIN, (int) GridLayer.CELESTIAL_EXTENT_MAX,
                (int) Math.round(initialDegrees));
        slider.addChangeListener(e -> valueSetter.accept(slider.getValue()));
        return slider;
    }

    private static JHVSlider createOpacitySlider(double initialValue, DoubleConsumer valueSetter) {
        JHVSlider slider = new JHVSlider(0, 100, (int) Math.round(initialValue * 100));
        slider.addChangeListener(e -> valueSetter.accept(slider.getValue() / 100.));
        return slider;
    }

    private static JHVSlider createLineWidthSlider(GridLayer layer) {
        int min = (int) Math.round(GridLayer.GRID_LINE_SCALE_MIN * 10);
        int max = (int) Math.round(GridLayer.GRID_LINE_SCALE_MAX * 10);
        JHVSlider slider = new JHVSlider(min, max, (int) Math.round(layer.getGridLineScale() * 10));
        slider.addChangeListener(e -> layer.setGridLineScale(slider.getValue() / 10.));
        return slider;
    }

    private static JHVSlider createLabelSizeSlider(GridLayer layer) {
        JHVSlider slider = new JHVSlider((int) GridLayer.GRID_LABEL_SIZE_MIN, (int) GridLayer.GRID_LABEL_SIZE_MAX, (int) Math.round(layer.getGridLabelSize()));
        slider.addChangeListener(e -> layer.setGridLabelSize(slider.getValue()));
        return slider;
    }

    private static JHVSlider createLabelAngleSlider(GridLayer layer) {
        JHVSlider slider = new JHVSlider(0, 360, (int) Math.round(layer.getGridLabelAngle()));
        slider.addChangeListener(e -> layer.setGridLabelAngle(slider.getValue()));
        return slider;
    }

    private JCheckBox createToggle(String text, boolean initialValue, Consumer<Boolean> onChange) {
        JCheckBox checkBox = new JCheckBox(text, initialValue);
        checkBox.setHorizontalTextPosition(SwingConstants.LEFT);
        checkBox.addActionListener(e -> onChange.accept(checkBox.isSelected()));
        return checkBox;
    }

    private JComboBox<GridType> createGridTypeBox(GridLayer layer) {
        JComboBox<GridType> comboBox = new JComboBox<>(GridType.values());
        comboBox.setSelectedItem(Display.gridType);
        comboBox.addActionListener(e -> {
            GridType gridType = (GridType) Objects.requireNonNull(comboBox.getSelectedItem());
            layer.setGridType(gridType);
        });
        return comboBox;
    }

    private JHVSpinner createGridResolutionSpinner(double initialValue, DoubleConsumer valueSetter) {
        JHVSpinner spinner = new JHVSpinner(initialValue, GridLayer.GRID_STEP_MIN, GridLayer.GRID_STEP_MAX, GridLayer.GRID_STEP);
        spinner.addChangeListener(e -> valueSetter.accept((Double) spinner.getValue()));
        JFormattedTextField textField = ((JHVSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        textField.setFormatterFactory(new TerminatedFormatterFactory("%.1f", "°", GridLayer.GRID_STEP_MIN, GridLayer.GRID_STEP_MAX));
        return spinner;
    }

    private static final class ColorIcon implements Icon {

        private static final int WIDTH = 24;
        private static final int HEIGHT = 12;

        private final Colors.NamedColor color;

        private ColorIcon(Colors.NamedColor _color) {
            color = _color;
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color.awtColor());
            g.fillRect(x, y, WIDTH, HEIGHT);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(x, y, WIDTH - 1, HEIGHT - 1);
        }
    }

    private static final class ColorRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Colors.NamedColor color) {
                label.setIcon(new ColorIcon(color));
                label.setText(color.toString());
            }
            return label;
        }
    }

}
