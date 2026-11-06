package org.helioviewer.jhv.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.gui.component.ModelPreview;
import org.helioviewer.jhv.gui.dialog.ModelManager;
import org.helioviewer.jhv.layers.ModelLayer;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.opengl.model.ModelMaterial;
import org.helioviewer.jhv.opengl.model.ModelMesh;
import org.helioviewer.jhv.opengl.model.ModelScene;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The 3D Model Manager's list, download and preview.
 *
 * <p>Pins: (1) every entry in resources/data/assets.json is pinned (a raw URL at a full commit, a
 * SHA-256, a length), names a target Studio can place or none, and gives metersPerUnit exactly as
 * its quoted size over its measured extent, with a source whenever it gives a size; (2) a download
 * is kept only when length and hash match, leaves no partial file when they do not, and moves a
 * mismatched file aside instead of overwriting it; (3) the software preview draws a model and keeps
 * the nearer of two overlapping faces in front; (4) Add as Layer places a model at its spacecraft
 * with the listed scale, or at Sun centre when there is no target.
 */
public final class ModelAssetsCheck {

    private static int failures;
    private static final Pattern PINNED = Pattern.compile("https://raw\\.githubusercontent\\.com/[^/]+/[^/]+/[0-9a-f]{40}/.+\\.glb");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("model-assets-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice(); // a model layer's setters reach DisplayController, which needs Earth's position

        List<ModelAssets.Entry> entries = checkRegistry();
        checkDownload(home);
        checkPreview();
        checkConfigure(home, entries);

        if (failures != 0) {
            System.out.println("ModelAssetsCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ModelAssetsCheck: PASS");
    }

    private static List<ModelAssets.Entry> checkRegistry() throws IOException {
        List<ModelAssets.Entry> entries = ModelAssets.entries();
        expect(!entries.isEmpty(), "the model list reads (" + entries.size() + " entries)");
        String raw = new String(Files.readAllBytes(Path.of("resources/data/assets.json")), StandardCharsets.UTF_8);
        expect(raw.indexOf(0x2014) < 0, "the model list has no em dash");
        Set<String> ids = new HashSet<>();
        for (ModelAssets.Entry e : entries) {
            String n = e.id() + ": ";
            expect(ids.add(e.id()), n + "id is unique");
            expect(PINNED.matcher(e.url()).matches(), n + "URL is pinned to a full commit (" + e.url() + ")");
            expect(SHA.matcher(e.sha256()).matches() && e.bytes() > 0 && e.extent() > 0, n + "has a SHA-256, a length and an extent");
            expect(e.target() == null || (SpaceObject.get(e.target()) != null && !"Sun".equals(e.target())), n + "target is one Studio can place, or none");
            if (e.size().meters() == null)
                expect(e.metersPerUnit() == null, n + "no checked size, so no scale is claimed");
            else
                expect(e.metersPerUnit() != null && Math.abs(e.metersPerUnit() - e.size().meters() / e.extent()) < 1e-6
                                && e.size().source() != null && e.size().source().startsWith("https://") && e.size().quote() != null,
                        n + "scale is the quoted size over the measured extent, with its source");
            expect(!e.size().note().isBlank() && !e.credit().isBlank() && !e.terms().isBlank(), n + "says how its size was judged, whom to credit and on what terms");
        }
        return entries;
    }

    private static ModelAssets.Entry fake(String id, Path file, String sha, long bytes) {
        return new ModelAssets.Entry(id, id, file.toUri().toString(), "https://example.invalid", sha, bytes, 1, null, null, null,
                new ModelAssets.Size(null, null, null, "test"), "test", "test", "test");
    }

    private static void checkDownload(Path home) throws Exception {
        byte[] body = "not really a model, but bytes are bytes".getBytes(StandardCharsets.US_ASCII);
        Path src = home.resolve("served.glb");
        Files.write(src, body);
        String sha = ModelAssets.sha256(src);
        ModelAssets.Entry good = fake("good", src, sha, body.length);

        Path got = ModelAssets.download(good, b -> {}, () -> false);
        expect(got.equals(ModelAssets.file(good)) && java.util.Arrays.equals(Files.readAllBytes(got), body) && ModelAssets.isPresent(good),
                "a matching download lands under its name in the models folder");

        ModelAssets.Entry badHash = fake("badhash", src, "0".repeat(64), body.length);
        try {
            ModelAssets.download(badHash, b -> {}, () -> false);
            expect(false, "a download whose SHA-256 differs is refused");
        } catch (IOException e) {
            expect(e.getMessage().contains("SHA-256"), "a download whose SHA-256 differs is refused (" + e.getMessage() + ")");
        }
        ModelAssets.Entry badLength = fake("badlength", src, sha, body.length - 1);
        try {
            ModelAssets.download(badLength, b -> {}, () -> false);
            expect(false, "a download longer than listed is refused");
        } catch (IOException e) {
            expect(true, "a download longer than listed is refused (" + e.getMessage() + ")");
        }
        try (var files = Files.list(ModelAssets.folder())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            expect(names.stream().noneMatch(s -> s.endsWith(".part") || s.startsWith("badhash") || s.startsWith("badlength")),
                    "refused downloads leave nothing behind (folder holds " + names + ")");
        }

        // A file under the final name that does not match is moved aside, then replaced by the real one.
        Path stale = ModelAssets.file(good);
        Files.writeString(stale, "stale");
        ModelAssets.download(good, b -> {}, () -> false);
        try (var files = Files.list(ModelAssets.folder())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            expect(java.util.Arrays.equals(Files.readAllBytes(stale), body) && names.stream().anyMatch(s -> s.contains(".unverified-")),
                    "a mismatched file is moved aside, not overwritten (folder holds " + names + ")");
        }
    }

    /** Two unit triangles facing the viewer, red at z = 0 and green at z = 0.5 (nearer). */
    private static ModelScene twoTriangles() {
        float[] pos = {-0.5f, -0.5f, 0, 0.5f, -0.5f, 0, 0, 0.5f, 0, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0, 0.5f, 0.5f};
        float[] nrm = new float[18];
        for (int i = 2; i < 18; i += 3)
            nrm[i] = 1;
        ByteBuffer col = ByteBuffer.allocate(24);
        for (int v = 0; v < 6; v++)
            col.put((byte) (v < 3 ? 255 : 0)).put((byte) (v < 3 ? 0 : 255)).put((byte) 0).put((byte) 255);
        col.flip();
        ModelMesh mesh = new ModelMesh("two", ModelMesh.Primitive.TRIANGLES, FloatBuffer.wrap(pos), FloatBuffer.wrap(nrm), col, null,
                IntBuffer.wrap(new int[]{0, 1, 2, 3, 4, 5}), IntBuffer.allocate(0), 0);
        ModelMaterial white = new ModelMaterial(1, 1, 1, 1, ModelMaterial.NO_TEXTURE, ModelMaterial.AlphaMode.OPAQUE, 0.5f, true, false);
        return new ModelScene("two", new JHVTime("2025-01-01T00:00:00"), List.of(mesh), List.of(white), List.of());
    }

    private static void checkPreview() {
        ModelPreview.Prepared p = ModelPreview.prepare(twoTriangles());
        expect(p.triangles() == 2, "the preview keeps every triangle");
        BufferedImage img = ModelPreview.render(p, 100, 100, 0, 0, 1, 0);
        int covered = 0;
        for (int y = 0; y < 100; y++)
            for (int x = 0; x < 100; x++)
                if ((img.getRGB(x, y) >>> 24) != 0)
                    covered++;
        // Each triangle spans 0.8 * 100 px wide and high: half of 80 x 80 is 3200 px.
        expect(Math.abs(covered - 3200) < 250, "the model fills the preview as sized (" + covered + " px covered, about 3200 expected)");
        int centre = img.getRGB(50, 55);
        expect(((centre >> 8) & 0xff) > 100 && ((centre >> 16) & 0xff) < 30, "the nearer (green) face is drawn in front of the farther (red) one");
        BufferedImage turned = ModelPreview.render(p, 100, 100, Math.PI, 0, 1, 0);
        int back = turned.getRGB(50, 55);
        expect(((back >> 16) & 0xff) > 100 && ((back >> 8) & 0xff) < 30, "turned half round, the red face is in front");
    }

    private static void checkConfigure(Path home, List<ModelAssets.Entry> entries) throws Exception {
        Path model = home.resolve("triangle.gltf");
        float[] data = {0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data)
            b.putFloat(f);
        Files.writeString(model, """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]}}],
                 "buffers":[{"byteLength":72,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[2,1,0]},
                              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"}]}
                """.formatted(java.util.Base64.getEncoder().encodeToString(b.array())));

        ModelAssets.Entry placed = entries.stream().filter(e -> e.target() != null && e.metersPerUnit() != null).findFirst().orElseThrow();
        ModelLayer layer = new ModelLayer(model.toUri());
        ModelManager.configure(layer, placed);
        expect(layer.getPlacementMode() == ModelPlacement.Mode.EPHEMERIS && layer.getTarget().equals(placed.target())
                        && layer.getMetersPerUnit() == placed.metersPerUnit(),
                "Add as Layer puts " + placed.name() + " at " + placed.target() + " at " + placed.metersPerUnit() + " m per unit");
        layer.remove();

        ModelAssets.Entry loose = entries.stream().filter(e -> e.target() == null).findFirst().orElseThrow();
        ModelLayer origin = new ModelLayer(model.toUri());
        ModelManager.configure(origin, loose);
        expect(origin.getPlacementMode() == ModelPlacement.Mode.ORIGIN && origin.getMetersPerUnit() == 1,
                "Add as Layer leaves " + loose.name() + " (no ephemeris, no checked size) at Sun centre in file units");
        origin.remove();
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
