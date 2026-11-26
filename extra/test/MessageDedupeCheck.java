package org.helioviewer.jhv.gui;

/**
 * Issue 298: an identical report already on screen is dropped, but the same words as a different
 * kind of report (warning versus error, another cause) are not the same report.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.MessageDedupeCheck
 */
public final class MessageDedupeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String a = MessageHandler.dedupeKey("t", "text", 0, null);
        expect("same title, text, type and cause collide", a.equals(MessageHandler.dedupeKey("t", "text", 0, null)));
        expect("a warning is not an error", !a.equals(MessageHandler.dedupeKey("t", "text", 2, null)));
        expect("a cause makes a different report", !a.equals(MessageHandler.dedupeKey("t", "text", 0, new java.net.SocketTimeoutException())));
        expect("other text is another report", !a.equals(MessageHandler.dedupeKey("t", "other", 0, null)));
        if (failures > 0) {
            System.out.println("MessageDedupeCheck: " + failures + " failed");
            System.exit(1);
        }
    }
}
