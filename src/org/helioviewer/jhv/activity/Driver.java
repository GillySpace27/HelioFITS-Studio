package org.helioviewer.jhv.activity;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Settings;

/**
 * Whether something outside Studio is driving it, and who.
 *
 * <p>Driving starts when a command arrives through the local API or SAMP and ends a few seconds
 * (the "linger", a Setting) after the last one has answered, so a script that sends one command a
 * second reads as one stretch of driving rather than a flicker. A deferred command, a layer load or
 * an export, holds it until its answer.
 *
 * <p>Who: the caller names itself in the {@code X-HFS-Client} header of the local API.
 * pyHelioFITS sends {@code pyheliofits/<version>}; heliofits-mcp sends {@code heliofits-mcp/<version>}
 * and the name its own MCP client gave, after a semicolon. Anything else, SAMP included, is "other".
 * The class picks the glow's colour (theme tokens DriverMcp, DriverPython, DriverOther).
 */
public final class Driver {

    public static final String GLOW_KEY = "api.glow";
    public static final String LINGER_KEY = "api.glowLingerSeconds";
    public static final int DEFAULT_LINGER_S = 5;

    /** Which colour the glow takes. */
    public enum Kind {
        MCP, PYTHON, OTHER
    }

    /** Who is driving, what they last asked, and when that started. */
    public record State(Kind kind, String name, String command, long sinceMillis, boolean inFlight) {}

    private static final AtomicInteger inFlight = new AtomicInteger();
    private static final AtomicReference<State> last = new AtomicReference<>();
    private static final AtomicReference<Long> lastEndNanos = new AtomicReference<>(Long.MIN_VALUE / 2);

    private Driver() {}

    /** The glow class of a caller from its client header (null or blank when it sent none). */
    public static Kind classify(@Nullable String client) {
        if (client == null)
            return Kind.OTHER;
        String c = client.toLowerCase(Locale.ROOT);
        if (c.startsWith("heliofits-mcp"))
            return Kind.MCP;
        if (c.startsWith("pyheliofits"))
            return Kind.PYTHON;
        return Kind.OTHER;
    }

    /**
     * What the person reads: "Claude Desktop via heliofits-mcp", "pyheliofits", "SAMP: ds9", "a script".
     * The header is the caller's own words, so it is cut short and stripped of anything but print.
     */
    public static String name(@Nullable String client, String transport) {
        if (client == null || client.isBlank())
            return transport.isEmpty() ? "a script" : transport;
        String clean = client.replaceAll("[^\\p{Print}]", "").trim();
        if (clean.length() > 80)
            clean = clean.substring(0, 80);
        int semi = clean.indexOf(';');
        String product = (semi < 0 ? clean : clean.substring(0, semi)).trim();
        int slash = product.indexOf('/');
        String bare = slash < 0 ? product : product.substring(0, slash);
        if (semi >= 0) {
            String host = clean.substring(semi + 1).trim();
            if (!host.isEmpty())
                return host + " via " + bare;
        }
        return bare.isEmpty() ? "a script" : bare;
    }

    /** A command from outside has arrived. Call {@link #end} when it has answered, whatever the outcome. */
    public static void begin(Kind kind, String name, String command) {
        inFlight.incrementAndGet();
        last.set(new State(kind, name, command, System.currentTimeMillis(), true));
    }

    public static void end() {
        if (inFlight.decrementAndGet() < 0)
            inFlight.set(0);
        lastEndNanos.set(System.nanoTime());
    }

    public static boolean glowEnabled() {
        return !"false".equals(Settings.getProperty(GLOW_KEY));
    }

    public static int lingerSeconds() {
        try {
            return Math.clamp(Integer.parseInt(Settings.getProperty(LINGER_KEY).trim()), 0, 120);
        } catch (RuntimeException e) { // unset or not a number: the default
            return DEFAULT_LINGER_S;
        }
    }

    /** Who is driving now, or null when nobody has for longer than the linger. */
    @Nullable
    public static State current(long nowNanos) {
        State s = last.get();
        if (s == null)
            return null;
        boolean busy = inFlight.get() > 0;
        if (!busy && nowNanos - lastEndNanos.get() > lingerSeconds() * 1_000_000_000L)
            return null;
        return busy == s.inFlight() ? s : new State(s.kind(), s.name(), s.command(), s.sinceMillis(), busy);
    }

    /** For a check: forget everything. */
    static void reset() {
        inFlight.set(0);
        last.set(null);
        lastEndNanos.set(Long.MIN_VALUE / 2);
    }

}
