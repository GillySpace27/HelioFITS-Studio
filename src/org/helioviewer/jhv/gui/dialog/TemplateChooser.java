package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.state.TemplateLibrary;
import org.helioviewer.jhv.app.state.TemplateState;
import org.helioviewer.jhv.app.state.TemplateTime;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.DataSources;
import org.helioviewer.jhv.time.TimeUtils;

import com.google.common.html.HtmlEscapers;

/**
 * File > New from Template... (docs/templates.md): the built-in templates, then the user's, each
 * with its thumbnail, name, description and the range it would open on now. Opening one never makes
 * the template the session's file: the scene comes up Untitled, so Save asks for a name. When the
 * session on screen has unsaved changes the template opens in a new window instead, as a second
 * session does (Session.spawnWindow), and the changes stay where they are.
 */
public final class TemplateChooser {

    @SuppressWarnings("serial")
    public static final class Open extends AbstractAction {
        public Open() {
            super("New from Template...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            show(MainFrame.get());
        }
    }

    /** Open {@code e}: in this window, or in a new one when this session has unsaved changes. */
    public static void open(TemplateLibrary.Entry e) {
        if (Session.isDirty()) {
            File f = Session.newWindowFile(); // the new window's own scratch session: it opens Untitled
            try {
                Files.writeString(f.toPath(), e.session().toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                Log.error("Template " + e.meta().name(), ex);
                Message.err("New from Template", "Could not open a new window: " + ex.getMessage());
                return;
            }
            Session.spawnWindow(f);
            Message.info("New from Template", "\"" + e.meta().name() + "\" opens in a new window, because this session has unsaved changes.");
        } else if (e.file() != null) {
            Commands.loadState(e.file().toURI()); // State.load sees the template and leaves the window Untitled
        } else {
            Commands.loadState(null, e.session().toString());
        }
    }

    /** The range {@code e} would open on at {@code now}: its rule's, or the saved master range for a fixed one. */
    static String rangeText(TemplateLibrary.Entry e, long now) {
        TemplateTime.Range r = TemplateTime.resolve(e.meta(), now,
                e.meta().mode() == TemplateTime.Mode.LATEST ? TemplateState.newest(e.state(), DataSources::newest) : Long.MIN_VALUE);
        long start = r == null ? TimeUtils.optParse(e.state().optString("masterStartTime"), Long.MIN_VALUE) : r.start();
        long end = r == null ? TimeUtils.optParse(e.state().optString("masterEndTime"), Long.MIN_VALUE) : r.end();
        if (start == Long.MIN_VALUE || end == Long.MIN_VALUE)
            return TemplateTime.describe(e.meta());
        return TemplateTime.describe(e.meta()) + ": " + when(start) + " to " + when(end) + " UTC";
    }

    private static String when(long t) {
        return TimeUtils.formatDate(t) + " " + TimeUtils.formatTime(t).substring(0, 5);
    }

    /** {@code image} scaled to {@code size} on its long side, as an icon; a blank square when there is none. */
    static Icon icon(@Nullable BufferedImage image, int size) {
        if (image == null)
            return new ImageIcon(new BufferedImage(size, size * 3 / 4, BufferedImage.TYPE_INT_ARGB));
        double scale = size / (double) Math.max(image.getWidth(), image.getHeight());
        return new ImageIcon(image.getScaledInstance(Math.max(1, (int) (image.getWidth() * scale)),
                Math.max(1, (int) (image.getHeight() * scale)), Image.SCALE_SMOOTH));
    }

    static void show(Component parent) {
        List<TemplateLibrary.Entry> entries = TemplateLibrary.all();
        JDialog dialog = new JDialog(MainFrame.get(), "New from Template", true);
        long now = System.currentTimeMillis();
        Map<TemplateLibrary.Entry, Icon> icons = new HashMap<>();

        JList<TemplateLibrary.Entry> list = new JList<>(entries.toArray(TemplateLibrary.Entry[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, "", index, selected, focus);
                TemplateLibrary.Entry e = (TemplateLibrary.Entry) value;
                setIcon(icons.computeIfAbsent(e, k -> icon(k.thumbnail(), 128)));
                setIconTextGap(12);
                setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
                var esc = HtmlEscapers.htmlEscaper();
                setText("<html><b>" + esc.escape(e.meta().name()) + "</b>" + (e.builtIn() ? " (built in)" : "")
                        + "<br>" + esc.escape(e.meta().description()) + "<br><i>" + esc.escape(rangeText(e, now)) + "</i>"
                        + "<br>" + esc.escape(e.datasets()) + "</html>");
                return this;
            }
        });

        JButton open = new JButton("Open");
        JButton cancel = new JButton("Cancel");
        open.setEnabled(false);
        list.addListSelectionListener(ev -> open.setEnabled(list.getSelectedValue() != null));
        Runnable go = () -> {
            TemplateLibrary.Entry e = list.getSelectedValue();
            if (e == null)
                return;
            dialog.dispose();
            open(e);
        };
        open.addActionListener(ev -> go.run());
        cancel.addActionListener(ev -> dialog.dispose());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent ev) {
                if (ev.getClickCount() == 2)
                    go.run();
            }
        });
        if (!entries.isEmpty())
            list.setSelectedIndex(0);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(open);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(entries.isEmpty()
                ? new JLabel("<html>No templates yet. File &gt; Save as Template... makes one from the scene on screen; "
                        + "your templates are kept in " + HtmlEscapers.htmlEscaper().escape(TemplateLibrary.userDir().getPath()) + ".</html>")
                : new JScrollPane(list), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(open);
        dialog.setPreferredSize(new Dimension(640, 520));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private TemplateChooser() {}
}
