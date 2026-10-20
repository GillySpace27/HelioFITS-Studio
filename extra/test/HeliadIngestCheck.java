package org.helioviewer.jhv.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

/**
 * Handing Studio's downloads to Heliad: which core is started and how, which files are offered,
 * and the JSON-RPC exchange with the core, including a core that refuses, hangs or ends.
 *
 * <p>The exchange is checked against a stand-in core (FakeCore below, run in its own JVM), so it
 * runs everywhere. With {@code HELIAD_CORE} set to a real core, it also adds a fixture to a
 * scratch library through a second core while a first one holds the library, which is Studio
 * downloading while the Heliad app is open, and reads the result back the way the Heliad tab does.
 * Never against the real library: every library and config folder here is a temp directory.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.HeliadIngestCheck
 */
public final class HeliadIngestCheck {

    private static final Path FIXTURE = Path.of("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits");
    private static final Duration SHORT = Duration.ofSeconds(20);

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        commands();
        files();
        cacheImport();
        exchange();
        realCore();
        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
    }

    private static void commands() {
        Path home = Path.of("/home/u");
        Path root = Path.of("/data/HeliadLibrary");
        Path app = Path.of("/Applications/Heliad.app");
        Path bundled = app.resolve("Contents/Resources/python/bin/python3");
        List<String> tail = List.of("--library", root.toString(), "serve");

        String py = Path.of("/opt/py/bin/python3").toString(); // as this platform spells it
        List<String> python = HeliadIngest.command("/opt/py/bin/python3", null, home, root, p -> true);
        expect("HELIAD_CORE naming a Python runs it with -m heliad, got " + python,
                List.of(py, "-m", "heliad", "--library", root.toString(), "serve").equals(python));
        List<String> command = HeliadIngest.command("~/venv/bin/heliad", app, home, root, p -> true);
        expect("HELIAD_CORE naming the heliad command runs it as it is, ~ expanded, and wins over the app, got " + command,
                List.of(home.resolve("venv/bin/heliad").toString(), "--library", root.toString(), "serve").equals(command));
        List<String> inApp = HeliadIngest.command("/gone/heliad", app, home, root, bundled::equals);
        expect("a HELIAD_CORE that is not there falls back to the app's own Python, isolated, got " + inApp,
                inApp != null && inApp.subList(0, 6).equals(List.of(bundled.toString(), "-B", "-s", "-P", "-m", "heliad"))
                        && inApp.subList(6, inApp.size()).equals(tail));
        expect("no HELIAD_CORE and no app: no core", HeliadIngest.command(null, null, home, root, p -> true) == null);
        expect("an app without its Python: no core", HeliadIngest.command("", app, home, root, p -> false) == null);
    }

    private static void files() throws IOException {
        expect("a FITS file is offered", HeliadIngest.isFits(FIXTURE.toFile()));
        expect("a text file is not", !HeliadIngest.isFits(new File("extra/test/data/README.md")));
        File empty = Files.createTempFile("hfs-heliad-empty", ".fits").toFile();
        empty.deleteOnExit();
        expect("an empty file is not", !HeliadIngest.isFits(empty));
        expect("a missing file is not", !HeliadIngest.isFits(new File(empty.getParentFile(), "no-such-file.fits")));
        // Not offered, so this never looks for a library: a FITS file here could reach a real one.
        expect("offer() turns down a file that is not FITS", !HeliadIngest.offer(URI.create("https://example.invalid/r.md"),
                new File("extra/test/data/README.md")));
    }

    private static void cacheImport() throws IOException {
        Path root = Path.of("/data/HeliadLibrary");
        expect("no mark: every cached file is offered", HeliadIngest.importedThrough(null, root) == Long.MIN_VALUE);
        expect("a mark for this library: files from then on",
                HeliadIngest.importedThrough("1700000000000 " + root, root) == 1_700_000_000_000L);
        expect("a mark for another library: every file again",
                HeliadIngest.importedThrough("1700000000000 /elsewhere/lib", root) == Long.MIN_VALUE);
        expect("a library path with a space survives the mark",
                HeliadIngest.importedThrough("5 /Volumes/My Disk/lib", Path.of("/Volumes/My Disk/lib")) == 5L);
        expect("an unreadable mark: every file", HeliadIngest.importedThrough("soon " + root, root) == Long.MIN_VALUE);

        Path dir = Files.createTempDirectory("hfs-heliad-cache");
        byte[] fits = "SIMPLE  =                    T".getBytes(StandardCharsets.US_ASCII);
        File old = write(dir.resolve("old_aia_1a2b3c4d5e6f.fits"), fits, 1_000_000L);
        File fresh = write(dir.resolve("new_punch_0a0b0c0d0e0f.fits"), fits, 3_000_000L);
        write(dir.resolve("timeline_9f8e7d6c5b4a.cdf"), "CDF".getBytes(StandardCharsets.US_ASCII), 3_000_000L);
        write(dir.resolve("dl12345.tmp"), fits, 3_000_000L); // a download still in flight
        expect("the first import offers every FITS file, oldest first, and nothing else, got " + HeliadIngest.pending(dir.toFile(), Long.MIN_VALUE),
                HeliadIngest.pending(dir.toFile(), Long.MIN_VALUE).equals(List.of(old, fresh)));
        expect("a later one only what arrived since the mark",
                HeliadIngest.pending(dir.toFile(), 2_000_000L).equals(List.of(fresh)));
        expect("a missing cache folder offers nothing", HeliadIngest.pending(dir.resolve("gone").toFile(), Long.MIN_VALUE).isEmpty());

        List<File> offered = new java.util.ArrayList<>();
        expect("every file answered counts them all",
                HeliadIngest.importFiles(List.of(old, fresh), f -> offered.add(f)) == 2 && offered.equals(List.of(old, fresh)));
        offered.clear();
        expect("a lost core stops the import there, so the mark does not move past it",
                HeliadIngest.importFiles(List.of(old, fresh), f -> { offered.add(f); return false; }) == 0 && offered.equals(List.of(old)));
    }

    private static File write(Path path, byte[] bytes, long modified) throws IOException {
        Files.write(path, bytes);
        File file = path.toFile();
        if (!file.setLastModified(modified))
            throw new IOException("cannot set the time of " + file);
        return file;
    }

    private static void exchange() throws Exception {
        Path dir = Files.createTempDirectory("hfs-heliad-fake");
        Path log = dir.resolve("requests.jsonl");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                FakeCore.class.getName(), log.toString());
        HeliadIngest.Core core = new HeliadIngest.Core(command, Map.of(), dir);

        JSONObject added = core.call("ingest.path", new JSONObject().put("path", "/x/a.fits").put("source_url", "https://a.example/a.fits"), SHORT);
        expect("the reply is matched past a notification sent before it, got " + added, "added".equals(added.optString("status")));
        JSONObject sent = new JSONObject(Files.readAllLines(log).getFirst());
        expect("the request is JSON-RPC 2.0 with the path and the URL it came from, got " + sent,
                "2.0".equals(sent.optString("jsonrpc")) && "ingest.path".equals(sent.optString("method"))
                        && "/x/a.fits".equals(sent.getJSONObject("params").optString("path"))
                        && "https://a.example/a.fits".equals(sent.getJSONObject("params").optString("source_url")));

        try {
            core.call("refuse", new JSONObject(), SHORT);
            expect("an error reply is thrown", false);
        } catch (HeliadIngest.RpcError e) {
            expect("an error reply is thrown with the core's message, got " + e.getMessage(), "IncompleteFits: cut off".equals(e.getMessage()));
        }
        expect("and the core is still up", core.alive());

        try {
            core.call("hang", new JSONObject(), Duration.ofMillis(300));
            expect("an unanswered call times out", false);
        } catch (HeliadIngest.RpcError e) {
            expect("an unanswered call times out, not as the core's refusal", false);
        } catch (IOException e) {
            expect("an unanswered call times out, got " + e.getMessage(), e.getMessage().startsWith("no answer"));
        }

        long started = System.nanoTime();
        try {
            core.call("exit", new JSONObject(), SHORT);
            expect("a core that ends mid-call is reported", false);
        } catch (HeliadIngest.RpcError e) {
            expect("a core that ends is lost, not a refusal", false);
        } catch (IOException e) {
            expect("a core that ends mid-call is reported at once, not at the timeout ("
                            + (System.nanoTime() - started) / 1_000_000 + " ms)",
                    System.nanoTime() - started < SHORT.toNanos() / 2);
        }
        core.waitForExit();
        expect("and is no longer alive", !core.alive());
        try {
            core.call("ingest.path", new JSONObject(), SHORT);
            expect("a call to an ended core fails", false);
        } catch (IOException e) {
            expect("a call to an ended core fails at once", true);
        }
        core.close(); // closing an ended core is quiet
    }

    private static void realCore() throws Exception {
        String heliadCore = System.getenv("HELIAD_CORE");
        if (heliadCore == null || heliadCore.isEmpty()) {
            System.out.println("  skip a real Heliad core: HELIAD_CORE is not set");
            return;
        }
        Path tmp = Files.createTempDirectory("hfs-heliad-real");
        Path appSupport = tmp.resolve("appsupport");
        Path root = tmp.resolve("library");
        Map<String, String> env = Map.of("HELIAD_HOME", appSupport.toString());
        List<String> command = HeliadIngest.command(heliadCore, null, tmp, root, Files::isExecutable);
        expect("HELIAD_CORE gives a command, got " + command, command != null);
        if (command == null)
            return;

        HeliadIngest.Core app = new HeliadIngest.Core(command, env, root); // the Heliad app's core: the writer
        expect("the first core writes the library", !app.call("heliad.hello", new JSONObject(), SHORT).optBoolean("readonly", true));
        HeliadIngest.Core studio = new HeliadIngest.Core(command, env, root); // Studio's, which relays to it
        Duration ingest = Duration.ofMinutes(2);
        String url = "https://archive.example/" + FIXTURE.getFileName();
        JSONObject first = studio.call("ingest.path", new JSONObject().put("path", FIXTURE.toAbsolutePath().toString()).put("source_url", url), ingest);
        expect("a second core adds the file while the first holds the library, got " + first, "added".equals(first.optString("status")));
        JSONObject again = studio.call("ingest.path", new JSONObject().put("path", FIXTURE.toAbsolutePath().toString()).put("source_url", url), ingest);
        expect("the same file again is a duplicate, got " + again, "duplicate".equals(again.optString("status")));
        expect("the writer sees it, got " + app.call("library.stats", new JSONObject(), SHORT),
                app.call("library.stats", new JSONObject(), SHORT).optInt("files") == 1);
        studio.close();
        expect("Studio's core exits cleanly", studio.waitForExit() == 0);
        app.close();
        expect("and so does the writer", app.waitForExit() == 0);

        HeliadLibrary.Location lib = HeliadLibrary.read(appSupport);
        expect("the Heliad tab finds the library", lib != null && lib.root().equals(root.toRealPath()));
        if (lib == null)
            return;
        List<HeliadLibrary.Dataset> sets = HeliadLibrary.datasets(lib, 0L, 32_503_680_000_000L); // 1970 to 3000
        expect("and lists one dataset of one frame, got " + sets, sets.size() == 1 && sets.getFirst().frames() == 1);
        List<File> files = sets.isEmpty() ? List.of() : HeliadLibrary.files(lib, sets.getFirst().key());
        expect("whose file is the library's copy, not Studio's, got " + files,
                files.size() == 1 && files.getFirst().isFile() && files.getFirst().toPath().startsWith(lib.root())
                        && Files.size(files.getFirst().toPath()) == Files.size(FIXTURE));
    }

    /** A stand-in core: answers ingest.path, refuses "refuse", never answers "hang", ends on "exit". */
    public static final class FakeCore {
        public static void main(String[] args) throws IOException {
            Path log = Path.of(args[0]);
            PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                Files.writeString(log, line + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                JSONObject request = new JSONObject(line);
                Object id = request.get("id");
                String method = request.getString("method");
                if (Set.of("hang").contains(method))
                    continue;
                if (method.equals("exit"))
                    System.exit(0);
                if (method.equals("refuse")) {
                    out.println(new JSONObject().put("jsonrpc", "2.0").put("id", id)
                            .put("error", new JSONObject().put("code", -32603).put("message", "IncompleteFits: cut off")));
                    continue;
                }
                out.println(new JSONObject().put("jsonrpc", "2.0").put("method", "ingest.progress").put("params", new JSONObject().put("done", 1)));
                out.println(new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", new JSONObject().put("status", "added").put("file_id", 1).put("relpath", "store/a.fits")));
            }
        }
    }

}
