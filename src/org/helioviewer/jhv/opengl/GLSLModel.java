package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.opengl.model.ModelMaterial;
import org.helioviewer.jhv.opengl.model.ModelMesh;
import org.helioviewer.jhv.opengl.model.ModelSampler;
import org.helioviewer.jhv.opengl.model.ModelScene;
import org.helioviewer.jhv.opengl.model.ModelTexture;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

public final class GLSLModel {

    private static final float DEFAULT_POINT_SIZE = 0.02f;
    private static final double DEFAULT_LINE_WIDTH = GLSLLine.LINEWIDTH_BASIC;
    private static final Comparator<RenderMesh> BACK_TO_FRONT = Comparator.comparingDouble(RenderMesh::depth);

    private final List<ModelTexture> textureData;
    private final ModelMaterial[] meshMaterials;
    private final int[] meshMaterialIndices;
    private final GLSLMesh[] triangleMeshes;
    private final GLSLLine[] lineMeshes;
    private final GLSLShape[] pointMeshes;
    private final DirectBufVertex[] lineVertices;
    private final DirectBufVertex[] pointVertices;
    private final GLSLMeshMaterial[] materialBuffers;
    private final boolean hasTriangles;
    private final ArrayList<Integer> opaqueMeshes = new ArrayList<>();
    private final ArrayList<RenderMesh> transparentMeshes = new ArrayList<>();

    private final Matrix4f viewRotation = new Matrix4f();
    private final Quaternionf quaternion = new Quaternionf();

    private GLTexture[] textures;
    private boolean initialized;

    public GLSLModel(ModelScene scene) {
        textureData = scene.textures();
        int meshCount = scene.meshes().size();
        meshMaterials = new ModelMaterial[meshCount];
        meshMaterialIndices = new int[meshCount];
        triangleMeshes = new GLSLMesh[meshCount];
        lineMeshes = new GLSLLine[meshCount];
        pointMeshes = new GLSLShape[meshCount];
        lineVertices = new DirectBufVertex[meshCount];
        pointVertices = new DirectBufVertex[meshCount];
        materialBuffers = new GLSLMeshMaterial[scene.materials().size()];
        boolean triangles = false;

        for (int i = 0; i < meshCount; i++) {
            ModelMesh mesh = scene.meshes().get(i);
            ModelMaterial material = scene.materials().get(mesh.materialIndex());
            meshMaterials[i] = material;
            meshMaterialIndices[i] = mesh.materialIndex();
            switch (mesh.primitive()) {
                case TRIANGLES -> {
                    triangles = true;
                    triangleMeshes[i] = new GLSLMesh(mesh);
                    if (materialBuffers[mesh.materialIndex()] == null)
                        materialBuffers[mesh.materialIndex()] = new GLSLMeshMaterial(material);
                }
                case LINES -> {
                    lineMeshes[i] = new GLSLLine(false);
                    lineVertices[i] = createLineVertices(mesh, material);
                }
                case POINTS -> {
                    pointMeshes[i] = new GLSLShape(false);
                    pointVertices[i] = createPointVertices(mesh, material);
                }
            }
            if (mesh.primitive() != ModelMesh.Primitive.TRIANGLES || material.alphaMode() == ModelMaterial.AlphaMode.BLEND)
                transparentMeshes.add(new RenderMesh(i, mesh.positions()));
            else
                opaqueMeshes.add(i);
        }
        hasTriangles = triangles;
    }

    public void init() {
        if (initialized)
            return;
        initialized = true;
        try {
            initTextures();
            for (GLSLMeshMaterial material : materialBuffers) {
                if (material != null)
                    material.init();
            }
            for (int i = 0; i < triangleMeshes.length; i++) {
                if (triangleMeshes[i] != null)
                    triangleMeshes[i].init();
                if (lineMeshes[i] != null) {
                    lineMeshes[i].init();
                    lineMeshes[i].setVertexRepeatable(lineVertices[i]);
                }
                if (pointMeshes[i] != null) {
                    pointMeshes[i].init();
                    pointMeshes[i].setVertexRepeatable(pointVertices[i]);
                }
            }
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    private void initTextures() {
        textures = new GLTexture[textureData.size()];
        for (int i = 0; i < textures.length; i++) {
            ModelTexture data = textureData.get(i);
            if (data.width() > GL.maxTextureSize || data.height() > GL.maxTextureSize)
                throw new IllegalArgumentException("Texture exceeds the OpenGL size limit: " + data.width() + 'x' + data.height());
            ModelSampler sampler = data.sampler();
            GLTexture texture = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.THREE);
            textures[i] = texture;
            texture.upload2D(GLTexture.Format.RGBA8, data.width(), data.height(), minFilter(sampler.minFilter()), magFilter(sampler.magFilter()),
                    wrap(sampler.wrapS()), wrap(sampler.wrapT()), data.rgba());
        }
    }

    public void render(MapView mv, Viewport vp) {
        if (!initialized)
            return;

        FloatBuffer worldToClip = Transform.get();
        Quat rotation = mv.viewRotation();
        viewRotation.rotation(quaternion.set((float) rotation.x, (float) rotation.y, (float) rotation.z, (float) rotation.w));
        if (hasTriangles)
            GLSLMeshShader.bindFrame(worldToClip, viewRotation.m02(), viewRotation.m12(), viewRotation.m22());

        for (int meshIndex : opaqueMeshes)
            renderTriangle(meshIndex);

        for (RenderMesh mesh : transparentMeshes)
            mesh.updateDepth(viewRotation);
        transparentMeshes.sort(BACK_TO_FRONT);

        double pointFactor = ViewportMath.getPixelFactor(vp, mv.cameraWidth(vp));
        for (RenderMesh mesh : transparentMeshes) {
            if (triangleMeshes[mesh.meshIndex] != null) {
                GL.glDepthMask(false);
                try {
                    renderTriangle(mesh.meshIndex);
                } finally {
                    GL.glDepthMask(true);
                }
            } else
                renderDrawing(mesh.meshIndex, vp, pointFactor, worldToClip);
        }
    }

    private void renderDrawing(int meshIndex, Viewport vp, double pointFactor, FloatBuffer worldToClip) {
        GLSLLine line = lineMeshes[meshIndex];
        if (line != null)
            line.renderLine(vp, DEFAULT_LINE_WIDTH, worldToClip);
        else
            pointMeshes[meshIndex].renderPoints(pointFactor, worldToClip);
    }

    private void renderTriangle(int meshIndex) {
        ModelMaterial material = meshMaterials[meshIndex];
        if (material.doubleSided())
            GL.glDisable(GL.CULL_FACE);

        try {
            GLSLMeshShader.mesh.use();
            materialBuffers[meshMaterialIndices[meshIndex]].bind();
            if (material.baseColorTexture() != ModelMaterial.NO_TEXTURE)
                textures[material.baseColorTexture()].bind();
            triangleMeshes[meshIndex].render();
        } finally {
            if (material.doubleSided())
                GL.glEnable(GL.CULL_FACE);
        }
    }

    public void dispose() {
        if (!initialized)
            return;
        initialized = false;

        for (GLSLMesh mesh : triangleMeshes) {
            if (mesh != null)
                mesh.dispose();
        }
        for (GLSLLine line : lineMeshes) {
            if (line != null)
                line.dispose();
        }
        for (GLSLShape points : pointMeshes) {
            if (points != null)
                points.dispose();
        }
        for (GLSLMeshMaterial material : materialBuffers) {
            if (material != null)
                material.dispose();
        }
        if (textures != null) {
            for (GLTexture texture : textures) {
                if (texture != null)
                    texture.delete();
            }
            textures = null;
        }
    }

    private static DirectBufVertex createPointVertices(ModelMesh mesh, ModelMaterial material) {
        IntBuffer indices = mesh.indices();
        BufVertex vertices = new BufVertex(Math.multiplyExact(indices.remaining(), GLSLShape.stride));
        FloatBuffer positions = mesh.positions();
        ByteBuffer colors = mesh.colors();
        byte[] color = new byte[4];
        while (indices.hasRemaining()) {
            int index = indices.get();
            setColor(colors, material, index, color);
            putVertex(positions, index, DEFAULT_POINT_SIZE, color, vertices);
        }
        return new DirectBufVertex(vertices);
    }

    private static DirectBufVertex createLineVertices(ModelMesh mesh, ModelMaterial material) {
        IntBuffer indices = mesh.indices();
        IntBuffer offsets = mesh.lineOffsets();
        int lineCount = offsets.remaining() - 1;
        int vertexCount = Math.addExact(indices.remaining(), Math.multiplyExact(2, lineCount));
        BufVertex vertices = new BufVertex(Math.multiplyExact(vertexCount, GLSLLine.stride));
        FloatBuffer positions = mesh.positions();
        ByteBuffer colors = mesh.colors();
        byte[] color = new byte[4];

        // The shared line shader has no material cutoff, so MASK is applied at vertices and interpolated along each segment.
        for (int line = 0; line < lineCount; line++) {
            int start = offsets.get(line);
            int end = offsets.get(line + 1);
            int firstIndex = indices.get(start);
            putVertex(positions, firstIndex, 1, Colors.Null, vertices);
            for (int i = start; i < end; i++) {
                int index = indices.get(i);
                setColor(colors, material, index, color);
                putVertex(positions, index, 1, color, vertices);
            }
            vertices.repeatVertex(Colors.Null);
        }
        return new DirectBufVertex(vertices);
    }

    private static void putVertex(FloatBuffer positions, int index, float size, byte[] color, BufVertex vertices) {
        vertices.putVertex(positions.get(3 * index), positions.get(3 * index + 1), positions.get(3 * index + 2), size, color);
    }

    private static void setColor(ByteBuffer colors, ModelMaterial material, int index, byte[] result) {
        float red = (colors.get(4 * index) & 0xff) / 255f * material.red();
        float green = (colors.get(4 * index + 1) & 0xff) / 255f * material.green();
        float blue = (colors.get(4 * index + 2) & 0xff) / 255f * material.blue();
        float alpha = Math.clamp((colors.get(4 * index + 3) & 0xff) / 255f * material.alpha(), 0, 1);

        alpha = switch (material.alphaMode()) {
            case OPAQUE -> 1;
            case MASK -> alpha < material.alphaCutoff() ? 0 : 1;
            case BLEND -> alpha;
        };
        result[0] = toByte(Math.clamp(red, 0, 1) * alpha);
        result[1] = toByte(Math.clamp(green, 0, 1) * alpha);
        result[2] = toByte(Math.clamp(blue, 0, 1) * alpha);
        result[3] = toByte(alpha);
    }

    private static byte toByte(float value) {
        return (byte) Math.round(255 * value);
    }

    private static int minFilter(ModelSampler.MinFilter filter) {
        return switch (filter) {
            case NEAREST -> GL.NEAREST;
            case LINEAR -> GL.LINEAR;
            case NEAREST_MIPMAP_NEAREST -> GL.NEAREST_MIPMAP_NEAREST;
            case LINEAR_MIPMAP_NEAREST -> GL.LINEAR_MIPMAP_NEAREST;
            case NEAREST_MIPMAP_LINEAR -> GL.NEAREST_MIPMAP_LINEAR;
            case LINEAR_MIPMAP_LINEAR -> GL.LINEAR_MIPMAP_LINEAR;
        };
    }

    private static int magFilter(ModelSampler.MagFilter filter) {
        return switch (filter) {
            case NEAREST -> GL.NEAREST;
            case LINEAR -> GL.LINEAR;
        };
    }

    private static int wrap(ModelSampler.Wrap wrap) {
        return switch (wrap) {
            case CLAMP_TO_EDGE -> GL.CLAMP_TO_EDGE;
            case MIRRORED_REPEAT -> GL.MIRRORED_REPEAT;
            case REPEAT -> GL.REPEAT;
        };
    }

    private static final class RenderMesh {
        final int meshIndex;
        final float centerX;
        final float centerY;
        final float centerZ;
        private float depth;

        RenderMesh(int _meshIndex, FloatBuffer positions) {
            meshIndex = _meshIndex;
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            while (positions.hasRemaining()) {
                float x = positions.get();
                float y = positions.get();
                float z = positions.get();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            centerX = 0.5f * (minX + maxX);
            centerY = 0.5f * (minY + maxY);
            centerZ = 0.5f * (minZ + maxZ);
        }

        void updateDepth(Matrix4fc view) {
            depth = view.m02() * centerX + view.m12() * centerY + view.m22() * centerZ;
        }

        float depth() {
            return depth;
        }
    }

}
