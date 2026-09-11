package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
//import java.util.LinkedHashMap;
//import java.util.Map;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.JToolBar;

import org.helioviewer.jhv.annotation.AnnotationMode;
import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.SkyProjection;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.display.interaction.Interaction;
import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.io.samp.SampClient;
import org.helioviewer.jhv.layers.ImageLayers;
//import org.helioviewer.jhv.timelines.band.HapiReader;

import com.formdev.flatlaf.FlatClientProperties;

@SuppressWarnings("serial")
public final class ToolBar extends JToolBar implements ViewState.ModeListener {

    private static final int ZOOM_HOLD_REPEAT_MS = 33;
    private static final int POPUP_SLIDER_WIDTH = 120;

    private static DisplayMode displayMode = DisplayMode.ICONANDTEXT;

    private enum DisplayMode {
        ICONANDTEXT, ICONONLY
    }

    private record ButtonText(Icon icon, String text, String tip) {}

    // Icon over label, or the icon on its own: one button either way, so the display-mode switch
    // is now a matter of taking the text away rather than handing the button a different string
    // of HTML with a line break in it.
    private static void dress(AbstractButton b, ButtonText text) {
        b.setIcon(text.icon());
        b.setText(displayMode == DisplayMode.ICONONLY ? null : text.text());
        b.setHorizontalTextPosition(SwingConstants.CENTER);
        b.setVerticalTextPosition(SwingConstants.BOTTOM);
        b.setToolTipText(text.tip());
    }

    private final ButtonText AXIS = new ButtonText(Buttons.axis, "Axis", "Axis");
    private final ButtonText DIFFROTATION = new ButtonText(Buttons.diffRotation, "Differential", "Toggle differential rotation");
    private final ButtonText MULTIVIEW = new ButtonText(Buttons.multiview, "Multiview", "Multiview");
    private final ButtonText OFFDISK = new ButtonText(Buttons.offDisk, "Corona", "Toggle off-disk corona");
    private final ButtonText PAN = new ButtonText(Buttons.pan, "Pan", "Pan");
    private final ButtonText PROJECTION = new ButtonText(Buttons.projection, "Projection", "Projection");
    private final ButtonText COLOUR = new ButtonText(Buttons.colourSettings, "HDR", "How the whole view is mapped into the display's extended range: headroom, mapping, knee, in-range share, clipped pixels");
    private final ButtonText SEQUENCE = new ButtonText(Buttons.sequenceFilter, "Fourier", "Fourier filter over the whole movie: pick the layer, drag a band, watch it play");
    private final ButtonText GRID = new ButtonText(Buttons.grid, "Grid", "Grid, Thomson sphere, celestial sphere, ecliptic and planet overlay settings");
    private final ButtonText CAMERA = new ButtonText(Buttons.camera, "Camera", "Where the view is seen from: Free, Follow, Turntable, Overview, and their settings");
    private final ButtonText MORE = new ButtonText(Buttons.moreSettings, "More", "Less common controls: annotation, automatic refresh, the SDO cut-out, SAMP");
    private final ButtonText PRESENTATION = new ButtonText(Buttons.presentation, "Present", "Presentation mode: output only, fullscreen (Esc to leave)");
    private final ButtonText REFRESH = new ButtonText(Buttons.refresh, "Refresh", "Automatic refresh");
    private final ButtonText RESETCAMERA = new ButtonText(Buttons.resetCamera, "Reset View", "Reset view to default");
    private final ButtonText RESETCAMERAAXIS = new ButtonText(Buttons.resetCameraAxis, "Reset Axis", "Reset view axis");
    private final ButtonText ROTATE = new ButtonText(Buttons.rotate, "Rotate", "Rotate");
    private final ButtonText ROTATE90 = new ButtonText(Buttons.rotate90, "Rotate View 90°", "Rotate view 90°");
    private final ButtonText SAMP = new ButtonText(Buttons.samp, "SAMP", "Send SAMP message");
    private final ButtonText TRACK = new ButtonText(Buttons.track, "Track", "Track solar rotation");
    private final ButtonText ZOOMFIT = new ButtonText(Buttons.zoomFit, "Zoom-Fit", "Zoom to fit");
    private final ButtonText ZOOMIN = new ButtonText(Buttons.zoomIn, "Zoom In", "Zoom in");
    private final ButtonText ZOOMONE = new ButtonText(Buttons.zoomOne, "Actual Size", "Zoom to native resolution");
    private final ButtonText ZOOMOUT = new ButtonText(Buttons.zoomOut, "Zoom Out", "Zoom out");

//  private final LinkedHashMap<ButtonText, ActionListener> pluginButtons = new LinkedHashMap<>();

    private static JButton toolButton(ButtonText text) {
        JButton b = Buttons.flat((String) null);
        dress(b, text);
        return b;
    }

    private static SplitButton toolSplitButton(ButtonText text) {
        SplitButton b = new SplitButton((String) null);
        b.dress(text.icon(), displayMode == DisplayMode.ICONONLY ? null : text.text());
        b.setToolTipText(text.tip);
        b.setAlwaysDropdown(true);
        return b;
    }

    private static JToggleButton toolToggleButton(ButtonText text) {
        JToggleButton b = Buttons.flatToggle((String) null);
        dress(b, text);
        return b;
    }

    public ToolBar() {
        setLayout(new FlowLayout(FlowLayout.LEADING, 1, 3));
        UIGlobals.themed(this, c -> c.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIGlobals.separator())));
        setRollover(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });

        try {
            displayMode = DisplayMode.valueOf(Settings.getProperty("display.toolbar").toUpperCase());
        } catch (Exception ignore) {}
        setDisplayMode(displayMode);
        ViewState.addModeListener(this);
        org.helioviewer.jhv.gui.UITimer.register(this::paletteTick);
    }

    private JToggleButton coronaButton;
    private JToggleButton diffRotationButton;
    private JToggleButton multiviewButton;
    private final EnumMap<AnnotationMode, JRadioButtonMenuItem> annotationItems = new EnumMap<>(AnnotationMode.class);
    private final EnumMap<MapMode, javax.swing.JRadioButton> projectionItems = new EnumMap<>(MapMode.class);
    private JHVSlider warpLambdaSlider;
    private JHVSlider warpCropSlider;
    private JLabel warpLambdaValue;
    private JLabel warpCropValue;
    // CME tracking writes lambda / outer radius straight to Display; while it does, we mirror the
    // values into the sliders. Guarded so that programmatic move does not look like a manual one
    // and disengage the very tracking that caused it.
    private boolean syncingFromTracker;
    private JCheckBoxMenuItem refreshItem;
    private JToggleButton trackingButton;

    // --- which tools are on the bar, and in what order ----------------------------------------
    // Every control is BUILT every time, and only the chosen ones are added. That is the whole
    // trick: the buttons carry live wiring (a shared ButtonGroup for the interaction modes, the
    // palette bindings, the fields modeStateChanged() writes into), so a control left off the bar
    // has to exist anyway or hiding one would break the ones that stayed. Hidden simply means not
    // added here; the Tools menu adopts the very same component, which is why a toggle in that
    // menu still shows its pressed state.
    static final String SEPARATOR = "---"; // a gap, not a control: allowed more than once
    static final String ORDER_KEY = "ui.toolbar.order";

    /** One customisable place on the bar: a stable id, how it looks in the editor, and the control. */
    public record Tool(String id, String label, Icon icon, String tip, JComponent comp) {}

    private final java.util.LinkedHashMap<String, Tool> built = new java.util.LinkedHashMap<>();

    // The bar as it has always looked, and the fallback whenever the stored order is missing or
    // has rotted. Ids are persisted, so they are API: rename one and a saved bar loses that tool.
    static final String DEFAULT_ORDER = String.join("|",
            "present", SEPARATOR,
            "zoomIn", "zoomOut", "zoomFit", "zoomOne", SEPARATOR,
            "resetCamera", "resetAxis", "rotate90", SEPARATOR,
            "pan", "rotate", "axis", SEPARATOR,
            "track", "diffRotation", "corona", "multiview", SEPARATOR,
            "projection", "colour", "sequence", "grid", "camera", SEPARATOR,
            "more");

    /** Build a control and record it under an id, without deciding yet whether it is shown. */
    private void register(String id, ButtonText text, JComponent comp) {
        built.put(id, new Tool(id, text.text(), text.icon(), text.tip(), comp));
    }

    /**
     * The stored order, dropped down to ids that still exist.
     *
     * <p>Edit used to be appended here when missing, because it is the way back and a bar you can
     * customise into a state with no way to customise it again is a trap. It is not a tool any
     * more: it is a fixed control in the bar's trailing corner, which closes that trap outright
     * rather than by patching every saved order. An "edit" left in an older settings file is
     * simply an id that no longer exists, and is dropped like any other.
     */
    static java.util.List<String> order(java.util.Set<String> known) {
        return resolveOrder(Settings.getProperty(ORDER_KEY), known);
    }

    /** The same, with the stored string handed in: pure, so ToolbarOrderCheck can pin the rules. */
    static java.util.List<String> resolveOrder(@javax.annotation.Nullable String stored, java.util.Set<String> known) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String id : (stored == null || stored.isBlank() ? DEFAULT_ORDER : stored).split("\\|"))
            if (SEPARATOR.equals(id) || known.isEmpty() || known.contains(id))
                ids.add(id);
        return ids;
    }

    static void setOrder(java.util.List<String> ids) {
        Settings.setProperty(ORDER_KEY, String.join("|", ids));
        if (current != null)
            current.recreate();
    }

    static void resetOrder() {
        Settings.setProperty(ORDER_KEY, DEFAULT_ORDER);
        if (current != null)
            current.recreate();
    }

    /** Every control that exists, in the order the bar was built, for the editor to list. */
    public static java.util.List<Tool> allTools() {
        return current == null ? java.util.List.of() : java.util.List.copyOf(current.built.values());
    }

    /** The ids laid out on the bar right now, separators aside. */
    public static java.util.Set<String> shownIds() {
        return current == null ? java.util.Set.of() : onBar(order(current.built.keySet()));
    }

    /**
     * The controls an order puts on the bar, out of the ids handed in.
     *
     * <p>With {@link #missing} this is a partition, and it has to be: the Tools menu lists every
     * tool once, taking the ones on the bar as items that click them and the rest as themselves.
     * A tool in neither set would vanish from both the bar and the menu; one in both would be
     * listed twice, and the second copy would steal the control out of the first. Pure, so
     * ToolbarOrderCheck can hold the two sides against each other.
     */
    static java.util.Set<String> onBar(java.util.List<String> order) {
        java.util.Set<String> ids = new java.util.HashSet<>(order);
        ids.remove(SEPARATOR);
        return ids;
    }

    /** The other half: what exists and the order leaves off. */
    static java.util.List<String> missing(java.util.List<String> order, java.util.Collection<String> known) {
        java.util.Set<String> shown = onBar(order);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : known)
            if (!shown.contains(id))
                out.add(id);
        return out;
    }

    /** The controls that exist but are not on the bar, which are the ones the menu itself holds. */
    public static java.util.List<Tool> hiddenTools() {
        if (current == null)
            return java.util.List.of();
        java.util.List<Tool> hidden = new java.util.ArrayList<>();
        for (String id : missing(order(current.built.keySet()), current.built.keySet()))
            hidden.add(current.built.get(id));
        return hidden;
    }

    /** Add the chosen tools, in the chosen order. Everything else stays built and unparented. */
    private void layOutTools(Dimension dim) {
        for (String id : order(built.keySet())) {
            if (SEPARATOR.equals(id)) {
                addSeparator(dim);
                continue;
            }
            Tool tool = built.get(id);
            if (tool != null)
                addButton(tool.comp());
        }
    }

    // --- overflow ----------------------------------------------------------------------------
    // A toolbar narrower than its contents used to just clip whatever did not fit, with no way
    // to reach it: the buttons were still there, laid out past the right edge and invisible.
    // That is only more likely now, since the presenter window is a third of a screen wide.
    // Everything that does not fit moves into a chevron menu at the right-hand end instead.
    private final java.util.List<Component> items = new java.util.ArrayList<>();
    private final java.util.List<Component> overflowed = new java.util.ArrayList<>();
    private JButton overflowButton;
    private JButton editCorner; // permanent, in the trailing corner, never part of the order
    private JPopupMenu overflowPopup;
    private JPanel overflowPanel;
    // While the menu is open its buttons are parented to it rather than to the toolbar, so
    // re-running the fit calculation would see them missing and "fit" everything. Freeze it.
    private boolean overflowOpen;

    private void createNewToolBar() {
        current = this;
        built.clear();
        annotationItems.clear();
        projectionItems.clear();
        if (Platform.isMacOS()) {
            // The window has full-window content and a transparent title bar, so the traffic
            // lights sit on top of this bar and something has to hold their width. It used to be
            // a 90 pixel strut, which is a guess: the real width depends on the buttons' spacing
            // (this window asks for medium) and it is zero in full screen, where they are gone.
            // A FlatLaf placeholder panel asks macOS for the actual bounds instead.
            JPanel placeholder = new JPanel();
            // "horizontal": reserve the width and no height, as the strut did, so the bar's
            // preferred height still comes from the buttons on it.
            placeholder.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac horizontal");
            add(placeholder, 0);
        }

        Interaction.Mode interactionMode = InputController.getMode();
        try {
            interactionMode = Interaction.Mode.valueOf(Settings.getProperty("display.interaction").toUpperCase());
        } catch (Exception ignore) {}

        Dimension dim = new Dimension(32, 32);

        // First on the bar, and first for a reason: the overflow chevron drops buttons from the
        // right as the window narrows, so the leftmost place is the one that can never be taken.
        // Leaving a talk to hunt for Present in an overflow menu is the failure this avoids.
        JToggleButton presentationButton = toolToggleButton(PRESENTATION);
        presentationToggle = presentationButton;
        presentationButton.setSelected(org.helioviewer.jhv.gui.PresentationMode.isActive());
        presentationButton.addActionListener(e -> {
            // The button's own selected state has already flipped; drive the mode from what it
            // now says, so a stale state (toolbar rebuilt while presenting) cannot invert it.
            if (presentationButton.isSelected() != org.helioviewer.jhv.gui.PresentationMode.isActive())
                org.helioviewer.jhv.gui.PresentationMode.toggle();
        });
        register("present", PRESENTATION, presentationButton);

        // Zoom
        JButton zoomIn = toolButton(ZOOMIN);
        zoomIn.addActionListener(new Actions.ZoomIn());
        HoldRepeat.install(zoomIn, ZOOM_HOLD_REPEAT_MS);
        JButton zoomOut = toolButton(ZOOMOUT);
        zoomOut.addActionListener(new Actions.ZoomOut());
        HoldRepeat.install(zoomOut, ZOOM_HOLD_REPEAT_MS);
        JButton zoomFit = toolButton(ZOOMFIT);
        zoomFit.addActionListener(new Actions.ZoomFit());
        JButton zoomOne = toolButton(ZOOMONE);
        zoomOne.addActionListener(new Actions.ZoomOneToOne());
        JButton resetCamera = toolButton(RESETCAMERA);
        resetCamera.addActionListener(new Actions.ResetCamera());
        JButton resetCameraAxis = toolButton(RESETCAMERAAXIS);
        resetCameraAxis.addActionListener(new Actions.ResetCameraAxis());

        SplitButton rotate90Button = toolSplitButton(ROTATE90);
        rotate90Button.addItem(new Actions.Rotate90Camera("X Axis", "X"));
        rotate90Button.addItem(new Actions.Rotate90Camera("Y Axis", "Y"));
        rotate90Button.addItem(new Actions.Rotate90Camera("Z Axis", "Z"));

        register("zoomIn", ZOOMIN, zoomIn);
        register("zoomOut", ZOOMOUT, zoomOut);
        register("zoomFit", ZOOMFIT, zoomFit);
        register("zoomOne", ZOOMONE, zoomOne);
        register("resetCamera", RESETCAMERA, resetCamera);
        register("resetAxis", RESETCAMERAAXIS, resetCameraAxis);
        register("rotate90", ROTATE90, rotate90Button);

        // Interaction
        ButtonGroup group = new ButtonGroup();

        JToggleButton pan = toolToggleButton(PAN);
        pan.addActionListener(e -> InputController.setMode(Interaction.Mode.PAN));
        JToggleButton rotate = toolToggleButton(ROTATE);
        rotate.addActionListener(e -> InputController.setMode(Interaction.Mode.ROTATE));
        JToggleButton axis = toolToggleButton(AXIS);
        axis.addActionListener(e -> InputController.setMode(Interaction.Mode.AXIS));

        group.add(pan);
        group.add(rotate);
        group.add(axis);

        register("pan", PAN, pan);
        register("rotate", ROTATE, rotate);
        register("axis", AXIS, axis);

        if (interactionMode == Interaction.Mode.ZOOM) // only ever momentary; never a remembered choice
            interactionMode = Interaction.Mode.ROTATE;
        switch (interactionMode) {
            case PAN -> pan.setSelected(true);
            case AXIS -> axis.setSelected(true);
            case ROTATE -> rotate.setSelected(true);
            case ZOOM -> {}
        }
        InputController.setMode(interactionMode);

        // The mode in effect, momentary ones included, shows in the toggles and in the pointer:
        // holding Option should look like having pressed Rotate, and letting go should look like
        // letting go. Registered from the toolbar because the toolbar owns the toggles.
        InputController.setModeListener(effective -> {
            switch (effective) {
                case PAN -> pan.setSelected(true);
                case ROTATE -> rotate.setSelected(true);
                case AXIS -> axis.setSelected(true);
                case ZOOM -> group.clearSelection();
            }
            java.awt.Component view = org.helioviewer.jhv.gui.MainFrame.getRenderComponent();
            if (view != null)
                view.setCursor(java.awt.Cursor.getPredefinedCursor(switch (effective) {
                    case PAN -> java.awt.Cursor.MOVE_CURSOR;
                    case ROTATE -> java.awt.Cursor.DEFAULT_CURSOR;
                    case AXIS -> java.awt.Cursor.CROSSHAIR_CURSOR;
                    case ZOOM -> java.awt.Cursor.N_RESIZE_CURSOR;
                }));
        });

        trackingButton = toolToggleButton(TRACK);
        trackingButton.setSelected(ViewState.isTracking());
        trackingButton.addItemListener(e -> ViewState.setTracking(trackingButton.isSelected()));

        diffRotationButton = toolToggleButton(DIFFROTATION);
        diffRotationButton.setSelected(ViewState.isDifferentialRotation());
        diffRotationButton.addItemListener(e -> ViewState.setDifferentialRotation(diffRotationButton.isSelected()));

        coronaButton = toolToggleButton(OFFDISK);
        coronaButton.setSelected(ViewState.isShowCorona());
        coronaButton.addItemListener(e -> ViewState.setShowCorona(coronaButton.isSelected()));

        multiviewButton = toolToggleButton(MULTIVIEW);
        multiviewButton.setSelected(ViewState.isMultiview());
        multiviewButton.addItemListener(e -> ViewState.setMultiview(multiviewButton.isSelected()));

        register("track", TRACK, trackingButton);
        register("diffRotation", DIFFROTATION, diffRotationButton);
        register("corona", OFFDISK, coronaButton);
        register("multiview", MULTIVIEW, multiviewButton);

        // The projection controls live in a persistent palette, not a dropdown: it survives
        // focus loss (so the sliders can be worked against the view) and only collapses when
        // the toolbar button is toggled again or its window is closed.
        JToggleButton projectionButton = toolToggleButton(PROJECTION);
        projectionPalette.bind(projectionButton);
        register("projection", PROJECTION, projectionButton);

        // Colour settings are per view, not per layer: they decide how every frame of every movie
        // is shown, so they belong beside Projection rather than inside a layer's own row.
        JToggleButton colourButton = toolToggleButton(COLOUR);
        colourPalette.bind(colourButton);
        register("colour", COLOUR, colourButton);

        // The sequence filter is a whole-movie computation with a lot of settings and a readout
        // worth watching while the view plays, which is what the palette form is for. It acts on
        // one layer, which the palette itself now lets you pick (SequencePaletteContent's own
        // combo), so a button here reads as "open the Fourier filter", not as "filter everything":
        // Apply still only ever reaches the layer the palette is bound to.
        JToggleButton sequenceButton = toolToggleButton(SEQUENCE);
        if (sequencePalette == null)
            sequencePalette = new Palette("Fourier filter", SequencePaletteContent::build, SequencePaletteContent::refresh, true); // has text fields
        sequencePalette.bind(sequenceButton);
        register("sequence", SEQUENCE, sequenceButton);

        // The grid, Thomson sphere, celestial sphere, ecliptic and planets are one default layer's
        // settings, reachable before only by opening its row in the layer list. A button beside
        // the other view-wide palettes is the more discoverable route; the row keeps working too.
        JToggleButton gridButton = toolToggleButton(GRID);
        if (gridPalette == null)
            gridPalette = new Palette("Grid", GridPaletteContent::build, GridPaletteContent::refresh);
        gridPalette.bind(gridButton);
        register("grid", GRID, gridButton);

        // The camera behaviours are the Viewpoint layer's options, the sidebar's Camera section.
        // Same move as the grid: the palette is the one home, the row points at it.
        JToggleButton cameraButton = toolToggleButton(CAMERA);
        if (cameraPalette == null)
            cameraPalette = new Palette("Camera", CameraPaletteContent::build, CameraPaletteContent::refresh, true); // has text fields
        cameraPalette.bind(cameraButton);
        register("camera", CAMERA, cameraButton);

        // Everything reached once a session rather than once a minute, plus annotation, behind
        // one button. Annotation used to have its own top-level button; it is a mode you set once
        // and then draw in, not a control worked against the view while watching it (the thing
        // that earns a place of its own on this bar), so it folded in here with the rest.
        //
        // As its own submenu, not poured into this one. Annotation is eight items, a colour strip
        // and a slider; flattened into More they were most of the menu and the three things More
        // is actually for sat under them. A submenu keeps More a short list of destinations.
        SplitButton more = toolSplitButton(MORE);
        JMenu annotation = new JMenu("Annotation");
        annotation.setIcon(Buttons.annotate);
        annotation.setToolTipText("Annotation (Press Shift to draw)");
        ButtonGroup annotationGroup = new ButtonGroup();
        for (AnnotationMode mode : AnnotationMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(mode.toString());
            if (mode == ViewState.getAnnotationMode())
                item.setSelected(true);
            item.addActionListener(e -> ViewState.setAnnotationMode(mode));
            annotationGroup.add(item);
            annotation.add(item);
            annotationItems.put(mode, item);
        }
        annotation.addSeparator();
        addAnnotationColorItems(annotation);
        annotation.add(createAnnotationThicknessPanel());
        annotation.addSeparator();
        annotation.add(new Actions.ClearAnnotations());
        annotation.addSeparator();
        annotation.add(new Actions.ZoomFOVAnnotation());
        more.addItem(annotation);
        more.addItemSeparator();
        refreshItem = new JCheckBoxMenuItem(REFRESH.text(), ViewState.isRefresh());
        refreshItem.setToolTipText(REFRESH.tip());
        refreshItem.addItemListener(e -> ViewState.setRefresh(refreshItem.isSelected()));
        more.addItem(refreshItem);
        more.addItemSeparator();
        more.addItem(new Actions.SDOCutOut());
        if (Boolean.parseBoolean(Settings.getProperty("startup.sampHub"))) {
            JMenuItem samp = new JMenuItem(SAMP.text());
            samp.setToolTipText(SAMP.tip());
            samp.addActionListener(e -> SampClient.notifyRequestData());
            more.addItem(samp);
        }
        register("more", MORE, more);

        layOutTools(dim);
/*
        ButtonText hText = new ButtonText("HAPI", "HAPI", "HAPI");
        JButton hButton = toolButton(hText);
        hButton.addActionListener(e -> HapiReader.requestCatalog());
        addButton(hButton);
*/
/*
        for (Map.Entry<ButtonText, ActionListener> entry : pluginButtons.entrySet()) {
            JButton b = toolButton(entry.getKey());
            b.addActionListener(entry.getValue());
            addButton(b);
        }
*/
    }

    // Called once the bar is fully populated: remember the running order, then add the chevron
    // as the one child that is not part of it.
    private void installOverflow() {
        items.clear();
        java.util.Collections.addAll(items, getComponents());

        overflowButton = Buttons.flat(Buttons.overflow);
        overflowButton.setToolTipText("More toolbar controls");
        overflowButton.setFocusPainted(false);
        overflowButton.addActionListener(e -> showOverflow());
        overflowButton.setVisible(false);
        add(overflowButton);

        // Added after the snapshot above, so it is a child of the bar without being part of the
        // running order: doLayout pins it to the trailing corner and the overflow never eats it.
        // That is the point of moving it off the row. As the last tool it was the first thing a
        // narrow window pushed into the chevron, and the way back to the editor is not something
        // to go hunting for in the menu that the editor decides the contents of.
        editCorner = Buttons.flat(Buttons.editToolbarCorner);
        editCorner.setToolTipText("Edit the toolbar: choose which tools are on it, and in what order");
        editCorner.setFocusPainted(false);
        editCorner.addActionListener(e -> ToolbarEditor.open());
        add(editCorner);
    }

    private void showOverflow() {
        if (overflowed.isEmpty())
            return;
        if (overflowPopup == null) {
            overflowPopup = new JPopupMenu();
            overflowPanel = new JPanel();
            overflowPanel.setLayout(new javax.swing.BoxLayout(overflowPanel, javax.swing.BoxLayout.PAGE_AXIS));
            overflowPopup.add(overflowPanel);
            overflowPopup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    // Hand the buttons back on the next tick: moving them out from under a popup
                    // that is still closing leaves Swing repainting a component with no parent.
                    javax.swing.SwingUtilities.invokeLater(ToolBar.this::reclaimOverflow);
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
            });
        }
        overflowOpen = true;
        overflowPanel.removeAll();
        // The real buttons are moved into the menu rather than mirrored by proxy items, so a
        // split button keeps its dropdown and a toggle keeps its pressed state.
        for (Component c : overflowed) {
            remove(c);
            c.setVisible(true);
            if (c instanceof JComponent jc)
                jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            overflowPanel.add(c);
        }
        overflowPopup.pack();
        overflowPopup.show(overflowButton, 0, overflowButton.getHeight());
    }

    private void reclaimOverflow() {
        if (!overflowOpen)
            return;
        overflowOpen = false;
        for (Component c : overflowed) {
            overflowPanel.remove(c);
            add(c);
        }
        revalidate();
        repaint();
    }

    // Lay the bar out by hand: FlowLayout would wrap the surplus onto a second row that the
    // toolbar has no height to show, which is the clipping this replaces.
    @Override
    public void doLayout() {
        // getWidth() is 0 until the first real layout pass; without this every item would
        // "not fit" and the whole bar would collapse into the chevron for a frame.
        if (items.isEmpty() || overflowButton == null || editCorner == null || overflowOpen || getWidth() <= 0) {
            super.doLayout();
            return;
        }
        java.awt.Insets in = getInsets();
        int hgap = 1;
        int avail = getWidth() - in.left - in.right;
        int rowHeight = getHeight() - in.top - in.bottom;

        int total = 0;
        for (Component c : items)
            total += c.getPreferredSize().width + hgap;

        int chevron = overflowButton.getPreferredSize().width;
        int edit = editCorner.getPreferredSize().width;
        // The corner control is always there, so its width is never available to the row.
        avail -= edit + hgap;
        boolean needed = total > avail;
        int limit = needed ? avail - chevron - hgap : avail;

        overflowed.clear();
        int x = in.left;
        for (Component c : items) {
            int cw = c.getPreferredSize().width;
            if (x - in.left + cw <= limit) {
                c.setVisible(true);
                c.setBounds(x, in.top, cw, rowHeight);
                x += cw + hgap;
            } else {
                c.setVisible(false);
                overflowed.add(c);
            }
        }
        int right = getWidth() - in.right;
        editCorner.setBounds(right - edit, in.top, edit, rowHeight);
        overflowButton.setVisible(!overflowed.isEmpty());
        if (!overflowed.isEmpty())
            overflowButton.setBounds(right - edit - hgap - chevron, in.top, chevron, rowHeight);
    }

    // Takes a JComponent rather than an AbstractButton because a split button is now a small
    // panel of two buttons rather than one button of JIDE's.
    private void addButton(JComponent b) {
        if (b instanceof AbstractButton button)
            button.setFocusPainted(false);
        add(b);
    }

    private static void addAnnotationColorItems(JMenu annotationMenu) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 3, 8));
        ButtonGroup colorGroup = new ButtonGroup();
        for (Colors.NamedColor color : Annotations.BASE_COLORS) {
            JToggleButton button = new JToggleButton(new ColorIcon(color.awtColor()));
            button.setSelected(color == Annotations.getBaseColor());
            button.setToolTipText(color.toString());
            button.setFocusPainted(false);
            button.setPreferredSize(new Dimension(22, 22));
            button.addActionListener(e -> Annotations.setBaseColor(color));
            colorGroup.add(button);
            panel.add(button);
        }
        annotationMenu.add(panel);
    }

    // The projection controls live in a persistent palette, not a dropdown: it survives focus
    // loss (so the sliders can be worked against the view) and only collapses when its toolbar
    // button is toggled again. Palette holds the window behaviour; this supplies the controls.
    private static final Palette projectionPalette =
            new Palette("Projection", ToolBar::projectionContent, () -> {});

    private static Palette sequencePalette;
    private static Palette gridPalette;
    private static Palette cameraPalette;

    private static final Palette colourPalette =
            new Palette("HDR", ColourPaletteContent::build, ColourPaletteContent::refresh);

    /** Toggle the HDR palette (used by View > HDR Settings). */
    public static void toggleColourPalette() {
        colourPalette.toggle();
    }

    // Toggle the grid palette the same way (used by View > Grid Settings).
    public static void toggleGridPalette() {
        if (gridPalette != null)
            gridPalette.toggle();
    }

    /** Open or raise the grid palette: the Grid row's "settings" button in the sidebar. */
    public static void showGridPalette() {
        if (gridPalette != null)
            gridPalette.open();
    }

    // Toggle the camera palette the same way (used by View > Camera Settings).
    public static void toggleCameraPalette() {
        if (cameraPalette != null)
            cameraPalette.toggle();
    }

    /** Open or raise the camera palette: the Camera row's "settings" button in the sidebar. */
    public static void showCameraPalette() {
        if (cameraPalette != null)
            cameraPalette.open();
    }

    /** Open or raise the Fourier palette bound to this layer: a layer row's "Open" button. */
    public static void showSequencePalette(org.helioviewer.jhv.layers.ImageLayer layer) {
        SequencePaletteContent.show(layer);
        if (sequencePalette != null)
            sequencePalette.open();
    }

    // Toggle the projection palette exactly as the toolbar button does (used by View > Projection).
    public static void toggleProjectionPalette() {
        projectionPalette.toggle();
    }

    // Toggle the sequence-filter palette the same way (used by View > Sequence Filter).
    public static void toggleSequencePalette() {
        if (sequencePalette != null)
            sequencePalette.toggle();
    }

    private static JToggleButton presentationToggle; // current toolbar's presentation button

    // Toggle presentation mode exactly as the toolbar button does (used by View > Presentation
    // Mode and by Escape). Falls through to the mode directly if the toolbar is mid-recreate, so
    // the Escape route can never be dead.
    public static void togglePresentationMode() {
        if (presentationToggle != null)
            presentationToggle.doClick();
        else
            org.helioviewer.jhv.gui.PresentationMode.toggle();
    }

    // Presentation mode can also be left with Escape, which does not go through the button; keep
    // the button's pressed state honest when that happens.
    public static void syncPresentationToggle() {
        if (presentationToggle != null)
            presentationToggle.setSelected(org.helioviewer.jhv.gui.PresentationMode.isActive());
    }

    // Presentation mode moves the chrome to another screen, and a JDialog cannot be re-owned, so
    // every open palette is rebuilt under the new owner.
    public static void redockProjectionPalette() {
        Palette.rebuildAll();
    }

    /** The projection controls themselves, with no window around them. */
    private static JPanel projectionContent() {
        return current == null ? new JPanel() : current.buildProjectionContent();
    }

    private static ToolBar current;

    private JPanel buildProjectionContent() {
        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.PAGE_AXIS));
        ButtonGroup projectionGroup = new ButtonGroup();
        for (MapMode el : MapMode.values()) {
            // The sky is not one of these. It is the checkbox at the bottom of the palette, applied
            // last, on top of whichever of these is selected: see MapMode.hostsSky.
            if (el == MapMode.ObserverSky)
                continue;
            javax.swing.JRadioButton item = new javax.swing.JRadioButton(el.toString());
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
                modeStateChanged(); // the Warp, Crop, Disk and Surface controls follow the base
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
        warpLambdaSlider.setToolTipText("Warp strength (Box-Cox lambda) for warp projections: right stretches the inner corona outward, left is the unwarped view");
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
    private void syncSurfaceModelToggle() {
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

    private JPanel createDiskPanel() {
        diskSlider = new JHVSlider(0, 1000, diskScaleToSlider(Display.getDiskScale())).animates("display.diskScale");
        diskSlider.setToolTipText("Size of the solar disk as a multiple of the nominal Box-Cox warp: 1.00\u00d7 is the warp untouched, right is bigger, left is smaller. Double-click to return to nominal.");
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

    /**
     * Runs at UITimer's 10 Hz: keeps the palette on screen, then keeps its zoom readout honest.
     *
     * <p>The zoom half is a poll because the mouse wheel writes Viewport.zoom directly with no
     * notification, and polling beats threading a listener through every zoom write site.
     *
     * <p>The visibility half is a watchdog, and deliberately so. The palette kept vanishing, and
     * each time it was traced to a different mechanism hiding it from underneath: first macOS
     * ordering out a Window.Type.UTILITY panel on app deactivate, then the owned-window rules
     * that pull a child down with its parent. Chasing those one at a time meant re-learning the
     * platform's rules for every new way it found to close the thing. This inverts the problem:
     * the toolbar toggle is the single record of whether the user wants the palette open, so
     * anything that hides it while that toggle is still pressed is by definition wrong and is
     * simply undone, whatever did it and for whatever reason. Worst case it costs a flicker;
     * the alternative was a control that silently disappeared mid-adjustment.
     */
    private void paletteTick() {
        syncSurfaceModelToggle();
        // The aim moves by dragging in the view, which this palette never hears about.
        if (skyAimValue != null && projectionPalette.isOpen())
            skyAimValue.setText(formatSkyAim());
        Palette.keepVisible();
        syncZoomSliderFromDisplay();
    }

    // Off-scale zooms (the wheel is unbounded, this slider is not) park the handle at the end
    // and let the number keep telling the truth.
    private void syncZoomSliderFromDisplay() {
        if (zoomSlider == null || !projectionPalette.isOpen())
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
        warpCropSlider.setToolTipText("Circular crop, in solar radii: cuts the picture to a disc without moving the camera or changing the warp. Zoom magnifies instead; leftmost is auto, no crop.");
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

    private static JPanel createAnnotationThicknessPanel() {
        int thickness = Annotations.getThicknessValue();
        JHVSlider slider = new JHVSlider(Annotations.MIN_THICKNESS, Annotations.MAX_THICKNESS, Annotations.DEFAULT_THICKNESS);
        slider.setValue(thickness);
        slider.setMajorTickSpacing(1);
        slider.setSnapToTicks(true);
        slider.setToolTipText("Annotation thickness");
        slider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, slider.getPreferredSize().height));
        slider.addChangeListener(e -> Annotations.setThicknessValue(slider.getValue()));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(slider, BorderLayout.CENTER);
        return panel;
    }

    private static final class ColorIcon implements Icon {

        private static final int SIZE = 12;

        private final Color color;

        private ColorIcon(Color _color) {
            color = _color;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x, y, SIZE, SIZE);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(x, y, SIZE - 1, SIZE - 1);
        }
    }

    private void setDisplayMode(DisplayMode mode) {
        displayMode = mode;
        Settings.setProperty("display.toolbar", mode.toString().toLowerCase());
        recreate();
    }

    private void recreate() {
        overflowOpen = false;
        overflowed.clear();
        removeAll();
        createNewToolBar();
        installOverflow(); // must come last: it snapshots the finished running order
        revalidate();
        repaint();
    }

    /*
        public void addPluginButton(ButtonText text, ActionListener a) {
            pluginButtons.put(text, a);
            recreate();
        }

        public void removePluginButton(ButtonText text) {
            pluginButtons.remove(text);
            recreate();
        }
    */
    private void maybeShowPopup(MouseEvent me) {
        if (me.isPopupTrigger() || me.getButton() == MouseEvent.BUTTON3) {
            JPopupMenu popUpMenu = new JPopupMenu();
            ButtonGroup group = new ButtonGroup();

            JRadioButtonMenuItem iconAndText = new JRadioButtonMenuItem("Icon and Text", displayMode == DisplayMode.ICONANDTEXT);
            iconAndText.addActionListener(e -> setDisplayMode(DisplayMode.ICONANDTEXT));
            group.add(iconAndText);
            popUpMenu.add(iconAndText);

            JRadioButtonMenuItem iconOnly = new JRadioButtonMenuItem("Icon Only", displayMode == DisplayMode.ICONONLY);
            iconOnly.addActionListener(e -> setDisplayMode(DisplayMode.ICONONLY));
            group.add(iconOnly);
            popUpMenu.add(iconOnly);

            popUpMenu.addSeparator();
            JMenuItem edit = new JMenuItem("Edit Toolbar...");
            edit.addActionListener(ev -> ToolbarEditor.open());
            popUpMenu.add(edit);

            popUpMenu.show(me.getComponent(), me.getX(), me.getY());
        }
    }

    @Override
    public void modeStateChanged() {
        trackingButton.setSelected(ViewState.isTracking());
        diffRotationButton.setSelected(ViewState.isDifferentialRotation());
        coronaButton.setSelected(ViewState.isShowCorona());
        multiviewButton.setSelected(ViewState.isMultiview());
        refreshItem.setSelected(ViewState.isRefresh());
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
        JRadioButtonMenuItem activeAnnotationMode = annotationItems.get(ViewState.getAnnotationMode());
        if (activeAnnotationMode != null)
            activeAnnotationMode.setSelected(true);
    }

}
