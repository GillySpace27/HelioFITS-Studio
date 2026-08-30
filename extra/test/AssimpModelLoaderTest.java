package org.helioviewer.jhv.opengl.model;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.GZIPOutputStream;

import org.helioviewer.jhv.io.NetFileCache;

public final class AssimpModelLoaderTest {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jhv-assimp-");
        try {
            Path model = directory.resolve("model.gltf");
            byte[] json = modelJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(model, json);
            writeGeometry(directory.resolve("geometry.bin"));

            checkScene(AssimpModelLoader.load(NetFileCache.get(model.toUri())));

            Path compressed = directory.resolve("model.gltf.gz");
            try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
                output.write(json);
            }
            checkScene(AssimpModelLoader.load(NetFileCache.get(compressed.toUri())));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void checkScene(ModelScene scene) {
        check(scene.name().equals("URI loader test"), "scene name");
        check(scene.meshes().size() == 3, "triangle, line and point meshes");
        check(scene.instances().size() == 6, "two instances of each mesh");

        ModelMesh triangles = mesh(scene, ModelMesh.Primitive.TRIANGLES);
        check(triangles.vertexCount() == 3, "triangle vertex count");
        check(triangles.normals() != null && triangles.normals().remaining() == 9, "generated triangle normals");

        ModelMesh lines = mesh(scene, ModelMesh.Primitive.LINES);
        check(lines.indices().remaining() == 3, "line-strip indices");
        IntBuffer offsets = lines.lineOffsets();
        check(offsets.remaining() == 2 && offsets.get(0) == 0 && offsets.get(1) == 3, "line-strip reconstruction");

        long translated = scene.instances().stream().filter(instance -> Math.abs(instance.transform().m30() - 2) < 1e-6).count();
        check(translated == 3, "flattened node transforms");
    }

    private static ModelMesh mesh(ModelScene scene, ModelMesh.Primitive primitive) {
        return scene.meshes().stream().filter(mesh -> mesh.primitive() == primitive).findFirst().orElseThrow();
    }

    private static void writeGeometry(Path path) throws Exception {
        ByteBuffer data = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        data.putFloat(0).putFloat(0).putFloat(0);
        data.putFloat(1).putFloat(0).putFloat(0);
        data.putFloat(0).putFloat(1).putFloat(0);
        data.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        data.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        data.putShort((short) 0).putShort((short) 2);
        Files.write(path, data.array());
    }

    private static String modelJson() {
        return """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"name": "URI loader test", "nodes": [0]}],
                  "nodes": [
                    {"mesh": 0, "children": [1]},
                    {"mesh": 0, "translation": [2, 0, 0]}
                  ],
                  "buffers": [{"byteLength": 52, "uri": "geometry.bin"}],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 36, "target": 34962},
                    {"buffer": 0, "byteOffset": 36, "byteLength": 6, "target": 34963},
                    {"buffer": 0, "byteOffset": 42, "byteLength": 6, "target": 34963},
                    {"buffer": 0, "byteOffset": 48, "byteLength": 4, "target": 34963}
                  ],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                     "min": [0, 0, 0], "max": [1, 1, 0]},
                    {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"},
                    {"bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR"},
                    {"bufferView": 3, "componentType": 5123, "count": 2, "type": "SCALAR"}
                  ],
                  "materials": [{}],
                  "meshes": [{"primitives": [
                    {"attributes": {"POSITION": 0}, "indices": 1, "material": 0, "mode": 4},
                    {"attributes": {"POSITION": 0}, "indices": 2, "material": 0, "mode": 3},
                    {"attributes": {"POSITION": 0}, "indices": 3, "material": 0, "mode": 0}
                  ]}]
                }
                """;
    }

    private static void check(boolean condition, String description) {
        if (!condition)
            throw new AssertionError(description);
    }

    private AssimpModelLoaderTest() {}

}
