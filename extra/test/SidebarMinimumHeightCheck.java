package org.helioviewer.jhv.gui.component;

import java.awt.Dimension;

import javax.swing.JPanel;

/**
 * A sidebar squeezed narrower than its contents still gives every section its full height.
 *
 * <p>GridBagLayout falls back to minimum sizes for everything as soon as its container is short in
 * EITHER dimension (arrangeGrid, JDK 25 GridBagLayout.java:2070), and the sidebars are always short in
 * width, because SqueezeView refuses to let a wide label push a horizontal scrollbar. So sections were
 * always laid out at their minimum height. Seen three ways: the Track CME table drawn as a bare header,
 * the layer lists cut short, and the image layer readout jumping up and down with every frame, since
 * its minimum height followed whatever the current frame's text happened to wrap to.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.SidebarMinimumHeightCheck
 */
public final class SidebarMinimumHeightCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("jhv-sidebar-min").toString());

        // Wants 600 x 200, will settle for 10 x 20: the shape of a wrapping label or a table.
        @SuppressWarnings("serial")
        JPanel content = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(600, 200);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(10, 20);
            }
        };
        SideContentPane pane = new SideContentPane();
        pane.add("Readout", content, true);

        Dimension wants = pane.getPreferredSize();
        pane.setSize(wants.width / 2, wants.height + 100); // half the width it asks for, more height than it needs
        pane.doLayout();
        CollapsiblePane section = (CollapsiblePane) pane.getComponent(0);
        section.doLayout();

        expect("squeezed in width, the section still gets its whole height (" + section.getHeight() + " of "
                + section.getPreferredSize().height + ")", section.getHeight() == section.getPreferredSize().height);
        expect("so its content gets the 200 px it asked for, not its 20 px minimum (" + content.getHeight() + ")",
                content.getHeight() == 200);
        expect("while the section's width stays free to squeeze", section.getMinimumSize().width == 0);

        System.out.println(failures == 0 ? "SidebarMinimumHeightCheck: PASS" : "SidebarMinimumHeightCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private SidebarMinimumHeightCheck() {}
}
