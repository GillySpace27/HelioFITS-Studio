package org.helioviewer.jhv.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;

import org.helioviewer.jhv.app.Log;

/**
 * Every control on screen that is smaller than it asked to be.
 *
 * <p>"Panels are not large enough to keep the contents from overflowing" is a symptom with many
 * causes, and guessing at them means changing sizes that were right. This asks the components
 * themselves: a control that reports a preferred width and has been given less is truncated,
 * whatever the reason, and it can say so.
 *
 * <p>Only leaves are reported. A container being narrower than its preferred size is usually
 * correct and is how a sidebar compresses at all; it is the label, field or combo at the bottom of
 * the tree that actually loses characters. A container appears only in the path, to say where.
 *
 * <p>Not a check, because there is nothing to assert: which of these matter is a judgement about
 * the window in front of you, and a session with eight layers loaded truncates things an empty one
 * never reaches. Run it on the window you are complaining about.
 */
public final class LayoutProbe {

    /** Pixels a control may be short by before it is worth reporting. Rounding, mostly. */
    private static final int SLACK = 2;

    public static List<String> report(Component root) {
        List<String> out = new ArrayList<>();
        walk(root, "", out);
        out.sort((a, b) -> Integer.compare(deficit(b), deficit(a)));
        return out;
    }

    /** Run it over the main window and put the result in the log. Help > Report Clipped Controls. */
    public static void logReport() {
        Component root = MainFrame.get();
        if (root == null)
            return;
        List<String> lines = report(root);
        Log.info("LayoutProbe: " + lines.size() + " control(s) narrower than they asked to be");
        for (String line : lines)
            Log.info("LayoutProbe: " + line);
        System.out.println("LayoutProbe: " + lines.size() + " clipped");
        for (String line : lines)
            System.out.println("  " + line);
    }

    private static void walk(Component c, String path, List<String> out) {
        // Both dimensions, because on macOS the screen menu bar leaves its JMenus in the frame at
        // zero height: laid out nowhere, reported as short of everything, and not on screen at all.
        if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0)
            return;
        String here = path + (path.isEmpty() ? "" : " > ") + describe(c);
        if (isLeaf(c)) {
            Dimension pref = c.getPreferredSize();
            int dw = pref.width - c.getWidth();
            int dh = pref.height - c.getHeight();
            if (dw > SLACK || dh > SLACK)
                out.add(String.format("%4d x %-4d short of %4d x %-4d  %s",
                        c.getWidth(), c.getHeight(), pref.width, pref.height, here));
            return;
        }
        if (c instanceof Container container)
            for (Component child : container.getComponents())
                walk(child, here, out);
    }

    /**
     * Whether this is where the truncation actually happens.
     *
     * <p>A combo box, a spinner and a text field all have children of their own (an editor, an
     * arrow button), so "has no children" is not the test: they are leaves in the sense that
     * matters, which is that their contents cannot be laid out any smaller.
     */
    private static boolean isLeaf(Component c) {
        return c instanceof JLabel || c instanceof AbstractButton || c instanceof JComboBox
                || c instanceof JSpinner || c instanceof JTextComponent
                || (c instanceof Container container && container.getComponentCount() == 0);
    }

    private static String describe(Component c) {
        String name = c.getClass().getSimpleName();
        if (name.isEmpty())
            name = c.getClass().getName(); // anonymous subclasses, of which Swing code has many
        String text = text(c);
        return text == null || text.isBlank() ? name : name + "[" + text.trim() + "]";
    }

    private static String text(Component c) {
        if (c instanceof JLabel label)
            return label.getText();
        if (c instanceof AbstractButton button)
            return button.getText();
        if (c instanceof JTextComponent field)
            return field.getText();
        if (c instanceof JComboBox<?> combo)
            return String.valueOf(combo.getSelectedItem());
        return null;
    }

    private static int deficit(String line) {
        try { // the report is sorted worst-first; the numbers are at fixed places in the format
            return Integer.parseInt(line.substring(21, 26).trim()) - Integer.parseInt(line.substring(0, 4).trim());
        } catch (RuntimeException ignore) {
            return 0;
        }
    }

    private LayoutProbe() {}

}
