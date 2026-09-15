package org.helioviewer.jhv.event.info;

import java.awt.Component;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * The Track CME list shows every row it has, however the sidebar is laid out around it.
 *
 * <p>Docked in the right sidebar at launch for the first time, it read "23 CACTus event(s)" above a
 * column header with no rows under it: the table's scroll pane was squeezed to its one-header minimum
 * by the sidebar's GridBag, and the palette had no minimum of its own to stop it. Gilly's rule for
 * sidebar lists is that a member you can see the list for is never hidden behind a scroll or a resize.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.event.info.CactusTrackSizeCheck
 */
public final class CactusTrackSizeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-cactus-size").toString());

        Constructor<CactusTrackPanel> ctor = CactusTrackPanel.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CactusTrackPanel panel = ctor.newInstance();
        Field modelField = CactusTrackPanel.class.getDeclaredField("model");
        modelField.setAccessible(true);
        DefaultTableModel model = (DefaultTableModel) modelField.get(panel);
        JTable table = find(panel, JTable.class);
        JScrollPane scroller = find(panel, JScrollPane.class);

        expect("empty, the panel never gives up its own height", panel.getMinimumSize().height == panel.getPreferredSize().height);

        for (int i = 0; i < 23; i++) // the count from the launch that showed none of them
            model.addRow(new Object[]{"2026-09-11 00:00", 400, 60, 90, "CACTus"});
        panel.fitRows();

        int rowsHeight = table.getPreferredSize().height;
        int viewport = scroller.getViewport().getPreferredSize().height;
        expect("with 23 rows the viewport asks for all of them (" + viewport + " px for " + rowsHeight + ")",
                viewport >= rowsHeight);
        expect("and the panel's minimum is that whole height, so a GridBag cannot squeeze rows away",
                panel.getMinimumSize().height == panel.getPreferredSize().height
                        && panel.getMinimumSize().height >= rowsHeight);
        expect("while its width stays free for the sidebar to squeeze", panel.getMinimumSize().width == 0);

        System.out.println(failures == 0 ? "CactusTrackSizeCheck: PASS" : "CactusTrackSizeCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static <T> T find(Component c, Class<T> type) {
        if (type.isInstance(c))
            return type.cast(c);
        if (c instanceof java.awt.Container container)
            for (Component child : container.getComponents()) {
                if (child instanceof JScrollPane sp && type == JTable.class)
                    return type.cast(sp.getViewport().getView());
                T found = find(child, type);
                if (found != null)
                    return found;
            }
        return null;
    }

    private CactusTrackSizeCheck() {}
}
