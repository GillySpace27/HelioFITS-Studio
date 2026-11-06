package org.helioviewer.jhv.app.update;

import java.io.IOException;

/**
 * An offline update check says so, instead of "No such host is known (api.github.com)".
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.update.UpdateCheckMessageCheck
 */
public final class UpdateCheckMessageCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String offline = "Could not reach GitHub. Check your internet connection.";
        expect("unknown host", offline.equals(UpdateCheck.failureText(new java.net.UnknownHostException("api.github.com"))));
        expect("connection refused", offline.equals(UpdateCheck.failureText(new java.net.ConnectException("refused"))));
        expect("wrapped cause", offline.equals(UpdateCheck.failureText(new RuntimeException("x", new java.net.UnknownHostException("h")))));
        expect("other errors keep their text", UpdateCheck.failureText(new IOException("HTTP 500")).endsWith("HTTP 500"));
        if (failures > 0)
            System.exit(1);
    }
}
