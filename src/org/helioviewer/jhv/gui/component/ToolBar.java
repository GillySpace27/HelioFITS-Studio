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
    private final ButtonText CUTOUT = new ButtonText(Buttons.cutOut, "SDO Cut-out", "Send layers to SDO cut-out service");
    private final ButtonText DIFFROTATION = new ButtonText(Buttons.diffRotation, "Differential", "Toggle differential rotation");
    private final ButtonText MULTIVIEW = new ButtonText(Buttons.multiview, "Multiview", "Multiview");
    private final ButtonText OFFDISK = new ButtonText(Buttons.offDisk, "Corona", "Toggle off-disk corona");
    private final ButtonText PAN = new ButtonText(Buttons.pan, "Pan", "Pan");
    private final ButtonText PROJECTION = new ButtonText(Buttons.projection, "Projection", "Projection");
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
    private JideToggleButton refreshButton;
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

        switch (interactionMode) {
            case PAN -> pan.setSelected(true);
            case AXIS -> axis.setSelected(true);
            case ROTATE -> rotate.setSelected(true);
        }
        InputController.setMode(interactionMode);

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
        projectionToggle = projectionButton; // so the View menu can toggle the same palette
        if (projectionPalette != null) {
            projectionPalette.dispose(); // toolbar is recreated on display-mode change
            projectionPalette = null;
        }
        projectionButton.addActionListener(e -> {
            if (projectionPalette == null)
                projectionPalette = createProjectionPalette(projectionButton);
            if (projectionButton.isSelected()) {
                dockPalette();
                projectionPalette.setVisible(true);
            } else
                projectionPalette.setVisible(false);
        });
        addButton(projectionButton);

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

        refreshButton = toolToggleButton(REFRESH);
        refreshButton.setSelected(ViewState.isRefresh());
        refreshButton.addItemListener(e -> ViewState.setRefresh(refreshButton.isSelected()));
        addButton(refreshButton);

        addSeparator(dim);

        JideButton cutOut = toolButton(CUTOUT);
        cutOut.addActionListener(new Actions.SDOCutOut());
        addButton(cutOut);

        if (Boolean.parseBoolean(Settings.getProperty("startup.sampHub"))) {
            JideButton samp = toolButton(SAMP);
            samp.addActionListener(e -> SampClient.notifyRequestData());
            addButton(samp);
        }

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

    private JDialog projectionPalette;
    private boolean palettePinned = true; // pinned: docks to the corner + follows; unpinned: free-floating
    private static JideToggleButton projectionToggle; // current toolbar's projection button, for the View menu

    // Toggle the projection palette exactly as the toolbar button does (used by View ▸ Projection).
    public static void toggleProjectionPalette() {
        if (projectionToggle != null)
            projectionToggle.doClick();
    }

    private static JideToggleButton presentationToggle; // current toolbar's presentation button

    // Toggle presentation mode exactly as the toolbar button does (used by View ▸ Presentation Mode
    // and by Escape). Falls through to the mode directly if the toolbar is mid-recreate, so the
    // Escape route can never be dead.
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

    // Pin the palette to the top-right corner of the render canvas; it follows the window.
    private void dockPalette() {
        if (!palettePinned || projectionPalette == null)
            return;
        // In presenter view the render canvas is on the projector, so docking to it would park
        // the palette on top of the output the audience is watching. The palette is a control,
        // so it belongs with the rest of the chrome on the presenter's screen.
        java.awt.Window chrome = org.helioviewer.jhv.gui.PresentationMode.chromeWindow();
        if (chrome != null && chrome.isShowing()) {
            projectionPalette.setLocation(
                    chrome.getX() + chrome.getWidth() - projectionPalette.getWidth() - 12,
                    chrome.getY() + 12);
            return;
        }
        java.awt.Component rc = org.helioviewer.jhv.gui.MainFrame.getRenderComponent();
        if (rc == null || !rc.isShowing())
            return;
        java.awt.Point loc = rc.getLocationOnScreen();
        projectionPalette.setLocation(loc.x + rc.getWidth() - projectionPalette.getWidth() - 12, loc.y + 12);
    }

    // Presentation mode moves the chrome to another screen, so the palette has to be re-docked
    // when it enters or leaves. The toolbar instance is per-window and gets rebuilt on display
    // mode changes, so route through the live one.
    private static ToolBar current;

    public static void redockProjectionPalette() {
        if (current != null)
            current.rebuildPalette();
    }

    private static boolean paletteFrameListenersAdded;

    private static void redockIfOpen() {
        if (current != null && current.projectionPalette != null && current.projectionPalette.isVisible())
            current.dockPalette();
    }

    private static java.awt.Window paletteOwner() {
        java.awt.Window chrome = org.helioviewer.jhv.gui.PresentationMode.chromeWindow();
        return chrome != null ? chrome : org.helioviewer.jhv.gui.MainFrame.get();
    }

    // Recreate the palette under the current owner, preserving whether it was open. Called when
    // presentation mode moves the controls to another window.
    private void rebuildPalette() {
        if (projectionPalette == null) {
            dockPalette();
            return;
        }
        boolean wasVisible = projectionPalette.isVisible();
        projectionPalette.dispose();
        projectionPalette = null;
        if (wasVisible && projectionToggle != null) {
            projectionPalette = createProjectionPalette(projectionToggle);
            dockPalette();
            projectionPalette.setVisible(true);
        }
    }

    private JDialog createProjectionPalette(JideToggleButton toggle) {
        // Owned by whichever window is carrying the controls. Ownership, not position, is what
        // decides the screen here: macOS makes an owned window a child of its parent, so a
        // palette owned by the main frame is dragged onto the projector with it no matter where
        // it was told to sit. Re-owning is the only way to keep it with the controls, and a
        // JDialog's owner is fixed at construction -- hence rebuildPalette() below.
        JDialog palette = new JDialog(paletteOwner(), java.awt.Dialog.ModalityType.MODELESS);
        palette.setUndecorated(true); // no OS chrome: a docked tool palette, not a window
        palette.setFocusableWindowState(false); // don't steal keyboard focus from the view
        // UTILITY is the window class macOS actually has for a floating tool palette (an NSPanel).
        // Left as a plain undecorated non-focusable dialog it is an ordinary NSWindow that never
        // becomes key, and a never-key window does not get its cursor rects re-evaluated -- the
        // pointer keeps whatever the last component set and can be left invisible over the palette.
        // Three other dialogs here (FITSSettings, CactusTrackDialog, SWEKEventInformationDialog)
        // already use UTILITY; this one was the odd one out.
        palette.setType(java.awt.Window.Type.UTILITY);

        JPanel content = new JPanel();
        // Belt and braces with the UTILITY type above: state the cursor this panel wants, so
        // entering it always restores a visible arrow instead of inheriting a stale one.
        content.setCursor(java.awt.Cursor.getDefaultCursor());
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(content.getBackground().brighter()),
                BorderFactory.createEmptyBorder(4, 8, 6, 8)));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("Projection");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        title.setToolTipText("Drag to move (undocks); use the pin to re-dock to the corner");
        header.add(title, BorderLayout.CENTER);

        // Drag the header to float the palette anywhere (this undocks it).
        java.awt.event.MouseAdapter dragger = new java.awt.event.MouseAdapter() {
            private java.awt.Point grab;

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                grab = e.getPoint();
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                palettePinned = false; // dragging floats it
                java.awt.Point on = e.getLocationOnScreen();
                palette.setLocation(on.x - grab.x, on.y - grab.y);
            }
        };
        title.addMouseListener(dragger);
        title.addMouseMotionListener(dragger);

        JPanel headerButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 0, 0));
        headerButtons.setOpaque(false);
        JideToggleButton pin = new JideToggleButton("\u25f1"); // dock-to-corner glyph
        pin.setSelected(palettePinned);
        pin.setToolTipText("Dock to the top-right corner (unpin to float freely)");
        pin.addActionListener(e -> {
            palettePinned = pin.isSelected();
            if (palettePinned)
                dockPalette();
        });
        JideButton close = new JideButton("\u2715");
        close.setToolTipText("Collapse (the toolbar Projection button reopens it)");
        close.addActionListener(e -> {
            toggle.setSelected(false);
            palette.setVisible(false);
        });
        headerButtons.add(pin);
        headerButtons.add(close);
        header.add(headerButtons, BorderLayout.LINE_END);
        content.add(header);
        content.add(new javax.swing.JSeparator());

        ButtonGroup projectionGroup = new ButtonGroup();
        for (MapMode el : MapMode.values()) {
            javax.swing.JRadioButton item = new javax.swing.JRadioButton(el.toString());
            if (el == ViewState.getProjection())
                item.setSelected(true);
            item.addActionListener(e -> ViewState.setProjection(el));
            projectionGroup.add(item);
            content.add(item);
            projectionItems.put(el, item);
        }
        content.add(new javax.swing.JSeparator());
        content.add(createWarpLambdaPanel());
        content.add(createWarpEdgePanel());
        content.add(createHelioradial3DPanel());
        boolean warpOn = ViewState.getProjection().usesWarpLambda();
        warpLambdaSlider.setEnabled(warpOn);
        warpEdgeSlider.setEnabled(warpOn);
        helioradial3DBox.setEnabled(ViewState.getProjection() == MapMode.Helioradial);
        CMETracker.addSolveListener(this::syncWarpSlidersFromTracker); // follow the tracked knob

        palette.setContentPane(content);
        palette.pack();

        // These two are registered once for the lifetime of the app, and deliberately act on
        // the projectionPalette FIELD rather than on the palette local: rebuildPalette() can
        // construct the palette many times, so a listener that captured one instance would go
        // on nudging a disposed window while the live one sat unmanaged.
        if (!paletteFrameListenersAdded) {
            paletteFrameListenersAdded = true;
            java.awt.event.ComponentAdapter follow = new java.awt.event.ComponentAdapter() {
                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    redockIfOpen();
                }

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    redockIfOpen();
                }
            };
            org.helioviewer.jhv.gui.MainFrame.get().addComponentListener(follow);
            // Owned + non-focusable, so clicking into the render view raises the frame above the
            // palette. Re-raise it (without stealing focus) whenever the frame comes forward:
            // this keeps it above the JHV window only, never over other apps the way
            // alwaysOnTop would.
            org.helioviewer.jhv.gui.MainFrame.get().addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowActivated(java.awt.event.WindowEvent e) {
                    if (current == null || current.projectionPalette == null)
                        return;
                    // Window.Type.UTILITY makes this an NSPanel, and macOS hides utility panels
                    // when the owning application deactivates. That is what made the palette
                    // vanish on every click away. The toggle button is the record of whether the
                    // user wants it open, so restore from that rather than from isVisible, which
                    // the platform has already set to false behind our back.
                    if (current.projectionToggle != null && current.projectionToggle.isSelected()
                            && !current.projectionPalette.isVisible())
                        current.projectionPalette.setVisible(true);
                    if (current.projectionPalette.isVisible())
                        current.projectionPalette.toFront();
                }
            });
        }
        return palette;
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
    private JPanel createHelioradial3DPanel() {
        helioradial3DBox = new javax.swing.JCheckBox("Render in 3D", Display.isHelioradial3D());
        helioradial3DBox.setToolTipText("Draw Helioradial as a rotatable surface instead of a flat face-on disk");
        // setHelioradial3D does the camera reset itself, the same way a projection change does.
        helioradial3DBox.addItemListener(e -> Display.setHelioradial3D(helioradial3DBox.isSelected()));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        panel.add(helioradial3DBox, BorderLayout.LINE_START);
        return panel;
    }

    private JPanel createWarpEdgePanel() {
        warpEdgeSlider = new JHVSlider(0, 1000, 1000);
        warpEdgeSlider.setToolTipText("Outer edge of the warp projections (far right: full field of view)");
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
        refreshButton.setSelected(ViewState.isRefresh());
        javax.swing.JRadioButton activeProjection = projectionItems.get(ViewState.getProjection());
        if (activeProjection != null)
            activeProjection.setSelected(true);
        if (warpLambdaSlider != null) {
            boolean warpEnabled = ViewState.getProjection().usesWarpLambda();
            warpLambdaSlider.setEnabled(warpEnabled);
            if (warpEdgeSlider != null)
                warpEdgeSlider.setEnabled(warpEnabled);
            warpLambdaSlider.setValue((int) Math.round(ViewState.getWarpLambda() * 1000));
        }
        // Enabled state has to be refreshed on every projection change, not just set once when
        // the palette is built: a palette constructed while another projection was selected
        // would otherwise stay disabled for the life of the window.
        if (helioradial3DBox != null) {
            helioradial3DBox.setEnabled(ViewState.getProjection() == MapMode.Helioradial);
            helioradial3DBox.setSelected(Display.isHelioradial3D());
        }
        if (warpLambdaValue != null)
            warpLambdaValue.setText(String.format("%.3f", ViewState.getWarpLambda()));
        JRadioButtonMenuItem activeAnnotationMode = annotationItems.get(ViewState.getAnnotationMode());
        if (activeAnnotationMode != null)
            activeAnnotationMode.setSelected(true);
    }

}
