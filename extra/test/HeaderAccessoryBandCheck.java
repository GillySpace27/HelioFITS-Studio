package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Container;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.app.Theme;

/**
 * The buttons at the end of a section header sit inside the title's band, not over its groove.
 *
 * <p>The title band stops a row short of the header and draws a one-pixel groove in that row. The
 * strip beside it (padlock, movers, Maximize) fills the whole header, so its buttons covered the
 * groove row and the groove broke off beside the title: the padlock's tab looked taller than the
 * header and hung below it. Gilly, 2026-11-08.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.HeaderAccessoryBandCheck
 */
public final class HeaderAccessoryBandCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component k : c.getComponents())
            if (k instanceof Container cc)
                layout(cc);
    }

    public static void main(String[] args) {
        JPanel body = new JPanel();
        body.add(new JLabel("controls"));
        CollapsiblePane pane = new CollapsiblePane("Layers", body, false, false);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        JButton button = new JButton("L");
        bar.add(button);
        pane.setAccessory(bar);

        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.add(pane, java.awt.BorderLayout.CENTER);
        int w = 320;
        root.setSize(w, root.getPreferredSize().height);
        layout(root);

        JPanel header = (JPanel) pane.getComponent(0);
        int h = header.getHeight();
        BufferedImage img = new BufferedImage(w, root.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        root.paint(g);
        g.dispose();

        java.awt.Point p = javax.swing.SwingUtilities.convertPoint(button, 0, 0, header);
        expect("the button ends above the header's last row", p.y + button.getHeight() <= h - 1);
        expect("the button starts inside the header", p.y >= 0);
        Color groove = Theme.current().get(Theme.Token.Background);
        int under = img.getRGB(w - 2, h - 1);
        expect("the groove runs unbroken under the button", under == groove.getRGB());
        expect("and under the title", img.getRGB(10, h - 1) == groove.getRGB());

        System.out.println(failures == 0 ? "HeaderAccessoryBandCheck: PASS" : "HeaderAccessoryBandCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private HeaderAccessoryBandCheck() {}
}
