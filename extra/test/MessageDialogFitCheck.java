package org.helioviewer.jhv.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Message;

/**
 * A message dialog gives its text the height the wrapped text needs, and keeps Close clear of it.
 *
 * <p>The dialog wraps its text at 45 columns but used to be packed before the text knew its width,
 * so the text area was sized to the unwrapped height: two lines short on the graphics-unsupported
 * message, whose last lines then ran past the text area over where Close sits (seen on GitHub's
 * Intel Mac runner). Close staying inside the window is not the test, since it did; the text
 * fitting its own box is.
 * Needs a display; run-checks.sh retries a check with one when it throws HeadlessException.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.MessageDialogFitCheck
 */
public final class MessageDialogFitCheck {

    private static final String TITLE = "Fit check";
    private static final String TEXT = "HelioFITS Studio could not start its graphics on this computer, so it cannot show images.\n\n"
            + "It draws through Metal, and this system's graphics would not start it. That usually means the graphics "
            + "hardware or its driver is too old, or that this is a virtual machine without full graphics support.\n\n"
            + "If it happens on a computer you expect to work, please send the log from\n"
            + "/Users/someone/HFStudio/Logs/\n"
            + "to gilly@nwra.com or https://github.com/GillySpace27/HelioFITS-Studio/issues";

    public static void main(String[] args) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless())
            throw new java.awt.HeadlessException();
        Message.setHandler(new MessageHandler());
        Message.err(TITLE, TEXT);

        AtomicReference<String> verdict = new AtomicReference<>();
        for (int i = 0; i < 100 && verdict.get() == null; i++) {
            Thread.sleep(100);
            EventQueue.invokeAndWait(() -> {
                for (Window w : Window.getWindows())
                    if (w instanceof JDialog d && d.isShowing() && TITLE.equals(d.getTitle())) {
                        JButton close = find(d.getContentPane(), JButton.class);
                        JTextArea text = find(d.getContentPane(), JTextArea.class);
                        if (close == null || text == null) {
                            verdict.set("  FAIL the dialog has no Close button or no text area");
                        } else {
                            // What the text needs at the width it was actually given.
                            int needed = text.getUI().getPreferredSize(text).height;
                            Rectangle t = SwingUtilities.convertRectangle(text.getParent(), text.getBounds(), d.getContentPane());
                            Rectangle b = SwingUtilities.convertRectangle(close.getParent(), close.getBounds(), d.getContentPane());
                            boolean fits = t.height >= needed, clear = b.y >= t.y + t.height, inside = b.y + b.height <= d.getContentPane().getHeight();
                            verdict.set(fits && clear && inside
                                    ? "  ok   text has the " + needed + " px it needs, and Close sits below it inside the dialog"
                                    : "  FAIL text given " + t.height + " px, needs " + needed + "; Close top " + b.y
                                        + " vs text bottom " + (t.y + t.height) + "; Close inside the window: " + inside);
                        }
                        d.dispose();
                    }
            });
        }
        String v = verdict.get() == null ? "  FAIL the dialog never appeared" : verdict.get();
        System.out.println(v);
        boolean ok = v.startsWith("  ok");
        System.out.println(ok ? "MessageDialogFitCheck: ok" : "MessageDialogFitCheck: FAIL");
        System.exit(ok ? 0 : 1);
    }

    private static <T extends Component> T find(Container c, Class<T> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k) && (type != JButton.class || "Close".equals(((JButton) k).getText())))
                return type.cast(k);
            if (k instanceof Container kc) {
                T found = find(kc, type);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
