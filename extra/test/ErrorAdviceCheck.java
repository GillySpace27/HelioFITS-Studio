package org.helioviewer.jhv.gui;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * An error dialog must not send the user to the wrong place.
 *
 * <p>Every error used to carry the same footnote offering the Helioviewer API bug tracker, which
 * meant a laptop with no wifi failing to list the PUNCH archive at the SDAC advised filing a report
 * against a server that had never heard from it. The judgement is now made from the exception type
 * rather than its text, because "No route to host" IS the whole message the user sees and matching
 * on that string would break on the first rewording.
 *
 * <p>This pins the classification, which is the part with a right answer. The wording is not.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.ErrorAdviceCheck
 */
public final class ErrorAdviceCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // The machine cannot reach anything: not the archive's fault, and not reportable.
        offline(new NoRouteToHostException("No route to host"), "no route to host");
        offline(new UnknownHostException("umbra.nascom.nasa.gov"), "DNS failure");
        offline(new ConnectException("Connection refused"), "connection refused");

        // Wrapped, which is how they actually arrive: through a client, through an executor.
        offline(new IOException("listing failed", new NoRouteToHostException("No route to host")),
                "a network failure wrapped once");
        offline(new RuntimeException("query", new IOException("read", new UnknownHostException("host"))),
                "a network failure wrapped twice");

        // Reached the server and it went wrong there. That IS worth reporting.
        online(new SocketTimeoutException("Read timed out"), "a timeout means something answered slowly");
        online(new IOException("HTTP 500"), "a server error");
        online(new IllegalStateException("bad response"), "a parsing failure");
        online(null, "no exception at all");

        // A cycle in the cause chain must not hang the dialog. Java forbids a one-element cycle,
        // so this is the shortest one it will actually let you build.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        online(a, "a cyclic cause chain");

        if (failures != 0)
            throw new AssertionError(failures + " error-advice failure(s)");
        System.out.println("ErrorAdviceCheck: PASS");
    }

    private static void offline(Throwable t, String what) throws Exception {
        expect(classify(t), what + " must be reported as a local network problem");
    }

    private static void online(Throwable t, String what) throws Exception {
        expect(!classify(t), what + " must NOT be blamed on the network");
    }

    /** MessageHandler.isOffline, reached reflectively: it is private and has no business not being. */
    private static boolean classify(Throwable t) throws Exception {
        java.lang.reflect.Method m = MessageHandler.class.getDeclaredMethod("isOffline", Throwable.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, t);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
