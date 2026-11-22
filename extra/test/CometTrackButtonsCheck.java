package org.helioviewer.jhv.event.info;

import java.awt.BorderLayout;
import java.awt.Rectangle;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;

/**
 * Every Track comet button is on screen in a narrow sidebar. The row used to be a FlowLayout in
 * BorderLayout.PAGE_END: given one row's height, the buttons that wrapped had none, so Show in
 * scene (the fourth, now Show in 3D scene) could not be pressed and no comet layer was ever added (2026-11-21).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.event.info.CometTrackButtonsCheck
 */
public final class CometTrackButtonsCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String[] names = {"Show in 3D scene", "Refresh", "Track (Warp)", "Track (Crop)"};
        for (int width : new int[]{220, 300, 380, 600}) {
            JButton[] buttons = new JButton[names.length];
            for (int i = 0; i < names.length; i++)
                buttons[i] = new JButton(names[i]);
            JPanel row = CometTrackPanel.buttonRow(buttons);
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JScrollPane(new JTable(3, 3)), BorderLayout.CENTER);
            panel.add(row, BorderLayout.PAGE_END);
            panel.setSize(width, 400);
            panel.doLayout();
            row.doLayout();
            for (JButton b : buttons) {
                Rectangle r = b.getBounds();
                boolean inside = r.width > 0 && r.height > 0 && r.x >= 0 && r.y >= 0
                        && r.x + r.width <= row.getWidth() && r.y + r.height <= row.getHeight();
                check(inside, width + " px wide: " + b.getText() + " inside its row (" + r.x + ',' + r.y + ' ' + r.width + 'x' + r.height
                        + " in " + row.getWidth() + 'x' + row.getHeight() + ')');
            }
        }
        System.out.println(failures == 0 ? "CometTrackButtonsCheck: PASS" : "CometTrackButtonsCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private CometTrackButtonsCheck() {}
}
