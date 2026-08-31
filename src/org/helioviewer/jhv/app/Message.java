package org.helioviewer.jhv.app;

public class Message {

    public interface Handler {
        void err(String title, Object msg);

        /**
         * The same report, with the exception that caused it. The default drops the cause, so a
         * handler only implements this if it has something to say about the KIND of failure.
         */
        default void err(String title, Object msg, Throwable cause) {
            err(title, msg);
        }

        void warn(String title, Object msg);

        void fatalErr(String msg);
    }

    private static Handler handler = new ConsoleHandler();

    public static void setHandler(Handler _handler) {
        handler = _handler;
    }

    public static void err(String title, Object msg) {
        handler.err(title, msg);
    }

    /**
     * Report a failure along with what threw it.
     *
     * <p>The body still comes from {@code msg}: the cause is passed so the dialog can tell a
     * machine with no network from a server that is misbehaving, which are the same sentence and
     * very different situations.
     */
    public static void err(String title, Object msg, Throwable cause) {
        handler.err(title, msg, cause);
    }

    public static void warn(String title, Object msg) {
        handler.warn(title, msg);
    }

    public static void fatalErr(String msg) {
        handler.fatalErr(msg);
        System.exit(-1);
    }

    public static String format(Object msg) {
        return msg == null || msg.toString().isEmpty() ? "No details available." : msg.toString();
    }

    private static final class ConsoleHandler implements Handler {
        @Override
        public void err(String title, Object msg) {
            System.err.println(title + ": " + format(msg));
        }

        @Override
        public void warn(String title, Object msg) {
            System.err.println(title + ": " + format(msg));
        }

        @Override
        public void fatalErr(String msg) {
            System.err.println("Fatal Error: " + format(msg));
        }
    }

}
