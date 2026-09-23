package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import org.helioviewer.jhv.app.Log;
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
    private final JTextArea text;
    private final JScrollPane scroller;
    private final Timer timer;
    private long read;

    public static LogWindow get() {
        if (instance == null)
            instance = new LogWindow();
        return instance;
    }

    private LogWindow() {
        text = new JTextArea();
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

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING));
        buttons.add(clear);
        buttons.add(reveal);

        JPanel content = new JPanel(new BorderLayout());
        content.add(where, BorderLayout.PAGE_START);
        content.add(scroller, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);

        dialog = new JDialog(MainFrame.get(), "HFStudio Log", false); // never modal: the point is to watch
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
        append();
        timer.start();
        dialog.setVisible(true);
        dialog.toFront();
    }

    /** Whatever has been written since the last look, or the whole file if it rolled under us. */
    private void append() {
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
        text.append(tail);
        if (atBottom) // only follow when the reader was already following; never yank a scrolled-back view
            text.setCaretPosition(text.getDocument().getLength());
    }

}
