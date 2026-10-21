package org.helioviewer.jhv.io;

import java.awt.EventQueue;
import java.io.BufferedInputStream;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
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
 * <p>Files already in the cache are offered too, once: {@link #importCacheInBackground} at launch
 * offers every FITS file the library has not been offered yet, which is all of them the first
 * time and afterwards only those that arrived while Heliad was absent.
 *
 * <p>The core is found by the rules the Heliad app uses, minus its development fallback:
 * {@code HELIAD_CORE} (the {@code heliad} command, or a Python with heliad installed), then the
 * Python inside an installed Heliad.app.
 */
public final class HeliadIngest {

    /** Long enough to checksum and copy a large file on a slow disk. */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final byte[] FITS_MAGIC = "SIMPLE  =".getBytes(StandardCharsets.US_ASCII);
    /** Query parameters that may carry a credential, kept out of the library's provenance. */
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i).*(token|key|sig|pass|secret|auth|credential|session|cookie).*|x-(amz|goog)-.*");

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heliad ingest");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<Core> CORE = new AtomicReference<>(); // touched only on QUEUE
    private static final AtomicBoolean NO_CORE = new AtomicBoolean(); // looked once and found none

    /**
     * The library root, and the launch time up to which every file in Studio's cache was offered
     * to it, as {@code "<epoch ms> <root>"}. Another root starts over: Heliad stores a file once
     * by checksum, so offering one twice costs a read, never a second copy.
     */
    static final String IMPORTED_KEY = "heliad.cacheImported";

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

    /**
     * At launch, queue every FITS file in Studio's cache that the Heliad library has not been
     * offered yet ({@link #IMPORTED_KEY}). Returns at once; the files go one by one on the ingest
     * thread, and the mark moves past a file only once the core has settled it ({@link Outcome}).
     */
    public static void importCacheInBackground() {
        HeliadLibrary.Location lib = HeliadLibrary.find();
        if (lib == null)
            return;
        long launched = System.currentTimeMillis();
        long since = importedThrough(Settings.getProperty(IMPORTED_KEY), lib.root());
        File dir = Directories.FILECACHE.getFile();
        QUEUE.execute(() -> {
            List<File> files = pending(dir, since);
            if (files.isEmpty())
                return;
            Log.info("Adding " + files.size() + " file(s) from Studio's cache to the Heliad library at " + lib.root());
            long next = importFiles(files, file -> ingest(lib.root(), null, file), launched);
            Log.info("Heliad cache import: " + (next == launched ? "done" : "some files are left for the next launch"));
            if (next > since) {
                String mark = next + " " + lib.root();
                EventQueue.invokeLater(() -> Settings.setProperty(IMPORTED_KEY, mark)); // Settings is written on the EDT
            }
        });
    }

    /** Whether a Heliad core can be found to serve the library at root, without starting one. */
    public static boolean coreFound(Path root) {
        return command(System.getenv("HELIAD_CORE"), HeliadLibrary.app(), Path.of(System.getProperty("user.home")), root,
                p -> Files.isExecutable(p)) != null;
    }

    /** When Studio's cache was last offered in full to the library at root, or MIN_VALUE for never. */
    public static long cacheOfferedThrough(Path root) {
        return importedThrough(Settings.getProperty(IMPORTED_KEY), root);
    }

    /**
     * Ask the core that serves the library its {@code heliad.hello} (docs/api.md in the heliad
     * repository): its version, the library it serves and whether it may write. Starts the core the
     * downloads use when none is running yet. Null when no core can be found. Blocks for up to
     * {@code wait}, behind any files already queued: call it off the event thread.
     */
    @Nullable
    public static JSONObject hello(Path root, Duration wait) throws IOException {
        try {
            return QUEUE.submit(() -> {
                Core core = core(root);
                if (core == null)
                    return null;
                try {
                    return core.call("heliad.hello", new JSONObject(), wait);
                } catch (RpcError e) {
                    throw e;
                } catch (IOException e) {
                    CORE.set(null);
                    core.close();
                    throw e;
                }
            }).get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Heliad is still busy with Studio's files", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("heliad.hello");
        }
    }

    /** The time from which cache files still need offering to the library at root; MIN_VALUE for all of them. */
    static long importedThrough(@Nullable String mark, Path root) {
        if (mark == null)
            return Long.MIN_VALUE;
        int space = mark.indexOf(' ');
        if (space < 0 || !Path.of(mark.substring(space + 1)).equals(root))
            return Long.MIN_VALUE;
        try {
            return Long.parseLong(mark.substring(0, space));
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /** The FITS files in the cache folder written at or after since, oldest first. */
    static List<File> pending(File dir, long since) {
        File[] files = dir.listFiles(f -> f.isFile() && f.lastModified() >= since && !f.getName().endsWith(".tmp"));
        if (files == null)
            return List.of();
        List<File> out = new ArrayList<>();
        for (File f : files)
            if (isFits(f))
                out.add(f);
        out.sort(java.util.Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        return out;
    }

    /** What became of one file offered to the core. */
    enum Outcome {
        /** Taken (added, or already there), or refused for good: offering it again cannot help. */
        SETTLED,
        /** Refused for a reason that may pass (a full disk, say): offered again at the next launch. */
        RETRY,
        /** No core, or the core was lost: the import stops here. */
        LOST
    }

    /**
     * Offer each file (oldest first) until the core is lost. Returns the time from which files
     * still need offering: launched when every file was settled, else the time of the oldest one
     * that was not, so the next launch starts there and nothing unsettled is skipped.
     */
    static long importFiles(List<File> files, Function<File, Outcome> ingest, long launched) {
        long next = launched;
        for (File file : files) {
            Outcome outcome = ingest.apply(file);
            if (outcome != Outcome.SETTLED)
                next = Math.min(next, file.lastModified());
            if (outcome == Outcome.LOST)
                break;
        }
        return next;
    }

    private static Outcome ingest(Path root, @Nullable URI source, File file) {
        Core core = core(root);
        if (core == null)
            return Outcome.LOST;
        JSONObject params = new JSONObject().put("path", file.getAbsolutePath());
        String url = source == null ? null : provenance(source);
        if (url != null)
            params.put("source_url", url);
        try {
            core.call("ingest.path", params, TIMEOUT);
            return Outcome.SETTLED;
        } catch (RpcError e) { // the core is still running
            Log.warn("Heliad did not take " + file.getName() + ": " + e.getMessage());
            return e.code == RpcError.UNSTORABLE ? Outcome.SETTLED : Outcome.RETRY;
        } catch (IOException e) {
            Log.warn("Lost the Heliad core while adding " + file.getName(), e);
            CORE.set(null);
            core.close();
            return Outcome.LOST;
        }
    }

    /**
     * The download URL as the library records it: without user info, fragment or any query
     * parameter whose name suggests a credential, since anyone given the library can read it.
     * Null when it is not a URL with a host.
     */
    @Nullable
    static String provenance(URI source) {
        if (source.isOpaque() || source.getScheme() == null || source.getHost() == null)
            return null;
        StringBuilder url = new StringBuilder(source.getScheme()).append("://").append(source.getHost());
        if (source.getPort() != -1)
            url.append(':').append(source.getPort());
        if (source.getRawPath() != null)
            url.append(source.getRawPath());
        String query = source.getRawQuery();
        if (query != null) {
            String kept = Arrays.stream(query.split("&"))
                    .filter(p -> !p.isEmpty() && !SECRET_PARAM.matcher(URLDecoder.decode(p.split("=", 2)[0], StandardCharsets.UTF_8)).matches())
                    .collect(Collectors.joining("&"));
            if (!kept.isEmpty())
                url.append('?').append(kept);
        }
        return url.toString();
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
        NO_CORE.set(false); // found after all (Settings asked again, say), so downloads go to it too
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

    /** Whether the file starts the way every FITS file must, once unwrapped if it is gzip-compressed. */
    static boolean isFits(File file) {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            raw.mark(2);
            boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            if (!gzip)
                return Arrays.equals(raw.readNBytes(FITS_MAGIC.length), FITS_MAGIC);
            try (InputStream in = new GZIPInputStream(raw)) {
                return Arrays.equals(in.readNBytes(FITS_MAGIC.length), FITS_MAGIC);
            }
        } catch (IOException e) { // unreadable, or a broken gzip stream
            return false;
        }
    }

    /** An error the core answered with, as opposed to losing the core. */
    static final class RpcError extends IOException {
        private static final long serialVersionUID = 1L;
        /** ingest.path: the file can never enter the library (cut off, or not FITS); docs/api.md in heliad. */
        static final int UNSTORABLE = -32001;

        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
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
                        reply.completeExceptionally(new RpcError(error.optInt("code"), error.optString("message", "error " + error.optInt("code"))));
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
