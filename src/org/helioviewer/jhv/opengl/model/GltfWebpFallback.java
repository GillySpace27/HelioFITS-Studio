package org.helioviewer.jhv.opengl.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.helioviewer.jhv.base.BufferUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Makes a glTF that offers WebP textures through EXT_texture_webp use its PNG or JPEG fallbacks.
 *
 * <p>Assimp follows the extension and hands over the WebP bytes, which stb_image cannot decode, so
 * the whole model failed to load ("Image not of any known type"). Several of NASA's 3D Resources
 * spacecraft (Parker Solar Probe, Ulysses, MESSENGER) ship this way, each WebP texture paired with a
 * PNG as the texture's ordinary {@code source}, which is what the extension specification provides
 * fallbacks for. Dropping the extension from those textures makes every glTF reader take the
 * fallback. A texture with no fallback is left alone and fails as before, with the same message.
 *
 * <p>Only the JSON is touched; GLB binary chunks are copied unchanged.
 */
final class GltfWebpFallback {

    static final String EXTENSION = "EXT_texture_webp";
    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int JSON_CHUNK = 0x4E4F534A; // "JSON"

    /** The model bytes to give Assimp: {@code data} itself when nothing needs changing. */
    static ByteBuffer apply(ByteBuffer data) {
        if (!mentionsExtension(data))
            return data;
        try {
            if (data.remaining() >= 20 && data.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt() == GLB_MAGIC)
                return glb(data);
            String text = StandardCharsets.UTF_8.decode(data.duplicate()).toString();
            String rewritten = rewrite(text);
            if (rewritten == null)
                return data;
            byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
            return BufferUtils.newByteBuffer(bytes.length).put(bytes).flip();
        } catch (JSONException | IllegalArgumentException | IndexOutOfBoundsException e) {
            return data; // not something we understand: let Assimp report on the original
        }
    }

    private static boolean mentionsExtension(ByteBuffer data) {
        byte[] needle = EXTENSION.getBytes(StandardCharsets.US_ASCII);
        int limit = Math.min(data.limit(), data.position() + (1 << 24)); // the JSON comes first in a GLB
        outer:
        for (int i = data.position(); i <= limit - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data.get(i + j) != needle[j])
                    continue outer;
            }
            return true;
        }
        return false;
    }

    private static ByteBuffer glb(ByteBuffer data) {
        ByteBuffer in = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = in.position();
        int version = in.getInt(start + 4);
        int jsonLength = in.getInt(start + 12);
        if (in.getInt(start + 16) != JSON_CHUNK)
            return data;
        byte[] json = new byte[jsonLength];
        in.position(start + 20);
        in.get(json);
        String rewritten = rewrite(new String(json, StandardCharsets.UTF_8));
        if (rewritten == null)
            return data;

        byte[] newJson = rewritten.getBytes(StandardCharsets.UTF_8);
        int padded = (newJson.length + 3) & ~3;
        int restStart = start + 20 + jsonLength;
        int rest = data.limit() - restStart;
        int total = 12 + 8 + padded + rest;

        ByteBuffer out = BufferUtils.newByteBuffer(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GLB_MAGIC).putInt(version).putInt(total);
        out.putInt(padded).putInt(JSON_CHUNK).put(newJson);
        for (int i = newJson.length; i < padded; i++)
            out.put((byte) ' '); // the GLB specification pads the JSON chunk with spaces
        ByteBuffer tail = data.duplicate();
        tail.position(restStart);
        out.put(tail);
        return out.flip().order(ByteOrder.BIG_ENDIAN);
    }

    /** The JSON with WebP dropped from every texture that has a fallback, or null if none changed. */
    static String rewrite(String json) {
        JSONObject root = new JSONObject(json);
        JSONArray textures = root.optJSONArray("textures");
        if (textures == null)
            return null;
        boolean changed = false, unresolved = false;
        for (int i = 0; i < textures.length(); i++) {
            JSONObject texture = textures.optJSONObject(i);
            JSONObject extensions = texture == null ? null : texture.optJSONObject("extensions");
            if (extensions == null || !extensions.has(EXTENSION))
                continue;
            if (!texture.has("source")) {
                unresolved = true;
                continue;
            }
            extensions.remove(EXTENSION);
            if (extensions.isEmpty())
                texture.remove("extensions");
            changed = true;
        }
        if (!changed)
            return null;
        if (!unresolved) {
            drop(root.optJSONArray("extensionsUsed"));
            drop(root.optJSONArray("extensionsRequired"));
        }
        return root.toString();
    }

    private static void drop(JSONArray names) {
        if (names == null)
            return;
        for (int i = names.length() - 1; i >= 0; i--) {
            if (EXTENSION.equals(names.optString(i)))
                names.remove(i);
        }
    }

    private GltfWebpFallback() {}

}
