package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.app.Theme.Token;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.registry.KeyBindings;
import org.helioviewer.jhv.gui.registry.ShortcutIndex;
import org.helioviewer.jhv.gui.registry.ShortcutIndex.Row;

/**
 * Help > Keyboard Shortcuts, on Cmd+/ (Ctrl+/ off a Mac): a rounded panel over the main window with a
 * search field, a section per area and, per row, the action on the left and its key caps on the right.
 * The same key closes it again, and so does Esc. The rows come from {@link ShortcutIndex}, built each
 * time it opens, so they are the shortcuts in force now. Colours are the current {@link Theme}'s.
 */
public final class ShortcutsOverlay {

    /** Its ActionCatalog id. */
    public static final String ID = "keyboardShortcuts";
    private static final int WIDTH = 480;
    private static final int HEIGHT = 560;
    private static final int ARC = 18;

    private static final AtomicReference<ShortcutsOverlay> OPEN = new AtomicReference<>(); // at most one; the key toggles it

    public static Action action() {
        AbstractAction a = new AbstractAction("Keyboard Shortcuts...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggle();
            }
        };
        a.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, DesktopIntegration.menuShortcutMask));
        return a;
    }

    /** Opens the overlay, or closes it if it is open. */
    public static void toggle() {
        ShortcutsOverlay shown = OPEN.get();
        if (shown != null) {
            shown.close();
            return;
        }
        JFrame frame = MainFrame.get();
        if (frame != null)
            OPEN.set(new ShortcutsOverlay(frame));
    }

    private final JDialog dialog;
    private final List<Row> rows = ShortcutIndex.build();
    private final JPanel list = new JPanel();
    private final JTextField field = new JTextField();
    private final Color fg = Theme.current().get(Token.Foreground);
    private final Color bg = Theme.current().get(Token.Background);
    private final Color faint = Theme.mix(fg, bg, 0.55);

    private ShortcutsOverlay(JFrame frame) {
        dialog = new JDialog(frame, Dialog.ModalityType.MODELESS);
        dialog.setUndecorated(true);
        dialog.setName("keyboardShortcutsWindow");
        dialog.setBackground(new Color(0, 0, 0, 0)); // the rounded panel paints the corners

        JPanel panel = new JPanel(new BorderLayout(0, 8)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
                g2.setColor(Theme.current().get(Token.Separator));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        JLabel title = new JLabel("Keyboard shortcuts");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
        title.setForeground(fg);
        field.putClientProperty("JTextField.placeholderText", "Search the shortcuts");
        field.getAccessibleContext().setAccessibleName("Search keyboard shortcuts");
        field.setToolTipText("Part of an action, a section or a key");
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refill(); }
            @Override public void removeUpdate(DocumentEvent e) { refill(); }
            @Override public void changedUpdate(DocumentEvent e) { refill(); }
        });
        JPanel head = new JPanel(new BorderLayout(0, 10));
        head.setOpaque(false);
        head.add(title, BorderLayout.PAGE_START);
        head.add(field, BorderLayout.CENTER);
        panel.add(head, BorderLayout.PAGE_START);

        list.setLayout(new BoxLayout(list, BoxLayout.PAGE_AXIS));
        list.setOpaque(false);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        JLabel viewAll = new JLabel("View all and change keybindings");
        viewAll.setForeground(Theme.current().get(Token.Accent));
        viewAll.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        viewAll.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                close();
                ActionCatalog.Entry m = ActionCatalog.get("keybindingsManager");
                if (m != null)
                    m.action().actionPerformed(new ActionEvent(viewAll, ActionEvent.ACTION_PERFORMED, "keybindingsManager"));
            }
        });
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        foot.setOpaque(false);
        foot.add(viewAll);
        panel.add(foot, BorderLayout.PAGE_END);
        dialog.setContentPane(panel);

        // Esc closes; the shortcut's own key closes too, since a window without a menu bar does not run the accelerator
        JComponent root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "shortcuts.close");
        KeyStroke own = KeyBindings.current(ID);
        if (own != null)
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(own, "shortcuts.close");
        root.getActionMap().put("shortcuts.close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                close();
            }
        });

        refill();
        dialog.setSize(WIDTH, Math.min(HEIGHT, frame.getHeight() - 40));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        field.requestFocusInWindow();
    }

    private void close() {
        if (OPEN.compareAndSet(this, null))
            dialog.dispose();
    }

    /** Rebuilds the sections from the rows the search lets through. */
    private void refill() {
        list.removeAll();
        List<Row> shown = ShortcutIndex.filter(rows, field.getText());
        String group = null;
        for (Row r : shown) {
            if (!r.group().equals(group)) {
                group = r.group();
                JLabel h = new JLabel(group);
                h.setFont(h.getFont().deriveFont(Font.BOLD));
                h.setForeground(faint);
                h.setBorder(BorderFactory.createEmptyBorder(group.equals(shown.get(0).group()) ? 4 : 14, 0, 4, 0));
                h.setAlignmentX(0f);
                list.add(h);
            }
            list.add(rowPanel(r));
        }
        if (shown.isEmpty()) {
            JLabel none = new JLabel("No shortcut matches");
            none.setForeground(faint);
            list.add(none);
        }
        list.add(Box.createVerticalGlue());
        list.revalidate();
        list.repaint();
    }

    private JPanel rowPanel(Row r) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        JLabel name = new JLabel(r.name());
        name.setForeground(fg);
        p.add(name, BorderLayout.CENTER);
        JPanel caps = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        caps.setOpaque(false);
        for (String c : r.caps())
            caps.add(new Cap(c));
        p.add(caps, BorderLayout.LINE_END);
        p.setAlignmentX(0f);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** One key cap: a small rounded box. */
    private final class Cap extends JLabel {
        Cap(String text) {
            super(text, CENTER);
            setForeground(fg);
            setFont(getFont().deriveFont(Font.PLAIN, getFont().getSize2D() - 1));
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(d.width, d.height), d.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.current().get(Token.Component));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g2.setColor(Theme.current().get(Token.Separator));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
