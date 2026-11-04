package org.helioviewer.jhv.study;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.json.JSONObject;

/**
 * Movie versions (parameter study, WP6): the pinned movies block round-trips and is editable, a
 * time sweep or a per-frame format is refused, the tile command is built, and one real headless run
 * of two variants yields two MP4s listed in the manifest.
 *
 * <p>The real run starts HFStudio.jar from the working directory with a throwaway home and needs a
 * display; it prints a lowercase skip line unless HFS_STUDY_E2E=1 is set and the jar exists.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.study.MovieRunnerCheck
 */
public final class MovieRunnerCheck {

    private static final String IMAGE = "extra/test/data/syn_AIA_171_2026-01-12T00-00-00_f_V3.fits";

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        try {
            System.setProperty("user.home", Files.createTempDirectory("hfs-movie-check").toString());
            pure();
            endToEnd();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            expect("no exception: " + t, false);
        }
        System.out.println(failures == 0 ? "MovieRunnerCheck: ok" : "MovieRunnerCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static JSONObject job(String movies, String sweep) {
        return new JSONObject("{\"hfstudy\":1,\"scene\":\"\",\"variants\":[{\"name\":\"a\"},{\"name\":\"b\"}],"
                + "\"output\":{\"name\":\"{index}_{variant}\",\"movies\":" + movies + "}" + (sweep == null ? "" : ",\"sweep\":" + sweep) + "}");
    }

    private static void pure() throws Exception {
        String m = "{\"format\":\"H264\",\"longSide\":720,\"first\":0,\"last\":9,\"speed\":12,\"speedUnit\":\"FRAMES_PER_SECOND\",\"advanceMode\":\"Loop\",\"keep\":1}";
        Study s = Study.fromJson(job(m, null));
        MovieBlock b = MovieBlock.of(s);
        expect("the movies block reads back", b != null && b.longSide() == 720 && b.last() == 9 && b.speed() == 12 && !b.tile());
        expect("the movies block round-trips with unknown keys",
                Study.fromJson(s.toJson()).output().movies().similar(new JSONObject(m)));

        b.with("longSide", 1440).with("tile", true); // with() copies: the study is unchanged until saved
        expect("a block edited and not saved leaves the job as it was", MovieBlock.of(s).longSide() == 720);
        Path file = Files.createTempFile("hfs-movie", ".hfstudy.json");
        b.with("longSide", 1440).saveTo(s);
        StudyIO.write(s, file);
        expect("Save to study changes the job file", MovieBlock.of(StudyIO.read(file)).longSide() == 1440);

        expect("a study with movies and no sweep is accepted", MovieRunner.refusal(s) == null);
        expect("a time sweep with movies is refused with the message",
                "A time sweep and movie output cannot be combined: pick one.".equals(
                        MovieRunner.refusal(Study.fromJson(job(m, "{\"kind\":\"time\",\"times\":[\"2026-01-12T00:00:00\"]}")))));
        expect("a field sweep with movies is accepted", MovieRunner.refusal(
                Study.fromJson(job(m, "{\"kind\":\"field\",\"field\":\"warpLambda\",\"values\":[0,1]}"))) == null);
        expect("a per-frame format is refused", MovieRunner.refusal(Study.fromJson(job(m.replace("H264", "PNG"), null))) != null);

        expect("a movies-only study makes no stills", !StudyRunner.stillsOn(s));
        expect("a study without movies makes stills", StudyRunner.stillsOn(Study.fromJson(new JSONObject("{\"hfstudy\":1,\"variants\":[]}"))));

        List<String> a = TileMovies.args(List.of(Path.of("1.mp4"), Path.of("2.mp4"), Path.of("3.mp4"), Path.of("4.mp4")), Path.of("t.mp4"), 540);
        String joined = String.join(" ", a);
        expect("tile of four is a 2x2 xstack", joined.contains("xstack=inputs=4") && joined.contains("layout=0_0|540_0|0_540|540_540"));
        MovieBlock seeded = MovieBlock.fromCurrentSettings();
        expect("a new study's block is seeded from the Record settings", seeded.longSide() > 0 && seeded.format() != null);
    }

    private static void endToEnd() throws Exception {
        if (!"1".equals(System.getenv("HFS_STUDY_E2E")) || !Files.isReadable(Path.of("HFStudio.jar")) || !Files.isReadable(Path.of(IMAGE))) {
            System.out.println("  skip end-to-end run: needs HFS_STUDY_E2E=1, HFStudio.jar (ant jar) and a display");
            return;
        }
        Path home = Files.createTempDirectory("hfs-movie-home");
        Path out = Files.createTempDirectory("hfs-movie-out").resolve("run");
        Path jobFile = home.resolve("movies.hfstudy.json");
        Files.writeString(jobFile, new JSONObject("""
                {"hfstudy":1,"scene":"","variants":[
                  {"name":"ortho","projection":"Orthographic"},
                  {"name":"radial","projection":"Helioradial"}],
                 "output":{"name":"{index}_{variant}","movies":{"format":"H264","longSide":480,"first":0,"last":0,"speed":10,"speedUnit":"FRAMES_PER_SECOND","advanceMode":"Loop"}}}""").toString(2));
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "--enable-native-access=ALL-UNNAMED", "-jar", "HFStudio.jar",
                "-load", Path.of(IMAGE).toAbsolutePath().toString(), "-study", jobFile.toString(), "-out", out.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(home.resolve("app.out").toFile()).start();
        boolean done = p.waitFor(420, TimeUnit.SECONDS);
        if (!done)
            p.destroyForcibly();
        expect("headless movie run exits 0", done && p.exitValue() == 0);
        List<String> names;
        try (Stream<Path> s = Files.list(out)) {
            names = s.map(x -> x.getFileName().toString()).toList();
        }
        expect("two MP4s", names.stream().filter(n -> n.endsWith(".mp4")).count() == 2);
        expect("no stills in a movies-only study", names.stream().noneMatch(n -> n.endsWith(".png")));
        JSONObject man = new JSONObject(Files.readString(out.resolve("manifest.hfstudy.json")));
        int listed = 0;
        for (int i = 0; i < man.getJSONArray("outputs").length(); i++) {
            JSONObject o = man.getJSONArray("outputs").getJSONObject(i);
            if (o.getString("kind").equals("movie") && o.getString("name").endsWith(".mp4") && o.getLong("bytes") > 0
                    && StudyIO.sha256(out.resolve(o.getString("name"))).equals(o.getString("sha256")))
                listed++;
        }
        expect("both MP4s are in the manifest with matching hashes", listed == 2);
    }
}
