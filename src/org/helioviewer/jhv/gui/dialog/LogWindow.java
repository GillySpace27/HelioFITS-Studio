package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;

/**
 * The log this session is writing, as it is written.
 *
 * <p>Help already had a Show Log, but it is modal and it is a snapshot: it exists so a log can be
 * copied into a bug report, and while it is open the application it is reporting on cannot run. A
 * fault you are trying to catch in the act needs the opposite, so this is a plain window that stays
 * out of the way and appends.
 *
 * <p>It tails by remembering how far it has read and asking only for what is past that, rather than
 * re-reading the file every second. The handler caps the file at a megabyte and then rolls it, so
 * the window notices a file that has shrunk and starts again from the top rather than reporting
 * whatever lands at a now-meaningless offset.
 */
@SuppressWarnings({"serial", "this-escape"})
public final class LogWindow implements Interfaces.ShowableDialog {

    private static final int POLL_MILLI = 700;
    private static LogWindow instance;

    private final JDialog dialog;
    private final JTextPane text;
    private final JScrollPane scroller;
    private final Timer timer;
    private long read;
    private Level level = Level.INFO; // of the line being read; a stack trace or a split chunk carries on from it
    private boolean paintedDark; // the theme the text on screen was coloured for

    private static final String WRAP_KEY = "log.wrap";
    private static final String COLOR_KEY = "log.color";
    private static final Pattern LEVEL = Pattern.compile("\\] (SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST) ");

    private enum Level { ERROR, WARN, INFO, DEBUG }

    private boolean wrap = !"false".equals(Settings.getProperty(WRAP_KEY)); // wrapping is the default: a long line is unreadable off-screen
    private boolean color = !"false".equals(Settings.getProperty(COLOR_KEY));

    /** A text pane that can be told not to wrap: Swing wraps a JTextPane unconditionally unless it is asked otherwise. */
    private final class LogPane extends JTextPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return wrap ? super.getScrollableTracksViewportWidth() : getUI().getPreferredSize(this).width <= getParent().getWidth();
        }
    }

    public static LogWindow get() {
        if (instance == null)
            instance = new LogWindow();
        return instance;
    }

    private LogWindow() {
        text = new LogPane();
        text.setEditable(false);
        text.setFont(UIGlobals.uiFontMonoSmall);
        scroller = new JScrollPane(text);
        scroller.setPreferredSize(new Dimension(900, 460));

        JLabel where = new JLabel(Log.filename());
        where.setFont(UIGlobals.uiFontMonoSmall);
        where.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton reveal = new JButton("Reveal");
        reveal.setEnabled(DesktopIntegration.canRevealFile);
        reveal.addActionListener(e -> DesktopIntegration.reveal(new File(Log.filename())));
        JButton clear = new JButton("Clear view");
        clear.setToolTipText("Empty this window. The file is untouched; new lines keep arriving.");
        clear.addActionListener(e -> text.setText(""));
        JCheckBox wrapBox = new JCheckBox("Wrap lines", wrap);
        wrapBox.setToolTipText("Fold long lines to the width of the window instead of scrolling sideways.");
        wrapBox.addActionListener(e -> {
            wrap = wrapBox.isSelected();
            Settings.setProperty(WRAP_KEY, Boolean.toString(wrap));
            scroller.setHorizontalScrollBarPolicy(wrap ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            text.revalidate();
            text.repaint();
        });
        scroller.setHorizontalScrollBarPolicy(wrap ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        JCheckBox colorBox = new JCheckBox("Colors", color);
        colorBox.setToolTipText("Colour lines by level: errors, warnings, and the rest. Off shows plain text.");
        colorBox.addActionListener(e -> {
            color = colorBox.isSelected();
            Settings.setProperty(COLOR_KEY, Boolean.toString(color));
            reload();
        });

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING));
        buttons.add(wrapBox);
        buttons.add(colorBox);
        buttons.add(clear);
        buttons.add(reveal);

        JPanel content = new JPanel(new BorderLayout());
        content.add(where, BorderLayout.PAGE_START);
        content.add(scroller, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);

        dialog = new JDialog(MainFrame.get(), org.helioviewer.jhv.app.AppInfo.programName + " Log", false); // never modal: the point is to watch
        dialog.setType(Window.Type.UTILITY); // avoids a tab on macOS when Prefer tabs is always
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());

        timer = new Timer(POLL_MILLI, e -> append());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                timer.stop(); // nothing to poll for while nobody is looking
            }
        });
    }

    @Override
    public void showDialog() {
        if (Theme.current().dark() != paintedDark)
            reload(); // the colours were chosen for the other ground
        append();
        timer.start();
        dialog.setVisible(true);
        dialog.toFront();
    }

    /** Whatever has been written since the last look, or the whole file if it rolled under us. */
    private void append() {
        append(true);
    }

    private void append(boolean mayFollow) {
        String tail;
        try (RandomAccessFile file = new RandomAccessFile(Log.filename(), "r")) {
            long length = file.length();
            if (length < read) { // rolled: start again rather than seek into a different file
                read = 0;
                text.setText("");
            }
            if (length == read)
                return;
            file.seek(read);
            byte[] bytes = new byte[(int) Math.min(length - read, 1 << 20)];
            file.readFully(bytes);
            read += bytes.length;
            tail = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // the file may not exist yet, or be mid-roll; the next poke will find it
        }

        JScrollBar bar = scroller.getVerticalScrollBar();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 2;
        insert(tail);
        if (mayFollow && atBottom) // only follow when the reader was already following; never yank a scrolled-back view
            text.setCaretPosition(text.getDocument().getLength());
    }

    /** Start again from the top of the file, so that every line takes the current colours (or none). */
    private void reload() {
        JScrollBar bar = scroller.getVerticalScrollBar();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 2;
        int was = bar.getValue();
        read = 0;
        level = Level.INFO;
        text.setText("");
        append(false); // the emptied view always looks like it is at the bottom, so decide from before it was emptied
        javax.swing.SwingUtilities.invokeLater(() -> { // the new text has no height until it has been laid out
            if (atBottom)
                text.setCaretPosition(text.getDocument().getLength());
            else
                bar.setValue(was);
        });
    }

    /** Adds text line by line, each in the colour of its level; a line without a level belongs to the one before. */
    private void insert(String tail) {
        StyledDocument doc = text.getStyledDocument();
        paintedDark = Theme.current().dark();
        try {
            int start = 0;
            while (start < tail.length()) {
                int end = tail.indexOf('\n', start);
                end = end < 0 ? tail.length() : end + 1;
                String line = tail.substring(start, end);
                Matcher m = LEVEL.matcher(line);
                if (m.find())
                    level = switch (m.group(1)) {
                        case "SEVERE" -> Level.ERROR;
                        case "WARNING" -> Level.WARN;
                        case "INFO" -> Level.INFO;
                        default -> Level.DEBUG;
                    };
                doc.insertString(doc.getLength(), line, color ? style(level) : null);
                start = end;
            }
        } catch (BadLocationException e) {
            Log.warn(e); // inserting at the end cannot be out of range; a log window must not take the program down
        }
    }

    /** Plain info is left in the theme's own text colour; only what needs noticing, or can be passed over, is changed. */
    private AttributeSet style(Level l) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        boolean dark = paintedDark;
        switch (l) {
            case ERROR -> {
                StyleConstants.setForeground(a, dark ? new Color(0xFF7B72) : new Color(0xB3261E));
                StyleConstants.setBold(a, true);
            }
            case WARN -> StyleConstants.setForeground(a, dark ? new Color(0xE3B341) : new Color(0x8A5A00));
            case DEBUG -> StyleConstants.setForeground(a, dark ? new Color(0x8B949E) : new Color(0x6E7781));
            case INFO -> StyleConstants.setForeground(a, text.getForeground());
        }
        return a;
    }

}
