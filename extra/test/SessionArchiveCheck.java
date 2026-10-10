package org.helioviewer.jhv.app.state;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A session exported with its data opens on a machine that never had the local files.
 *
 * <p>Pins the round trip the PUNCH team asked for on 2026-10-07: export a session whose layers read
 * local files, take those files away (another computer), load, and every missing file comes back
 * from the archive byte for byte, while archive layers are left to download as before. Also pins
 * the refusals: a damaged archive, a manifest naming a path outside its folder, and that nothing
 * half-written is ever left under a real name.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.state.SessionArchiveCheck
 */
public final class SessionArchiveCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-session-archive");
        System.setProperty("user.home", home.toString()); // nothing here may reach the real ~/HFStudio

        Path mine = Files.createDirectories(home.resolve("mine/proprietary"));
        Path a = Files.write(mine.resolve("frame one.fits"), bytes("SIMPLE  =  T  frame one", 5000));
        Path b = Files.write(mine.resolve("frame2.fits"), bytes("SIMPLE  =  T  frame two", 7000));
        Path other = Files.createDirectories(home.resolve("mine/elsewhere"));
        Path c = Files.write(other.resolve("frame2.fits"), bytes("same name, other folder", 3000)); // name collision
        String remote = "https://umbra.nascom.nasa.gov/punch/x.fits";
        String gone = home.resolve("mine/never-existed.fits").toUri().toString();

        JSONObject wrapped = session(
                layer(a.toUri().toString(), b.toUri().toString(), b.toUri().toString()), // b twice: packed once
                layer(remote, c.toUri().toString()),
                layer(gone));
        JSONObject state = wrapped.getJSONObject("org.helioviewer.jhv.state");

        Map<URI, Path> local = SessionArchive.localFiles(state);
        expect("localFiles: the three local files, each once, got " + local.size(), local.size() == 3);
        expect("localFiles: a remote URI is not packed", !local.containsKey(URI.create(remote)));
        expect("localFiles: a file that does not exist is not packed", !local.containsKey(URI.create(gone)));

        // Export.
        Path outDir = Files.createDirectories(home.resolve("outbox"));
        Path jhv = outDir.resolve("talk.jhv");
        Files.writeString(jhv, "previous"); // an export replaces it whole
        SessionArchive.Export ex = SessionArchive.export(wrapped, jhv);
        Path zip = outDir.resolve("talk.data.zip");
        expect("export: archive written beside the session as talk.data.zip", zip.equals(ex.archive()) && Files.isRegularFile(zip));
        expect("export: 3 files, 15000 bytes, got " + ex.files() + ", " + ex.bytes(), ex.files() == 3 && ex.bytes() == 15000);
        try (Stream<Path> s = Files.list(outDir)) {
            expect("export: no .tmp left behind", s.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
        JSONObject written = new JSONObject(Files.readString(jhv)).getJSONObject("org.helioviewer.jhv.state");
        expect("export: the .jhv names its archive", "talk.data.zip".equals(written.getJSONObject(SessionArchive.SESSION_KEY).optString("file")));
        expect("export: the caller's snapshot is not modified", !state.has(SessionArchive.SESSION_KEY));
        try (ZipFile z = new ZipFile(zip.toFile())) {
            JSONObject manifest = new JSONObject(new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            expect("manifest: format and version", SessionArchive.FORMAT.equals(manifest.getString("format")) && manifest.getInt("version") == 1);
            JSONArray files = manifest.getJSONArray("files");
            expect("manifest: three entries with a 64-hex SHA-256 each",
                    files.length() == 3 && files.getJSONObject(0).getString("sha256").matches("[0-9a-f]{64}"));
            expect("manifest: the two frame2.fits land in different folders",
                    !files.getJSONObject(1).getString("path").equals(files.getJSONObject(2).getString("path")));
            expect("manifest: a space in a name is made safe, got " + files.getJSONObject(0).getString("path"),
                    "data/0001/frame_one.fits".equals(files.getJSONObject(0).getString("path")));
            expect("archive: carries the session", z.getEntry("session.jhv") != null);
        }
        expect("sessionIn: the archive alone opens the session",
                SessionArchive.sessionIn(zip).getJSONArray("imageLayers").length() == 3);

        // Same machine: the originals are here, so nothing is rewritten and nothing unpacked.
        Path unpack = home.resolve("HFStudio/SessionData");
        JSONObject here = new JSONObject(Files.readString(jhv)).getJSONObject("org.helioviewer.jhv.state");
        SessionArchive.Reattach r0 = SessionArchive.reattach(here, jhv, null, unpack);
        expect("same machine: only the never-existed file is missing, got " + r0.missing(), r0.missing() == 1 && r0.restored() == 0);
        expect("same machine: originals still referenced", uris(here, 0).getString(0).equals(a.toUri().toString()));
        expect("same machine: the archive is not unpacked when it holds none of the missing files", !Files.exists(unpack));

        // Another computer: the local folder is gone.
        Files.move(home.resolve("mine"), home.resolve("mine-away"));
        JSONObject there = new JSONObject(Files.readString(jhv)).getJSONObject("org.helioviewer.jhv.state");
        SessionArchive.Reattach r1 = SessionArchive.reattach(there, jhv, null, unpack);
        expect("other machine: 4 missing, 3 restored from the archive, got " + r1.missing() + "/" + r1.restored(),
                r1.missing() == 4 && r1.restored() == 3);
        Path ra = Path.of(URI.create(uris(there, 0).getString(0)));
        Path rc = Path.of(URI.create(uris(there, 1).getString(1)));
        expect("other machine: a restored file has the original bytes",
                java.util.Arrays.equals(Files.readAllBytes(ra), bytes("SIMPLE  =  T  frame one", 5000)));
        expect("other machine: the colliding name restores the right file",
                java.util.Arrays.equals(Files.readAllBytes(rc), bytes("same name, other folder", 3000)));
        expect("other machine: b listed twice, both rewritten to one copy", uris(there, 0).getString(1).equals(uris(there, 0).getString(2)));
        expect("other machine: the remote URI is left to download", remote.equals(uris(there, 1).getString(0)));
        expect("other machine: unpacked under the unpack root", ra.startsWith(unpack));
        expect("other machine: a file the archive never had stays as it was", gone.equals(uris(there, 2).getString(0)));

        // A second load reuses the unpacked copy.
        Files.writeString(ra, "touched"); // if it unpacked again this would be overwritten
        JSONObject again = new JSONObject(Files.readString(jhv)).getJSONObject("org.helioviewer.jhv.state");
        SessionArchive.reattach(again, jhv, null, unpack);
        expect("second load: reuses the unpacked folder", "touched".equals(Files.readString(ra)));

        // A session renamed after export still finds its archive through the key it carries.
        Path renamed = outDir.resolve("renamed.jhv");
        Files.copy(jhv, renamed);
        JSONObject viaKey = new JSONObject(Files.readString(renamed)).getJSONObject("org.helioviewer.jhv.state");
        expect("renamed session: archive found through its key", SessionArchive.reattach(viaKey, renamed, null, unpack).restored() == 3);

        // No archive at all: say how many are missing, rewrite nothing.
        Path lonely = Files.createDirectories(home.resolve("lonely")).resolve("lonely.jhv");
        Files.writeString(lonely, wrapped.toString());
        JSONObject alone = new JSONObject(Files.readString(lonely)).getJSONObject("org.helioviewer.jhv.state");
        SessionArchive.Reattach r2 = SessionArchive.reattach(alone, lonely, null, unpack);
        expect("no archive: missing counted, none restored", r2.missing() == 4 && r2.restored() == 0 && r2.archive() == null);

        // No archive, but the files were sent loose beside the session (Sarah Gibson's case, 2026-10).
        Path loose = Files.createDirectories(home.resolve("loose"));
        Path looseJhv = loose.resolve("sent.jhv");
        Files.writeString(looseJhv, wrapped.toString());
        Files.write(loose.resolve("frame one.fits"), bytes("loose copy", 100));
        JSONObject sent = new JSONObject(Files.readString(looseJhv)).getJSONObject("org.helioviewer.jhv.state");
        SessionArchive.Reattach r3 = SessionArchive.reattach(sent, looseJhv, null, unpack);
        expect("beside the session: 4 missing, the one sent loose restored, got " + r3.missing() + "/" + r3.restored(),
                r3.missing() == 4 && r3.restored() == 1 && r3.archive() == null);
        expect("beside the session: the layer now reads the loose copy",
                loose.resolve("frame one.fits").toUri().toString().equals(uris(sent, 0).getString(0)));
        expect("beside the session: a file not sent stays as it was", gone.equals(uris(sent, 2).getString(0)));

        // A damaged archive: same manifest, one byte changed in a data entry.
        Path damaged = outDir.resolve("damaged.data.zip");
        rewrite(zip, damaged, "data/0001/frame_one.fits", data -> { data[10] ^= 1; return data; }, null);
        Path freshRoot = home.resolve("fresh");
        expect("damaged archive: refused", throwsIO(() -> SessionArchive.unpack(damaged, freshRoot)));
        try (Stream<Path> s = Files.exists(freshRoot) ? Files.list(freshRoot) : Stream.empty()) {
            expect("damaged archive: nothing kept under the unpack root", s.findAny().isEmpty());
        }

        // A manifest naming a path outside its folder.
        Path evil = outDir.resolve("evil.data.zip");
        rewrite(zip, evil, null, null, m -> {
            m.getJSONArray("files").getJSONObject(0).put("path", "data/0001/../../../escape.fits");
            return m;
        });
        expect("traversal: a manifest path outside data/NNNN/ is refused", throwsIO(() -> SessionArchive.unpack(evil, freshRoot)));
        expect("traversal: nothing written outside", !Files.exists(home.resolve("escape.fits")));

        // A zip that is not ours.
        Path foreign = outDir.resolve("foreign.data.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(foreign))) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        expect("foreign zip: refused", throwsIO(() -> SessionArchive.sessionIn(foreign)));

        // Nothing local: only the .jhv.
        Path remoteOnly = outDir.resolve("remote.jhv");
        SessionArchive.Export ex2 = SessionArchive.export(session(layer(remote)), remoteOnly);
        expect("remote-only: no archive written", ex2.archive() == null && !Files.exists(outDir.resolve("remote.data.zip")));
        expect("remote-only: the .jhv is written", Files.isRegularFile(remoteOnly));

        expect("archiveName: talk.jhv -> talk.data.zip", "talk.data.zip".equals(SessionArchive.archiveName("talk.jhv")));
        expect("isArchiveName", SessionArchive.isArchiveName("TALK.DATA.ZIP") && !SessionArchive.isArchiveName("talk.jhv"));

        System.out.println(failures == 0 ? "SessionArchiveCheck: all passed" : "SessionArchiveCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static JSONObject session(JSONObject... layers) {
        JSONArray ja = new JSONArray();
        for (JSONObject l : layers)
            ja.put(l);
        return new JSONObject().put("org.helioviewer.jhv.state", new JSONObject().put("imageLayers", ja).put("layers", new JSONArray()));
    }

    private static JSONObject layer(String... uris) {
        return new JSONObject().put("className", "org.helioviewer.jhv.layers.ImageLayer").put("name", "L")
                .put("data", new JSONObject().put("uris", new JSONArray(uris)));
    }

    private static JSONArray uris(JSONObject state, int layer) {
        return state.getJSONArray("imageLayers").getJSONObject(layer).getJSONObject("data").getJSONArray("uris");
    }

    private static byte[] bytes(String seed, int n) {
        byte[] out = new byte[n];
        byte[] s = seed.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < n; i++)
            out[i] = (byte) (s[i % s.length] + i / s.length);
        return out;
    }

    private interface IO {
        void run() throws IOException;
    }

    private static boolean throwsIO(IO io) {
        try {
            io.run();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private interface Edit<T> {
        T apply(T t);
    }

    // Copy an archive, changing one data entry's bytes or the manifest.
    private static void rewrite(Path from, Path to, String entryName, Edit<byte[]> data, Edit<JSONObject> manifest) throws IOException {
        try (ZipFile in = new ZipFile(from.toFile()); OutputStream os = Files.newOutputStream(to);
             ZipOutputStream out = new ZipOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = in.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] b = in.getInputStream(e).readAllBytes();
                if (e.getName().equals(entryName) && data != null)
                    b = data.apply(b);
                if (e.getName().equals("manifest.json") && manifest != null)
                    b = manifest.apply(new JSONObject(new String(b, StandardCharsets.UTF_8))).toString().getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(b);
                out.closeEntry();
            }
        }
    }
}
