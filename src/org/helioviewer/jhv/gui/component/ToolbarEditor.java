package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;

import org.helioviewer.jhv.gui.MainFrame;

/**
 * Which tools are on the toolbar, and in what order: drag one into the middle list to put it
 * there, drag it out to take it off.
 *
 * <p>Modeless on purpose. The bar rebuilds itself on every change, so the thing being edited is
 * the strip directly above the dialog, and a modal window covering its own result would be the
 * wrong shape for this. That also decides what the lists hold: ids, not controls. A rebuild
 * replaces every live button, so a list holding those would be editing a toolbar that no longer
 * exists. The glyphs are snapshotted once when the dialog opens (they are shared constants and do
 * not change); everything else is looked up by id.
 *
 * <p>Nothing taken off the bar is lost. The Tools menu lists every tool whatever this dialog says,
 * and for the ones that are not on the bar it holds the very same controls ({@link
 * ToolBar#hiddenTools()}), which is why a toggle put away there still shows whether it is on. That
 * is what the left-hand column is named for: menu-only, not unavailable.
 *
 * <p>Separator is the one entry that behaves differently, because it is a gap rather than a
 * control: it stays in the left list however many are in use, and it is the only id allowed to
 * appear on the bar more than once.
 */
@SuppressWarnings("serial")
final class ToolbarEditor {

    private static final String AVAILABLE = "available";
    private static final String ON_BAR = "bar";

    private static final int AVAILABLE_WIDTH = 200;
    private static final int BAR_WIDTH = 260;

    @Nullable
    private static JDialog dialog;

    /** id to what should be drawn for it. Snapshotted at open: the glyphs are constants, the buttons are not. */
    private static final Map<String, ToolBar.Tool> catalog = new LinkedHashMap<>();

    // One dialog at a time, so the two models can simply be named. A drag carries the name of the
    // list it started in, and that is enough to find where it came from.
    private static final DefaultListModel<String> barModel = new DefaultListModel<>();
    private static final DefaultListModel<String> availableModel = new DefaultListModel<>();

    static void open() {
        if (dialog != null) {
            dialog.setVisible(true);
            dialog.toFront();
            return;
        }
        List<ToolBar.Tool> tools = ToolBar.allTools();
        if (tools.isEmpty())
            return; // no toolbar built yet, so there is nothing to edit and nothing to say
        catalog.clear();
        for (ToolBar.Tool tool : tools)
            catalog.put(tool.id(), tool);

        barModel.clear();
        for (String id : ToolBar.order(catalog.keySet()))
            barModel.addElement(id);
        availableModel.clear();
        availableModel.addElement(ToolBar.SEPARATOR); // always on offer, however many are in use
        for (String id : catalog.keySet())
            if (!barModel.contains(id))
                availableModel.addElement(id);

        JList<String> barList = list(barModel, ON_BAR);
        JList<String> availableList = list(availableModel, AVAILABLE);
        moveOnDoubleClick(availableList, barList);
        moveOnDoubleClick(barList, availableList);

        JPanel content = new JPanel(new BorderLayout(10, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(titled("Menu-Only", availableList, AVAILABLE_WIDTH), BorderLayout.LINE_START);
        content.add(titled("On the toolbar", barList, BAR_WIDTH), BorderLayout.CENTER);

        JButton reset = new JButton("Restore Defaults");
        reset.addActionListener(e -> {
            ToolBar.resetOrder();
            close();
            open(); // rebuilt from the default order, which is shorter than editing both models
        });
        JButton done = new JButton("Done");
        done.addActionListener(e -> close());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(reset);
        buttons.add(done);

        // Wrapped, and to a width this dialog picked rather than one the sentence picked. A plain
        // JLabel is one line however long its text is, and in PAGE_END of a BorderLayout that line
        // becomes the window's preferred width: the hint was stretching the dialog past the edge of
        // the screen and taking the toolbar list, which is in CENTER, with it.
        JLabel hint = new JLabel("<html><body style='width:" + (AVAILABLE_WIDTH + BAR_WIDTH) + "px'>"
                + "Drag into \"On the toolbar\" to put a tool on the bar, out of it to take one off. "
                + "Double-click does the same. Every tool is listed in the Tools menu either way; "
                + "these are the ones the menu is the only way to reach.</body></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JPanel south = new JPanel(new BorderLayout(10, 6));
        south.add(hint, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.PAGE_END);
        content.add(south, BorderLayout.PAGE_END);

        JDialog d = new JDialog(MainFrame.get(), "Edit Toolbar", false);
        d.setContentPane(content);
        d.pack();
        d.setLocationRelativeTo(MainFrame.get());
        dialog = d;
        d.setVisible(true);
    }

    private static void close() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    private static JComponent titled(String title, JList<String> list, int width) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label, BorderLayout.PAGE_START);
        JScrollPane scroller = new JScrollPane(list);
        scroller.setPreferredSize(new Dimension(width, 430));
        panel.add(scroller, BorderLayout.CENTER);
        return panel;
    }

    private static JList<String> list(DefaultListModel<String> model, String name) {
        JList<String> list = new JList<>(model);
        list.setName(name);
        list.setCellRenderer(new Renderer());
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new Mover());
        return list;
    }

    private static DefaultListModel<String> modelNamed(String name) {
        return ON_BAR.equals(name) ? barModel : availableModel;
    }

    /** The glyph and its label, which is what makes a list of ids read as a list of tools. */
    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            String id = String.valueOf(value);
            if (ToolBar.SEPARATOR.equals(id)) {
                setIcon(Buttons.dragHandle);
                setText("Separator");
                setToolTipText("A gap between groups of tools. Use as many as you like.");
            } else {
                ToolBar.Tool tool = catalog.get(id);
                setIcon(tool == null ? null : tool.icon());
                setText(tool == null ? id : tool.label());
                setToolTipText(tool == null ? null : tool.tip());
            }
            setIconTextGap(10);
            setHorizontalTextPosition(SwingConstants.TRAILING);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return this;
        }
    }

    /** Double-click as the plain-keyboard-and-mouse route to the same move a drag makes. */
    private static void moveOnDoubleClick(JList<String> from, JList<String> to) {
        from.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2)
                    return;
                int index = from.locationToIndex(e.getPoint());
                if (index < 0 || !from.getCellBounds(index, index).contains(e.getPoint()))
                    return;
                DefaultListModel<String> fromModel = modelNamed(from.getName());
                DefaultListModel<String> toModel = modelNamed(to.getName());
                String id = fromModel.get(index);
                boolean separator = ToolBar.SEPARATOR.equals(id);
                if (!separator || ON_BAR.equals(from.getName()))
                    fromModel.remove(index);
                if (!separator || ON_BAR.equals(to.getName()))
                    if (separator || !toModel.contains(id))
                        toModel.addElement(id);
                apply();
                to.repaint();
            }
        });
    }

    /**
     * Moves one id between the lists, or within the toolbar list to reorder it.
     *
     * <p>All of it happens in importData, and the action reported is COPY so exportDone does
     * nothing. The alternative, a real MOVE removing the original in exportDone, has to remove by
     * an index the insertion has already shifted, which is the classic way a drag onto a list eats
     * the wrong row.
     */
    private static final class Mover extends TransferHandler {

        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        @Nullable
        protected Transferable createTransferable(JComponent c) {
            JList<?> list = (JList<?>) c;
            int index = list.getSelectedIndex();
            return index < 0 ? null : new StringSelection(list.getName() + ' ' + index);
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support))
                return false;
            String payload;
            try {
                payload = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            } catch (Exception ignore) { // a drag from outside the application, or one that died
                return false;
            }
            int space = payload.indexOf(' ');
            if (space < 0)
                return false;
            DefaultListModel<String> sourceModel = modelNamed(payload.substring(0, space));
            int sourceIndex;
            try {
                sourceIndex = Integer.parseInt(payload.substring(space + 1));
            } catch (NumberFormatException ignore) {
                return false;
            }
            if (sourceIndex < 0 || sourceIndex >= sourceModel.size())
                return false;

            JList<?> target = (JList<?>) support.getComponent();
            DefaultListModel<String> targetModel = modelNamed(target.getName());
            String id = sourceModel.get(sourceIndex);
            boolean separator = ToolBar.SEPARATOR.equals(id);
            int drop = ((JList.DropLocation) support.getDropLocation()).getIndex();

            if (sourceModel == targetModel) {
                if (drop > sourceIndex)
                    drop--;
                sourceModel.remove(sourceIndex);
                targetModel.add(Math.min(Math.max(drop, 0), targetModel.size()), id);
            } else if (ON_BAR.equals(target.getName())) {
                if (!separator && targetModel.contains(id))
                    return false; // a control belongs in one place; only gaps repeat
                if (!separator)
                    sourceModel.remove(sourceIndex); // Separator is a supply, not a stock item
                targetModel.add(Math.min(Math.max(drop, 0), targetModel.size()), id);
            } else {
                sourceModel.remove(sourceIndex);
                if (!separator && !targetModel.contains(id))
                    targetModel.add(Math.min(Math.max(drop, 0), targetModel.size()), id);
            }
            apply();
            return true;
        }
    }

    /** Write the running order back. The toolbar rebuilds itself, so the result is on screen at once. */
    private static void apply() {
        List<String> ids = new ArrayList<>(barModel.size());
        for (int i = 0; i < barModel.size(); i++)
            ids.add(barModel.get(i));
        ToolBar.setOrder(ids);
    }

    private ToolbarEditor() {}

}
