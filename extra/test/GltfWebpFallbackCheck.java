package org.helioviewer.jhv.opengl.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.NetFileCache;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * glTF files that offer WebP textures through EXT_texture_webp load from their PNG or JPEG fallback.
 *
 * <p>NASA 3D Resources ships Parker Solar Probe, Ulysses and MESSENGER this way, and before the fix
 * each failed to load with "Image not of any known type". Pins: the JSON rewrite (extension dropped
 * from resolved textures, and from extensionsUsed/Required only when every texture is resolved), a
 * GLB keeps its binary chunk byte for byte with a correctly padded JSON chunk, a file without the
 * extension is passed through untouched, and a real load through Assimp of a textured triangle whose
 * WebP is not decodable succeeds from its PNG fallback.
 */
public final class GltfWebpFallbackCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("gltf-webp-check");
        System.setProperty("user.home", home.toString());
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();

        checkRewrite();
        checkGlb();
        checkLoad(home);

        if (failures != 0) {
            System.out.println("GltfWebpFallbackCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("GltfWebpFallbackCheck: PASS");
    }

    private static JSONObject texture(boolean withSource) {
        JSONObject t = new JSONObject().put("extensions", new JSONObject().put(GltfWebpFallback.EXTENSION, new JSONObject().put("source", 1)));
        if (withSource)
            t.put("source", 0);
        return t;
    }

    private static String doc(JSONObject... textures) {
        return new JSONObject().put("asset", new JSONObject().put("version", "2.0"))
                .put("extensionsUsed", new JSONArray().put(GltfWebpFallback.EXTENSION).put("KHR_materials_unlit"))
                .put("extensionsRequired", new JSONArray().put(GltfWebpFallback.EXTENSION))
                .put("textures", new JSONArray(textures)).toString();
    }

    private static void checkRewrite() {
        JSONObject out = new JSONObject(GltfWebpFallback.rewrite(doc(texture(true))));
        JSONObject t = out.getJSONArray("textures").getJSONObject(0);
        expect(!t.has("extensions") && t.getInt("source") == 0, "a texture with a fallback loses the WebP extension and keeps its source");
        expect(out.getJSONArray("extensionsUsed").length() == 1 && "KHR_materials_unlit".equals(out.getJSONArray("extensionsUsed").getString(0)),
                "extensionsUsed drops only EXT_texture_webp");
        expect(out.getJSONArray("extensionsRequired").isEmpty(), "extensionsRequired drops EXT_texture_webp");

        expect(GltfWebpFallback.rewrite(doc(texture(false))) == null, "a WebP-only texture is left alone (nothing to fall back to)");

        JSONObject mixed = new JSONObject(GltfWebpFallback.rewrite(doc(texture(true), texture(false))));
        expect(mixed.getJSONArray("extensionsRequired").length() == 1
                        && mixed.getJSONArray("textures").getJSONObject(1).getJSONObject("extensions").has(GltfWebpFallback.EXTENSION),
                "with one texture unresolved, the extension stays required and on that texture");

        ByteBuffer plain = ByteBuffer.wrap("{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.UTF_8));
        expect(GltfWebpFallback.apply(plain) == plain, "a file that never mentions the extension is passed through as is");
    }

    private static void checkGlb() {
        byte[] json = doc(texture(true)).getBytes(StandardCharsets.UTF_8);
        int jsonPadded = (json.length + 3) & ~3;
        byte[] bin = {1, 2, 3, 4, 5, 6, 7, 8};
        int total = 12 + 8 + jsonPadded + 8 + bin.length;
        ByteBuffer glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(total).putInt(jsonPadded).putInt(0x4E4F534A).put(json);
        for (int i = json.length; i < jsonPadded; i++)
            glb.put((byte) ' ');
        glb.putInt(bin.length).putInt(0x004E4942).put(bin).flip();

        ByteBuffer out = GltfWebpFallback.apply(glb).duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int outTotal = out.getInt(8), outJson = out.getInt(12);
        expect(out.getInt(0) == 0x46546C67 && outTotal == out.remaining(), "GLB header length matches the rewritten file (" + outTotal + " vs " + out.remaining() + ")");
        expect(outJson % 4 == 0, "the rewritten JSON chunk is padded to four bytes");
        byte[] newJson = new byte[outJson];
        out.get(20, newJson);
        expect(!new String(newJson, StandardCharsets.UTF_8).contains(GltfWebpFallback.EXTENSION), "the GLB's JSON no longer names the extension");
        int binAt = 20 + outJson;
        byte[] newBin = new byte[bin.length];
        out.get(binAt + 8, newBin);
        expect(out.getInt(binAt) == bin.length && out.getInt(binAt + 4) == 0x004E4942 && java.util.Arrays.equals(newBin, bin),
                "the binary chunk is copied byte for byte");
    }

    /** A lit, textured triangle whose texture offers an undecodable WebP and a real PNG. */
    private static void checkLoad(Path dir) throws Exception {
        float[] data = {0, 0, 0, 1, 0, 0, 0, 1, 0, /* normals */ 0, 0, 1, 0, 0, 1, 0, 0, 1, /* uv */ 0, 0, 1, 0, 0, 1};
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data)
            b.putFloat(f);
        String buffer = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(b.array());

        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xffff0000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String pngUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        String webpUri = "data:image/webp;base64," + Base64.getEncoder().encodeToString("RIFF\0\0\0\0WEBPVP8 not really".getBytes(StandardCharsets.US_ASCII));

        String gltf = """
                {"asset":{"version":"2.0"},"extensionsUsed":["EXT_texture_webp"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                 "textures":[{"source":0,"extensions":{"EXT_texture_webp":{"source":1}}}],
                 "images":[{"uri":"%s"},{"uri":"%s"}],
                 "buffers":[{"byteLength":96,"uri":"%s"}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},{"buffer":0,"byteOffset":72,"byteLength":24}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"}]}
                """.formatted(pngUri, webpUri, buffer);
        Path model = dir.resolve("webp-triangle.gltf");
        Files.writeString(model, gltf);
        try {
            ModelScene scene = AssimpModelLoader.load(NetFileCache.get(model.toUri()));
            expect(scene.textures().size() == 1 && scene.textures().getFirst().width() == 2, "a WebP texture with a PNG fallback loads from the PNG");
        } catch (java.io.IOException | IllegalArgumentException e) {
            expect(false, "a WebP texture with a PNG fallback loads from the PNG (got " + e + ")");
        }
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
