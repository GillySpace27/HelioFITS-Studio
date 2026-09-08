package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * A button with a menu hung off it: what JideSplitButton did, built from two ordinary buttons.
 *
 * <p>JideSplitButton is painted by BasicJideButtonUI, which reads none of FlatLaf's client
 * properties, so these were the controls on the bar that could not follow the theme: no hover,
 * no disabled foreground, no toolbar rounding. Two JButtons get all of that from the
 * look-and-feel, at the cost of writing the dropdown behaviour here.
 *
 * <p>The arrow is always its own button, even when {@link #setAlwaysDropdown} makes the whole
 * control a dropdown. A single button with a trailing arrow icon would be narrower, but the icon
 * slot is already taken on most of these (a glyph plus a label), and losing the arrow entirely
 * would leave no sign that there is a menu.
 */
@SuppressWarnings("serial")
public final class SplitButton extends JPanel {

    private final JButton main;
    private final JButton arrow;
    private final JPopupMenu popup = new JPopupMenu();

    private boolean alwaysDropdown;
    // Pressing the button while its menu is open closes the menu first, so by the time the click
    // arrives the menu is already gone and would simply be reopened: the button would look dead.
    private long popupHiddenAt;

    public SplitButton(Icon icon) {
        this((String) null);
        main.setIcon(icon);
    }

    public SplitButton(String text) {
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
        setOpaque(false);

        main = Buttons.flat(text);
        arrow = Buttons.flat("");
        arrow.setIcon(ARROW);
        arrow.setMargin(new Insets(0, 0, 0, 0));

        main.addActionListener(e -> {
            if (alwaysDropdown)
                showPopup();
        });
        arrow.addActionListener(e -> showPopup());
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                popupHiddenAt = System.currentTimeMillis();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        super.add(main);
        super.add(arrow);
    }

    /**
     * Refuse the JIDE habit loudly.
     *
     * <p>On a JideSplitButton {@code add} filled the dropdown, because it was a JMenu. Here it
     * would quietly add a child to the panel, next to the two buttons, and the menu would come up
     * empty: exactly the silent failure this conversion could leave behind everywhere.
     */
    @Override
    public Component add(Component c) {
        throw new UnsupportedOperationException("SplitButton.addItem fills the menu; add would make a third button");
    }

    private void showPopup() {
        if (System.currentTimeMillis() - popupHiddenAt < 200)
            return;
        popup.show(this, 0, getHeight());
    }

    /** Whether pressing the button itself opens the menu, rather than firing its own action. */
    public void setAlwaysDropdown(boolean b) {
        alwaysDropdown = b;
    }

    public JPopupMenu getPopupMenu() {
        return popup;
    }

    /** Open the menu from code, as the layer list does after adding a source. */
    public void doClickOnMenu() {
        showPopup();
    }

    public JMenuItem addItem(Action a) {
        return popup.add(a);
    }

    public void addItem(Component c) {
        popup.add(c);
    }

    public void addItemSeparator() {
        popup.addSeparator();
    }

    /** Listeners go on the button half; the arrow half only ever opens the menu. */
    public void addActionListener(ActionListener l) {
        main.addActionListener(l);
    }

    @Override
    public void setToolTipText(String text) {
        main.setToolTipText(text);
        arrow.setToolTipText(text);
    }

    public void setText(String text) {
        main.setText(text);
    }

    public void setIcon(Icon icon) {
        main.setIcon(icon);
    }

    /** The toolbar's icon-over-label arrangement, applied to the button half. */
    void dress(Icon icon, String text) {
        main.setIcon(icon);
        main.setText(text);
        main.setHorizontalTextPosition(SwingConstants.CENTER);
        main.setVerticalTextPosition(SwingConstants.BOTTOM);
    }

    private static final Icon ARROW = new Icon() {

        private static final int WIDTH = 9;
        private static final int HEIGHT = 5;

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
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // The component's own foreground, so the look-and-feel's disabled and pressed
                // colours reach the arrow the same way they reach the text beside it.
                g2.setColor(c.getForeground());
                g2.fillPolygon(new int[]{x, x + WIDTH, x + WIDTH / 2}, new int[]{y, y, y + HEIGHT}, 3);
            } finally {
                g2.dispose();
            }
        }
    };

}
