package org.helioviewer.jhv.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hands each FITS file Studio downloads to Heliad, so it lands in the Heliad library too.
 *
 * <p>Studio keeps its own archive clients and its own cache: a download goes into
 * {@code ~/HFStudio/FileCache} exactly as before and loads from there. When a Heliad library exists
 * ({@link HeliadLibrary#find}) and a Heliad core can be found, the new file is also offered to the
 * core's {@code ingest.path}, which copies it into the library (an APFS clone on a Mac, so no extra
 * disk there), deduplicates it by checksum and serves the newest version of each observation. The
 * core is the library's only writer; Studio starts one with {@code heliad serve}, which relays to
 * the Heliad app's core when the app is open. Without a library or a core nothing happens, and a
 * failed ingest is logged and never reaches the download.
 *
 * <p>The core is found by the rules the Heliad app uses, minus its development fallback:
 * {@code HELIAD_CORE} (the {@code heliad} command, or a Python with heliad installed), then the
 * Python inside an installed Heliad.app.
 */
public final class HeliadIngest {

    /** Long enough to checksum and copy a large file on a slow disk. */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final byte[] FITS_MAGIC = "SIMPLE  =".getBytes(StandardCharsets.US_ASCII);

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heliad ingest");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<Core> CORE = new AtomicReference<>(); // touched only on QUEUE
    private static final AtomicBoolean NO_CORE = new AtomicBoolean(); // looked once and found none

    private HeliadIngest() {}

    /**
     * Queue a just-downloaded file for the Heliad library. False when nothing was queued: not a
     * FITS file, no Heliad library, or no core this session.
     */
    public static boolean offer(URI source, File file) {
        if (NO_CORE.get() || !isFits(file))
            return false;
        HeliadLibrary.Location lib = HeliadLibrary.find();
        if (lib == null)
            return false;
        QUEUE.execute(() -> ingest(lib.root(), source, file));
        return true;
    }

    private static void ingest(Path root, URI source, File file) {
        Core core = core(root);
        if (core == null)
            return;
        try {
            core.call("ingest.path", new JSONObject().put("path", file.getAbsolutePath()).put("source_url", source.toString()), TIMEOUT);
        } catch (RpcError e) { // the core refused this file (a cut-off download, say); it is still running
            Log.warn("Heliad did not take " + file.getName() + ": " + e.getMessage());
        } catch (IOException e) {
            Log.warn("Lost the Heliad core while adding " + file.getName(), e);
            CORE.set(null);
            core.close();
        }
    }

    @Nullable
    private static Core core(Path root) {
        Core core = CORE.get();
        if (core != null && core.alive() && core.root.equals(root))
            return core;
        if (core != null)
            core.close();
        CORE.set(null);
        List<String> command = command(System.getenv("HELIAD_CORE"), HeliadLibrary.app(),
                Path.of(System.getProperty("user.home")), root, p -> Files.isExecutable(p));
        if (command == null) {
            NO_CORE.set(true);
            Log.info("Heliad library found but no Heliad core (install Heliad.app or set HELIAD_CORE); Studio downloads stay in its own cache");
            return null;
        }
        try {
            core = new Core(command, Map.of(), root);
        } catch (IOException e) {
            NO_CORE.set(true);
            Log.warn("Could not start the Heliad core " + command.getFirst(), e);
            return null;
        }
        CORE.set(core);
        return core;
    }

    /**
     * The command that starts a core serving the library at root, or null when no core is found.
     *
     * @param heliadCore the {@code HELIAD_CORE} variable
     * @param app        the installed Heliad.app, if any
     */
    @Nullable
    static List<String> command(@Nullable String heliadCore, @Nullable Path app, Path home, Path root,
                                Predicate<Path> executable) {
        List<String> command = new ArrayList<>();
        if (heliadCore != null && !heliadCore.isEmpty()) {
            Path path = heliadCore.equals("~") ? home : heliadCore.startsWith("~/") ? home.resolve(heliadCore.substring(2)) : Path.of(heliadCore);
            if (executable.test(path)) {
                command.add(path.toString());
                if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("python"))
                    command.addAll(List.of("-m", "heliad"));
            }
        }
        if (command.isEmpty() && app != null) {
            Path python = app.resolve("Contents").resolve("Resources").resolve("python").resolve("bin").resolve("python3");
            if (executable.test(python))
                // -B: writing bytecode into a signed app would break its signature.
                // -s, -P: no user site-packages, no current folder on sys.path.
                command.addAll(List.of(python.toString(), "-B", "-s", "-P", "-m", "heliad"));
        }
        if (command.isEmpty())
            return null;
        command.addAll(List.of("--library", root.toString(), "serve"));
        return command;
    }

    /** Whether the file starts the way every FITS file must. */
    static boolean isFits(File file) {
        byte[] head = new byte[FITS_MAGIC.length];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return in.readNBytes(head, 0, head.length) == head.length && java.util.Arrays.equals(head, FITS_MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    /** An error the core answered with, as opposed to losing the core. */
    static final class RpcError extends IOException {
        RpcError(String message) {
            super(message);
        }
    }

    /** One {@code heliad serve} process, spoken to in JSON-RPC lines over its stdin and stdout. */
    static final class Core implements AutoCloseable {

        final Path root;
        private final Process process;
        private final Writer in;
        private final Map<Integer, CompletableFuture<JSONObject>> waiting = new ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();
        private final AtomicBoolean ended = new AtomicBoolean();

        Core(List<String> command, Map<String, String> env, Path root) throws IOException {
            this.root = root;
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(env);
            builder.environment().put("PYTHONUNBUFFERED", "1");
            process = builder.start();
            in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            daemon("Heliad core replies", this::readReplies);
            daemon("Heliad core messages", this::readMessages);
        }

        private static void daemon(String name, Runnable body) {
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            t.start();
        }

        private void readReplies() {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    JSONObject message;
                    try {
                        message = new JSONObject(line);
                    } catch (JSONException e) {
                        continue;
                    }
                    if (!message.has("id") || message.isNull("id"))
                        continue; // a notification (download progress and the like): nothing here waits for one
                    CompletableFuture<JSONObject> reply = waiting.get(message.optInt("id", -1));
                    if (reply == null)
                        continue;
                    JSONObject error = message.optJSONObject("error");
                    if (error != null)
                        reply.completeExceptionally(new RpcError(error.optString("message", "error " + error.optInt("code"))));
                    else
                        reply.complete(message.opt("result") instanceof JSONObject result ? result : new JSONObject());
                }
            } catch (IOException e) {
                // the pipe broke: the same as the core ending, below
            }
            ended.set(true);
            IOException gone = new IOException("the Heliad core ended");
            waiting.values().forEach(reply -> reply.completeExceptionally(gone));
        }

        private void readMessages() {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null)
                    Log.warn("Heliad core: " + line);
            } catch (IOException e) {
                // the core has gone; readReplies reports that
            }
        }

        boolean alive() {
            return process.isAlive() && !ended.get();
        }

        JSONObject call(String method, JSONObject params, Duration timeout) throws IOException {
            int id = ids.incrementAndGet();
            CompletableFuture<JSONObject> reply = new CompletableFuture<>();
            waiting.put(id, reply);
            try {
                if (!alive()) // checked after waiting holds this call, so an ending core cannot strand it
                    throw new IOException("the Heliad core ended");
                String request = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString();
                synchronized (in) {
                    in.write(request);
                    in.write('\n');
                    in.flush();
                }
                return reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (TimeoutException e) {
                throw new IOException("no answer from the Heliad core to " + method + " in " + timeout.toSeconds() + " s", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException(method);
            } finally {
                waiting.remove(id);
            }
        }

        /**
         * Ends the core's input, which is how a core is told to finish: it answers what it was
         * asked first. Never killed: if it is the library's writer, the Heliad app may be relaying
         * through it, and it stays up until the app has let go.
         */
        @Override
        public void close() {
            try {
                synchronized (in) {
                    in.close();
                }
            } catch (IOException e) {
                // already closed, or the core has gone
            }
        }

        /** The core's exit code once it has ended. */
        int waitForExit() throws InterruptedException {
            return process.waitFor();
        }
    }

}
