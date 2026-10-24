package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;

import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Use FITS for a Helioviewer layer: the headers sent from each frame's metadata XML, and reading
 * the core's {@code layer.upgrade} answer (files to open, jobs to wait for, the words shown).
 *
 * <p>The XML below is shaped like Helioviewer's frame metadata (a {@code meta} element holding
 * the FITS keywords); it is a fixture, not a copy of a real frame's. With {@code HELIAD_CORE} set to
 * a real core, a scratch library is given {@code sample.171.fits} and the core is asked about a
 * picture of it and a picture of a frame it lacks. Never against the real library.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.HeliadFitsCheck
 */
public final class HeliadFitsCheck {

    private static final Path FIXTURE = Path.of("extra/test/data/sample.171.fits");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String picture(String date) {
        return "<meta><fits><SIMPLE>1</SIMPLE><TELESCOP>SDO/AIA</TELESCOP><INSTRUME>AIA_3</INSTRUME>"
                + "<WAVELNTH>171</WAVELNTH><WAVEUNIT>angstrom</WAVEUNIT><LVL_NUM>1.5</LVL_NUM><DETECTOR> </DETECTOR>"
                + "<DATE-OBS>" + date + "</DATE-OBS></fits><helioviewer><HV_ZERO>0</HV_ZERO></helioviewer></meta>";
    }

    public static void main(String[] args) throws Exception {
        headers();
        reading();
        realCore();
        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
    }

    private static void headers() throws Exception {
        DocumentBuilder builder = HeliadFits.builder();
        JSONObject h = HeliadFits.header(builder, picture("2012-08-31T17:34:12.000"));
        expect("a frame's keywords are read, got " + h, "SDO/AIA".equals(h.optString("TELESCOP"))
                && "AIA_3".equals(h.optString("INSTRUME")) && "171".equals(h.optString("WAVELNTH"))
                && "2012-08-31T17:34:12.000".equals(h.optString("DATE-OBS")) && "1.5".equals(h.optString("LVL_NUM")));
        expect("keywords Heliad does not read are left out, got " + h.keySet(), !h.has("SIMPLE") && !h.has("HV_ZERO"));
        expect("a blank keyword is left out", !h.has("DETECTOR"));
        expect("no metadata gives an empty header", HeliadFits.header(builder, View.EMPTY_METAXML).isEmpty()
                && HeliadFits.header(builder, null).isEmpty());
        expect("unreadable metadata gives an empty header", HeliadFits.header(builder, "<meta><fits>").isEmpty());
        expect("metadata without a meta element gives an empty header", HeliadFits.header(builder, "<x><TELESCOP>A</TELESCOP></x>").isEmpty());
        expect("a doctype is refused, not read", HeliadFits.header(builder,
                "<!DOCTYPE meta [<!ENTITY e \"SDO\">]><meta><fits><TELESCOP>&e;</TELESCOP></fits></meta>").isEmpty());
    }

    private static void reading() throws Exception {
        Path tmp = Files.createTempDirectory("hfs-heliad-fits");
        Path a = Files.writeString(tmp.resolve("a.fits"), "x");
        JSONObject plan = new JSONObject()
                .put("counts", new JSONObject().put("in_library", 2).put("missing", 1).put("rejected", 0).put("unreadable", 1))
                .put("frames", new JSONArray()
                        .put(new JSONObject().put("status", "in_library").put("path", a.toString()))
                        .put(new JSONObject().put("status", "in_library").put("path", a.toString()))
                        .put(new JSONObject().put("status", "in_library").put("path", tmp.resolve("gone.fits").toString()))
                        .put(new JSONObject().put("status", "missing"))
                        .put(new JSONObject().put("status", "unreadable")))
                .put("groups", new JSONArray()
                        .put(new JSONObject().put("label", "SDO AIA 171 Å").put("counts", new JSONObject().put("missing", 1))
                                .put("fetch", new JSONObject().put("source", "fido").put("inferred", true)
                                        .put("query", new JSONObject().put("start", "2012-08-31T18:03:12.000")
                                                .put("end", "2012-08-31T18:05:12.000").put("sample_s", 1800.0)))
                                .put("job", new JSONObject().put("job_id", 7)))
                        .put(new JSONObject().put("label", "SOHO LASCO C2").put("fetch", JSONObject.NULL)));
        List<URI> files = HeliadFits.files(plan);
        expect("each kept file once, in frame order, and only files still on disk, got " + files,
                files.equals(List.of(a.toFile().toURI())));
        expect("the jobs a plan queued, got " + HeliadFits.jobs(plan), HeliadFits.jobs(plan).equals(List.of(7)));
        String words = HeliadFits.summary(plan);
        expect("the summary counts the frames, got " + words, words.startsWith("5 frames: 2 already in the Heliad library as FITS, 1 missing, 1 without a usable header."));
        expect("and says what would be fetched, got " + words, words.contains("SDO AIA 171 Å: fetch 1 frame through fido, "
                + "2012-08-31T18:03:12.000 to 2012-08-31T18:05:12.000 UTC, one per 30 min.") && words.contains("inferred"));
        expect("a group with nothing to fetch says nothing", !words.contains("LASCO"));
    }

    private static void realCore() throws Exception {
        String heliadCore = System.getenv("HELIAD_CORE");
        if (heliadCore == null || heliadCore.isEmpty()) {
            System.out.println("  skip a real Heliad core: HELIAD_CORE is not set");
            return;
        }
        Path tmp = Files.createTempDirectory("hfs-heliad-fits-real");
        Path root = tmp.resolve("library");
        List<String> command = HeliadIngest.command(heliadCore, null, tmp, root, Files::isExecutable);
        expect("HELIAD_CORE gives a command, got " + command, command != null);
        if (command == null)
            return;
        Duration wait = Duration.ofMinutes(2);
        HeliadIngest.Core core = new HeliadIngest.Core(command, Map.of("HELIAD_HOME", tmp.resolve("appsupport").toString()), root);
        JSONObject added = core.call("ingest.path", new JSONObject().put("path", FIXTURE.toAbsolutePath().toString()), wait);
        expect("the fixture enters the scratch library, got " + added, "added".equals(added.optString("status")));
        DocumentBuilder builder = HeliadFits.builder();
        JSONArray headers = new JSONArray()
                .put(HeliadFits.header(builder, picture("2012-08-31T17:34:12.000")))  // 0.66 s after the fixture
                .put(HeliadFits.header(builder, picture("2012-08-31T18:04:12.000")));
        JSONObject plan = core.call("layer.upgrade", new JSONObject().put("headers", headers).put("dry_run", true), wait);
        expect("a dry run, got " + plan.opt("dry_run"), plan.optBoolean("dry_run"));
        expect("the level 1.5 picture finds the level 1 file, the other is missing, got " + plan.opt("counts"),
                HeliadFits.count(plan, "in_library") == 1 && HeliadFits.count(plan, "missing") == 1);
        List<URI> files = HeliadFits.files(plan);
        expect("the file to open is the library's copy, got " + files, files.size() == 1
                && new File(files.getFirst()).toPath().startsWith(root.toRealPath())
                && Files.size(new File(files.getFirst()).toPath()) == Files.size(FIXTURE));
        expect("a dry run queues nothing", HeliadFits.jobs(plan).isEmpty());
        String words = HeliadFits.summary(plan);
        expect("the summary names the fetch, got " + words, words.contains(": fetch 1 frame through fido, "));
        core.close();
        expect("the core exits cleanly", core.waitForExit() == 0);
    }

}
