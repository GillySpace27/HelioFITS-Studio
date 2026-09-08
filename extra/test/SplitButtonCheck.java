package org.helioviewer.jhv.gui.component;

import java.awt.Component;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The two things that made retiring JIDE from the chrome worth doing, and the one way it can
 * silently go wrong.
 *
 * <p>Every flat button in the app is now an ordinary JButton carrying FlatLaf's toolbar-button
 * property. That property is the whole point: without it FlatLaf paints an ordinary bordered
 * button, and the bar goes back to looking like a row of boxes. Nothing on screen would say the
 * property had been dropped from the factory, so it is pinned here.
 *
 * <p>The silent failure is {@code add}. On a JideSplitButton, add() filled the dropdown, because
 * it was a JMenu. On the replacement it would quietly add a third child next to the button and
 * the arrow, and the menu would come up empty: the control would look right and do nothing. The
 * replacement therefore refuses add() outright, and this check holds it to that.
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

        // The arrow has to paint from the component's foreground, or it stays black on a dark
        // theme and stays black when the button is disabled.
        Component arrow = split.getComponent(1);
        expect(arrow instanceof JButton button && button.getIcon() != null, "the arrow half carries the arrow icon");

        System.out.println(failures == 0 ? "SplitButtonCheck: PASS" : "SplitButtonCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
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
