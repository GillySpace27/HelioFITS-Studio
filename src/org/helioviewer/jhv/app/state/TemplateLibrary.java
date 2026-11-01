package org.helioviewer.jhv.app.state;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where scene templates live (docs/templates.md): the built-ins in resources/templates/, read-only
 * and listed by resources/templates/templates.txt, then the user's in ~/HFStudio/Templates/. A user
 * template with a built-in's file name or display name shadows it in place, the ExportPreset rule.
 * Nothing here deletes a file: a retired template moves to Templates/attic/.
 */
public final class TemplateLibrary {

    /** The long side of a template thumbnail, in pixels. */
    public static final int THUMBNAIL_SIZE = 512;

    private static final String BUILT_IN_DIR = "/templates/";
    private static final String BUILT_IN_INDEX = BUILT_IN_DIR + "templates.txt";
    private static final String ATTIC = "attic";
    private static final String BUILT_IN_COPIES = "builtin"; // a built-in set as startup, as a file startup can open

    /**
     * One template. {@code session} is the whole .jhv; {@code file} is null for a built-in. {@code key}
     * is the file name without .jhv.
     */
    public record Entry(String key, TemplateTime.Meta meta, JSONObject session, boolean builtIn,
                        @Nullable File file, long modified) {

        /** The inside of the .jhv, where the layers and the template object are. */
        public JSONObject state() {
            return session.getJSONObject("org.helioviewer.jhv.state");
        }

        public String datasets() {
            return TemplateState.datasets(state());
        }

        /** The thumbnail, or null when there is none or it cannot be read. */
        @Nullable
        public BufferedImage thumbnail() {
            String name = meta.thumbnail();
            if (name.isBlank())
                return null;
            try {
                if (file != null) {
                    File png = new File(file.getParentFile(), name);
                    return png.isFile() ? ImageIO.read(png) : null;
                }
                try (InputStream in = TemplateLibrary.class.getResourceAsStream(BUILT_IN_DIR + name)) {
                    return in == null ? null : ImageIO.read(in);
                }
            } catch (IOException e) {
                Log.warn("Template thumbnail " + name, e);
                return null;
            }
        }

        /** The PNG beside a user template, or null for a built-in or a template with none. */
        @Nullable
        File thumbnailFile() {
            return file == null || meta.thumbnail().isBlank() ? null : new File(file.getParentFile(), meta.thumbnail());
        }
    }

    public static File userDir() {
        return Directories.TEMPLATES.getFile();
    }

    /** Every template: the built-ins first (each replaced by a user one that shadows it), then the user's by name. */
    public static List<Entry> all() {
        List<Entry> builtIns = builtIns();
        List<Entry> users = users();
        List<Entry> out = new ArrayList<>(builtIns.size() + users.size());
        for (Entry b : builtIns) {
            Entry shadow = users.stream().filter(u -> shadows(u, b)).findFirst().orElse(null);
            out.add(shadow == null ? b : shadow);
        }
        for (Entry u : users)
            if (builtIns.stream().noneMatch(b -> shadows(u, b)))
                out.add(u);
        return out;
    }

    private static boolean shadows(Entry user, Entry builtIn) {
        return user.key.equals(builtIn.key) || user.meta.name().equals(builtIn.meta.name());
    }

    /** Whether a template by this name would shadow a built-in. */
    public static boolean isBuiltInName(String name) {
        String key = safeName(name);
        return builtIns().stream().anyMatch(b -> b.key.equals(key) || b.meta.name().equals(name.strip()));
    }

    private static List<Entry> builtIns() {
        List<Entry> out = new ArrayList<>();
        String index;
        try (InputStream in = TemplateLibrary.class.getResourceAsStream(BUILT_IN_INDEX)) {
            if (in == null)
                return out;
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Built-in template list", e);
            return out;
        }
        for (String line : index.split("\n")) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#"))
                continue;
            try (InputStream in = TemplateLibrary.class.getResourceAsStream(BUILT_IN_DIR + name)) {
                if (in == null) {
                    Log.warn("Built-in template listed but missing: " + name);
                    continue;
                }
                Entry e = entry(name, new String(in.readAllBytes(), StandardCharsets.UTF_8), true, null, 0);
                if (e != null)
                    out.add(e);
            } catch (IOException e) {
                Log.warn("Built-in template " + name, e);
            }
        }
        return out;
    }

    private static List<Entry> users() {
        List<Entry> out = new ArrayList<>();
        File[] files = userDir().listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jhv"));
        if (files == null)
            return out;
        for (File f : files) {
            try {
                Entry e = entry(f.getName(), Files.readString(f.toPath(), StandardCharsets.UTF_8), false, f, f.lastModified());
                if (e != null)
                    out.add(e);
            } catch (IOException e) {
                Log.warn("Template " + f, e);
            }
        }
        out.sort((a, b) -> a.meta.name().compareToIgnoreCase(b.meta.name()));
        return out;
    }

    // A file that is not a template, or whose template object does not parse, is left out (and logged).
    @Nullable
    private static Entry entry(String fileName, String json, boolean builtIn, @Nullable File file, long modified) {
        try {
            JSONObject session = new JSONObject(json);
            TemplateTime.Meta meta = TemplateState.meta(session.getJSONObject("org.helioviewer.jhv.state"));
            if (meta == null) {
                Log.warn("Not a template (no template object): " + fileName);
                return null;
            }
            String key = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jhv") ? fileName.substring(0, fileName.length() - 4) : fileName;
            return new Entry(key, meta, session, builtIn, file, modified);
        } catch (JSONException | IllegalArgumentException e) {
            Log.warn("Unreadable template " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    /** Whether this file is a template: a .jhv whose state carries a template object. False when unreadable. */
    public static boolean isTemplate(File f) {
        try {
            JSONObject state = new JSONObject(Files.readString(f.toPath(), StandardCharsets.UTF_8)).optJSONObject("org.helioviewer.jhv.state");
            return state != null && state.has(TemplateState.KEY);
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    /**
     * A name as a file name: letters, digits, spaces, dots, dashes and underscores kept, anything
     * else an underscore, no leading dot, at most 80 characters, "template" when nothing is left. Pure.
     */
    public static String safeName(String name) {
        String s = name.strip().replaceAll("[^A-Za-z0-9 ._-]", "_").replaceAll("^[.\\s]+", "").strip();
        if (s.length() > 80)
            s = s.substring(0, 80).strip();
        return s.isEmpty() ? "template" : s;
    }

    public static File jhvFile(String name) {
        return new File(userDir(), safeName(name) + ".jhv");
    }

    /**
     * Writes {@code session} (a whole .jhv that already carries its template object) as the user
     * template named {@code meta.name()}, over one of the same name. Returns the file, or throws when
     * it could not be written.
     */
    public static File save(JSONObject session, TemplateTime.Meta meta) throws IOException {
        File dir = userDir();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        session.getJSONObject("org.helioviewer.jhv.state").put(TemplateState.KEY, meta.toJson());
        File f = jhvFile(meta.name());
        if (!State.writeNowConfirmed(session, dir.getPath(), f.getName()))
            throw new IOException("Could not write " + f);
        return f;
    }

    /** {@code image} scaled to {@link #THUMBNAIL_SIZE} on its long side (never up), written as a PNG. */
    public static void writeThumbnail(File png, BufferedImage image) throws IOException {
        double scale = Math.min(1, THUMBNAIL_SIZE / (double) Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale)), h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(out, "png", png))
            throw new IOException("No PNG writer");
    }

    /** A user template with a new description and time rule, written over itself. */
    public static void edit(Entry e, TemplateTime.Meta meta) throws IOException {
        requireUser(e);
        save(new JSONObject(e.session.toString()), meta);
    }

    /** A copy of {@code e} (built-in or not) as a user template called {@code name}, thumbnail and all. */
    public static File duplicate(Entry e, String name) throws IOException {
        File target = jhvFile(name);
        if (target.exists())
            throw new IOException("A template file " + target.getName() + " already exists");
        TemplateTime.Meta meta = e.meta.withName(name.strip()).withThumbnail(e.meta.thumbnail().isBlank() ? "" : safeName(name) + ".png");
        copyThumbnail(e, meta);
        return save(new JSONObject(e.session.toString()), meta);
    }

    /** A user template under a new name: its files move to the new name, nothing is left at the old one. */
    public static File rename(Entry e, String name) throws IOException {
        requireUser(e);
        File target = jhvFile(name);
        if (target.exists() && !target.equals(e.file))
            throw new IOException("A template file " + target.getName() + " already exists");
        TemplateTime.Meta meta = e.meta.withName(name.strip()).withThumbnail(e.meta.thumbnail().isBlank() ? "" : safeName(name) + ".png");
        File png = e.thumbnailFile();
        if (png != null && png.isFile() && !png.getName().equals(meta.thumbnail()))
            Files.move(png.toPath(), new File(userDir(), meta.thumbnail()).toPath());
        if (!target.equals(e.file))
            Files.move(e.file.toPath(), target.toPath()); // moved, then written over with the new name inside
        return save(new JSONObject(e.session.toString()), meta);
    }

    private static void copyThumbnail(Entry e, TemplateTime.Meta meta) throws IOException {
        if (meta.thumbnail().isBlank())
            return;
        File to = new File(userDir(), meta.thumbnail());
        File from = e.thumbnailFile();
        if (from != null && from.isFile()) {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else if (e.builtIn) {
            try (InputStream in = TemplateLibrary.class.getResourceAsStream(BUILT_IN_DIR + e.meta.thumbnail())) {
                if (in != null)
                    Files.copy(in, to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Moves a user template's .jhv and .png into Templates/attic/ (a time stamp added on a clash). Never deletes. */
    public static File retire(Entry e) throws IOException {
        requireUser(e);
        File attic = new File(userDir(), ATTIC);
        if (!attic.isDirectory() && !attic.mkdirs())
            throw new IOException("Cannot create " + attic);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File png = e.thumbnailFile();
        if (png != null && png.isFile())
            Files.move(png.toPath(), unique(attic, png.getName(), stamp).toPath());
        File to = unique(attic, e.file.getName(), stamp);
        Files.move(e.file.toPath(), to.toPath());
        return to;
    }

    private static File unique(File dir, String name, String stamp) {
        File f = new File(dir, name);
        if (!f.exists())
            return f;
        int dot = name.lastIndexOf('.');
        return new File(dir, dot < 0 ? name + " " + stamp : name.substring(0, dot) + " " + stamp + name.substring(dot));
    }

    /**
     * The template fit to hand to someone else: PortableScene's copy (the home folder written as
     * "~", the local files outside the cache listed) with the thumbnail inside it, as base64 PNG
     * under template.thumbnail_data, so one file carries everything.
     */
    public static PortableScene.Result portable(Entry e) throws IOException {
        PortableScene.Result r = PortableScene.of(new JSONObject(e.session.toString()), System.getProperty("user.home"),
                Directories.FILECACHE.getPath());
        BufferedImage thumb = e.thumbnail();
        if (thumb != null) {
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            ImageIO.write(thumb, "png", png);
            r.scene().getJSONObject("org.helioviewer.jhv.state").getJSONObject(TemplateState.KEY)
                    .put("thumbnail_data", Base64.getEncoder().encodeToString(png.toByteArray()));
        }
        return r;
    }

    /**
     * The file startup opens for this template (Set as startup): a user template's own file; for a
     * built-in, a copy in Templates/builtin/, refreshed each time, since startup opens a path.
     */
    public static File startupFile(Entry e) throws IOException {
        if (e.file != null)
            return e.file;
        File dir = new File(userDir(), BUILT_IN_COPIES);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        File f = new File(dir, e.key + ".jhv");
        if (!State.writeNowConfirmed(e.session, dir.getPath(), f.getName()))
            throw new IOException("Could not write " + f);
        return f;
    }

    private static void requireUser(Entry e) throws IOException {
        if (e.builtIn || e.file == null)
            throw new IOException("\"" + e.meta.name() + "\" is built in and cannot be changed; duplicate it first");
    }

    /** Whether {@code path} is inside the Templates folder. */
    static boolean inTemplates(Path path) {
        return path.toAbsolutePath().startsWith(Path.of(userDir().getPath()).toAbsolutePath());
    }

    private TemplateLibrary() {}
}
