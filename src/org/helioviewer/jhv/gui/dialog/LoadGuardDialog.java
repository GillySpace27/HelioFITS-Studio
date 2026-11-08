package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Locale;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import org.helioviewer.jhv.app.state.LoadGuard;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * The manifest of a session that is about to download more than the limit, and the question:
 * download now, or postpone. Postponed layers stay in the scene, held, and Refresh on a layer
 * starts it later (ImageLayer.releaseHold). Closing the window postpones, never downloads.
 */
public final class LoadGuardDialog {

    private LoadGuardDialog() {}

    /** True to download now; false to postpone. On the event thread. */
    public static boolean download(LoadGuard.Estimate estimate) {
        String[] columns = {"Layer", "Source", "Frames", "Cached", "To download", "About"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (LoadGuard.Item i : estimate.items()) {
            model.addRow(i.unknown()
                    ? new Object[]{i.name(), i.source(), "?", "?", "?", "?"}
                    : new Object[]{i.name(), i.source(), String.format(Locale.ROOT, "%,d", i.frames()),
                    String.format(Locale.ROOT, "%,d", i.cached()), String.format(Locale.ROOT, "%,d", i.toDownload()),
                    LoadGuard.sizeText(i.bytes())});
        }
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, Math.min(300, 40 + 22 * model.getRowCount())));

        JLabel text = new JLabel(String.format(Locale.ROOT,
                "<html>This session would download about <b>%,d frames</b>, roughly <b>%s</b> (an estimate: sizes are assumed, not read).<br>"
                        + "Your limit is %,d frames or %s (Settings). Frames already cached are not counted and are used as they are.<br>"
                        + "Postponed layers stay in the scene, empty. Refresh on a layer downloads it later.</html>",
                estimate.frames(), LoadGuard.sizeText(estimate.bytes()), LoadGuard.maxFrames(), LoadGuard.sizeText(LoadGuard.maxBytes())));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(text, BorderLayout.PAGE_START);
        panel.add(scroll, BorderLayout.CENTER);

        String[] options = {"Postpone", "Download Now"};
        int choice = JOptionPane.showOptionDialog(MainFrame.get(), panel, "Review the download before it starts",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return choice == 1;
    }
}
