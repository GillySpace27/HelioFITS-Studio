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
 * Handing Studio's downloads to HelioFITS Core: which core is started and how, which files are offered,
 * and the JSON-RPC exchange with the core, including a core that refuses, hangs or ends.
 *
 * <p>The exchange is checked against a stand-in core (FakeCore below, run in its own JVM), so it
 * runs everywhere. With {@code HFCORE_CORE} (or {@code HELIAD_CORE}) set to a real core, it also adds a fixture to a
 * scratch library through a second core while a first one holds the library, which is Studio
 * downloading while the HelioFITS Cache Manager is open, and reads the result back the way the FITS Library tab does.
 * Never against the real library: every library and config folder here is a temp directory.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.FitsCoreIngestCheck
 */
public final class FitsCoreIngestCheck {

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
        Path root = Path.of("/data/HelioFITSLibrary");
        Path app = Path.of("/Applications/HelioFITS Cache Manager.app");
        Path oldApp = Path.of("/Applications/Heliad.app");
        Path bundled = app.resolve("Contents/Resources/python/bin/python3");
        Path oldBundled = oldApp.resolve("Contents/Resources/python/bin/python3");
        Path studio = Path.of("/Applications/HelioFITS Studio.app/Contents/Resources/heliofits-core/bin/python3");
        List<String> tail = List.of("--library", root.toString(), "serve");

        String py = Path.of("/opt/py/bin/python3").toString(); // as this platform spells it
        List<String> python = FitsCoreIngest.command("/opt/py/bin/python3", null, null, null, home, root, p -> true);
        // Isolated like the bundled ones: a ~/.local astropy whose asdf_astropy lacked asdf broke every
        // download of a core named this way (Gilly, 2026-10-25).
        expect("HFCORE_CORE naming a Python runs it isolated with -m heliofits_core, got " + python,
                List.of(py, "-s", "-P", "-m", "heliofits_core", "--library", root.toString(), "serve").equals(python));
        List<String> legacy = FitsCoreIngest.command(null, "/opt/py/bin/python3", null, null, home, root, p -> true);
        expect("HELIAD_CORE, its name before the rename, runs a Python with -m heliad, got " + legacy,
                List.of(py, "-s", "-P", "-m", "heliad", "--library", root.toString(), "serve").equals(legacy));
        List<String> both = FitsCoreIngest.command("/opt/py/bin/python3", "/old/bin/python3", null, null, home, root, p -> true);
        expect("HFCORE_CORE wins over HELIAD_CORE, got " + both, both != null && both.equals(python));
        List<String> command = FitsCoreIngest.command("~/venv/bin/hfcore", null, studio, app, home, root, p -> true);
        expect("HFCORE_CORE naming the hfcore command runs it as it is, ~ expanded, and wins over the app, got " + command,
                List.of(home.resolve("venv/bin/hfcore").toString(), "--library", root.toString(), "serve").equals(command));
        List<String> inApp = FitsCoreIngest.command("/gone/hfcore", null, null, app, home, root, bundled::equals);
        expect("an HFCORE_CORE that is not there falls back to the app's own Python, isolated, got " + inApp,
                inApp != null && inApp.subList(0, 6).equals(List.of(bundled.toString(), "-B", "-s", "-P", "-m", "heliofits_core"))
                        && inApp.subList(6, inApp.size()).equals(tail));
        List<String> inOldApp = FitsCoreIngest.command(null, null, null, oldApp, home, root, oldBundled::equals);
        expect("Heliad.app from before the rename runs its Python with -m heliad, got " + inOldApp,
                inOldApp != null && inOldApp.subList(0, 6).equals(List.of(oldBundled.toString(), "-B", "-s", "-P", "-m", "heliad")));
        expect("no core variable and no app: no core", FitsCoreIngest.command(null, null, null, null, home, root, p -> true) == null);
        expect("an app without its Python: no core", FitsCoreIngest.command("", "", studio, app, home, root, p -> false) == null);

        Path studio2 = FitsCoreIngest.studioCore("/Applications/HelioFITS Studio.app/Contents/MacOS/HelioFITS Studio", "Mac OS X");
        expect("Studio's own core is in its bundle's Resources, got " + studio2, studio.equals(studio2));
        expect("a build tree has no packaged core", FitsCoreIngest.studioCore(null, "Mac OS X") == null);
        expect("no packaged core outside macOS yet", FitsCoreIngest.studioCore("/opt/hfstudio/bin/HFStudio", "Linux") == null);
        List<String> own = FitsCoreIngest.command(null, null, studio, oldApp, home, root, p -> true);
        expect("Studio's own core wins over an installed app's, isolated, got " + own,
                own != null && own.subList(0, 6).equals(List.of(studio.toString(), "-B", "-s", "-P", "-m", "heliofits_core"))
                        && own.subList(6, own.size()).equals(tail));
        List<String> appOnly = FitsCoreIngest.command(null, null, studio, app, home, root, bundled::equals);
        expect("a Studio core that is not there falls back to the app's, got " + appOnly,
                appOnly != null && appOnly.getFirst().equals(bundled.toString()));
        List<String> dev = FitsCoreIngest.command("/opt/py/bin/python3", null, studio, app, home, root, p -> true);
        expect("HFCORE_CORE wins over Studio's own core, got " + dev, dev != null && dev.getFirst().equals(py));
    }

    private static void files() throws IOException {
        expect("a FITS file is offered", FitsCoreIngest.isFits(FIXTURE.toFile()));
        expect("a text file is not", !FitsCoreIngest.isFits(new File("extra/test/data/README.md")));
        File empty = Files.createTempFile("hfs-fitscore-empty", ".fits").toFile();
        empty.deleteOnExit();
        expect("an empty file is not", !FitsCoreIngest.isFits(empty));
        expect("a missing file is not", !FitsCoreIngest.isFits(new File(empty.getParentFile(), "no-such-file.fits")));
        File zipped = Files.createTempFile("hfs-fitscore-zipped", ".fits.gz").toFile();
        zipped.deleteOnExit();
        try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(zipped.toPath()))) {
            out.write(Files.readAllBytes(FIXTURE));
        }
        expect("a gzip-compressed FITS file is offered", FitsCoreIngest.isFits(zipped));
        File zippedText = Files.createTempFile("hfs-fitscore-zipped", ".txt.gz").toFile();
        zippedText.deleteOnExit();
        try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(zippedText.toPath()))) {
            out.write("SIMPLE  ? no".getBytes(StandardCharsets.US_ASCII));
        }
        expect("a gzip-compressed text file is not", !FitsCoreIngest.isFits(zippedText));
        File broken = Files.createTempFile("hfs-fitscore-broken", ".fits.gz").toFile();
        broken.deleteOnExit();
        Files.write(broken.toPath(), new byte[] {0x1f, (byte) 0x8b, 0, 0});
        expect("a broken gzip stream is not", !FitsCoreIngest.isFits(broken));

        expect("the recorded URL loses user info, fragment and credential-like parameters, keeps the rest, got "
                        + FitsCoreIngest.provenance(URI.create("https://me:pw@a.example:8443/d/f.fits?date=2025-09-21&access_token=t&X-Amz-Signature=s&apikey=k&wave=193#x")),
                "https://a.example:8443/d/f.fits?date=2025-09-21&wave=193".equals(FitsCoreIngest.provenance(
                        URI.create("https://me:pw@a.example:8443/d/f.fits?date=2025-09-21&access_token=t&X-Amz-Signature=s&apikey=k&wave=193#x"))));
        expect("a URL with only secrets keeps no query",
                "https://a.example/f.fits".equals(FitsCoreIngest.provenance(URI.create("https://a.example/f.fits?sig=abc"))));
        expect("a plain URL is kept as it is",
                "https://a.example/f%20g.fits".equals(FitsCoreIngest.provenance(URI.create("https://a.example/f%20g.fits"))));
        expect("a URL without a host is not recorded", FitsCoreIngest.provenance(URI.create("mailto:me@a.example")) == null);
        // Not offered, so this never looks for a library: a FITS file here could reach a real one.
        expect("offer() turns down a file that is not FITS", !FitsCoreIngest.offer(URI.create("https://example.invalid/r.md"),
                new File("extra/test/data/README.md")));
    }

    private static void cacheImport() throws IOException {
        Path root = Path.of("/data/HelioFITSLibrary");
        expect("no mark: every cached file is offered", FitsCoreIngest.importedThrough(null, root) == Long.MIN_VALUE);
        expect("a mark for this library: files from then on",
                FitsCoreIngest.importedThrough("1700000000000 " + root, root) == 1_700_000_000_000L);
        expect("a mark for another library: every file again",
                FitsCoreIngest.importedThrough("1700000000000 /elsewhere/lib", root) == Long.MIN_VALUE);
        expect("a library path with a space survives the mark",
                FitsCoreIngest.importedThrough("5 /Volumes/My Disk/lib", Path.of("/Volumes/My Disk/lib")) == 5L);
        expect("an unreadable mark: every file", FitsCoreIngest.importedThrough("soon " + root, root) == Long.MIN_VALUE);

        Path dir = Files.createTempDirectory("hfs-fitscore-cache");
        byte[] fits = "SIMPLE  =                    T".getBytes(StandardCharsets.US_ASCII);
        File old = write(dir.resolve("old_aia_1a2b3c4d5e6f.fits"), fits, 1_000_000L);
        File fresh = write(dir.resolve("new_punch_0a0b0c0d0e0f.fits"), fits, 3_000_000L);
        write(dir.resolve("timeline_9f8e7d6c5b4a.cdf"), "CDF".getBytes(StandardCharsets.US_ASCII), 3_000_000L);
        write(dir.resolve("dl12345.tmp"), fits, 3_000_000L); // a download still in flight
        expect("the first import offers every FITS file, oldest first, and nothing else, got " + FitsCoreIngest.pending(dir.toFile(), Long.MIN_VALUE),
                FitsCoreIngest.pending(dir.toFile(), Long.MIN_VALUE).equals(List.of(old, fresh)));
        expect("a later one only what arrived since the mark",
                FitsCoreIngest.pending(dir.toFile(), 2_000_000L).equals(List.of(fresh)));
        expect("a missing cache folder offers nothing", FitsCoreIngest.pending(dir.resolve("gone").toFile(), Long.MIN_VALUE).isEmpty());

        long launched = 5_000_000L;
        List<File> offered = new java.util.ArrayList<>();
        expect("every file settled moves the mark to the launch",
                FitsCoreIngest.importFiles(List.of(old, fresh), f -> { offered.add(f); return FitsCoreIngest.Outcome.SETTLED; }, launched) == launched
                        && offered.equals(List.of(old, fresh)));
        offered.clear();
        expect("a lost core stops the import there, so the mark does not move past it",
                FitsCoreIngest.importFiles(List.of(old, fresh), f -> { offered.add(f); return FitsCoreIngest.Outcome.LOST; }, launched) == old.lastModified()
                        && offered.equals(List.of(old)));
        offered.clear();
        expect("a refusal that may pass goes on with the rest but keeps that file for the next launch",
                FitsCoreIngest.importFiles(List.of(old, fresh), f -> { offered.add(f); return f == old ? FitsCoreIngest.Outcome.RETRY : FitsCoreIngest.Outcome.SETTLED; }, launched) == old.lastModified()
                        && offered.equals(List.of(old, fresh)));
        expect("and a later one keeps only itself",
                FitsCoreIngest.importFiles(List.of(old, fresh), f -> f == fresh ? FitsCoreIngest.Outcome.RETRY : FitsCoreIngest.Outcome.SETTLED, launched) == fresh.lastModified());
    }

    private static File write(Path path, byte[] bytes, long modified) throws IOException {
        Files.write(path, bytes);
        File file = path.toFile();
        if (!file.setLastModified(modified))
            throw new IOException("cannot set the time of " + file);
        return file;
    }

    private static void exchange() throws Exception {
        Path dir = Files.createTempDirectory("hfs-fitscore-fake");
        Path log = dir.resolve("requests.jsonl");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                FakeCore.class.getName(), log.toString());
        FitsCoreIngest.Core core = new FitsCoreIngest.Core(command, Map.of(), dir);

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
        } catch (FitsCoreIngest.RpcError e) {
            expect("an error reply is thrown with the core's message and code, got " + e.code + " " + e.getMessage(),
                    "OSError: disk full".equals(e.getMessage()) && e.code == -32603);
        }
        try {
            core.call("unstorable", new JSONObject(), SHORT);
            expect("a final refusal is thrown", false);
        } catch (FitsCoreIngest.RpcError e) {
            expect("a final refusal carries its own code, got " + e.code, e.code == FitsCoreIngest.RpcError.UNSTORABLE);
        }
        expect("and the core is still up", core.alive());

        try {
            core.call("hang", new JSONObject(), Duration.ofMillis(300));
            expect("an unanswered call times out", false);
        } catch (FitsCoreIngest.RpcError e) {
            expect("an unanswered call times out, not as the core's refusal", false);
        } catch (IOException e) {
            expect("an unanswered call times out, got " + e.getMessage(), e.getMessage().startsWith("no answer"));
        }

        long started = System.nanoTime();
        try {
            core.call("exit", new JSONObject(), SHORT);
            expect("a core that ends mid-call is reported", false);
        } catch (FitsCoreIngest.RpcError e) {
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
        String fitsCore = System.getenv("HFCORE_CORE");
        String legacyCore = System.getenv("HELIAD_CORE");
        if ((fitsCore == null || fitsCore.isEmpty()) && (legacyCore == null || legacyCore.isEmpty())) {
            System.out.println("  skip a real HelioFITS Core: HFCORE_CORE is not set");
            return;
        }
        Path tmp = Files.createTempDirectory("hfs-fitscore-real");
        Path appSupport = tmp.resolve("appsupport");
        Path root = tmp.resolve("library");
        Map<String, String> env = Map.of("HFCORE_HOME", appSupport.toString(), "HELIAD_HOME", appSupport.toString());
        List<String> command = FitsCoreIngest.command(fitsCore, legacyCore, null, null, tmp, root, Files::isExecutable);
        expect("HFCORE_CORE gives a command, got " + command, command != null);
        if (command == null)
            return;

        FitsCoreIngest.Core app = new FitsCoreIngest.Core(command, env, root); // the Cache Manager's core: the writer
        expect("the first core writes the library", !app.call("core.hello", new JSONObject(), SHORT).optBoolean("readonly", true));
        FitsCoreIngest.Core studio = new FitsCoreIngest.Core(command, env, root); // Studio's, which relays to it
        Duration ingest = Duration.ofMinutes(2);
        String url = "https://archive.example/" + FIXTURE.getFileName();
        JSONObject first = studio.call("ingest.path", new JSONObject().put("path", FIXTURE.toAbsolutePath().toString()).put("source_url", url), ingest);
        expect("a second core adds the file while the first holds the library, got " + first, "added".equals(first.optString("status")));
        JSONObject again = studio.call("ingest.path", new JSONObject().put("path", FIXTURE.toAbsolutePath().toString()).put("source_url", url), ingest);
        expect("the same file again is a duplicate, got " + again, "duplicate".equals(again.optString("status")));
        Path cut = tmp.resolve("cut.fits");
        Files.write(cut, java.util.Arrays.copyOf(Files.readAllBytes(FIXTURE), 1000));
        try {
            studio.call("ingest.path", new JSONObject().put("path", cut.toString()), ingest);
            expect("a cut-off file is refused", false);
        } catch (FitsCoreIngest.RpcError e) {
            expect("a cut-off file is refused for good, got " + e.code + " " + e.getMessage(), e.code == FitsCoreIngest.RpcError.UNSTORABLE);
        }
        expect("the writer sees it, got " + app.call("library.stats", new JSONObject(), SHORT),
                app.call("library.stats", new JSONObject(), SHORT).optInt("files") == 1);
        studio.close();
        expect("Studio's core exits cleanly", studio.waitForExit() == 0);
        app.close();
        expect("and so does the writer", app.waitForExit() == 0);

        FitsLibrary.Location lib = FitsLibrary.read(appSupport);
        expect("the FITS Library tab finds the library", lib != null && lib.root().equals(root.toRealPath()));
        if (lib == null)
            return;
        List<FitsLibrary.Dataset> sets = FitsLibrary.datasets(lib, 0L, 32_503_680_000_000L); // 1970 to 3000
        expect("and lists one dataset of one frame, got " + sets, sets.size() == 1 && sets.getFirst().frames() == 1);
        List<File> files = sets.isEmpty() ? List.of() : FitsLibrary.files(lib, sets.getFirst().key());
        expect("whose file is the library's copy, not Studio's, got " + files,
                files.size() == 1 && files.getFirst().isFile() && files.getFirst().toPath().startsWith(lib.root())
                        && Files.size(files.getFirst().toPath()) == Files.size(FIXTURE));
    }

    /** A stand-in core: answers ingest.path, refuses "refuse" and "unstorable", never answers "hang", ends on "exit". */
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
                if (method.equals("refuse") || method.equals("unstorable")) {
                    boolean last = method.equals("unstorable");
                    out.println(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", new JSONObject()
                            .put("code", last ? -32001 : -32603).put("message", last ? "a.fits has 1000 bytes; its headers need 2880" : "OSError: disk full")));
                    continue;
                }
                out.println(new JSONObject().put("jsonrpc", "2.0").put("method", "ingest.progress").put("params", new JSONObject().put("done", 1)));
                out.println(new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", new JSONObject().put("status", "added").put("file_id", 1).put("relpath", "store/a.fits")));
            }
        }
    }

}
