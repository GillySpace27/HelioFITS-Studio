package org.helioviewer.jhv.io.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.registry.CommandRegistry;
import org.helioviewer.jhv.gui.registry.CommandSpec;
import org.helioviewer.jhv.gui.registry.Dispatch;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONException;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The local API: the command registry over HTTP on this computer, for the heliofits Python client,
 * the heliofits-mcp server and any script. Off by default; the setting "api.enabled" turns it on.
 *
 * <p>How it is reached: Studio listens on 127.0.0.1 at a port the system picks and writes
 * {@code ~/HFStudio/api.json} ({@code port}, {@code token}, {@code pid}), readable by this user
 * only where the file system can say so. A call is a JSON-RPC 2.0 request POSTed to {@code /rpc}
 * with {@code Authorization: Bearer <token>}; the token is new each run, so a client reads the
 * file again when it is refused. The Host header must name this computer, so a web page cannot
 * reach the port through a DNS name it controls.
 *
 * <p>Methods: {@code commands.list} answers the registry as an MCP tools list ({@code tools});
 * {@code commands.invoke} with {@code id} and {@code args} runs one command through
 * {@link Dispatch}. An error carries {@code data.kind}: unknown-command, invalid-arguments,
 * disabled, failed, or declined when a destructive command was put to the person and refused
 * (setting "api.confirmDestructive", on by default). The verbs are the registry's; this adds none.
 */
public final class LocalApi {

    public static final String ENABLED = "api.enabled";
    public static final String CONFIRM = "api.confirmDestructive";
    static final int MAX_BODY = 1 << 20;

    /** Asks the person about a destructive command; on the event thread. */
    @FunctionalInterface
    public interface Confirmer {
        boolean confirm(CommandSpec spec, JSONObject args);
    }

    /** Where this run listens, and the file that says so. */
    public record Endpoint(int port, String token, Path file) {}

    private static final AtomicReference<Confirmer> confirmer = new AtomicReference<>(LocalApi::askInDialog);
    private static final AtomicReference<HttpServer> server = new AtomicReference<>();
    private static final AtomicReference<Endpoint> endpoint = new AtomicReference<>();
    private static final Thread cleanup = new Thread(LocalApi::removeFile, "HFS-LocalApiCleanup");

    private LocalApi() {}

    public static boolean isEnabled() {
        return Boolean.parseBoolean(Settings.getProperty(ENABLED));
    }

    public static boolean confirmsDestructive() {
        return !"false".equals(Settings.getProperty(CONFIRM));
    }

    /** The settings' switch: remembers the choice and starts or stops now. */
    public static void setEnabled(boolean on) {
        Settings.setProperty(ENABLED, Boolean.toString(on));
        if (on)
            startLogged();
        else
            stop();
    }

    public static void setConfirmsDestructive(boolean on) {
        Settings.setProperty(CONFIRM, Boolean.toString(on));
    }

    /** At start-up: listen when the setting says so. */
    public static void startIfEnabled() {
        if (isEnabled())
            startLogged();
    }

    private static void startLogged() {
        try {
            start();
        } catch (IOException e) {
            Log.warn("Local API could not start", e);
        }
    }

    public static Path endpointFile() {
        return Path.of(Directories.HOME.getPath(), "api.json");
    }

    @Nullable
    public static Endpoint endpoint() {
        return endpoint.get();
    }

    /** Listen (again, if already listening nothing changes) and write the endpoint file. */
    public static synchronized Endpoint start() throws IOException {
        Endpoint running = endpoint.get();
        if (running != null)
            return running;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        http.createContext("/rpc", exchange -> serve(exchange, token));
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HFS-LocalApi");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.start();
        int port = http.getAddress().getPort();
        Path file = endpointFile();
        Files.createDirectories(file.getParent());
        JSONObject json = new JSONObject().put("port", port).put("token", token).put("pid", ProcessHandle.current().pid())
                .put("protocol", "hfstudio-api/1");
        Files.writeString(file, json.toString(1) + "\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the home folder is the person's own already
        }
        Endpoint ep = new Endpoint(port, token, file);
        server.set(http);
        endpoint.set(ep);
        try {
            Runtime.getRuntime().addShutdownHook(cleanup);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // already attached, or shutting down
        }
        Log.info("Local API listening on 127.0.0.1:" + port + " (" + file + ")");
        return ep;
    }

    public static synchronized void stop() {
        HttpServer http = server.getAndSet(null);
        if (http != null)
            http.stop(0);
        endpoint.set(null);
        removeFile();
    }

    private static void removeFile() {
        try {
            Files.deleteIfExists(endpointFile());
        } catch (IOException e) {
            Log.warn("Could not remove " + endpointFile(), e);
        }
    }

    /** For a check: what stands in for the dialog. Returns the previous one. */
    public static Confirmer setConfirmer(Confirmer c) {
        return confirmer.getAndSet(c);
    }

    // HTTP ----------------------------------------------------------------------------------------

    private static void serve(HttpExchange exchange, String token) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, 405, "{\"error\":\"POST a JSON-RPC request\"}");
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!localHost(host)) {
                reply(exchange, 403, "{\"error\":\"this API answers this computer only\"}");
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!bearerMatches(auth, token)) {
                reply(exchange, 401, "{\"error\":\"the token in ~/HFStudio/api.json is needed\"}");
                return;
            }
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                reply(exchange, 413, "{\"error\":\"request too large\"}");
                return;
            }
            JSONObject request;
            try {
                request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                reply(exchange, 200, error(JSONObject.NULL, -32700, "not JSON: " + e.getMessage(), "failed").toString());
                return;
            }
            JSONObject response;
            try {
                response = handle(request);
            } catch (RuntimeException e) { // a failure Dispatch did not translate; the caller still gets an answer
                Log.warn("Local API request failed", e);
                response = error(request.opt("id") == null ? JSONObject.NULL : request.opt("id"), -32000, String.valueOf(e.getMessage()), "failed");
            }
            reply(exchange, 200, response.toString());
        }
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static boolean localHost(@Nullable String host) {
        if (host == null)
            return false;
        String h = host.toLowerCase(java.util.Locale.ROOT).trim();
        if (h.startsWith("[")) { // an IPv6 literal, then maybe a port
            int end = h.indexOf(']');
            if (end < 0)
                return false;
            h = h.substring(0, end + 1);
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0)
                h = h.substring(0, colon);
        }
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]");
    }

    static boolean bearerMatches(@Nullable String auth, String token) {
        if (auth == null || !auth.startsWith("Bearer "))
            return false;
        byte[] given = auth.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    // JSON-RPC ------------------------------------------------------------------------------------

    /** One JSON-RPC 2.0 request to its response; the transport is not involved, so a check calls it directly. */
    public static JSONObject handle(JSONObject request) {
        Object id = request.opt("id") == null ? JSONObject.NULL : request.opt("id");
        String method = request.optString("method", "");
        JSONObject params = request.optJSONObject("params");
        if (params == null)
            params = new JSONObject();
        switch (method) {
            case "commands.list":
                return result(id, new JSONObject().put("tools", CommandRegistry.manifest().getJSONArray("tools")));
            case "commands.invoke":
                return invoke(id, params);
            default:
                return error(id, -32601, "no method '" + method + "'; commands.list and commands.invoke exist", "unknown-method");
        }
    }

    private static JSONObject invoke(Object id, JSONObject params) {
        String command = params.optString("id", "");
        JSONObject args = params.optJSONObject("args");
        if (args == null)
            args = new JSONObject();
        CommandSpec spec = CommandRegistry.get(command);
        if (spec == null)
            return error(id, -32601, "no command '" + command + "'", "unknown-command");
        if (spec.effect() == CommandSpec.Effect.DESTRUCTIVE && confirmsDestructive() && !confirm(spec, args))
            return error(id, -32002, "'" + command + "' was declined", "declined");
        Dispatch.Result r = Dispatch.invoke(command, args);
        if (r.ok())
            return result(id, r.value());
        int code = switch (r.error()) {
            case "unknown-command" -> -32601;
            case "invalid-arguments" -> -32602;
            case "disabled" -> -32001;
            default -> -32000;
        };
        return error(id, code, r.message(), r.error());
    }

    private static boolean confirm(CommandSpec spec, JSONObject args) {
        Confirmer c = confirmer.get();
        if (SwingUtilities.isEventDispatchThread())
            return c.confirm(spec, args);
        boolean[] box = {false};
        try {
            SwingUtilities.invokeAndWait(() -> box[0] = c.confirm(spec, args));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Log.warn("Confirmation of " + spec.id() + " failed", e.getCause());
            return false;
        }
        return box[0];
    }

    private static boolean askInDialog(CommandSpec spec, JSONObject args) {
        String text = "A script or assistant asks Studio to run \"" + spec.id() + "\": " + spec.description() + "."
                + (args.isEmpty() ? "" : "\nArguments: " + args)
                + "\n\nIt changes or discards work. Allow it?"
                + "\n(Settings > General turns this question off.)";
        int answer = JOptionPane.showConfirmDialog(MainFrame.get(), text, "Local API", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
    }

    private static JSONObject result(Object id, JSONObject value) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value);
    }

    private static JSONObject error(Object id, int code, String message, String kind) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message).put("data", new JSONObject().put("kind", kind)));
    }
}
