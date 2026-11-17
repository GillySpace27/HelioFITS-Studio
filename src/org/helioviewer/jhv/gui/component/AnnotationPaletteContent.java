package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;

import java.util.EnumMap;

import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.annotation.AnnotationMode;
import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.input.InputMouseListener;
import org.helioviewer.jhv.input.PointerEvent;

/**
 * Drawing mode, colour and thickness as a palette, next to Projection, HDR, Grid and Camera.
 *
 * <p>These lived in a submenu of the toolbar's More button, and a menu is the wrong container for
 * them. Annotation is a mode you stay in: you hold Shift and draw, and then you want the next
 * colour or a thinner line without losing what is on screen. A menu is momentary, so the thickness
 * slider in particular was a control you could not really use, since it closed the menu it lived
 * in and you had to reopen it to try the next value. That is the same reason the projection
 * controls are a palette rather than a dropdown.
 *
 * <p>The colour strip and the thickness row are the panels that were already being built for the
 * menu; only the mode radios changed shape, from JRadioButtonMenuItem to JRadioButton.
 */
final class AnnotationPaletteContent {

    private static final int SLIDER_WIDTH = 120;

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static final EnumMap<AnnotationMode, JRadioButton> modeButtons = new EnumMap<>(AnnotationMode.class);
    private static JHVSlider thickness;
    private static final EnumMap<Colors, JToggleButton> colorButtons = new EnumMap<>(Colors.class);
    private static boolean built;
    private static boolean syncing; // mirroring state into the widgets, not the user clicking them

    static Component build() {
        if (built)
            return panel;
        built = true;
        InputController.addListener(new InputMouseListener() {
            @Override
            public void mouseDragged(PointerEvent e) {
                shiftHint(e);
            }
        });

        JPanel modes = new JPanel(new GridLayout(0, 2, 0, 0));
        modes.setBorder(BorderFactory.createTitledBorder("Draw (hold Shift)"));
        ButtonGroup modeGroup = new ButtonGroup();
        for (AnnotationMode mode : AnnotationMode.values()) {
            JRadioButton button = new JRadioButton(mode.toString(), mode == ViewState.getAnnotationMode());
            button.setFocusPainted(false);
            button.addActionListener(e -> {
                if (!syncing)
                    ViewState.setAnnotationMode(mode);
            });
            modeGroup.add(button);
            modes.add(button);
            modeButtons.put(mode, button);
        }

        JPanel colors = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        ButtonGroup colorGroup = new ButtonGroup();
        for (Colors color : Annotations.BASE_COLORS) {
            JToggleButton button = new JToggleButton(new Swatch(color.awtColor()));
            button.setSelected(color == Annotations.getBaseColor());
            button.setToolTipText(color.toString());
            button.setFocusPainted(false);
            button.setPreferredSize(new Dimension(22, 22));
            button.addActionListener(e -> {
                if (!syncing)
                    Annotations.setBaseColor(color);
            });
            colorGroup.add(button);
            colors.add(button);
            colorButtons.put(color, button);
        }

        thickness = new JHVSlider(Annotations.MIN_THICKNESS, Annotations.MAX_THICKNESS, Annotations.DEFAULT_THICKNESS);
        thickness.setValue(Annotations.getThicknessValue());
        thickness.setMajorTickSpacing(1);
        thickness.setSnapToTicks(true);
        thickness.setToolTipText("Annotation thickness");
        thickness.setPreferredSize(new Dimension(SLIDER_WIDTH, thickness.getPreferredSize().height));
        thickness.addChangeListener(e -> {
            if (!syncing)
                Annotations.setThicknessValue(thickness.getValue());
        });

        JPanel look = new JPanel(new BorderLayout(0, 4));
        look.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        look.add(colors, BorderLayout.PAGE_START);
        look.add(thickness, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        actions.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        actions.add(new JButton(new Actions.ZoomFOVAnnotation()));
        actions.add(new JButton(new Actions.ClearAnnotations()));

        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        body.add(modes, BorderLayout.PAGE_START);
        body.add(look, BorderLayout.CENTER);
        body.add(actions, BorderLayout.PAGE_END);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /** Put back what the state says, for a session restore or anything else that set it elsewhere. */
    static void refresh() {
        if (!built)
            return;
        syncing = true;
        try {
            JRadioButton mode = modeButtons.get(ViewState.getAnnotationMode());
            if (mode != null)
                mode.setSelected(true);
            JToggleButton color = colorButtons.get(Annotations.getBaseColor());
            if (color != null)
                color.setSelected(true);
            thickness.setValue(Annotations.getThicknessValue());
        } finally {
            syncing = false;
        }
    }

    /** A filled square of one colour, the swatch the menu strip used. */
    private record Swatch(Color color) implements Icon {

        private static final int SIZE = 12;

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

    static final String HINT_KEY = "annotation.shiftHint.shown";

    // Once ever: the palette is showing, so the tool was picked, and a plain drag is moving the
    // view instead of drawing. Say what draws, at the pointer, for a few seconds.
    private static void shiftHint(PointerEvent e) {
        if (e.shiftDown() || Annotations.hasPending() || "true".equals(Settings.getProperty(HINT_KEY)))
            return;
        java.awt.EventQueue.invokeLater(() -> {
            Palette palette = Palette.named("Annotation");
            java.awt.PointerInfo pointer = java.awt.GraphicsEnvironment.isHeadless() ? null : java.awt.MouseInfo.getPointerInfo();
            if (palette == null || !palette.isOpen() || pointer == null || "true".equals(Settings.getProperty(HINT_KEY)))
                return;
            Settings.setProperty(HINT_KEY, "true");
            javax.swing.JToolTip tip = new javax.swing.JToolTip();
            tip.setTipText("Hold Shift and drag to draw an annotation");
            java.awt.Point at = pointer.getLocation();
            javax.swing.Popup popup = javax.swing.PopupFactory.getSharedInstance().getPopup(panel, tip, at.x + 14, at.y + 14);
            popup.show();
            javax.swing.Timer hide = new javax.swing.Timer(5000, done -> popup.hide());
            hide.setRepeats(false);
            hide.start();
        });
    }

    private AnnotationPaletteContent() {}
}
