package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.SkyProjection;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.layers.ImageLayers;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The Projection palette's controls: the projection radios, Surface, Warp, Crop, Zoom, Disk,
 * Render in 3D with Reset view, and the Observer sky block.
 *
 * <p>Moved out of ToolBar.java unchanged (HS-8). They were instance members of the bar and are
 * instance members of the one ProjectionPaletteContent the bar owns, so nothing about their life
 * changed. ToolBar still owns the Palette ("Projection", a persisted title) and reaches in through
 * build(), modeStateChanged(), clearProjectionItems() and the three syncs its tick calls; this
 * class reaches back through its toolBar and ToolBar.projectionPaletteOpen().
 */
final class ProjectionPaletteContent {

    private static final int POPUP_SLIDER_WIDTH = 120; // ToolBar's value; private here, so never inlined across classes

    private final ToolBar toolBar; // the bar that owns the palette: selectProjection refreshes its toggles

    ProjectionPaletteContent(ToolBar _toolBar) {
        toolBar = _toolBar;
    }

    private final EnumMap<MapMode, javax.swing.JRadioButton> projectionItems = new EnumMap<>(MapMode.class);
    private JHVSlider warpLambdaSlider;
    private JHVSlider warpCropSlider;
    private JLabel warpLambdaValue;
    private JLabel warpCropValue;
    // CME tracking writes lambda / outer radius straight to Display; while it does, we mirror the
    // values into the sliders. Guarded so that programmatic move does not look like a manual one
    // and disengage the very tracking that caused it.
    private boolean syncingFromTracker;

    JPanel build() {
        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.PAGE_AXIS));
        ButtonGroup projectionGroup = new ButtonGroup();
        for (MapMode el : MapMode.values()) {
            // The sky is not one of these. It is the checkbox at the bottom of the palette, applied
            // last, on top of whichever of these is selected: see MapMode.hostsSky.
            if (el == MapMode.ObserverSky)
                continue;
            javax.swing.JRadioButton item = new javax.swing.JRadioButton(el.toString());
            item.setName("projection" + el.name());
            if (el == displayedProjection())
                item.setSelected(true);
            item.addActionListener(e -> selectProjection(el));
            projectionGroup.add(item);
            // A BoxLayout positions each child by its own alignmentX, and JComponent's default is
            // centred. The rows below are panels that stretch to the full width, so only these
            // buttons -- narrow, and each a different width -- were left floating on the centre
            // line with a ragged left edge.
            item.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            content.add(item);
            projectionItems.put(el, item);
        }
        content.add(new javax.swing.JSeparator());
        content.add(createSurfaceModelPanel());
        content.add(createWarpLambdaPanel());
        content.add(createWarpCropPanel());
        content.add(createZoomPanel());
        content.add(createDiskPanel());
        content.add(createHelioradial3DPanel());
        content.add(createSkyPanel()); // last, because it is applied last
        setSkyPanelEnabled(ViewState.getProjection() == MapMode.ObserverSky);
        surfaceModelToggle.setEnabled(ViewState.getProjection().usesSurfaceModel());
        warpLambdaSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
        // The disk scale is a multiplier on the Box-Cox limb anchor, so it has nothing to act on
        // wherever the warp itself does not: same condition, not a similar one.
        diskSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
        warpCropSlider.setEnabled(ViewState.getProjection().usesWarpCrop());
        helioradial3DBox.setEnabled(ViewState.getProjection() == MapMode.Helioradial);
        CMETracker.addSolveListener(this::syncWarpSlidersFromTracker); // follow the tracked knob

        return content;
    }

    /** The projection the radio buttons show: while the sky is on, the one under it. */
    private static MapMode displayedProjection() {
        MapMode projection = ViewState.getProjection();
        return projection == MapMode.ObserverSky ? Display.getSkyBase() : projection;
    }

    // With the sky on, a radio changes what the sky is drawn on top of and the view stays in the
    // sky; one that cannot host it (MapMode.hostsSky) switches the sky off. The base change is
    // deferred through the transition like a projection switch, so the outgoing picture is still
    // there to fade from, and the palette follows once it has been applied.
    private void selectProjection(MapMode el) {
        if (ViewState.getProjection() == MapMode.ObserverSky && el.hostsSky()) {
            org.helioviewer.jhv.display.ProjectionTransition.requestChange(() -> {
                Display.setSkyBase(el);
                toolBar.modeStateChanged(); // the Warp, Crop, Disk and Surface controls follow the base
            });
        } else
            ViewState.setProjection(el);
    }

    // Mirror the knob CME tracking is animating back into its slider, so the readout matches what
    // the projection is actually doing. Both directions go through the same value-to-tick pair the
    // slider itself uses, so the handle and the readout cannot disagree about which end is which.
    private void syncWarpSlidersFromTracker() {
        if (warpLambdaSlider == null || warpCropSlider == null)
            return;
        syncingFromTracker = true;
        try {
            if (CMETracker.getMode() == CMETracker.Mode.WARP) {
                warpLambdaSlider.setValue(warpLambdaToSlider(Display.getWarpLambda()));
                warpLambdaValue.setText(String.format("%.3f", Display.getWarpLambda()));
            } else {
                double radius = Display.getWarpOuterRadius();
                int t = cropRadiusToSlider(radius, Math.max(ImageLayers.getLargestRadialSize(), 2));
                warpCropSlider.setValue(t);
                warpCropValue.setText(t == CROP_SLIDER_AUTO ? "auto" : String.format("%.0f R☉", radius));
            }
        } finally {
            syncingFromTracker = false;
        }
    }

    // Right is a stronger warp, which is right-is-bigger: lambda towards -1 stretches the inner
    // corona outward, so structure near the Sun grows. Lambda runs the other way (1 is the exact
    // identity), hence the sign flip here rather than in Display, which keeps storing the physical
    // lambda so a saved session restores the same picture.
    static double sliderToWarpLambda(int t) {
        return -Math.clamp(t, -1000, 1000) / 1000.;
    }

    static int warpLambdaToSlider(double lambda) {
        return (int) Math.round(-Math.clamp(lambda, -1, 1) * 1000);
    }

    private JPanel createWarpLambdaPanel() {
        warpLambdaSlider = new JHVSlider(-1000, 1000, warpLambdaToSlider(ViewState.getWarpLambda())).animates("display.warpLambda");
        named(warpLambdaSlider, "projectionWarp", "Warp");
        warpLambdaSlider.setToolTipText("Warp strength (Box-Cox lambda) for warp projections: right stretches the inner corona outward, left is the unwarped view (available in Helioradial projections)");
        warpLambdaSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, warpLambdaSlider.getPreferredSize().height));
        JLabel label = new JLabel("Warp");
        warpLambdaValue = new JLabel(String.format("%.3f", ViewState.getWarpLambda()), JLabel.RIGHT);
        warpLambdaValue.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        warpLambdaSlider.readout(warpLambdaValue);
        warpLambdaSlider.addChangeListener(e -> {
            if (!syncingFromTracker && CMETracker.getMode() == CMETracker.Mode.WARP)
                CMETracker.stop(); // a manual move takes the wheel back, but only from the knob tracking drives
            ViewState.setWarpLambda(sliderToWarpLambda(warpLambdaSlider.getValue()));
            warpLambdaValue.setText(String.format("%.3f", ViewState.getWarpLambda()));
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(label, BorderLayout.LINE_START);
        panel.add(warpLambdaSlider, BorderLayout.CENTER);
        panel.add(warpLambdaValue, BorderLayout.LINE_END);
        return panel;
    }

    // ponytail: session-only knob -- not persisted in ViewState; add there if it earns it.
    // Crop: the projection's outer radius as a fraction of the loaded FOV, mapped in log
    // space from the full FOV (far left = auto) to 2 Rsun (far right). A radial crop: a
    // linear zoom-in independent of the lambda warp, tracking layer changes when at auto.
    private javax.swing.JCheckBox helioradial3DBox;

    // Off by default: the flat rendering is what the poster, the paper figures and every
    // screenshot show, so a default install reproduces them. 3D is for exploring.
    // Where a coronagraph line of sight is taken to have originated: a placement assumption, not a
    // measurement (see SurfaceModel), which moves radial positions by 6 to 40 percent across a
    // wide field. It sits here rather than under View because it is a projection choice, and it is
    // the only copy of the control -- two entry points would need syncing, and this one is beside
    // the warp knobs it interacts with.
    private JPanel createSurfaceModelPanel() {
        JToggleButton toggle = new JToggleButton(Display.getSurfaceModel().toString());
        toggle.setSelected(Display.getSurfaceModel() != SurfaceModel.PlaneOfSky);
        surfaceModelToggle = toggle;
        // All labels get the same width, so the button does not resize under the pointer when it
        // flips. That is the whole point of it being one button rather than a list: the surfaces
        // are worth comparing by cycling through them, and that only works if the control stays
        // where your cursor already is. The cycle is plane of sky, Thomson sphere, celestial
        // sphere: the measurement's placement, then that placement projected back out onto the
        // sky it came from.
        toggle.setPreferredSize(surfaceToggleSize(toggle));
        toggle.setName("projectionSurface");
        toggle.addActionListener(e -> {
            SurfaceModel[] cycle = SurfaceModel.values();
            SurfaceModel wanted = cycle[(Display.getSurfaceModel().ordinal() + 1) % cycle.length];
            // The other half of the Location/Thomson exclusivity; see
            // ViewpointLayerOptions.enforceSurfaceExclusivity for why they cannot coexist.
            if (wanted == SurfaceModel.ThomsonSphere
                    && !org.helioviewer.jhv.layers.ViewpointLayerOptions.allowsThomsonSphere()) {
                toggle.setSelected(Display.getSurfaceModel() != SurfaceModel.PlaneOfSky);
                toggle.setText(Display.getSurfaceModel().toString());
                org.helioviewer.jhv.app.Message.warn("Surface model",
                        "The Thomson sphere cannot be used while the active Viewpoint layer is set to a "
                                + "location: that puts the observer inside the field, and the sphere does not "
                                + "reach past the observer. Switch the Viewpoint layer to \"Observer at 1au\" "
                                + "or \"Heliosphere\", or turn the layer off.");
                return;
            }
            Display.setSurfaceModel(wanted);
            toggle.setSelected(wanted != SurfaceModel.PlaneOfSky);
            toggle.setText(wanted.toString());
            DisplayController.display();
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(new JLabel("Surface"), BorderLayout.LINE_START);
        panel.add(toggle, BorderLayout.LINE_END);
        return panel;
    }

    /** Wide enough for whichever surface has the longer name, so the button never moves. */
    private static Dimension surfaceToggleSize(JToggleButton toggle) {
        String was = toggle.getText();
        int width = 0, height = 0;
        for (SurfaceModel model : SurfaceModel.values()) {
            toggle.setText(model.toString());
            Dimension d = toggle.getPreferredSize();
            width = Math.max(width, d.width);
            height = Math.max(height, d.height);
        }
        toggle.setText(was);
        return new Dimension(width, height);
    }

    private JToggleButton surfaceModelToggle;

    /**
     * Say whether the Thomson sphere is currently costing you any of the field, and how much.
     *
     * <p>The mode stays selectable either way: greying it out was tried and locked it off in
     * precisely the wide-field, near-Sun views it is for. So this reports rather than refuses, and
     * rides the palette's existing tick because both inputs move with no event of their own -- the
     * observer distance drifts frame by frame during playback, the field changes as layers load.
     *
     * <p>It also keeps the toggle's own state honest, for the same reason: the surface can be
     * changed from outside this palette, and a toggle that says one thing while the picture shows
     * the other is worse than a stale tooltip.
     */
    void syncSurfaceModelToggle() {
        if (surfaceModelToggle == null)
            return;
        // Ridden on the tick rather than wired to a listener because two independent things enable
        // it, the projection and the 3D checkbox, and the checkbox changes it with no projection
        // change to hang a listener on.
        boolean acts = ViewState.getProjection().usesSurfaceModel();
        if (surfaceModelToggle.isEnabled() != acts)
            surfaceModelToggle.setEnabled(acts);
        if (!acts) {
            surfaceModelToggle.setText(Display.getSurfaceModel().toString());
            surfaceModelToggle.setToolTipText("Where wide-field brightness is placed in depth. Only "
                    + "Helioradial with \"Render in 3D\", or the sky projected over Helioradial, places "
                    + "the imagery on a surface; every other projection reconstructs it per pixel and "
                    + "never consults this.");
            return;
        }

        SurfaceModel current = Display.getSurfaceModel();
        // Something else can move this: a restored session, or the exclusivity rule dropping back
        // to plane of sky when the viewpoint moves inside the field.
        if (surfaceModelToggle.isSelected() != (current != SurfaceModel.PlaneOfSky))
            surfaceModelToggle.setSelected(current != SurfaceModel.PlaneOfSky);
        if (!current.toString().equals(surfaceModelToggle.getText()))
            surfaceModelToggle.setText(current.toString());

        double distance = org.helioviewer.jhv.opengl.GLRenderer.getDisplayedViewpoint().distance;
        double outer = Display.effectiveWarpOuterRadius();
        surfaceModelToggle.setToolTipText(current.canDescribe(distance, outer)
                ? "Where wide-field brightness is placed in depth. Click to cycle: plane of sky, Thomson sphere, celestial sphere."
                : String.format("Where wide-field brightness is placed in depth. The %s reaches only "
                        + "to %.0f R\u2609 here, so the field beyond that (out to %.0f R\u2609) "
                        + "is not shown while it is selected: the model cannot place it at any elongation.",
                        current.toString().toLowerCase(), current.reach(distance), outer));
    }

    private JPanel createHelioradial3DPanel() {
        helioradial3DBox = new javax.swing.JCheckBox("Render in 3D", Display.isHelioradial3D());
        helioradial3DBox.setName("projectionRender3d");
        helioradial3DBox.setToolTipText("Draw Helioradial as a rotatable surface instead of a flat face-on disk");
        // setHelioradial3D does the camera reset itself, the same way a projection change does.
        helioradial3DBox.addItemListener(e -> Display.setHelioradial3D(helioradial3DBox.isSelected()));

        // Puts every control in this palette back to neutral in one press: warp off, crop wide
        // open, magnification 1x. "Warp off" is lambda = 1, NOT the app's start-up lambda of 0
        // -- 0 is the logarithmic member of the family and warps hard; 1 is the exact identity,
        // where the projection reduces to the unwarped view. Resetting to the start-up value
        // would leave the picture visibly warped, which is not what a reset can mean here.
        javax.swing.JButton resetView = new javax.swing.JButton("Reset view");
        resetView.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        resetView.setToolTipText("Return warp, crop and zoom to their defaults");
        resetView.setName("projectionReset");
        resetView.addActionListener(e -> resetProjectionControls());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(helioradial3DBox, BorderLayout.LINE_START);
        panel.add(resetView, BorderLayout.LINE_END);
        return panel;
    }

    // Tracking animates lambda / the crop frame by frame, so it has to let go before the
    // defaults are written or it would overwrite them on the next tick. The state is set
    // directly rather than by moving the sliders, because a slider already sitting at its
    // default fires no change event and would silently skip its half of the reset.
    private void resetProjectionControls() {
        CMETracker.stop();
        ViewState.setWarpLambda(1); // the identity member: no warp at all
        Display.setWarpOuterRadius(0); // auto: the full loaded field
        Display.setDiskScale(Display.DEFAULT_DISK_SCALE); // the shipped default, not the raw anchor
        if (diskSlider != null)
            diskSlider.setValue(diskScaleToSlider(Display.DEFAULT_DISK_SCALE));
        Display.resetViewportZoom();

        syncingFromTracker = true; // the widgets are following state here, not driving it
        try {
            if (warpLambdaSlider != null) {
                warpLambdaSlider.setValue(warpLambdaToSlider(1)); // the identity is the left end now
                warpLambdaValue.setText(String.format("%.3f", 1.));
            }
            if (warpCropSlider != null) {
                warpCropSlider.setValue(CROP_SLIDER_AUTO);
                warpCropValue.setText("auto");
            }
        } finally {
            syncingFromTracker = false;
        }
        syncZoomSliderFromDisplay();
        DisplayController.display();
    }

    // Zoom: the viewport zoom the mouse wheel drives (Viewport.zoom), shown as a magnification
    // rather than as the raw factor, because the raw factor runs the other way -- it multiplies
    // the CAMERA WIDTH, so 2 means half size. Exposed mostly as an indicator: at extreme zoom
    // the imagery degrades, grids crowd and picking drifts, and with the wheel as the only
    // control there was nothing on screen that said how far in or out you actually were.
    private JHVSlider zoomSlider;
    private JLabel zoomValue;
    private boolean syncingZoom;

    private static final double ZOOM_LOG2_RANGE = 6; // 2^-6 .. 2^6, i.e. 1/64x .. 64x, 1x centred

    // Right is bigger, like every other slider in this palette: the right end magnifies, the left
    // end pulls back. Zoom and Crop are the pair that decides how much sky is on screen and they sit
    // one above the other, so a mismatch between them shows up at once as two sliders that undo each
    // other when dragged the same way.
    static double zoomSliderToMagnification(int t) {
        return Math.pow(2, (t / 1000. - 0.5) * 2 * ZOOM_LOG2_RANGE);
    }

    static int magnificationToZoomSlider(double magnification) {
        double t = 1000 * (0.5 + Math.log(magnification) / (Math.log(2) * 2 * ZOOM_LOG2_RANGE));
        return (int) Math.round(Math.clamp(t, 0, 1000));
    }

    private static String formatMagnification(double magnification) {
        return magnification >= 100 || magnification < 0.01
                ? String.format("%.0e×", magnification)
                : String.format(magnification < 10 ? "%.2f×" : "%.1f×", magnification);
    }

    private JHVSlider diskSlider;
    private JLabel diskValue;

    /**
     * The Observer Sky controls: which zenithal projection, how wide a field, and where it is aimed.
     *
     * <p>Grouped in one bordered block rather than added as three more loose sliders, because they
     * only mean anything together and only in one mode. The rest of this palette describes the
     * corona; this block describes where you are standing and which way you are looking.
     */
    private JPanel createSkyPanel() {
        skyProjectionBox = new javax.swing.JComboBox<>(SkyProjection.values());
        skyProjectionBox.setSelectedItem(Display.getSkyProjection());
        skyProjectionBox.setToolTipText(Display.getSkyProjection().tooltip());
        named(skyProjectionBox, "projectionSkyStyle", "Sky projection");
        skyProjectionBox.addActionListener(e -> {
            if (skyProjectionBox.getSelectedItem() instanceof SkyProjection projection) {
                skyProjectionBox.setToolTipText(projection.tooltip());
                // Through the transition rather than straight to Display: switching styles
                // replaces every pixel at once with nothing in motion, which is exactly the change
                // the crossfade exists for. Falls through to an immediate switch when the fade is
                // turned off in Settings.
                org.helioviewer.jhv.display.ProjectionTransition.requestChange(
                        () -> Display.setSkyProjection(projection));
            }
        });
        JPanel projectionRow = new JPanel(new BorderLayout());
        projectionRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        projectionRow.add(new JLabel("Sky"), BorderLayout.LINE_START);
        projectionRow.add(skyProjectionBox, BorderLayout.LINE_END);

        // The switch. A checkbox rather than a radio because the sky is applied LAST, on top of the
        // projection selected above: over Orthographic or HPC it is the sky as it is, over
        // Helioradial it is composed with that mode's radial scale, so the Warp, Crop and Disk
        // sliders and the Surface choice all reach the dome. Unticking returns to that projection.
        skyBox = new javax.swing.JCheckBox("Project onto the sky", ViewState.getProjection() == MapMode.ObserverSky);
        skyBox.setName("projectionSky");
        skyBox.setToolTipText("Draw the selected projection on the observer's sky, aimed and laid flat by the controls "
                + "below. Over Orthographic or HPC that is the sky as it is. Over Helioradial the dome shows the warped "
                + "corona: a dome angle is read as a Helioradial page radius and undone through its Box-Cox scale, so "
                + "the warp shows up as a change of angular scale with the field edge held still, and the Surface choice "
                + "decides where along each line of sight the radius is measured. Not available over Helioradial "
                + "Unrolled or Latitudinal, whose pages are not views of the sky.");
        skyBox.addActionListener(e -> {
            if (skyBox.isSelected()) {
                Display.setSkyBase(ViewState.getProjection());
                ViewState.setProjection(MapMode.ObserverSky);
            } else
                ViewState.setProjection(Display.getSkyBase());
        });
        JPanel composeRow = new JPanel(new BorderLayout());
        composeRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        composeRow.add(skyBox, BorderLayout.LINE_START);

        skyFieldSlider = new JHVSlider(0, 1000, skyFieldToSlider(Display.getSkyFieldDegrees()));
        named(skyFieldSlider, "projectionSkyField", "Sky field");
        skyFieldSlider.setToolTipText("Angular radius of the view, centre of the picture to top edge. "
                + "180\u00b0 is the whole sky, and only azimuthal equidistant reaches it. Double-click to reset.");
        skyFieldSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, skyFieldSlider.getPreferredSize().height));
        skyFieldValue = new JLabel(formatSkyField(Display.getSkyFieldDegrees()), JLabel.RIGHT);
        skyFieldValue.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        skyFieldSlider.addChangeListener(e -> {
            double degrees = sliderToSkyField(skyFieldSlider.getValue());
            Display.setSkyFieldDegrees(degrees);
            skyFieldValue.setText(formatSkyField(degrees));
            DisplayController.display();
        });
        skyFieldSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    skyFieldSlider.setValue(skyFieldToSlider(Display.DEFAULT_SKY_FIELD));
            }
        });
        JPanel fieldRow = new JPanel(new BorderLayout());
        fieldRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        fieldRow.add(new JLabel("Field"), BorderLayout.LINE_START);
        fieldRow.add(skyFieldSlider, BorderLayout.CENTER);
        fieldRow.add(skyFieldValue, BorderLayout.LINE_END);

        skyAimValue = new JLabel(formatSkyAim(), JLabel.RIGHT);
        skyAimValue.setToolTipText("Where the centre of the picture is pointing, as an offset from the Sun. "
                + "Drag in the view to look around.");
        // Round-rect: these two act on the view rather than settling the palette, so they should
        // not read as the affirmative button of a dialog.
        javax.swing.JButton aimAtSun = new javax.swing.JButton("Aim at Sun");
        aimAtSun.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        aimAtSun.setToolTipText("Put the Sun back at the centre of the picture");
        aimAtSun.setName("projectionAimAtSun");
        aimAtSun.addActionListener(e -> {
            Display.resetSkyLook();
            skyAimValue.setText(formatSkyAim());
            DisplayController.display();
        });
        JPanel aimRow = new JPanel(new BorderLayout());
        aimRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        aimRow.add(aimAtSun, BorderLayout.LINE_START);
        aimRow.add(skyAimValue, BorderLayout.LINE_END);

        skyPanel = new JPanel();
        skyPanel.setLayout(new javax.swing.BoxLayout(skyPanel, javax.swing.BoxLayout.PAGE_AXIS));
        skyPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Observer sky"),
                BorderFactory.createEmptyBorder(0, 4, 2, 4)));
        skyPanel.add(composeRow);
        skyPanel.add(projectionRow);
        skyPanel.add(fieldRow);
        skyPanel.add(aimRow);
        return skyPanel;
    }

    private JPanel skyPanel;
    private javax.swing.JCheckBox skyBox;
    private javax.swing.JComboBox<SkyProjection> skyProjectionBox;
    private JHVSlider skyFieldSlider;
    private JLabel skyFieldValue;
    private JLabel skyAimValue;

    // Greyed rather than hidden: the block would otherwise appear and disappear as the projection
    // list is stepped through, and a palette that changes height under the pointer is worse than
    // one with a section that is plainly not in use.
    private void setSkyPanelEnabled(boolean enabled) {
        if (skyPanel == null)
            return;
        skyPanel.setEnabled(enabled);
        for (java.awt.Component row : skyPanel.getComponents()) {
            row.setEnabled(enabled);
            if (row instanceof java.awt.Container container)
                for (java.awt.Component c : container.getComponents())
                    c.setEnabled(enabled);
        }
        // The switch itself is live wherever the sky could be put on: on top of the selected
        // projection, or already on. Greyed only over the two pages that are not views of the sky.
        if (skyBox != null) {
            skyBox.setEnabled(enabled || ViewState.getProjection().hostsSky());
            skyBox.setSelected(enabled);
        }
    }

    // Log-spaced: the useful settings are bunched at the narrow end (a few degrees covers LASCO),
    // while the wide end is one gesture from all-sky.
    static double sliderToSkyField(int value) {
        double t = Math.clamp(value, 0, 1000) / 1000.;
        return Display.SKY_FIELD_MIN * Math.pow(Display.SKY_FIELD_MAX / Display.SKY_FIELD_MIN, t);
    }

    static int skyFieldToSlider(double degrees) {
        double t = Math.log(Math.clamp(degrees, Display.SKY_FIELD_MIN, Display.SKY_FIELD_MAX) / Display.SKY_FIELD_MIN)
                / Math.log(Display.SKY_FIELD_MAX / Display.SKY_FIELD_MIN);
        return (int) Math.round(Math.clamp(t, 0, 1) * 1000);
    }

    private static String formatSkyField(double degrees) {
        return degrees < 10 ? String.format("%.1f\u00b0", degrees) : String.format("%.0f\u00b0", degrees);
    }

    private static String formatSkyAim() {
        double lon = Math.toDegrees(Display.getSkyLookLon());
        double lat = Math.toDegrees(Display.getSkyLookLat());
        if (Math.abs(lon) < 0.05 && Math.abs(lat) < 0.05)
            return "on the Sun";
        return String.format("%+.1f\u00b0, %+.1f\u00b0", lon, lat);
    }

    /**
     * How much of the radial axis the solar disk gets, as a multiple of the nominal Box-Cox
     * anchor, separated from the warp exponent that used to decide it as a side effect.
     *
     * <p>Runs the same way as Warp, Crop and Zoom: further right is a bigger disk, because on those
     * three further right is a tighter field and so a larger apparent size.
     *
     * <p><b>No sentinel, deliberately.</b> A discrete "auto" position adjacent to a continuous
     * range is a discontinuity by construction: one pixel of travel would jump the disk from the
     * nominal share to the top of the range. Making 1.0 an ordinary value on the scale removes the
     * jump entirely, and it costs nothing, because 1.0 IS the automatic behaviour -- the anchor is
     * returned untouched there. Nominal therefore sits near the right rather than at it, about four
     * fifths of the way along, which is where log-spacing puts it between 0.05 and 2.
     *
     * <p>Logarithmic for the usual reason: a multiplier's useful travel is in ratios, so a linear
     * scale would give the whole range below 1.0 a tenth of the track.
     */
    private JPanel createDiskPanel() {
        diskSlider = new JHVSlider(0, 1000, diskScaleToSlider(Display.getDiskScale())).animates("display.diskScale");
        named(diskSlider, "projectionDisk", "Disk");
        diskSlider.setToolTipText("Size of the solar disk as a multiple of the nominal Box-Cox warp: 1.00\u00d7 is the warp untouched, right is bigger, left is smaller. Double-click to return to nominal. (available in Helioradial projections)");
        diskSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, diskSlider.getPreferredSize().height));
        JLabel label = new JLabel("Disk");
        diskValue = new JLabel(formatDiskScale(Display.getDiskScale()), JLabel.RIGHT);
        diskSlider.readout(diskValue);
        diskValue.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        diskSlider.addChangeListener(e -> {
            double scale = sliderToDiskScale(diskSlider.getValue());
            Display.setDiskScale(scale);
            diskValue.setText(formatDiskScale(scale));
        });
        // The same escape hatch the zoom slider offers: nominal is a specific value on a log
        // scale and landing on it by dragging is luck.
        diskSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    diskSlider.setValue(diskScaleToSlider(Display.DEFAULT_DISK_SCALE));
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(label, BorderLayout.LINE_START);
        panel.add(diskSlider, BorderLayout.CENTER);
        panel.add(diskValue, BorderLayout.LINE_END);
        return panel;
    }

    private static String formatDiskScale(double scale) {
        // Nominal is worth naming: it is the one value that leaves the warp exactly as it was.
        return Math.abs(scale - Display.DISK_SCALE_NOMINAL) < 5e-3
                ? "nominal" : String.format("%.2f\u00d7", scale);
    }

    // Log-spaced, MAX at the right so the disk grows rightward like Warp, Crop and Zoom.
    static double sliderToDiskScale(int value) {
        double t = Math.clamp(value, 0, 1000) / 1000.;
        return Display.DISK_SCALE_MIN * Math.pow(Display.DISK_SCALE_MAX / Display.DISK_SCALE_MIN, t);
    }

    static int diskScaleToSlider(double scale) {
        double t = Math.log(Math.clamp(scale, Display.DISK_SCALE_MIN, Display.DISK_SCALE_MAX) / Display.DISK_SCALE_MIN)
                / Math.log(Display.DISK_SCALE_MAX / Display.DISK_SCALE_MIN);
        return (int) Math.round(Math.clamp(t, 0, 1) * 1000);
    }

    private JPanel createZoomPanel() {
        zoomSlider = new JHVSlider(0, 1000, 500);
        named(zoomSlider, "projectionZoom", "Zoom");
        zoomSlider.setToolTipText("View magnification, running the same way as Crop: right magnifies, left pulls back. Far from 1× is where imagery softens and overlays crowd; double-click to recentre");
        zoomSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, zoomSlider.getPreferredSize().height));
        JLabel label = new JLabel("Zoom");
        zoomValue = new JLabel("1.00×", JLabel.RIGHT);
        zoomValue.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        zoomSlider.addChangeListener(e -> {
            if (syncingZoom)
                return;
            double zoom = 1 / zoomSliderToMagnification(zoomSlider.getValue());
            // Mirrors Zoom.zoom's fan-out: one viewport when they zoom separately, else all.
            if (Display.separateViewportZoom) {
                Display.getActiveViewport().zoom = zoom;
            } else {
                for (org.helioviewer.jhv.display.Viewport viewport : Display.getViewports())
                    viewport.zoom = zoom;
            }
            zoomValue.setText(formatMagnification(1 / zoom));
            DisplayController.display();
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(label, BorderLayout.LINE_START);
        panel.add(zoomSlider, BorderLayout.CENTER);
        panel.add(zoomValue, BorderLayout.LINE_END);
        return panel;
    }

    // Off-scale zooms (the wheel is unbounded, this slider is not) park the handle at the end
    // and let the number keep telling the truth.
    void syncZoomSliderFromDisplay() {
        if (zoomSlider == null || !ToolBar.projectionPaletteOpen())
            return;
        double zoom = Display.getActiveViewport().zoom;
        if (zoom <= 0)
            return;
        double magnification = 1 / zoom;
        syncingZoom = true;
        try {
            int t = magnificationToZoomSlider(magnification);
            if (zoomSlider.getValue() != t)
                zoomSlider.setValue(t);
            String text = formatMagnification(magnification);
            if (!text.equals(zoomValue.getText()))
                zoomValue.setText(text);
        } finally {
            syncingZoom = false;
        }
    }

    // Auto (no crop) sits at the LEFT end, because tightening the crop magnifies and this palette
    // runs right-is-bigger throughout. The sentinel is at the wide end of the continuous range, so
    // the tick beside it is the full field and there is no jump across it.
    static final int CROP_SLIDER_AUTO = 0;

    /** Slider tick to crop radius in solar radii, {@code full} being the loaded field; 0 means auto. */
    static double sliderToCropRadius(int t, double full) {
        if (t <= CROP_SLIDER_AUTO)
            return 0;
        return 2 * Math.pow(full / 2, 1 - Math.clamp(t, 0, 1000) / 1000.);
    }

    /** Inverse of {@link #sliderToCropRadius}, so a radius set elsewhere lands the handle on it. */
    static int cropRadiusToSlider(double radius, double full) {
        if (radius <= 0 || full <= 2)
            return CROP_SLIDER_AUTO;
        double t = 1000 * (1 - Math.log(Math.max(radius, 2) / 2) / Math.log(full / 2));
        return (int) Math.round(Math.clamp(t, 0, 1000));
    }

    private JPanel createWarpCropPanel() {
        warpCropSlider = new JHVSlider(0, 1000, CROP_SLIDER_AUTO).animates("display.warpOuterRadius");
        named(warpCropSlider, "projectionCrop", "Crop");
        warpCropSlider.setToolTipText("Circular crop, in solar radii: cuts the picture to a disc and frames that disc, without changing the warp. Zoom magnifies with no edge; leftmost is auto, no crop.");
        warpCropSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, warpCropSlider.getPreferredSize().height));
        JLabel label = new JLabel("Crop");
        warpCropValue = new JLabel("auto", JLabel.RIGHT);
        warpCropSlider.readout(warpCropValue);
        JLabel value = warpCropValue;
        value.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        warpCropSlider.addChangeListener(e -> {
            if (!syncingFromTracker && CMETracker.getMode() == CMETracker.Mode.CROP)
                CMETracker.stop(); // crop-mode tracking owns this slider; a manual move takes it back
            double radius = sliderToCropRadius(warpCropSlider.getValue(), Math.max(ImageLayers.getLargestRadialSize(), 2));
            Display.setWarpOuterRadius(radius); // 0 is auto: the full loaded FOV
            value.setText(radius <= 0 ? "auto" : String.format("%.0f R\u2609", radius));
            DisplayController.display();
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(label, BorderLayout.LINE_START);
        panel.add(warpCropSlider, BorderLayout.CENTER);
        panel.add(value, BorderLayout.LINE_END);
        return panel;
    }

    // A control's name for ActionCatalog.find and tours, and the label VoiceOver reads when it has no text of its own.
    private static void named(javax.swing.JComponent c, String id, String spoken) {
        c.setName(id);
        String now = c.getAccessibleContext().getAccessibleName();
        if (now == null || now.isEmpty())
            c.getAccessibleContext().setAccessibleName(spoken);
    }

    /** ToolBar.modeStateChanged's projection half, unchanged, called from there at the same point. */
    void modeStateChanged() {
        javax.swing.JRadioButton activeProjection = projectionItems.get(displayedProjection());
        if (activeProjection != null)
            activeProjection.setSelected(true);
        if (warpLambdaSlider != null) {
            warpLambdaSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
            if (warpCropSlider != null)
                warpCropSlider.setEnabled(ViewState.getProjection().usesWarpCrop());
            warpLambdaSlider.setValue(warpLambdaToSlider(ViewState.getWarpLambda()));
        }
        if (diskSlider != null)
            diskSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
        // Enabled state has to be refreshed on every projection change, not just set once when
        // the palette is built: a palette constructed while another projection was selected
        // would otherwise stay disabled for the life of the window.
        if (helioradial3DBox != null) {
            helioradial3DBox.setEnabled(ViewState.getProjection() == MapMode.Helioradial);
            helioradial3DBox.setSelected(Display.isHelioradial3D());
        }
        setSkyPanelEnabled(ViewState.getProjection() == MapMode.ObserverSky);
        if (warpLambdaValue != null)
            warpLambdaValue.setText(String.format("%.3f", ViewState.getWarpLambda()));
    }

    /** ToolBar.createNewToolBar forgot the radios on every rebuild of the bar; it still does, through here. */
    void clearProjectionItems() {
        projectionItems.clear();
    }

    /** The aim moves by dragging in the view, which this palette never hears about (ToolBar.paletteTick). */
    void syncSkyAim() {
        if (skyAimValue != null && ToolBar.projectionPaletteOpen())
            skyAimValue.setText(formatSkyAim());
    }
}
