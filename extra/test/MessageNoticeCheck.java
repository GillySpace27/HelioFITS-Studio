package org.helioviewer.jhv.gui;

import javax.swing.JOptionPane;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.dialog.FeedbackDialog;

/**
 * A warning whose cause is known and said in full (Use FITS with no HelioFITS Core) is shown with
 * Close only, so the dialog does not offer to report something that is not a bug. Errors and other
 * warnings keep their Report button.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.MessageNoticeCheck
 */
public final class MessageNoticeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String[] known = MessageHandler.optionsFor(JOptionPane.WARNING_MESSAGE, false);
        expect("a known-cause warning offers Close only", known.length == 1 && "Close".equals(known[0]));
        String[] warn = MessageHandler.optionsFor(JOptionPane.WARNING_MESSAGE, true);
        expect("an ordinary warning still offers Report", warn.length == 2 && FeedbackDialog.REPORT_THIS.equals(warn[0]));
        String[] err = MessageHandler.optionsFor(JOptionPane.ERROR_MESSAGE, true);
        expect("an error still offers Report", err.length == 2 && FeedbackDialog.REPORT_THIS.equals(err[0]));
        String[] info = MessageHandler.optionsFor(JOptionPane.INFORMATION_MESSAGE, true);
        expect("information offers Close only", info.length == 1);

        String[] seen = new String[1];
        Message.setHandler(new Message.Handler() {
            public void err(String t, Object m) { seen[0] = "err"; }
            public void warn(String t, Object m) { seen[0] = "warn"; }
            public void info(String t, Object m) { seen[0] = "info"; }
            public void fatalErr(String m) { seen[0] = "fatal"; }
        });
        Message.notice("t", "m");
        expect("a handler that does not know notice falls back to warn", "warn".equals(seen[0]));
        System.out.println(failures == 0 ? "MessageNoticeCheck: ok" : "MessageNoticeCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
