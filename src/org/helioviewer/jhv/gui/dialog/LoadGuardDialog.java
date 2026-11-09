package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

import org.helioviewer.jhv.app.state.LoadGuard;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * The manifest of a session that is about to download more than the limit, and one choice per layer:
 * Postpone, or Download / load from disk. A layer starts on Postpone when it alone is over either
 * limit, and on Download / load from disk otherwise (LoadGuard.Item.postponeByDefault). Postponed
 * layers stay in the scene, held, and Refresh on a layer starts it later (ImageLayer.releaseHold).
 * Closing the window postpones everything that would download, never downloads.
 */
public final class LoadGuardDialog {

    private LoadGuardDialog() {}

    /** The places in imageLayers of the layers to hold. On the event thread. */
    public static Set<Integer> postponed(LoadGuard.Estimate estimate) {
        long maxFrames = LoadGuard.maxFrames(), maxBytes = LoadGuard.maxBytes();
        List<LoadGuard.Item> items = estimate.items();
        int n = items.size();
        JRadioButton[] postpone = new JRadioButton[n], load = new JRadioButton[n];
        Set<Integer> holdAll = estimate.downloading();
        boolean[] go = {false};

        JLabel total = new JLabel();
        Runnable update = () -> {
            long frames = 0, bytes = 0;
            for (int i = 0; i < n; i++)
                if (load[i].isSelected() && !items.get(i).unknown()) {
                    frames += items.get(i).toDownload();
                    bytes += items.get(i).bytes();
                }
            total.setText(String.format(Locale.ROOT, "<html>Will download <b>%,d frames</b>, about <b>%s</b> (an estimate: sizes are assumed, not read).</html>",
                    frames, LoadGuard.sizeText(bytes)));
        };

        JPanel grid = new JPanel(new GridBagLayout());
        String[] heads = {"Layer", "Source", "Frames", "Cached", "To download", "About", "Postpone", "Download / load from disk"};
        for (int c = 0; c < heads.length; c++)
            grid.add(new JLabel("<html><b>" + heads[c] + "</b></html>"), cell(c, 0));
        for (int i = 0; i < n; i++) {
            LoadGuard.Item it = items.get(i);
            boolean hold = it.postponeByDefault(maxFrames, maxBytes);
            postpone[i] = new JRadioButton(null, null, hold);
            load[i] = new JRadioButton(null, null, !hold);
            ButtonGroup group = new ButtonGroup();
            group.add(postpone[i]);
            group.add(load[i]);
            postpone[i].addActionListener(e -> update.run());
            load[i].addActionListener(e -> update.run());
            String[] cells = it.unknown()
                    ? new String[]{it.name(), it.source(), "?", "?", "?", "?"}
                    : new String[]{it.name(), it.source(), String.format(Locale.ROOT, "%,d", it.frames()),
                    String.format(Locale.ROOT, "%,d", it.cached()), String.format(Locale.ROOT, "%,d", it.toDownload()),
                    LoadGuard.sizeText(it.bytes())};
            for (int c = 0; c < cells.length; c++)
                grid.add(new JLabel(cells[c]), cell(c, i + 1));
            grid.add(postpone[i], cell(6, i + 1));
            grid.add(load[i], cell(7, i + 1));
        }
        update.run();

        JLabel text = new JLabel(String.format(Locale.ROOT,
                "<html>This session would download about <b>%,d frames</b>, roughly <b>%s</b>. Your limit is %,d frames or %s (Settings).<br>"
                        + "Frames already cached are not counted and load from disk. Postponed layers stay in the scene, empty;<br>"
                        + "Refresh on a layer downloads it later.</html>",
                estimate.frames(), LoadGuard.sizeText(estimate.bytes()), maxFrames, LoadGuard.sizeText(maxBytes)));

        JDialog dialog = new JDialog(MainFrame.get(), "Review the download before it starts", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JButton goButton = new JButton("Go");
        goButton.addActionListener(e -> {
            go[0] = true;
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(goButton);
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(total, BorderLayout.CENTER);
        bottom.add(goButton, BorderLayout.LINE_END);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(text, BorderLayout.PAGE_START);
        JScrollPane scroll = new JScrollPane(grid);
        scroll.setPreferredSize(new Dimension(940, Math.min(320, 70 + 26 * n)));
        content.add(scroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.PAGE_END);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        dialog.setVisible(true); // modal: returns when disposed

        if (!go[0])
            return holdAll;
        Set<Integer> held = new TreeSet<>();
        for (int i = 0; i < n; i++)
            if (postpone[i].isSelected() && items.get(i).toDownload() > 0)
                held.add(items.get(i).at());
        return held;
    }

    private static GridBagConstraints cell(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 6, 2, 6);
        return c;
    }
}
