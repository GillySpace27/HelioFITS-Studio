package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
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
import javax.swing.JMenu;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.JToolBar;

import org.helioviewer.jhv.annotation.AnnotationMode;
import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.interaction.Interaction;
import javax.annotation.Nullable;

import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.io.samp.SampClient;
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
        // Icon-only buttons carry no visible text for VoiceOver to read, so the label always
        // becomes the accessible name, even when ICONONLY hides it from sight.
        b.getAccessibleContext().setAccessibleName(text.text());
    }

    private final ButtonText AXIS = new ButtonText(Buttons.axis, "Axis", "Axis");
    private final ButtonText DIFFROTATION = new ButtonText(Buttons.diffRotation, "Differential", "Toggle differential rotation");
    private final ButtonText MULTIVIEW = new ButtonText(Buttons.multiview, "Multiview", "Multiview");
    /**
     * The end of the bar, and a tool-shaped button rather than a corner control.
     *
     * <p>It used to wear the sliders glyph, which sat next to the gear-and-pencil and read as a
     * second settings control. It is not settings, it is the rest of the tools: a chevron pointing
     * down at a list, at the size and shape every other tool is drawn, so it reads as the last item
     * of the row it is the last item of.
     */
    private final ButtonText MORE = new ButtonText(Buttons.overflow, "More",
            "The tools parked here, and any the window is too narrow to show");
    private final ButtonText TIMELINES = new ButtonText(Buttons.timelineToolbar, "Timelines",
            "Show the Timelines pane under the picture");
    // Left | bottom | right, the way an editor draws its layout controls. Timelines is the bottom
    // one and predates the other two, so it keeps its own glyph and its own name.
    private final ButtonText SIDEBAR_LEFT = new ButtonText(Buttons.sidebarLeft, "Left Sidebar", "Show or fold the left sidebar");
    private final ButtonText SIDEBAR_RIGHT = new ButtonText(Buttons.sidebarRight, "Right Sidebar", "Show or fold the right sidebar");
    private final ButtonText OFFDISK = new ButtonText(Buttons.offDisk, "Corona", "Toggle off-disk corona");
    private final ButtonText PAN = new ButtonText(Buttons.pan, "Pan", "Pan");
    private final ButtonText PROJECTION = new ButtonText(Buttons.projection, "Projection", "Projection");
    private final ButtonText TRACK_CME = new ButtonText(Buttons.trackCme, "Track CME",
            "Pick a CACTus CME in the loaded range and hold its front at a fixed screen radius");
    private final ButtonText COLOUR = new ButtonText(Buttons.colourSettings, "HDR", "How the whole view is mapped into the display's extended range: headroom, mapping, knee, in-range share, clipped pixels");
    private final ButtonText SEQUENCE = new ButtonText(Buttons.sequenceFilter, "Filters",
            "Filters for one layer: RHEF per frame, and the Fourier filter or noise gate over the whole movie");
    private final ButtonText GRID = new ButtonText(Buttons.grid, "Grid", "Grid, Thomson sphere, celestial sphere, ecliptic and planet overlay settings");
    private final ButtonText CAMERA = new ButtonText(Buttons.camera, "Camera", "Where the view is seen from: Free, Follow, Turntable, Overview, and their settings");
    private final ButtonText ANNOTATE = new ButtonText(Buttons.annotate, "Annotation",
            "Draw on the view: mode, colour and thickness (hold Shift to draw)");
    private final ButtonText SDO_CUTOUT = new ButtonText(Buttons.sdoCutout, "SDO Cut-out",
            "Open LMSAL's AIA cut-out service for the enabled AIA layers and the current view");
    private final ButtonText PRESENTATION = new ButtonText(Buttons.presentation, "Present", "Presentation mode: output only, fullscreen (Esc to leave)");
    private final ButtonText REFRESH = new ButtonText(Buttons.refresh, "Refresh", "Automatic refresh");
    private final ButtonText RESETCAMERA = new ButtonText(Buttons.resetCamera, "Reset View", "Reset view to default");
    private final ButtonText RESETCAMERAAXIS = new ButtonText(Buttons.resetCameraAxis, "Reset Axis", "Reset view axis");
    private final ButtonText ROTATE = new ButtonText(Buttons.rotate, "Rotate", "Rotate");
    private final ButtonText ROTATE90 = new ButtonText(Buttons.rotate90, "Rotate View 90°", "Rotate view 90°");
    private final ButtonText SAMP = new ButtonText(Buttons.samp, "SAMP", "Send SAMP message");
    private final ButtonText TRACK = new ButtonText(Buttons.track, "Track", "Track solar rotation");
    private final ButtonText UNDO = new ButtonText(Buttons.undo, "Undo", "Undo the last change to the scene");
    private final ButtonText REDO = new ButtonText(Buttons.redo, "Redo", "Redo the last undone change to the scene");
    private final ButtonText ZOOMFIT = new ButtonText(Buttons.zoomFit, "Zoom to Fit", "Zoom to fit");
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
        b.getAccessibleContext().setAccessibleName(text.text());
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

        try {
            displayMode = DisplayMode.valueOf(Settings.getProperty("display.toolbar").toUpperCase());
        } catch (Exception ignore) {}
        setDisplayMode(displayMode);
        setVisible(Boolean.parseBoolean(Settings.getProperty("display.toolbar.visible")));
        ViewState.addModeListener(this);
        org.helioviewer.jhv.gui.UITimer.register(this::paletteTick);
    }

    private JToggleButton coronaButton;
    private JToggleButton diffRotationButton;
    private JToggleButton multiviewButton;
    private static JToggleButton refreshToggle;
    @Nullable private static Palette annotatePalette;
    private static JToggleButton timelinesToggle; // current toolbar's Timelines button
    private static JToggleButton leftBarToggle;
    private static JToggleButton rightBarToggle;
    // The Projection palette's controls and their state (ProjectionPaletteContent, HS-8).
    private final ProjectionPaletteContent projectionControls = new ProjectionPaletteContent(this);
    private JToggleButton trackingButton;

    // --- which tools are on the bar, and in what order ----------------------------------------
    // Every control is BUILT every time, and only the chosen ones are added. That is the whole
    // trick: the buttons carry live wiring (a shared ButtonGroup for the interaction modes, the
    // palette bindings, the fields modeStateChanged() writes into), so a control left off the bar
    // has to exist anyway or hiding one would break the ones that stayed. Hidden simply means not
    // added here; the Tools menu adopts the very same component, which is why a toggle in that
    // menu still shows its pressed state.

    // The order rules and their persisted ids live in ToolbarOrder (HS-8). These delegates keep
    // ToolbarEditor, MenuBar and the checks that name ToolBar compiling unchanged.
    static final String SEPARATOR = ToolbarOrder.SEPARATOR;
    static final String MORE_DIVIDER = ToolbarOrder.MORE_DIVIDER;
    static final String ORDER_KEY = ToolbarOrder.ORDER_KEY;
    static final String DEFAULT_ORDER = ToolbarOrder.DEFAULT_ORDER;

    static java.util.List<String> order(java.util.Set<String> known) {
        return ToolbarOrder.order(known);
    }

    static String migrateMore(String stored, java.util.Set<String> known) {
        return ToolbarOrder.migrateMore(stored, known);
    }

    static java.util.List<String> seedNewTools(java.util.List<String> ids, java.util.Set<String> known, @javax.annotation.Nullable String seeded) {
        return ToolbarOrder.seedNewTools(ids, known, seeded);
    }

    static java.util.List<String> resolveOrder(@javax.annotation.Nullable String stored, java.util.Set<String> known) {
        return ToolbarOrder.resolveOrder(stored, known);
    }

    static java.util.List<String> barIds(java.util.List<String> order) {
        return ToolbarOrder.barIds(order);
    }

    static java.util.List<String> moreIds(java.util.List<String> order) {
        return ToolbarOrder.moreIds(order);
    }

    static void setOrder(java.util.List<String> ids) {
        ToolbarOrder.setOrder(ids);
    }

    static void resetOrder() {
        ToolbarOrder.resetOrder();
    }

    static java.util.Set<String> onBar(java.util.List<String> order) {
        return ToolbarOrder.onBar(order);
    }

    static java.util.Set<String> placed(java.util.List<String> order) {
        return ToolbarOrder.placed(order);
    }

    static java.util.List<String> missing(java.util.List<String> order, java.util.Collection<String> known) {
        return ToolbarOrder.missing(order, known);
    }

    /** ToolbarOrder.setOrder and resetOrder rebuild the live bar through this. */
    static void recreateCurrent() {
        if (current != null)
            current.recreate();
    }

    /** One customisable place on the bar: a stable id, how it looks in the editor, and the control. */
    public record Tool(String id, String label, Icon icon, String tip, JComponent comp) {}

    private final java.util.LinkedHashMap<String, Tool> built = new java.util.LinkedHashMap<>();


    /** Build a control and record it under an id, without deciding yet whether it is shown. */
    private void register(String id, ButtonText text, JComponent comp) {
        comp.setName(id); // the persisted toolbar id is the control's name: ActionCatalog.find, help search and tours key on it
        String spoken = comp.getAccessibleContext().getAccessibleName();
        if (spoken == null || spoken.isEmpty())
            comp.getAccessibleContext().setAccessibleName(text.text());
        built.put(id, new Tool(id, text.text(), text.icon(), text.tip(), comp));
    }


    /** Every control that exists, in the order the bar was built, for the editor to list. */
    public static java.util.List<Tool> allTools() {
        return current == null ? java.util.List.of() : java.util.List.copyOf(current.built.values());
    }

    /** The ids laid out on the bar right now, separators aside. */
    public static java.util.Set<String> shownIds() {
        return current == null ? java.util.Set.of() : onBar(order(current.built.keySet()));
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
        java.util.List<String> ids = order(built.keySet());
        for (String id : barIds(ids)) {
            if (SEPARATOR.equals(id)) {
                addSeparator(dim);
                continue;
            }
            Tool tool = built.get(id);
            if (tool != null)
                addButton(tool.comp());
        }
        // Parked behind the divider: never on the bar, so More holds them whatever the width is.
        parked.clear();
        for (String id : moreIds(ids)) {
            Tool tool = built.get(id);
            if (tool != null)
                parked.add(tool.comp());
        }
    }

    // --- overflow ----------------------------------------------------------------------------
    // A toolbar narrower than its contents used to just clip whatever did not fit, with no way
    // to reach it: the buttons were still there, laid out past the right edge and invisible.
    // That is only more likely now, since the presenter window is a third of a screen wide.
    // Everything that does not fit moves into a chevron menu at the right-hand end instead.
    private final java.util.List<Component> items = new java.util.ArrayList<>();
    private final java.util.List<Component> overflowed = new java.util.ArrayList<>();
    /** Controls placed after the divider: in More by choice rather than because the window is narrow. */
    private final java.util.List<Component> parked = new java.util.ArrayList<>();
    private JButton overflowButton;
    private JButton editCorner;
    private JToggleButton lockCorner;

    private static Icon lockIcon() {
        return PanelLock.isLocked() ? Buttons.lockPanels : Buttons.unlockPanels;
    }

    private static String lockTip() {
        return PanelLock.isLocked()
                ? "Panels are locked where they are. Click to let them be moved; every toolbar button works the same either way."
                : "Lock the panels where they are: the move, cross and pop-out arrows and the toolbar editor stop, nothing else changes.";
    } // permanent, in the trailing corner, never part of the order
    private JPopupMenu overflowPopup;
    private JPanel overflowPanel;
    // While the menu is open its buttons are parented to it rather than to the toolbar, so
    // re-running the fit calculation would see them missing and "fit" everything. Freeze it.
    private boolean overflowOpen;

    private void createNewToolBar() {
        current = this;
        built.clear();
        projectionControls.clearProjectionItems();
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

        // Scene undo. Enabled and titled by SceneUndo; a click is always the scene's, whatever has the keyboard.
        JButton undo = toolButton(UNDO);
        undo.addActionListener(e -> org.helioviewer.jhv.app.SceneUndo.undo());
        org.helioviewer.jhv.app.SceneUndo.bind(undo, false);
        JButton redo = toolButton(REDO);
        redo.addActionListener(e -> org.helioviewer.jhv.app.SceneUndo.redo());
        org.helioviewer.jhv.app.SceneUndo.bind(redo, true);
        register("undo", UNDO, undo);
        register("redo", REDO, redo);

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

        // The pane the timelines and SWEK are drawn in has only ever been foldable by its own
        // header, which presentation mode takes away along with the rest of the chrome. So while
        // presenting there was no way to put the timeline on the screen at all, which is the one
        // configuration where someone most wants to point at it.
        JToggleButton timelinesButton = toolToggleButton(TIMELINES);
        timelinesToggle = timelinesButton;
        timelinesButton.setSelected(pluginsShowing());
        timelinesButton.addItemListener(e -> {
            MainContentPanel panel = MainFrame.getMainContentPanel();
            if (panel != null)
                panel.setPluginsShowing(timelinesButton.isSelected());
        });
        register("timelines", TIMELINES, timelinesButton);

        // The other two of the three. Fold means collapse to the rail, exactly what the rail's
        // own handle does, so the sidebar can be reopened from either end. Lit is showing.
        JToggleButton leftBar = toolToggleButton(SIDEBAR_LEFT);
        leftBarToggle = leftBar;
        leftBar.setSelected(!MainFrame.isSidebarCollapsed());
        leftBar.addItemListener(e -> {
            if (!syncingBars)
                MainFrame.setSidebarCollapsed(!leftBar.isSelected());
        });
        register("sidebarLeft", SIDEBAR_LEFT, leftBar);

        JToggleButton rightBar = toolToggleButton(SIDEBAR_RIGHT);
        rightBarToggle = rightBar;
        rightBar.addItemListener(e -> {
            if (!syncingBars)
                RightSidebar.getInstance().setCollapsed(!rightBar.isSelected());
        });
        register("sidebarRight", SIDEBAR_RIGHT, rightBar);
        syncSidebarToggles();

        // The projection controls live in a persistent palette, not a dropdown: it survives
        // focus loss (so the sliders can be worked against the view) and only collapses when
        // the toolbar button is toggled again or its window is closed.
        JToggleButton projectionButton = toolToggleButton(PROJECTION);
        projectionPalette.bind(projectionButton);
        register("projection", PROJECTION, projectionButton);

        // Track CME was a dialog, then a palette that only existed once something opened it, so it
        // could not be on the bar, had no home to come back to, and was never there at launch. It is
        // built here with the rest, docks into the right sidebar by default, and remembers where the
        // user puts it like any other palette. Beside Projection because tracking drives the warp.
        JToggleButton trackCmeButton = toolToggleButton(TRACK_CME);
        org.helioviewer.jhv.event.info.CactusTrackPanel.palette().bind(trackCmeButton);
        register("trackCme", TRACK_CME, trackCmeButton);
        // Track Comet has no button of its own, but a palette takes its place in the sidebar when it
        // is built, so building it here is what puts the two of them in this order. Left to the menu
        // it was built whenever it was first opened, and landed wherever that happened to be.
        org.helioviewer.jhv.event.info.CometTrackPanel.palette();

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
        if (sequencePalette == null) {
            // It was "Fourier filter" until RHEF moved in beside it. Before the constructor reads any of it,
            // so the palette comes back where it was put, folded as it was left, in its place in the bar.
            Palette.renameStored("Fourier filter", "Filters");
            sequencePalette = new Palette("Filters", SequencePaletteContent::build, SequencePaletteContent::refresh, true); // has text fields
        }
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
        // Annotation is a palette now, beside Projection, HDR, Grid and Camera: it is a mode you
        // stay in while you draw, and its thickness slider was unusable inside a menu that closed
        // the moment you touched it. See AnnotationPaletteContent.
        JToggleButton annotateButton = toolToggleButton(ANNOTATE);
        if (annotatePalette == null)
            annotatePalette = new Palette("Annotation", AnnotationPaletteContent::build, AnnotationPaletteContent::refresh);
        annotatePalette.bind(annotateButton);
        register("annotate", ANNOTATE, annotateButton);

        // The other three were written into More as menu items, so they could not be moved, put on
        // the bar, or taken off it. They are ordinary tools now; the divider is what puts a tool in
        // More, and by default that is where these three still are.
        JToggleButton refreshButton = toolToggleButton(REFRESH);
        refreshButton.setSelected(ViewState.isRefresh());
        refreshButton.addItemListener(e -> ViewState.setRefresh(refreshButton.isSelected()));
        refreshToggle = refreshButton;
        register("refresh", REFRESH, refreshButton);

        JButton cutOut = toolButton(SDO_CUTOUT);
        cutOut.addActionListener(new Actions.SDOCutOut());
        register("sdoCutout", SDO_CUTOUT, cutOut);

        if (Boolean.parseBoolean(Settings.getProperty("startup.sampHub"))) {
            JButton samp = toolButton(SAMP);
            samp.addActionListener(e -> SampClient.notifyRequestData());
            register("samp", SAMP, samp);
        }

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

        // One More button, not two. This used to be a chevron that appeared only when the bar ran
        // out of room, beside a separate "More" split button with four controls written into it.
        // Two menus at the same end of the bar meaning different things. Now there is one: it
        // holds whatever is after the divider plus whatever the width pushed past it.
        overflowButton = toolButton(MORE);
        overflowButton.setFocusPainted(false);
        overflowButton.addActionListener(e -> showOverflow());
        overflowButton.setVisible(false);
        add(overflowButton);

        // Added after the snapshot above, so it is a child of the bar without being part of the
        // running order: doLayout pins it to the trailing corner and the overflow never eats it.
        // That is the point of moving it off the row. As the last tool it was the first thing a
        // narrow window pushed into the chevron, and the way back to the editor is not something
        // to go hunting for in the menu that the editor decides the contents of.
        // Beside the edit control, and for the same reason it is out of the running order: a
        // permanent corner control, not a tool. The two belong together -- one decides what the
        // bar holds, the other decides whether the panels can be moved -- and neither should be
        // the first thing a narrow window pushes into a menu.
        lockCorner = Buttons.flatToggle(lockIcon(), PanelLock.isLocked());
        lockCorner.setToolTipText(lockTip());
        lockCorner.setFocusPainted(false);
        lockCorner.addActionListener(e -> PanelLock.setLocked(lockCorner.isSelected()));
        PanelLock.addListener(() -> {
            if (lockCorner != null) {
                lockCorner.setSelected(PanelLock.isLocked());
                lockCorner.setIcon(lockIcon());
                lockCorner.setToolTipText(lockTip());
            }
        });
        PanelLock.setLockButton(lockCorner); // so a refused move can blink it
        add(lockCorner);

        editCorner = Buttons.flat(Buttons.editToolbarCorner);
        editCorner.setToolTipText("Edit the toolbar: choose which tools are on it, and in what order");
        editCorner.setFocusPainted(false);
        editCorner.addActionListener(e -> ToolbarEditor.open()); // disabled outright while locked
        // Frozen with everything else. The editor is not only about which tools are on the bar: it
        // can take a palette's toggle off it entirely, and a palette whose toggle is gone is a
        // panel you cannot reach. That is a bigger rearrangement than any header arrow makes, so
        // the lock has to reach it too, and "nothing moves while it is on" is one rule rather than
        // a rule with an exception in it.
        PanelLock.registerBadged(editCorner, "Panels are locked in place, so the toolbar cannot be edited.");
        add(editCorner);
    }

    private void showOverflow() {
        if (overflowed.isEmpty() && parked.isEmpty())
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
        // Overflowed first, then parked, which is simply their order in the one list: an icon the
        // width pushed off the bar sits earlier than anything deliberately put behind the divider.
        // So the menu grows upward from the cut and never reshuffles as the window is resized.
        for (Component c : overflowed) {
            remove(c);
            c.setVisible(true);
            // A separator is a gap between groups, and which way it is drawn depends on which way
            // the things it separates run. On the bar they run across, so the gap is a vertical
            // rule; in the menu they run down, so it has to lie down with them or it is a one-pixel
            // sliver doing nothing. Turned back on the way out, in reclaimOverflow.
            if (c instanceof javax.swing.JToolBar.Separator sep)
                sep.setOrientation(SwingConstants.HORIZONTAL);
            if (c instanceof JComponent jc)
                jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            overflowPanel.add(c);
        }
        for (Component c : parked) { // never children of the bar, so nothing to take them out of
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
            if (c instanceof javax.swing.JToolBar.Separator sep)
                sep.setOrientation(SwingConstants.VERTICAL); // back to a rule between things in a row
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
        if (items.isEmpty() || overflowButton == null || editCorner == null || lockCorner == null || overflowOpen || getWidth() <= 0) {
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
        int edit = editCorner.getPreferredSize().width + hgap + lockCorner.getPreferredSize().width;
        // The corner control is always there, so its width is never available to the row.
        avail -= edit + hgap;
        // Room for the More button whenever anything is parked behind the divider, not only when
        // the row runs out of space: it is on screen either way.
        boolean needed = total > avail || !parked.isEmpty();
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
        int editW = editCorner.getPreferredSize().width;
        int lockW = lockCorner.getPreferredSize().width;
        editCorner.setBounds(right - editW, in.top, editW, rowHeight);
        lockCorner.setBounds(right - editW - hgap - lockW, in.top, lockW, rowHeight);
        overflowButton.setVisible(!overflowed.isEmpty() || !parked.isEmpty());
        if (!overflowed.isEmpty() || !parked.isEmpty())
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
        for (Colors color : Annotations.BASE_COLORS) {
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

    private static boolean pluginsShowing() {
        MainContentPanel panel = MainFrame.getMainContentPanel();
        return panel != null && panel.isPluginsShowing();
    }

    /**
     * Keep the Timelines button honest when something else moved the pane.
     *
     * <p>Entering and leaving presentation mode both take the whole pane away and give it back
     * without going near this button, and so does the pane's own header. Called from
     * MainFrame.setChromeVisible, which is where every one of those paths ends up.
     */
    private static boolean syncingBars; // mirroring state into the buttons, not the user clicking them

    /**
     * Keep the two sidebar buttons honest when a bar was folded some other way: its own rail
     * handle, presentation mode, or a session restore. Called from the two collapse setters, so
     * every path ends up here.
     *
     * <p>The right button is also greyed while nothing is docked on the right, because folding an
     * empty sidebar is a click that does nothing and should look like one.
     */
    public static void syncSidebarToggles() {
        if (leftBarToggle == null || rightBarToggle == null)
            return;
        syncingBars = true;
        try {
            leftBarToggle.setSelected(!MainFrame.isSidebarCollapsed());
            boolean east = MainFrame.isEastVisible();
            rightBarToggle.setEnabled(east);
            rightBarToggle.setSelected(east && !RightSidebar.getInstance().isCollapsed());
            rightBarToggle.setToolTipText(east ? SIDEBAR_RIGHT_TIP : "Nothing is docked on the right; drop a palette there first");
        } finally {
            syncingBars = false;
        }
    }

    private static final String SIDEBAR_RIGHT_TIP = "Show or fold the right sidebar";

    public static void syncTimelinesToggle() {
        if (timelinesToggle == null)
            return;
        boolean showing = pluginsShowing();
        if (timelinesToggle.isSelected() != showing)
            timelinesToggle.setSelected(showing);
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
        return current == null ? new JPanel() : current.projectionControls.build();
    }

    private static ToolBar current;

    // ProjectionPaletteContent asks this before syncing what only an open palette shows.
    static boolean projectionPaletteOpen() {
        return projectionPalette.isOpen();
    }

    // The slider maps moved to ProjectionPaletteContent; ZoomSliderCheck and DiskSliderCheck name them here.
    static double zoomSliderToMagnification(int t) {
        return ProjectionPaletteContent.zoomSliderToMagnification(t);
    }

    static int magnificationToZoomSlider(double magnification) {
        return ProjectionPaletteContent.magnificationToZoomSlider(magnification);
    }

    static double sliderToDiskScale(int value) {
        return ProjectionPaletteContent.sliderToDiskScale(value);
    }

    static int diskScaleToSlider(double scale) {
        return ProjectionPaletteContent.diskScaleToSlider(scale);
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
        projectionControls.syncSurfaceModelToggle();
        projectionControls.syncSkyAim();
        Palette.keepVisible();
        projectionControls.syncZoomSliderFromDisplay();
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

    public boolean isTextVisible() {
        return displayMode == DisplayMode.ICONANDTEXT;
    }

    public void setTextVisible(boolean visible) {
        setDisplayMode(visible ? DisplayMode.ICONANDTEXT : DisplayMode.ICONONLY);
    }

    public void setToolbarVisible(boolean visible) {
        Settings.setProperty("display.toolbar.visible", Boolean.toString(visible));
        setVisible(visible);
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
    @Override
    public void modeStateChanged() {
        trackingButton.setSelected(ViewState.isTracking());
        diffRotationButton.setSelected(ViewState.isDifferentialRotation());
        coronaButton.setSelected(ViewState.isShowCorona());
        multiviewButton.setSelected(ViewState.isMultiview());
        if (refreshToggle != null)
            refreshToggle.setSelected(ViewState.isRefresh());
        projectionControls.modeStateChanged();
        AnnotationPaletteContent.refresh(); // the mode radios live there now, not in a menu
    }

}
