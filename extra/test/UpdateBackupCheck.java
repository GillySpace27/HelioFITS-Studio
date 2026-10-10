package org.helioviewer.jhv.app.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.helioviewer.jhv.io.Directories;

/**
 * Gilly's guardrail for updates: a user's session is never deleted, corrupted or lost.
 *
 * <p>Runs the step that comes before any download, UpdateInstaller.protectSession, against a throwaway
 * home, and pins that it COPIES the sessions, autosaves, recovery copies and settings into
 * ~/HFStudio/Backups/before-&lt;version&gt;-&lt;time&gt;, leaves every original byte for byte where it was,
 * never writes over an earlier backup, and fails loudly (so the update stops) when it cannot copy.
 * Then the download itself, from a file: URL: verified by length and SHA-256, renamed from its
 * ".part" only when whole, never written over a file already there, and leaving nothing behind
 * when it fails or is cancelled.
 *
 * <p>user.home is a temp folder before anything reads it, as SessionStateArgCheck does: a check once
 * overwrote Gilly's settings file.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.update.UpdateBackupCheck
 */
public final class UpdateBackupCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    /** Every regular file under {@code root}, by path, with its bytes as text. */
    private static Map<Path, String> snapshot(Path root) throws IOException {
        Map<Path, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                out.put(p, HexFormat.of().formatHex(Files.readAllBytes(p)));
        }
        return out;
    }

    /** Every file of {@code before} is still there with the same bytes. */
    private static boolean untouched(Map<Path, String> before) throws IOException {
        for (Map.Entry<Path, String> e : before.entrySet())
            if (!Files.isRegularFile(e.getKey()) || !e.getValue().equals(HexFormat.of().formatHex(Files.readAllBytes(e.getKey()))))
                return false;
        return true;
    }

    private static boolean same(Path a, Path b) throws IOException {
        return Files.isRegularFile(a) && Files.isRegularFile(b) && Files.mismatch(a, b) == -1;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-update-backup");
        System.setProperty("user.home", home.toString());

        Path states = Directories.STATES.getFile().toPath();
        Path settings = Directories.SETTINGS.getFile().toPath();
        write(states.resolve("session-main.jhv"), "{\"org.helioviewer.jhv.state\":{\"imageLayers\":[{\"name\":\"AIA 171\"}]}}");
        write(states.resolve("window-20261010-120000-000.jhv"), "{\"second window\":true}");
        write(states.resolve("windows.json"), "[]");
        write(states.resolve("Recovery/session-main (1a2b) recovery 3.jhv"), "{\"ten minutes ago\":true}");
        write(states.resolve("session-main.jhv.bak"), "{\"one step back\":true}");
        write(states.resolve("Project.data.zip"), "a large archive that is not a session");
        write(states.resolve("session-main.jhv.123.tmp"), "half a write");
        write(settings.resolve("user.properties"), "startup.sampHub=true\n");
        write(settings.resolve("user.properties.2026-10-09"), "startup.sampHub=false\n");
        Map<Path, String> before = snapshot(home);

        // ---- the backup ----------------------------------------------------------------------------
        LocalDateTime at = LocalDateTime.of(2026, 10, 10, 12, 0, 0);
        SessionBackup.Result r = UpdateInstaller.protectSession("0.8.6", at);
        Path folder = Directories.BACKUPS.getFile().toPath().resolve("before-0.8.6-20261010-120000");
        expect("the backup is ~/HFStudio/Backups/before-0.8.6-20261010-120000, got " + r.folder(), folder.equals(r.folder()));
        expect("the autosave is copied, byte for byte", same(states.resolve("session-main.jhv"), folder.resolve("States/session-main.jhv")));
        expect("so is the other window's session", Files.isRegularFile(folder.resolve("States/window-20261010-120000-000.jhv")));
        expect("and the window registry", Files.isRegularFile(folder.resolve("States/windows.json")));
        expect("and the recovery copies", Files.isRegularFile(folder.resolve("States/Recovery/session-main (1a2b) recovery 3.jhv")));
        expect("and the session's .bak", Files.isRegularFile(folder.resolve("States/session-main.jhv.bak")));
        expect("and the settings file", same(settings.resolve("user.properties"), folder.resolve("Settings/user.properties")));
        expect("and its daily copies", Files.isRegularFile(folder.resolve("Settings/user.properties.2026-10-09")));
        expect("not a session's data archive", !Files.exists(folder.resolve("States/Project.data.zip")));
        expect("not a write in progress", !Files.exists(folder.resolve("States/session-main.jhv.123.tmp")));
        expect("7 files counted, got " + r.files(), r.files() == 7);
        expect("every original is still there, byte for byte", untouched(before));

        Map<Path, String> withBackup = snapshot(home);
        boolean refused = false;
        try {
            UpdateInstaller.protectSession("0.8.6", at);
        } catch (IOException e) {
            refused = true;
        }
        expect("a second backup into the same folder is refused", refused);
        expect("and the first backup and the originals are untouched", untouched(withBackup));

        Path blocked = home.resolve("not-a-folder");
        write(blocked, "a file where the backup folder would go");
        Map<String, SessionBackup.Source> sources = new LinkedHashMap<>();
        sources.put("States", new SessionBackup.Source(states, UpdateInstaller::isSessionFile));
        refused = false;
        try {
            SessionBackup.copy(blocked, "before-0.8.6-x", sources);
        } catch (IOException e) {
            refused = true;
        }
        expect("a backup that cannot be written throws, so the update stops", refused);
        expect("and nothing was lost", untouched(withBackup));

        Path project = home.resolve("Documents/My Project.jhv");
        write(project, "{\"a named project outside States\":true}");
        sources.put(project.getFileName().toString(), new SessionBackup.Source(project, p -> true));
        SessionBackup.Result named = SessionBackup.copy(Directories.BACKUPS.getFile().toPath(), "named", sources);
        expect("a named project kept outside States is copied under its own name",
                same(project, named.folder().resolve("My Project.jhv")));
        expect("a backup with a path for a name is refused", throwsIo(() -> SessionBackup.copy(home, "../escape", sources)));

        // ---- the download --------------------------------------------------------------------------
        byte[] payload = new byte[300_000];
        new Random(1).nextBytes(payload);
        Path server = home.resolve("server/HFStudio-9.9.9.dmg");
        Files.createDirectories(server.getParent());
        Files.write(server, payload);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        Path downloads = Files.createDirectories(home.resolve("Downloads"));
        ReleaseFeed.Asset good = new ReleaseFeed.Asset("HFStudio-9.9.9.dmg", server.toUri().toString(), payload.length, sha);

        Path got = UpdateInstaller.fetch(good, downloads, new AtomicBoolean(), n -> {});
        expect("the download lands under the asset's name", got.equals(downloads.resolve("HFStudio-9.9.9.dmg")));
        expect("whole", same(server, got));
        expect("and leaves no .part behind", noPart(downloads));

        Path again = UpdateInstaller.fetch(good, downloads, new AtomicBoolean(), n -> {
            throw new IllegalStateException("should not download again");
        });
        expect("a verified file already there is reused, not downloaded again", again.equals(got));

        Map<Path, String> beforeBad = snapshot(home);
        ReleaseFeed.Asset badSha = new ReleaseFeed.Asset("HFStudio-9.9.9.dmg", server.toUri().toString(), payload.length, "00".repeat(32));
        expect("a SHA-256 that does not match fails", throwsIo(() -> UpdateInstaller.fetch(badSha, downloads, new AtomicBoolean(), n -> {})));
        expect("the file already there is untouched, nothing new is kept", untouched(beforeBad) && snapshot(home).equals(beforeBad));
        ReleaseFeed.Asset badSize = new ReleaseFeed.Asset("HFStudio-9.9.8.dmg", server.toUri().toString(), payload.length - 1, null);
        expect("a length that does not match fails", throwsIo(() -> UpdateInstaller.fetch(badSize, downloads, new AtomicBoolean(), n -> {})));
        expect("and keeps nothing", snapshot(home).equals(beforeBad));

        Path stranger = downloads.resolve("HFStudio-9.9.7.dmg");
        write(stranger, "somebody else's file with this name");
        ReleaseFeed.Asset clash = new ReleaseFeed.Asset("HFStudio-9.9.7.dmg", server.toUri().toString(), payload.length, sha);
        Path beside = UpdateInstaller.fetch(clash, downloads, new AtomicBoolean(), n -> {});
        expect("a different file already under the name is never overwritten",
                Files.readString(stranger).equals("somebody else's file with this name"));
        expect("the download goes beside it, got " + beside.getFileName(), beside.equals(downloads.resolve("HFStudio-9.9.7 (2).dmg")));
        expect("named for a .tar.gz too", UpdateInstaller.freeName(downloads, "HFStudio-9.9.9.dmg").getFileName().toString().equals("HFStudio-9.9.9 (2).dmg")
                && UpdateInstaller.freeName(downloads, "a.tar.gz").getFileName().toString().equals("a.tar.gz"));

        Map<Path, String> beforeCancel = snapshot(home);
        boolean cancelled = false;
        try {
            UpdateInstaller.fetch(new ReleaseFeed.Asset("HFStudio-9.9.6.dmg", server.toUri().toString(), payload.length, sha),
                    downloads, new AtomicBoolean(true), n -> {});
        } catch (CancellationException e) {
            cancelled = true;
        }
        expect("Cancel stops the download", cancelled);
        expect("and keeps nothing of it, touching nothing else", snapshot(home).equals(beforeCancel));
        expect("the session survived all of it", untouched(before));

        expect("sessions, registries and .bak are session files", UpdateInstaller.isSessionFile(Path.of("a.jhv"))
                && UpdateInstaller.isSessionFile(Path.of("windows.json")) && UpdateInstaller.isSessionFile(Path.of("a.jhv.bak")));
        expect("a data archive is not", !UpdateInstaller.isSessionFile(Path.of("a.data.zip")));

        System.out.println(failures == 0 ? "UpdateBackupCheck: all ok" : "UpdateBackupCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private interface Io {
        void run() throws Exception;
    }

    private static boolean throwsIo(Io body) {
        try {
            body.run();
            return false;
        } catch (IOException e) {
            return true;
        } catch (Exception e) {
            System.out.println("  (threw " + e + ")");
            return false;
        }
    }

    private static boolean noPart(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.noneMatch(p -> p.getFileName().toString().endsWith(".part"));
        }
    }
}
