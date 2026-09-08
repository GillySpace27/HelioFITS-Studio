package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The two things that made retiring JIDE from the chrome worth doing, and the two ways it can
 * silently go wrong.
 *
 * <p>Every flat button in the app is now an ordinary JButton carrying FlatLaf's toolbar-button
 * property. That property is the whole point: without it FlatLaf paints an ordinary bordered
 * button, and the bar goes back to looking like a row of boxes. Nothing on screen would say the
 * property had been dropped from the factory, so it is pinned here.
 *
 * <p>The first silent failure is {@code add}. On a JideSplitButton, add() filled the dropdown,
 * because it was a JMenu. On the replacement it would quietly add a third child next to the
 * button and the arrow, and the menu would come up empty: the control would look right and do
 * nothing. The replacement therefore refuses add() outright, and this check holds it to that.
 *
 * <p>The second is the arrow. It is painted here rather than by the look-and-feel, so it gets
 * none of what the look-and-feel does to the text beside it for free. It has to paint in the
 * component's own foreground, or it stays black on a dark theme, and it has to grey itself when
 * the button is switched off, or a dead control still advertises a menu. FlatLaf will not do the
 * second for us: its disabled-icon path hands back null for an icon that is not one of its own
 * types. The icon is therefore painted into an image here and the pixel is read back, because
 * "the arrow half has an icon" was the assertion this file used to make and it passes just as
 * happily when the arrow is the wrong colour.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.SplitButtonCheck
 */
public final class SplitButtonCheck {

    private static int failures;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        JButton flat = Buttons.flat("x");
        equal(flat.getClientProperty(FlatClientProperties.BUTTON_TYPE), FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON,
                "a flat button is a toolbar button to FlatLaf");
        expect(!flat.isRequestFocusEnabled(), "clicking a flat button does not take the keyboard focus");

        JToggleButton toggle = Buttons.flatToggle("y", true);
        equal(toggle.getClientProperty(FlatClientProperties.BUTTON_TYPE), FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON,
                "a flat toggle is a toolbar button too");
        expect(toggle.isSelected(), "the flat toggle keeps its initial state");
        expect(!toggle.isRequestFocusEnabled(), "clicking a flat toggle does not take the keyboard focus");

        SplitButton split = new SplitButton("z");
        equal(split.getComponentCount(), 2, "a split button is the button and the arrow, nothing else");
        split.addItem(new AbstractAction("one") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {}
        });
        split.addItemSeparator();
        split.addItem(new JPanel());
        equal(split.getPopupMenu().getComponentCount(), 3, "items, separators and panels all go into the menu");
        equal(split.getComponentCount(), 2, "and none of them become children of the button");

        boolean refused = false;
        try {
            split.add(new JPanel());
        } catch (UnsupportedOperationException e) {
            refused = true;
        }
        expect(refused, "add() is refused rather than quietly making a third button");

        Component component = split.getComponent(1);
        if (!(component instanceof JButton arrow)) {
            expect(false, "the arrow half is a button");
        } else {
            Icon icon = arrow.getIcon();
            expect(icon != null, "the arrow half carries the arrow icon");

            // A colour no look-and-feel would pick on its own, so a pixel that matches it can
            // only have come from this button's foreground.
            Color foreground = new Color(0x20, 0xC0, 0x40);
            arrow.setForeground(foreground);
            Color enabled = paint(icon, arrow);
            equal(enabled, foreground, "the arrow paints in the component's own foreground");

            split.setEnabled(false);
            expect(!arrow.isEnabled(), "disabling a split button reaches the arrow half");
            expect(!split.getComponent(0).isEnabled(), "and the button half");

            Color disabled = paint(icon, arrow);
            expect(!foreground.equals(disabled),
                    "a disabled split button greys its arrow instead of leaving it at full strength"
                            + " -- got " + disabled);
        }

        System.out.println(failures == 0 ? "SplitButtonCheck: PASS" : "SplitButtonCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    /**
     * The colour the icon actually puts on screen, read out of the middle of the triangle.
     *
     * <p>Sampled one row down from the top so that antialiasing along the two sloping edges
     * cannot reach it: at that row the triangle is nearly its full width, and the sample is a
     * fully covered pixel carrying the fill colour and nothing else.
     */
    private static Color paint(Icon icon, Component c) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(c, g, 0, 0);
        } finally {
            g.dispose();
        }
        return new Color(image.getRGB(icon.getIconWidth() / 2, 1), true);
    }

    private static void equal(Object got, Object want, String what) {
        if (!want.equals(got)) {
            System.out.println("FAIL: " + what + " -- got \"" + got + "\", want \"" + want + '"');
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private SplitButtonCheck() {}
}
