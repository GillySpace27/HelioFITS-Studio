package org.helioviewer.jhv.gui;

import java.awt.Dimension;
import java.awt.EventQueue;

import javax.annotation.Nullable;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.component.HTMLPane;

final class MessageHandler implements Message.Handler {

    @Override
    public void err(String title, Object msg) {
        show(title, msg, JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void warn(String title, Object msg) {
        show(title, msg, JOptionPane.WARNING_MESSAGE);
    }

    @Override
    public void err(String title, Object msg, Throwable cause) {
        show(title, msg, JOptionPane.ERROR_MESSAGE, cause);
    }

    @Override
    public void fatalErr(String msg) {
        JOptionPane.showMessageDialog(null, Message.format(msg), "Fatal Error", JOptionPane.ERROR_MESSAGE);
    }

    private static void show(String title, Object msg, int type) {
        show(title, msg, type, null);
    }

    /**
     * Whether this failure is the network being unreachable rather than anything going wrong at
     * the other end.
     *
     * <p>Decided from the exception type rather than from its text. "No route to host" and
     * "Connection refused" are the whole message a user sees, and matching on those strings would
     * break the moment a locale or a library reworded them, while the type is the JDK's own
     * statement of what happened.
     */
    private static boolean isOffline(@Nullable Throwable cause) {
        int depth = 0;
        // Bounded rather than walked to the end: a cause chain can be a cycle (Java forbids only
        // the one-element case), and a dialog that hangs on a malformed exception is a worse bug
        // than the one it was trying to report.
        for (Throwable t = cause; t != null; t = t.getCause(), depth++) {
            if (depth > 32)
                return false;
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.PortUnreachableException)
                return true;
        }
        return false;
    }

    private static void show(String title, Object msg, int type, @Nullable Throwable cause) {
        if (Thread.currentThread().isInterrupted())
            return;

        EventQueue.invokeLater(() -> {
            JTextArea textArea = new JTextArea();
            textArea.setText(Message.format(msg));
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(600, 400));

            HTMLPane report = new HTMLPane();
            report.setOpaque(false);
            report.addHyperlinkListener(DesktopIntegration.hyperOpenURL);
            // A machine with no network is not a server bug, and offering a bug tracker for it
            // sends the user to file a report against an archive that never heard from them.
            // The link was previously shown on EVERY error, which is how "No route to host" from
            // the PUNCH archive at the SDAC came to suggest reporting it to Helioviewer.
            if (isOffline(cause)) {
                report.setText("This machine could not reach the network. Nothing is wrong at the "
                        + "other end; a session saved with its data downloaded will still open.");
            } else {
                String url = "https://github.com/Helioviewer-Project/api/issues/new";
                report.setText("If this is a JPIP connection failure, you can open a bug report for the<br>Helioviewer server at <a href='" + url + "'>" + url + "</a>.");
            }

            JOptionPane optionPane = new JOptionPane();
            optionPane.setMessage(new Object[]{report, scrollPane});
            optionPane.setMessageType(type);
            optionPane.setOptions(new String[]{"Close"});
            optionPane.createDialog(MainFrame.get(), title).setVisible(true);
        });
    }

}
