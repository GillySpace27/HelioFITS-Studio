package org.helioviewer.jhv.study;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.json.JSONException;
import org.json.JSONObject;

/** Reads and writes *.hfstudy.json. */
public final class StudyIO {

    private StudyIO() {
    }

    /** @throws IOException with a message fit for a dialog when the file is unreadable or newer than this version */
    public static Study read(Path file) throws IOException {
        try {
            return Study.fromJson(new JSONObject(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public static void write(Study study, Path file) throws IOException {
        Files.writeString(file, study.toJson().toString(2) + "\n", StandardCharsets.UTF_8);
    }

    /** Hex sha256 of a file's bytes, for the scene reference and the manifest. */
    public static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE has SHA-256
        }
    }
}
