package org.helioviewer.jhv.opengl.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.io.DataUri;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;

public final class AssimpModelLoader {

    private static final int IMPORT_FLAGS = Assimp.aiProcess_Triangulate | Assimp.aiProcess_ValidateDataStructure |
            Assimp.aiProcess_SortByPType | Assimp.aiProcess_RemoveRedundantMaterials | Assimp.aiProcess_GenSmoothNormals;

    private final AIScene source;

    public static ModelScene load(DataUri data) throws IOException {
        if (data.format() != DataUri.Format.GLTF)
            throw new IOException("Not a glTF model: " + data.sourceUri());

        AIScene scene;
        try (AssimpFileIO fileIO = new AssimpFileIO(data)) {
            scene = fileIO.importScene(IMPORT_FLAGS);
        }
        try {
            return new AssimpModelLoader(scene).convert(data.baseName());
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    private AssimpModelLoader(AIScene _source) {
        source = _source;
    }

    private ModelScene convert(String fallbackName) throws IOException {
        if ((source.mFlags() & Assimp.AI_SCENE_FLAGS_INCOMPLETE) != 0)
            throw new IOException("Assimp returned an incomplete scene");
        AINode root = source.mRootNode();
        if (root == null)
            throw new IOException("Assimp scene has no root node");
        if (source.mNumAnimations() != 0)
            throw new IOException("Animated model scenes are not supported");
        if (source.mNumSkeletons() != 0)
            throw new IOException("Model skeletons are not supported");

        List<ModelMesh> meshes = convertMeshes();
        ArrayList<ModelInstance> instances = new ArrayList<>();
        convertNode(root, new Matrix4f(), instances);
        if (instances.isEmpty())
            throw new IOException("Model scene has no mesh instances");

        String name = source.mName().dataString();
        return new ModelScene(name.isEmpty() ? fallbackName : name, meshes, instances);
    }

    private List<ModelMesh> convertMeshes() throws IOException {
        PointerBuffer sourceMeshes = source.mMeshes();
        if (source.mNumMeshes() == 0)
            throw new IOException("Model scene has no meshes");
        if (sourceMeshes == null)
            throw new IOException("Assimp scene has no mesh array");

        ArrayList<ModelMesh> result = new ArrayList<>(source.mNumMeshes());
        for (int i = 0; i < source.mNumMeshes(); i++)
            result.add(convertMesh(AIMesh.create(sourceMeshes.get(i)), i));
        return List.copyOf(result);
    }

    private ModelMesh convertMesh(AIMesh mesh, int meshIndex) throws IOException {
        if (mesh.mNumBones() != 0)
            throw new IOException("Mesh " + meshIndex + " uses unsupported skinning");
        if (mesh.mNumAnimMeshes() != 0)
            throw new IOException("Mesh " + meshIndex + " uses unsupported morph targets");

        ModelMesh.Primitive primitive = primitive(mesh.mPrimitiveTypes(), meshIndex);
        int materialIndex = mesh.mMaterialIndex();
        if (materialIndex < 0 || materialIndex >= source.mNumMaterials())
            throw new IOException("Mesh " + meshIndex + " has invalid material index " + materialIndex);

        FloatBuffer positions = copyPositions(mesh);
        FloatBuffer normals = primitive == ModelMesh.Primitive.TRIANGLES ? copyNormals(mesh, meshIndex) : null;
        ByteBuffer colors = copyColors(mesh);
        IndexData indexData = copyIndices(mesh, primitive, meshIndex);
        String name = mesh.mName().dataString();
        return new ModelMesh(name.isEmpty() ? "mesh-" + meshIndex : name, primitive, positions, normals, colors, indexData.indices(),
                indexData.lineOffsets(), materialIndex);
    }

    private static FloatBuffer copyPositions(AIMesh mesh) {
        int vertexCount = mesh.mNumVertices();
        FloatBuffer result = BufferUtils.newFloatBuffer(Math.multiplyExact(vertexCount, 3));
        AIVector3D.Buffer vertices = mesh.mVertices();
        for (int i = 0; i < vertexCount; i++) {
            AIVector3D vertex = vertices.get(i);
            result.put(vertex.x()).put(vertex.y()).put(vertex.z());
        }
        return result.flip();
    }

    private static FloatBuffer copyNormals(AIMesh mesh, int meshIndex) throws IOException {
        AIVector3D.Buffer normals = mesh.mNormals();
        if (normals == null)
            throw new IOException("Triangle mesh " + meshIndex + " has no normals");

        FloatBuffer result = BufferUtils.newFloatBuffer(Math.multiplyExact(mesh.mNumVertices(), 3));
        for (int i = 0; i < mesh.mNumVertices(); i++) {
            AIVector3D normal = normals.get(i);
            result.put(normal.x()).put(normal.y()).put(normal.z());
        }
        return result.flip();
    }

    private static ByteBuffer copyColors(AIMesh mesh) {
        int vertexCount = mesh.mNumVertices();
        ByteBuffer result = BufferUtils.newByteBuffer(Math.multiplyExact(vertexCount, 4));
        AIColor4D.Buffer colors = mesh.mColors(0);
        if (colors == null) {
            for (int i = 0; i < vertexCount; i++)
                result.putInt(-1);
        } else {
            for (int i = 0; i < vertexCount; i++) {
                AIColor4D color = colors.get(i);
                result.put(toByte(color.r())).put(toByte(color.g())).put(toByte(color.b())).put(toByte(color.a()));
            }
        }
        return result.flip();
    }

    private static IndexData copyIndices(AIMesh mesh, ModelMesh.Primitive primitive, int meshIndex) throws IOException {
        if (primitive == ModelMesh.Primitive.LINES)
            return copyLineIndices(mesh, meshIndex);

        int indicesPerFace = switch (primitive) {
            case POINTS -> 1;
            case TRIANGLES -> 3;
            case LINES -> throw new AssertionError();
        };
        int faceCount = mesh.mNumFaces();
        if (faceCount == 0)
            throw new IOException("Mesh " + meshIndex + " has no primitives");
        IntBuffer indices = BufferUtils.newIntBuffer(Math.multiplyExact(faceCount, indicesPerFace));

        AIFace.Buffer faces = mesh.mFaces();
        for (int i = 0; i < faceCount; i++) {
            AIFace face = faces.get(i);
            if (face.mNumIndices() != indicesPerFace)
                throw new IOException("Mesh " + meshIndex + " contains an unexpected " + face.mNumIndices() + "-vertex primitive");
            indices.put(face.mIndices());
        }
        return new IndexData(indices.flip(), BufferUtils.newIntBuffer(0));
    }

    private static IndexData copyLineIndices(AIMesh mesh, int meshIndex) throws IOException {
        int edgeCount = mesh.mNumFaces();
        if (edgeCount == 0)
            throw new IOException("Mesh " + meshIndex + " has no primitives");

        int[] starts = new int[edgeCount];
        int[] ends = new int[edgeCount];
        ArrayList<ArrayList<Integer>> incidentEdges = new ArrayList<>(mesh.mNumVertices());
        for (int i = 0; i < mesh.mNumVertices(); i++)
            incidentEdges.add(new ArrayList<>());

        AIFace.Buffer faces = mesh.mFaces();
        for (int edge = 0; edge < edgeCount; edge++) {
            AIFace face = faces.get(edge);
            if (face.mNumIndices() != 2)
                throw new IOException("Mesh " + meshIndex + " contains an unexpected " + face.mNumIndices() + "-vertex primitive");
            IntBuffer faceIndices = face.mIndices();
            int start = faceIndices.get(0);
            int end = faceIndices.get(1);
            if (start < 0 || start >= mesh.mNumVertices() || end < 0 || end >= mesh.mNumVertices())
                throw new IOException("Mesh " + meshIndex + " contains an invalid line index");
            starts[edge] = start;
            ends[edge] = end;
            incidentEdges.get(start).add(edge);
            incidentEdges.get(end).add(edge);
        }

        IntBuffer indices = BufferUtils.newIntBuffer(Math.multiplyExact(edgeCount, 2));
        IntBuffer lineOffsets = BufferUtils.newIntBuffer(Math.addExact(edgeCount, 1));
        boolean[] visited = new boolean[edgeCount];
        lineOffsets.put(0);

        // Assimp represents every line mode as separate edges. Rebuild paths so the JHV line renderer can form joins and loops.
        for (int vertex = 0; vertex < incidentEdges.size(); vertex++) {
            if (incidentEdges.get(vertex).size() == 2)
                continue;
            for (int edge : incidentEdges.get(vertex)) {
                if (!visited[edge])
                    appendLine(vertex, edge, starts, ends, incidentEdges, visited, indices, lineOffsets);
            }
        }
        for (int edge = 0; edge < edgeCount; edge++) {
            if (!visited[edge])
                appendLine(starts[edge], edge, starts, ends, incidentEdges, visited, indices, lineOffsets);
        }
        return new IndexData(indices.flip(), lineOffsets.flip());
    }

    private static void appendLine(int start, int firstEdge, int[] starts, int[] ends, List<? extends List<Integer>> incidentEdges,
                                   boolean[] visited, IntBuffer indices, IntBuffer lineOffsets) {
        indices.put(start);
        int vertex = start;
        int edge = firstEdge;
        while (true) {
            visited[edge] = true;
            vertex = starts[edge] == vertex ? ends[edge] : starts[edge];
            indices.put(vertex);
            List<Integer> nextEdges = incidentEdges.get(vertex);
            if (nextEdges.size() != 2)
                break;

            int nextEdge = -1;
            for (int candidate : nextEdges) {
                if (!visited[candidate]) {
                    nextEdge = candidate;
                    break;
                }
            }
            if (nextEdge == -1)
                break;
            edge = nextEdge;
        }
        lineOffsets.put(indices.position());
    }

    private void convertNode(AINode node, Matrix4fc parentTransform, List<ModelInstance> instances) throws IOException {
        Matrix4f transform = new Matrix4f(parentTransform).mul(matrix(node.mTransformation()));
        IntBuffer meshIndices = node.mMeshes();
        if (node.mNumMeshes() != 0 && meshIndices == null)
            throw new IOException("Node " + node.mName().dataString() + " has no mesh-index array");
        for (int i = 0; i < node.mNumMeshes(); i++) {
            int meshIndex = meshIndices.get(i);
            if (meshIndex < 0 || meshIndex >= source.mNumMeshes())
                throw new IOException("Node " + node.mName().dataString() + " uses invalid mesh index " + meshIndex);
            instances.add(new ModelInstance(meshIndex, transform));
        }

        PointerBuffer children = node.mChildren();
        if (node.mNumChildren() != 0 && children == null)
            throw new IOException("Node " + node.mName().dataString() + " has no child array");
        for (int i = 0; i < node.mNumChildren(); i++)
            convertNode(AINode.create(children.get(i)), transform, instances);
    }

    private static Matrix4f matrix(AIMatrix4x4 matrix) {
        // Assimp names entries by row; the JOML constructor takes columns.
        return new Matrix4f(
                matrix.a1(), matrix.b1(), matrix.c1(), matrix.d1(),
                matrix.a2(), matrix.b2(), matrix.c2(), matrix.d2(),
                matrix.a3(), matrix.b3(), matrix.c3(), matrix.d3(),
                matrix.a4(), matrix.b4(), matrix.c4(), matrix.d4());
    }

    private static ModelMesh.Primitive primitive(int type, int meshIndex) throws IOException {
        return switch (type) {
            case Assimp.aiPrimitiveType_POINT -> ModelMesh.Primitive.POINTS;
            case Assimp.aiPrimitiveType_LINE -> ModelMesh.Primitive.LINES;
            case Assimp.aiPrimitiveType_TRIANGLE -> ModelMesh.Primitive.TRIANGLES;
            default -> throw new IOException("Mesh " + meshIndex + " has unsupported or mixed primitive types: " + type);
        };
    }

    private static byte toByte(float value) {
        return (byte) Math.round(255 * Math.clamp(value, 0, 1));
    }

    private record IndexData(IntBuffer indices, IntBuffer lineOffsets) {}

}
