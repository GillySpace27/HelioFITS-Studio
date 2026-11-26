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
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.CollapsiblePane;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.gui.component.JHVSpinner;
import org.helioviewer.jhv.gui.component.TerminatedFormatterFactory;
import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;


@SuppressWarnings("serial")
public final class GridLayerOptions extends JPanel {

    /**
     * Four collapsed sections rather than one flat list.
     *
     * <p>The grid, the Thomson sphere, the ecliptic plane and the planets are four separate things
     * that happen to be drawn by one layer, and flattening them produced a column of eleven
     * unlabelled sliders where "Line width" and "Thomson line width" sat five rows apart with
     * nothing saying which belonged to what. Each section now carries its own colour, opacity,
     * width and density, so a slider's meaning comes from the header above it.
     *
     * <p>Public, and safe to construct a second time: {@code gui.component.GridPaletteContent}
     * builds its own instance for the floating palette rather than reusing the layer row's (a
     * Swing component has exactly one parent), the same split {@code SequencePaletteContent}
     * makes for the sequence filter. Every widget here binds straight to {@code layer}'s live
     * getters and setters with no other static or shared state, so two instances of the same
     * layer's options coexist without stepping on each other.
     */
    public GridLayerOptions(GridLayer layer) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        // A glyph per section, taken from the icons the toolbar already uses rather than invented:
        // the mesh for the grid, the wireframe solid and the 3-D globe for the two spheres, the
        // position marker for the planets. The ecliptic plane gets none, because nothing in the
        // set means a plane and a wrong glyph is worse than no glyph.
        add(new CollapsiblePane("Grid", gridSection(layer), true, true, Buttons.grid));
        add(new CollapsiblePane("Solar limb", limbSection(layer), false, true));
        add(new CollapsiblePane("Thomson sphere", thomsonSection(layer), false, true, Buttons.projection));
        add(new CollapsiblePane("Celestial sphere", celestialSection(layer), false, true, Buttons.rotate));
        add(new CollapsiblePane("Ecliptic plane", eclipticSection(layer), false, true));
        // The planets moved to the 3D Scene pane (planetControls), beside the other things placed in space.
    }

    private GridLayerOptions() {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
    }

    /** The planet controls on their own, for the 3D Scene pane: same widgets, bound to the same layer. */
    public static JPanel planetControls(GridLayer layer) {
        return new GridLayerOptions().planetSection(layer);
    }

    /**
     * The buoy controls, for a timeline row's options. The buoys belong to the grid layer, which a
     * session restore replaces, so each toggle looks the current one up when it is used.
     */
    public static JPanel buoyControls() {
        return new GridLayerOptions().buoySection();
    }

    private static org.helioviewer.jhv.layers.grid.DataBuoys currentBuoys() {
        for (Layer l : Layers.getLayers())
            if (l instanceof GridLayer g)
                return g.getBuoys();
        return new org.helioviewer.jhv.layers.grid.DataBuoys(); // no grid layer: nothing to drive
    }

    private JPanel buoySection() {
        org.helioviewer.jhv.layers.grid.DataBuoys now = currentBuoys();
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;
        JCheckBox show = createToggle("Show buoys", now.isShow(), v -> { currentBuoys().setShow(v); org.helioviewer.jhv.display.DisplayController.display(); });
        show.setToolTipText("A ring in the 3D scene at the place each timeline row's spacecraft was, in the row's line colour. Pick the spacecraft on the row if it is guessed wrong.");
        panel.add(show, c);
        c.gridy = 1;
        JCheckBox labels = createToggle("Always show names", now.isAlwaysLabels(), v -> { currentBuoys().setAlwaysLabels(v); org.helioviewer.jhv.display.DisplayController.display(); });
        labels.setToolTipText("Off: a short code beside each ring, the full name and the rows it feeds when the pointer is on it.");
        panel.add(labels, c);
        c.gridy = 2;
        JCheckBox halo = createToggle("Halo on spacecraft models", now.isModelHalo(), v -> { currentBuoys().setModelHalo(v); org.helioviewer.jhv.display.DisplayController.display(); });
        halo.setToolTipText("Where a model layer is placed at the spacecraft, a larger ring around the model replaces the small ring. Off: the small ring everywhere.");
        panel.add(halo, c);
        c.gridy = 3;
        JCheckBox trails = createToggle("Trails", now.isTrails(), v -> { currentBuoys().setTrails(v); org.helioviewer.jhv.display.DisplayController.display(); });
        trails.setToolTipText("The spacecraft's path over the movie's time span, in the row's colour.");
        panel.add(trails, c);
        return panel;
    }

    private JPanel gridSection(GridLayer layer) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        c.gridy = 0;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        JCheckBox gridToggle = createToggle("Main grid", layer.isShowGrid(), layer::setShowGrid);
        gridToggle.setToolTipText("The grid itself: latitude and longitude on the Sun, and in the helioradial view the rings and spokes, with their labels. Turning this off leaves the limb, spheres, planets and the rest as they are; the layer's own checkbox still hides everything.");
        panel.add(gridToggle, c);
        c.gridx = 1;
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
        addAdjustmentRow(rows, "Line opacity ", createOpacitySlider(layer.getGridAlpha(), layer::setGridAlpha).animates("grid:" + layer.getId() + "/alpha"), 2);
        addAdjustmentRow(rows, "Label opacity ", createOpacitySlider(layer.getLabelAlpha(), layer::setLabelAlpha).animates("grid:" + layer.getId() + "/labelAlpha"), 3);
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

    private JPanel limbSection(GridLayer layer) {
        JCheckBox toggle = createToggle("Show", layer.isShowLimb(), layer::setShowLimb);
        toggle.setToolTipText("The Sun's outline as seen from the camera: a dashed circle of one solar radius, round from every angle, to show where the Sun is and how big.");
        return surfacePanel(toggle,
                createSurfaceColorBox(layer.getLimbColor(), layer::setLimbColor),
                createOpacitySlider(layer.getLimbAlpha(), layer::setLimbAlpha),
                createScaleSlider(layer.getLimbLineScale(), layer::setLimbLineScale),
                null);
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
        int row = 0;
        addAdjustmentRow(rows, "Opacity ", opacity, row++);
        addAdjustmentRow(rows, "Line width ", lineWidth, row++);
        if (density != null) // the limb is a circle: nothing to make denser
            addAdjustmentRow(rows, "Density ", density, row++);
        if (extra != null)
            addAdjustmentRow(rows, extraLabel, extra, row);

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
        JCheckBox earth = createToggle("Earth", layer.isShowEarth(), layer::setShowEarth);
        earth.setToolTipText("The Earth dot and its name, on their own: independent of the other planets and of the grid. Drawn once, at Earth's place.");
        panel.add(earth, c);
        c.gridx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridwidth = 2;
        JCheckBox follow = createToggle("Follow solar rotation", layer.isPlanetsFollowRotation(), layer::setPlanetsFollowRotation);
        follow.setToolTipText("On: Earth sits on the observer marker and the planets register with the imagery, so a planet bright in a coronagraph frame lands on its marker. Off: an inertial layout where each planet moves at its own orbital rate, good for watching Mercury lap Earth, but nothing lines up with the picture.");
        panel.add(follow, c);
        c.gridwidth = 1;

        JPanel rows = new JPanel(new GridBagLayout());
        addAdjustmentRow(rows, "Color ", createPlanetColorBox(layer), 0);
        JHVSlider size = createScaleSlider(layer.getPlanetScale(), layer::setPlanetScale);
        size.setToolTipText("Size of the planet markers, from a quarter to four times the default");
        addAdjustmentRow(rows, "Marker size ", size, 1);
        addAdjustmentRow(rows, "Orbit opacity ",
                createOpacitySlider(layer.getPlanetOrbitAlpha(), layer::setPlanetOrbitAlpha), 2);

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

    private static JComboBox<Colors> createColorBox(GridLayer layer) {
        JComboBox<Colors> comboBox = new JComboBox<>(Colors.values());
        comboBox.setSelectedItem(layer.getGridColor());
        comboBox.setRenderer(new ColorRenderer());
        comboBox.addActionListener(e -> {
            Colors color = (Colors) Objects.requireNonNull(comboBox.getSelectedItem());
            layer.setGridColor(color);
        });
        return comboBox;
    }

    /** A colour box for a reference surface, which owns its own colour rather than the grid's. */
    private static JComboBox<Colors> createSurfaceColorBox(Colors initial, Consumer<Colors> setter) {
        JComboBox<Colors> comboBox = new JComboBox<>(Colors.values());
        comboBox.setSelectedItem(initial);
        comboBox.setToolTipText("Line colour of this surface");
        comboBox.setRenderer(new ColorRenderer());
        comboBox.addActionListener(e ->
                setter.accept((Colors) Objects.requireNonNull(comboBox.getSelectedItem())));
        return comboBox;
    }

    /** Each planet in its own colour (the first item), or every marker in one. */
    static final String PLANET_OWN_COLORS = "Each its own";

    private static JComboBox<Object> createPlanetColorBox(GridLayer layer) {
        JComboBox<Object> comboBox = new JComboBox<>();
        comboBox.addItem(PLANET_OWN_COLORS);
        for (Colors c : Colors.values())
            comboBox.addItem(c);
        comboBox.setSelectedItem(layer.getPlanetColor() == null ? PLANET_OWN_COLORS : layer.getPlanetColor());
        comboBox.setToolTipText("Colour of the planet markers: each planet's own, or one for all of them");
        comboBox.setRenderer(new ColorRenderer());
        comboBox.addActionListener(e -> layer.setPlanetColor(comboBox.getSelectedItem() instanceof Colors c ? c : null));
        return comboBox;
    }

    /** The viewpoint marker's style and colour on their own, for the Viewpoint palette: same widgets, bound to the same layer. */
    public static JPanel viewpointMarkerControls(GridLayer layer) {
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        addAdjustmentRow(rows, "Viewpoint marker ", createViewpointMarkerBox(layer), 0);
        addAdjustmentRow(rows, "Marker colour ", createViewpointColorBox(layer), 1);
        return rows;
    }

    /** How the observer (the viewpoint, wherever "Drive the viewpoint" has put it) is marked in the 3D scene. */
    private static JComboBox<org.helioviewer.jhv.layers.grid.ViewpointMarker.Style> createViewpointMarkerBox(GridLayer layer) {
        JComboBox<org.helioviewer.jhv.layers.grid.ViewpointMarker.Style> comboBox =
                new JComboBox<>(org.helioviewer.jhv.layers.grid.ViewpointMarker.Style.values());
        comboBox.setSelectedItem(layer.getViewpointMarker());
        comboBox.setToolTipText("The mark at the observer's place in the 3D Helioradial scene, which is where the viewpoint sits: hidden, a dot, a camera or an eye. Separate from the Earth dot.");
        comboBox.addActionListener(e -> layer.setViewpointMarker(
                (org.helioviewer.jhv.layers.grid.ViewpointMarker.Style) Objects.requireNonNull(comboBox.getSelectedItem())));
        return comboBox;
    }

    private static JComboBox<Colors> createViewpointColorBox(GridLayer layer) {
        return createSurfaceColorBox(layer.getViewpointMarkerColor(), layer::setViewpointMarkerColor);
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
        JHVSlider slider = new JHVSlider(min, max, (int) Math.round(layer.getGridLineScale() * 10)).animates("grid:" + layer.getId() + "/lineScale");
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

        private final Colors color;

        private ColorIcon(Colors _color) {
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
            if (value instanceof Colors color) {
                label.setIcon(new ColorIcon(color));
                label.setText(color.toString());
            }
            return label;
        }
    }

}
