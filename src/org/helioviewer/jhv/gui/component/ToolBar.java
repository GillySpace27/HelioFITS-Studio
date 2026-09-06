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
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JToggleButton;
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
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.io.samp.SampClient;
import org.helioviewer.jhv.layers.ImageLayers;
//import org.helioviewer.jhv.timelines.band.HapiReader;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideSplitButton;
import com.jidesoft.swing.JideToggleButton;

@SuppressWarnings("serial")
public final class ToolBar extends JToolBar implements ViewState.ModeListener {

    private static final int ZOOM_HOLD_REPEAT_MS = 33;
    private static final int POPUP_SLIDER_WIDTH = 120;

    private static DisplayMode displayMode = DisplayMode.ICONANDTEXT;

    private enum DisplayMode {
        ICONANDTEXT, ICONONLY
    }

    private record ButtonText(String icon, String text, String tip) {
        @Override
        public String toString() {
            return displayMode == DisplayMode.ICONONLY ? icon : icon + "<br/>" + text;
        }
    }

    private final ButtonText ANNOTATION = new ButtonText(Buttons.annotate, "Annotation", "Annotation (Press Shift to draw)");
    private final ButtonText AXIS = new ButtonText(Buttons.axis, "Axis", "Axis");
    private final ButtonText DIFFROTATION = new ButtonText(Buttons.diffRotation, "Differential", "Toggle differential rotation");
    private final ButtonText MULTIVIEW = new ButtonText(Buttons.multiview, "Multiview", "Multiview");
    private final ButtonText OFFDISK = new ButtonText(Buttons.offDisk, "Corona", "Toggle off-disk corona");
    private final ButtonText PAN = new ButtonText(Buttons.pan, "Pan", "Pan");
    private final ButtonText PROJECTION = new ButtonText(Buttons.projection, "Projection", "Projection");
    private final ButtonText COLOUR = new ButtonText(Buttons.colourSettings, "HDR", "How the whole view is mapped into the display's extended range: headroom, mapping, knee, in-range share, clipped pixels");
    private final ButtonText SEQUENCE_HIDDEN = new ButtonText(Buttons.sequenceFilter, "Fourier", "Fourier filter over the whole movie"); // not added to the bar; see createNewToolBar
    private final ButtonText MORE = new ButtonText(Buttons.moreSettings, "More", "Less common controls: automatic refresh, the SDO cut-out, SAMP");
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

    private static JideButton toolButton(ButtonText text) {
        JideButton b = new JideButton(text.toString());
        b.setToolTipText(text.tip);
        return b;
    }

    private static JideSplitButton toolSplitButton(ButtonText text) {
        JideSplitButton b = new JideSplitButton(text.toString());
        b.setToolTipText(text.tip);
        b.setAlwaysDropdown(true);
        return b;
    }

    private static JideToggleButton toolToggleButton(ButtonText text) {
        JideToggleButton b = new JideToggleButton(text.toString());
        b.setToolTipText(text.tip);
        return b;
    }

    public ToolBar() {
        setLayout(new FlowLayout(FlowLayout.LEADING, 1, 3));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, getBackground().brighter()));
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

    private JideToggleButton coronaButton;
    private JideToggleButton diffRotationButton;
    private JideToggleButton multiviewButton;
    private final EnumMap<AnnotationMode, JRadioButtonMenuItem> annotationItems = new EnumMap<>(AnnotationMode.class);
    private final EnumMap<MapMode, javax.swing.JRadioButton> projectionItems = new EnumMap<>(MapMode.class);
    private JHVSlider warpLambdaSlider;
    private JHVSlider warpEdgeSlider;
    private JLabel warpLambdaValue;
    private JLabel warpEdgeValue;
    // CME tracking writes lambda / outer radius straight to Display; while it does, we mirror the
    // values into the sliders. Guarded so that programmatic move does not look like a manual one
    // and disengage the very tracking that caused it.
    private boolean syncingFromTracker;
    private JCheckBoxMenuItem refreshItem;
    private JideToggleButton trackingButton;

    // --- overflow ----------------------------------------------------------------------------
    // A toolbar narrower than its contents used to just clip whatever did not fit, with no way
    // to reach it: the buttons were still there, laid out past the right edge and invisible.
    // That is only more likely now, since the presenter window is a third of a screen wide.
    // Everything that does not fit moves into a chevron menu at the right-hand end instead.
    private final java.util.List<Component> items = new java.util.ArrayList<>();
    private final java.util.List<Component> overflowed = new java.util.ArrayList<>();
    private JideButton overflowButton;
    private JPopupMenu overflowPopup;
    private JPanel overflowPanel;
    // While the menu is open its buttons are parented to it rather than to the toolbar, so
    // re-running the fit calculation would see them missing and "fit" everything. Freeze it.
    private boolean overflowOpen;

    private void createNewToolBar() {
        current = this;
        annotationItems.clear();
        projectionItems.clear();
        if (Platform.isMacOS()) {
            add(Box.createHorizontalStrut(90), 0);
        }

        Interaction.Mode interactionMode = InputController.getMode();
        try {
            interactionMode = Interaction.Mode.valueOf(Settings.getProperty("display.interaction").toUpperCase());
        } catch (Exception ignore) {}

        Dimension dim = new Dimension(32, 32);

        // Zoom
        JideButton zoomIn = toolButton(ZOOMIN);
        zoomIn.addActionListener(new Actions.ZoomIn());
        HoldRepeat.install(zoomIn, ZOOM_HOLD_REPEAT_MS);
        JideButton zoomOut = toolButton(ZOOMOUT);
        zoomOut.addActionListener(new Actions.ZoomOut());
        HoldRepeat.install(zoomOut, ZOOM_HOLD_REPEAT_MS);
        JideButton zoomFit = toolButton(ZOOMFIT);
        zoomFit.addActionListener(new Actions.ZoomFit());
        JideButton zoomOne = toolButton(ZOOMONE);
        zoomOne.addActionListener(new Actions.ZoomOneToOne());
        JideButton resetCamera = toolButton(RESETCAMERA);
        resetCamera.addActionListener(new Actions.ResetCamera());
        JideButton resetCameraAxis = toolButton(RESETCAMERAAXIS);
        resetCameraAxis.addActionListener(new Actions.ResetCameraAxis());

        JideSplitButton rotate90Button = toolSplitButton(ROTATE90);
        rotate90Button.add(new Actions.Rotate90Camera("X Axis", "X"));
        rotate90Button.add(new Actions.Rotate90Camera("Y Axis", "Y"));
        rotate90Button.add(new Actions.Rotate90Camera("Z Axis", "Z"));

        addButton(zoomIn);
        addButton(zoomOut);
        addButton(zoomFit);
        addButton(zoomOne);
        addSeparator(dim);
        addButton(resetCamera);
        addButton(resetCameraAxis);
        addButton(rotate90Button);
        addSeparator(dim);

        // Interaction
        ButtonGroup group = new ButtonGroup();

        JideToggleButton pan = toolToggleButton(PAN);
        pan.addActionListener(e -> InputController.setMode(Interaction.Mode.PAN));
        JideToggleButton rotate = toolToggleButton(ROTATE);
        rotate.addActionListener(e -> InputController.setMode(Interaction.Mode.ROTATE));
        JideToggleButton axis = toolToggleButton(AXIS);
        axis.addActionListener(e -> InputController.setMode(Interaction.Mode.AXIS));

        group.add(pan);
        group.add(rotate);
        group.add(axis);

        addButton(pan);
        addButton(rotate);
        addButton(axis);
        addSeparator(dim);

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

        addButton(trackingButton);
        addButton(diffRotationButton);
        addButton(coronaButton);
        addButton(multiviewButton);
        addSeparator(dim);

        // The projection controls live in a persistent palette, not a dropdown: it survives
        // focus loss (so the sliders can be worked against the view) and only collapses when
        // the toolbar button is toggled again or its window is closed.
        JideToggleButton projectionButton = toolToggleButton(PROJECTION);
        projectionPalette.bind(projectionButton);
        addButton(projectionButton);

        // The sequence filter still gets a palette, because it is a whole-movie computation with a
        // lot of settings and a readout worth watching while the view plays. It does NOT get a
        // place on this bar: it acts on one layer, and a global button for a per-layer thing
        // invites the reading that it is doing something to all of them. It is opened from the
        // Fourier row of the layer whose movie it will filter, or from the View menu, and Apply
        // reaches every SELECTED layer, which is the "all at once" this bar could not express.
        // The toggle still exists, unparented, because it is the record of whether the palette is
        // open that Palette.open and the keep-visible watchdog read.
        JideToggleButton sequenceButton = toolToggleButton(SEQUENCE_HIDDEN);
        if (sequencePalette == null)
            sequencePalette = new Palette("Fourier filter", SequencePaletteContent::build, SequencePaletteContent::refresh, true); // has text fields
        sequencePalette.bind(sequenceButton);

        // Colour settings are per view, not per layer: they decide how every frame of every movie
        // is shown, so they belong beside Projection rather than inside a layer's own row.
        JideToggleButton colourButton = toolToggleButton(COLOUR);
        colourPalette.bind(colourButton);
        addButton(colourButton);

        JideToggleButton presentationButton = toolToggleButton(PRESENTATION);
        presentationToggle = presentationButton;
        presentationButton.setSelected(org.helioviewer.jhv.gui.PresentationMode.isActive());
        presentationButton.addActionListener(e -> {
            // The button's own selected state has already flipped; drive the mode from what it
            // now says, so a stale state (toolbar rebuilt while presenting) cannot invert it.
            if (presentationButton.isSelected() != org.helioviewer.jhv.gui.PresentationMode.isActive())
                org.helioviewer.jhv.gui.PresentationMode.toggle();
        });
        addButton(presentationButton);

        JideSplitButton annotationButton = toolSplitButton(ANNOTATION);
        ButtonGroup annotationGroup = new ButtonGroup();
        for (AnnotationMode mode : AnnotationMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(mode.toString());
            if (mode == ViewState.getAnnotationMode())
                item.setSelected(true);
            item.addActionListener(e -> ViewState.setAnnotationMode(mode));
            annotationGroup.add(item);
            annotationButton.add(item);
            annotationItems.put(mode, item);
        }
        annotationButton.addSeparator();
        addAnnotationColorItems(annotationButton);
        annotationButton.add(createAnnotationThicknessPanel());
        annotationButton.addSeparator();
        annotationButton.add(new Actions.ClearAnnotations());
        annotationButton.addSeparator();
        annotationButton.add(new Actions.ZoomFOVAnnotation());
        addButton(annotationButton);

        addSeparator(dim);

        // Everything reached once a session rather than once a minute, behind one button. Three
        // top-level buttons for automatic refresh, the SDO cut-out and SAMP spent width that the
        // overflow chevron then had to reclaim on a narrow window; the chevron is still there for
        // whatever does not fit, but it no longer has to start with these.
        JideSplitButton more = toolSplitButton(MORE);
        refreshItem = new JCheckBoxMenuItem(REFRESH.text(), ViewState.isRefresh());
        refreshItem.setToolTipText(REFRESH.tip());
        refreshItem.addItemListener(e -> ViewState.setRefresh(refreshItem.isSelected()));
        more.add(refreshItem);
        more.addSeparator();
        more.add(new Actions.SDOCutOut());
        if (Boolean.parseBoolean(Settings.getProperty("startup.sampHub"))) {
            JMenuItem samp = new JMenuItem(SAMP.text());
            samp.setToolTipText(SAMP.tip());
            samp.addActionListener(e -> SampClient.notifyRequestData());
            more.add(samp);
        }
        addButton(more);

        addSeparator(dim);
/*
        ButtonText hText = new ButtonText("HAPI", "HAPI", "HAPI");
        JideButton hButton = toolButton(hText);
        hButton.addActionListener(e -> HapiReader.requestCatalog());
        addButton(hButton);
*/
/*
        for (Map.Entry<ButtonText, ActionListener> entry : pluginButtons.entrySet()) {
            JideButton b = toolButton(entry.getKey());
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

        overflowButton = new JideButton(Buttons.overflow);
        overflowButton.setToolTipText("More toolbar controls");
        overflowButton.setFocusPainted(false);
        overflowButton.addActionListener(e -> showOverflow());
        overflowButton.setVisible(false);
        add(overflowButton);
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
        if (items.isEmpty() || overflowButton == null || overflowOpen || getWidth() <= 0) {
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
        overflowButton.setVisible(!overflowed.isEmpty());
        if (!overflowed.isEmpty())
            overflowButton.setBounds(getWidth() - in.right - chevron, in.top, chevron, rowHeight);
    }

    private void addButton(AbstractButton b) {
        b.setFocusPainted(false);
        add(b);
    }

    private static void addAnnotationColorItems(JideSplitButton annotationButton) {
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
        annotationButton.add(panel);
    }

    // The projection controls live in a persistent palette, not a dropdown: it survives focus
    // loss (so the sliders can be worked against the view) and only collapses when its toolbar
    // button is toggled again. Palette holds the window behaviour; this supplies the controls.
    private static final Palette projectionPalette =
            new Palette("Projection", ToolBar::projectionContent, () -> {});

    private static Palette sequencePalette;

    private static final Palette colourPalette =
            new Palette("HDR", ColourPaletteContent::build, ColourPaletteContent::refresh);

    /** Toggle the HDR palette (used by View > HDR Settings). */
    public static void toggleColourPalette() {
        colourPalette.toggle();
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

    private static JideToggleButton presentationToggle; // current toolbar's presentation button

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
            javax.swing.JRadioButton item = new javax.swing.JRadioButton(el.toString());
            if (el == ViewState.getProjection())
                item.setSelected(true);
            item.addActionListener(e -> ViewState.setProjection(el));
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
        content.add(createWarpEdgePanel());
        content.add(createZoomPanel());
        content.add(createDiskPanel());
        content.add(createSkyPanel());
        content.add(createHelioradial3DPanel());
        setSkyPanelEnabled(ViewState.getProjection() == MapMode.ObserverSky);
        surfaceModelToggle.setEnabled(ViewState.getProjection().usesSurfaceModel());
        warpLambdaSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
        // The disk scale is a multiplier on the Box-Cox limb anchor, so it has nothing to act on
        // wherever the warp itself does not: same condition, not a similar one.
        diskSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
        warpEdgeSlider.setEnabled(ViewState.getProjection().usesWarpEdge());
        helioradial3DBox.setEnabled(ViewState.getProjection() == MapMode.Helioradial);
        CMETracker.addSolveListener(this::syncWarpSlidersFromTracker); // follow the tracked knob

        return content;
    }

    // Mirror the knob CME tracking is animating back into its slider, so the readout matches what
    // the projection is actually doing. Inverts the Edge slider's log mapping (radius = 2*(full/2)^t).
    private void syncWarpSlidersFromTracker() {
        if (warpLambdaSlider == null || warpEdgeSlider == null)
            return;
        syncingFromTracker = true;
        try {
            if (CMETracker.getMode() == CMETracker.Mode.WARP) {
                warpLambdaSlider.setValue((int) Math.round(Display.getWarpLambda() * 1000));
                warpLambdaValue.setText(String.format("%.3f", Display.getWarpLambda()));
            } else {
                double radius = Display.getWarpOuterRadius();
                double full = Math.max(ImageLayers.getLargestRadialSize(), 2);
                if (radius <= 0 || full <= 2) {
                    warpEdgeSlider.setValue(1000);
                    warpEdgeValue.setText("auto");
                } else {
                    double t = 1000 * Math.log(Math.max(radius, 2) / 2) / Math.log(full / 2);
                    warpEdgeSlider.setValue((int) Math.round(Math.clamp(t, 0, 1000)));
                    warpEdgeValue.setText(String.format("%.0f R☉", radius));
                }
            }
        } finally {
            syncingFromTracker = false;
        }
    }

    private JPanel createWarpLambdaPanel() {
        warpLambdaSlider = new JHVSlider(-1000, 1000, (int) Math.round(ViewState.getWarpLambda() * 1000));
        warpLambdaSlider.setToolTipText("Warp strength (Box-Cox lambda) for warp projections");
        warpLambdaSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, warpLambdaSlider.getPreferredSize().height));
        JLabel label = new JLabel("Warp");
        warpLambdaValue = new JLabel(String.format("%.3f", ViewState.getWarpLambda()), JLabel.RIGHT);
        warpLambdaValue.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        warpLambdaSlider.addChangeListener(e -> {
            if (!syncingFromTracker && CMETracker.getMode() == CMETracker.Mode.WARP)
                CMETracker.stop(); // a manual move takes the wheel back, but only from the knob tracking drives
            ViewState.setWarpLambda(warpLambdaSlider.getValue() / 1000.);
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
    // Edge: the projection's outer radius as a fraction of the loaded FOV, mapped in log
    // space from 2 Rsun (far left) to the full FOV (far right = auto). A radial crop: a
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
                    + "Helioradial with \"Render in 3D\" draws the imagery on a surface; every other "
                    + "projection reconstructs it per pixel and never consults this.");
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

        // Puts every control in this palette back to neutral in one press: warp off, edge wide
        // open, magnification 1x. "Warp off" is lambda = 1, NOT the app's start-up lambda of 0
        // -- 0 is the logarithmic member of the family and warps hard; 1 is the exact identity,
        // where the projection reduces to the unwarped view. Resetting to the start-up value
        // would leave the picture visibly warped, which is not what a reset can mean here.
        javax.swing.JButton resetView = new javax.swing.JButton("Reset view");
        resetView.setToolTipText("Return warp, edge and zoom to their defaults");
        resetView.addActionListener(e -> resetProjectionControls());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(helioradial3DBox, BorderLayout.LINE_START);
        panel.add(resetView, BorderLayout.LINE_END);
        return panel;
    }

    // Tracking animates lambda / the edge crop frame by frame, so it has to let go before the
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
                warpLambdaSlider.setValue(1000);
                warpLambdaValue.setText(String.format("%.3f", 1.));
            }
            if (warpEdgeSlider != null) {
                warpEdgeSlider.setValue(1000);
                warpEdgeValue.setText("auto");
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

    // Runs the same way round as Edge, which is the other control that decides how much sky is
    // on screen: left is a tight view, right is a wide one. Edge does that by construction
    // (its left end is a 2 R_sun crop, its right end the full field), and zoom read the other
    // way, so the two sliders undid each other when dragged in the same direction.
    static double zoomSliderToMagnification(int t) {
        return Math.pow(2, (0.5 - t / 1000.) * 2 * ZOOM_LOG2_RANGE);
    }

    static int magnificationToZoomSlider(double magnification) {
        double t = 1000 * (0.5 - Math.log(magnification) / (Math.log(2) * 2 * ZOOM_LOG2_RANGE));
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
     * <p>Runs the same way as Warp, Edge and Zoom: further left is a bigger disk, because on those
     * three further left is a tighter field and so a larger apparent size.
     *
     * <p><b>No sentinel, deliberately.</b> A discrete "auto" position adjacent to a continuous
     * range is a discontinuity by construction: one pixel of travel would jump the disk from the
     * nominal share to the top of the range. Making 1.0 an ordinary value on the scale removes the
     * jump entirely, and it costs nothing, because 1.0 IS the automatic behaviour -- the anchor is
     * returned untouched there. Nominal therefore sits near the left rather than at it, about a
     * fifth of the way in, which is where log-spacing puts it between 2 and 0.05.
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

        // The sky as a transformation ON TOP of the radial scale rather than as the sky itself.
        // With it on, the Box-Cox lambda and the Edge crop reach this mode, which is the point:
        // the dome shows the warped corona instead of the corona at its true angular size.
        javax.swing.JCheckBox compose = new javax.swing.JCheckBox("On the radial scale", Display.isSkyCompose());
        compose.setToolTipText("Draw the sky from the picture the radial modes draw rather than from the sky itself: "
                + "a dome angle is read as a Helioradial page radius and undone through its Box-Cox scale, so the warp "
                + "shows up as a change of angular scale. The field edge stays where it is. Turns on the Warp and Edge "
                + "sliders and the Surface choice, which is what decides where along each line of sight the radius is "
                + "measured: with the Thomson sphere, this is the Thomson-sphere placement drawn on the celestial sphere.");
        compose.addActionListener(e -> {
            Display.setSkyCompose(compose.isSelected());
            modeStateChanged(); // the Warp, Edge and Surface controls are gated on it
            DisplayController.display();
        });
        JPanel composeRow = new JPanel(new BorderLayout());
        composeRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        composeRow.add(compose, BorderLayout.LINE_START);

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
        javax.swing.JButton aimAtSun = new javax.swing.JButton("Aim at Sun");
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
        diskSlider = new JHVSlider(0, 1000, diskScaleToSlider(Display.getDiskScale()));
        diskSlider.setToolTipText("Size of the solar disk as a multiple of the nominal Box-Cox warp: 1.00\u00d7 is the warp untouched, left is bigger, right is smaller. Double-click to return to nominal.");
        diskSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, diskSlider.getPreferredSize().height));
        JLabel label = new JLabel("Disk");
        diskValue = new JLabel(formatDiskScale(Display.getDiskScale()), JLabel.RIGHT);
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

    // Log-spaced, MAX at the left so the disk grows leftward like Edge and Zoom.
    static double sliderToDiskScale(int value) {
        double t = Math.clamp(value, 0, 1000) / 1000.;
        return Display.DISK_SCALE_MAX * Math.pow(Display.DISK_SCALE_MIN / Display.DISK_SCALE_MAX, t);
    }

    static int diskScaleToSlider(double scale) {
        double t = Math.log(Math.clamp(scale, Display.DISK_SCALE_MIN, Display.DISK_SCALE_MAX) / Display.DISK_SCALE_MAX)
                / Math.log(Display.DISK_SCALE_MIN / Display.DISK_SCALE_MAX);
        return (int) Math.round(Math.clamp(t, 0, 1) * 1000);
    }

    private JPanel createZoomPanel() {
        zoomSlider = new JHVSlider(0, 1000, 500);
        zoomSlider.setToolTipText("View magnification, running the same way as Edge: left tighter, right wider. Far from 1× is where imagery softens and overlays crowd; double-click to recentre");
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

    private JPanel createWarpEdgePanel() {
        warpEdgeSlider = new JHVSlider(0, 1000, 1000);
        warpEdgeSlider.setToolTipText("Circular crop, in solar radii: cuts the picture to a disc without moving the camera or changing the warp. Zoom magnifies instead; rightmost is auto, no crop.");
        warpEdgeSlider.setPreferredSize(new Dimension(POPUP_SLIDER_WIDTH, warpEdgeSlider.getPreferredSize().height));
        JLabel label = new JLabel("Edge");
        warpEdgeValue = new JLabel("auto", JLabel.RIGHT);
        JLabel value = warpEdgeValue;
        value.setPreferredSize(new JLabel("-0.000").getPreferredSize());
        warpEdgeSlider.addChangeListener(e -> {
            if (!syncingFromTracker && CMETracker.getMode() == CMETracker.Mode.EDGE)
                CMETracker.stop(); // edge-mode tracking owns this slider; a manual move takes it back
            int t = warpEdgeSlider.getValue();
            if (t == 1000) {
                Display.setWarpOuterRadius(0); // auto: the full loaded FOV
                value.setText("auto");
            } else {
                double full = Math.max(ImageLayers.getLargestRadialSize(), 2);
                double radius = 2 * Math.pow(full / 2, t / 1000.);
                Display.setWarpOuterRadius(radius);
                value.setText(String.format("%.0f R\u2609", radius));
            }
            DisplayController.display();
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(label, BorderLayout.LINE_START);
        panel.add(warpEdgeSlider, BorderLayout.CENTER);
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
        javax.swing.JRadioButton activeProjection = projectionItems.get(ViewState.getProjection());
        if (activeProjection != null)
            activeProjection.setSelected(true);
        if (warpLambdaSlider != null) {
            warpLambdaSlider.setEnabled(ViewState.getProjection().usesWarpLambda());
            if (warpEdgeSlider != null)
                warpEdgeSlider.setEnabled(ViewState.getProjection().usesWarpEdge());
            warpLambdaSlider.setValue((int) Math.round(ViewState.getWarpLambda() * 1000));
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
