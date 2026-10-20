package org.helioviewer.jhv.gui;

import java.awt.Font;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.FontUIResource;

/**
 * A text-size change reaches fonts set by hand, which updateComponentTreeUI never touches: the
 * small labels, the mono readouts and the section headers stayed put (Gilly, 2026-10-20). The look
 * and feel's own fonts are left to the look and feel, and the icon font to the interface scale.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.FontRescaleCheck
 */
public final class FontRescaleCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        System.setProperty("user.home", System.getProperty("java.io.tmpdir")); // DisplayScale reads Settings
        UIGlobals.uiFontMDI = new Font("Dialog", Font.PLAIN, 12); // stands in for the icon font: a family of its own
        JPanel root = new JPanel();
        JPanel nested = new JPanel();
        JLabel small = new JLabel("detail");
        small.setFont(new Font("SansSerif", Font.PLAIN, 10));
        JLabel header = new JLabel("header");
        header.setFont(new Font("SansSerif", Font.BOLD, 16));
        JLabel laf = new JLabel("body");
        laf.setFont(new FontUIResource("SansSerif", Font.PLAIN, 13));
        JButton glyph = new JButton("x");
        glyph.setFont(UIGlobals.uiFontMDI);
        nested.add(header);
        root.add(small);
        root.add(nested);
        root.add(laf);
        root.add(glyph);

        // A shared detail font is remapped to its new self, whatever the body factor says.
        Font oldDetail = new Font("SansSerif", Font.PLAIN, 11);
        Font newDetail = new Font("SansSerif", Font.PLAIN, 11);
        JLabel detail = new JLabel("readout");
        detail.setFont(oldDetail);
        root.add(detail);
        // A section header is tagged and goes to headingFont, which needs the body font to size from.
        UIGlobals.uiFont = new Font("SansSerif", Font.PLAIN, 13);
        JButton heading = new JButton("Section");
        heading.putClientProperty(UIGlobals.HEADING, Boolean.FALSE);
        heading.setFont(new Font("SansSerif", Font.BOLD, 15));
        root.add(heading);

        UIGlobals.rescaleSetFonts(root, java.util.Map.of(oldDetail, newDetail.deriveFont(11f)), 1.5f);
        expect("a hand-set 10 pt label becomes 15 pt", small.getFont().getSize2D() == 15f);
        expect("a shared detail font takes its remapped size, not the body factor", detail.getFont().getSize2D() == 11f);
        expect("a tagged section header takes headingFont (bold, a step over the body at 100%)",
                heading.getFont().isBold() && heading.getFont().getSize2D() == 13f + UIGlobals.PARENT_HEADING_STEP);
        expect("a hand-set 16 pt header, nested a level down, becomes 24 pt", header.getFont().getSize2D() == 24f);
        expect("the header keeps its bold", header.getFont().isBold());
        expect("a look-and-feel font is left to the look and feel", laf.getFont().getSize2D() == 13f);
        expect("the icon font is left to the interface scale", glyph.getFont().getSize2D() == 12f);
        System.out.println(failures == 0 ? "FontRescaleCheck: ok" : "FontRescaleCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
