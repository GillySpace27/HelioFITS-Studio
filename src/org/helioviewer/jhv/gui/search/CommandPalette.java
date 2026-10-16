package org.helioviewer.jhv.gui.search;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * Help > Search Controls: a field over a list of every command and named control.
 *
 * <p>Typing filters the list (ControlIndex.search); Up and Down move, Enter or a double-click
 * chooses, Escape or clicking elsewhere closes. A command is run; a control is revealed and
 * spotlit as a one-step tour, so it uses the tour's overlay and card.
 */
public final class CommandPalette {

    /** Its ActionCatalog id. */
    public static final String ID = "searchControls";
    private static final int ROWS = 12;
    private static final int WIDTH = 520;
    private static final String HINT = "Enter runs a command or shows a control. Esc closes.";

    /** Help > Search Controls, on Cmd-K (Ctrl-K off macOS). */
    public static Action action() {
        AbstractAction a = new AbstractAction("Search Controls...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        };
        a.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_K, DesktopIntegration.menuShortcutMask));
        return a;
    }

    static void open() {
        JFrame frame = MainFrame.get();
        if (frame == null)
            return;
        CommandPalette palette = new CommandPalette(frame, ControlIndex.build());
        palette.dialog.setVisible(true);
        palette.field.requestFocusInWindow();
    }

    private final JDialog dialog;
    private final List<ControlIndex.Item> items;
    private final JTextField field = new JTextField();
    private final DefaultListModel<ControlIndex.Item> model = new DefaultListModel<>();
    private final JList<ControlIndex.Item> list = new JList<>(model);
    private final JLabel hint = new JLabel(HINT);
    private boolean done; // chosen or closed: windowDeactivated must not close it twice

    private CommandPalette(JFrame frame, List<ControlIndex.Item> _items) {
        items = _items;
        dialog = new JDialog(frame, Dialog.ModalityType.MODELESS);
        dialog.setUndecorated(true);
        dialog.setName("searchControlsWindow");

        field.getAccessibleContext().setAccessibleName("Search controls");
        field.setToolTipText("Type part of a command or control name, or a word from its tooltip");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(ROWS);
        list.setFocusable(false); // the field keeps the keyboard; Up and Down are bound on it
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    choose();
            }
        });

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(field, BorderLayout.PAGE_START);
        JScrollPane scroll = new JScrollPane(list);
        panel.add(scroll, BorderLayout.CENTER);
        hint.setEnabled(false);
        panel.add(hint, BorderLayout.PAGE_END);
        dialog.setContentPane(panel);

        bind("ESCAPE", this::close);
        bind("ENTER", this::choose);
        bind("UP", () -> move(-1));
        bind("DOWN", () -> move(1));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter(); }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                close();
            }
        });

        refilter();
        dialog.pack();
        dialog.setSize(WIDTH, dialog.getHeight());
        Point at = frame.getLocationOnScreen();
        dialog.setLocation(at.x + (frame.getWidth() - WIDTH) / 2, at.y + Math.min(120, frame.getHeight() / 6));
    }

    private void bind(String key, Runnable run) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "search." + key);
        field.getActionMap().put("search." + key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        });
    }

    private void refilter() {
        model.clear();
        for (ControlIndex.Item it : ControlIndex.search(field.getText(), items))
            model.addElement(it);
        hint.setText(model.isEmpty() ? "No command or control matches." : HINT);
        if (!model.isEmpty())
            list.setSelectedIndex(0);
    }

    private void move(int by) {
        if (model.isEmpty())
            return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + by));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void close() {
        if (done)
            return;
        done = true;
        dialog.dispose();
    }

    private void choose() {
        ControlIndex.Item it = list.getSelectedValue();
        if (it == null || done)
            return;
        close(); // first, so the action or spotlight gets the main window's focus
        run(it);
    }

    /** Runs a command, or spotlights a control after its reveal. */
    static void run(ControlIndex.Item it) {
        if (!it.action()) {
            Tour.point(it.id(), it.reveal(), it.title(), describe(it));
            return;
        }
        ActionCatalog.Entry entry = ActionCatalog.get(it.id());
        if (entry == null || !entry.action().isEnabled()) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            return;
        }
        try {
            entry.action().actionPerformed(new ActionEvent(MainFrame.get(), ActionEvent.ACTION_PERFORMED, it.id()));
        } catch (RuntimeException e) { // a failing command is logged, as from its menu item
            Log.warn("Search: command " + it.id() + " failed", e);
        }
    }

    /** The spotlight card's text: where the control lives, so a control not on screen can still be found. */
    static String describe(ControlIndex.Item it) {
        String tip = ControlIndex.tip(it.id());
        return (tip.isEmpty() ? "" : tip + (tip.endsWith(".") ? " " : ". ")) + "Where: " + it.where() + "." + (Tour.find(it.id()) == null && it.reveal() == null
                ? " It is not on screen now; it may be hidden, folded away or in the toolbar's More list." : "");
    }

    @SuppressWarnings("serial")
    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, @Nullable Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(l, value, index, selected, focus);
            if (value instanceof ControlIndex.Item it) {
                ActionCatalog.Entry e = it.action() ? ActionCatalog.get(it.id()) : null;
                boolean off = e != null && !e.action().isEnabled();
                setText("<html><b>" + escape(it.title()) + "</b>&nbsp;&nbsp;<font size=-1>" + escape(it.where())
                        + (it.action() ? "" : ", control") + (off ? ", unavailable now" : "") + "</font></html>");
                setEnabled(!off);
            }
            return this;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
