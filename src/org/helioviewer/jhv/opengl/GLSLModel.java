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
    private final ArrayList<RenderMesh> meshes;
    private final GLSLMeshMaterial[] materialBuffers;
    private final boolean hasTriangles;
    private final ArrayList<RenderMesh> opaqueMeshes = new ArrayList<>();
    private final ArrayList<RenderMesh> transparentMeshes = new ArrayList<>();

    private final Matrix4f viewRotation = new Matrix4f();
    private final Quaternionf quaternion = new Quaternionf();

    private GLTexture[] textures;
    private boolean initialized;

    public GLSLModel(ModelScene scene) {
        textureData = scene.textures();
        meshes = new ArrayList<>(scene.meshes().size());
        materialBuffers = new GLSLMeshMaterial[scene.materials().size()];
        boolean triangles = false;

        for (ModelMesh data : scene.meshes()) {
            ModelMaterial material = scene.materials().get(data.materialIndex());
            GLSLMeshMaterial materialBuffer = null;
            if (data.primitive() == ModelMesh.Primitive.TRIANGLES) {
                triangles = true;
                if (materialBuffers[data.materialIndex()] == null)
                    materialBuffers[data.materialIndex()] = new GLSLMeshMaterial(material);
                materialBuffer = materialBuffers[data.materialIndex()];
            }
            RenderMesh mesh = new RenderMesh(data, material, materialBuffer);
            meshes.add(mesh);
            if (mesh.isTransparent())
                transparentMeshes.add(mesh);
            else
                opaqueMeshes.add(mesh);
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
            for (RenderMesh mesh : meshes)
                mesh.init();
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

        for (RenderMesh mesh : opaqueMeshes)
            renderTriangle(mesh);

        for (RenderMesh mesh : transparentMeshes)
            mesh.updateDepth(viewRotation);
        transparentMeshes.sort(BACK_TO_FRONT);

        double pointFactor = ViewportMath.getPixelFactor(vp, mv.cameraWidth(vp));
        for (RenderMesh mesh : transparentMeshes) {
            if (mesh.triangle != null) {
                GL.glDepthMask(false);
                try {
                    renderTriangle(mesh);
                } finally {
                    GL.glDepthMask(true);
                }
            } else
                renderDrawing(mesh, vp, pointFactor, worldToClip);
        }
    }

    private void renderDrawing(RenderMesh mesh, Viewport vp, double pointFactor, FloatBuffer worldToClip) {
        if (mesh.line != null)
            mesh.line.renderLine(vp, DEFAULT_LINE_WIDTH, worldToClip);
        else
            mesh.points.renderPoints(pointFactor, worldToClip);
    }

    private void renderTriangle(RenderMesh mesh) {
        ModelMaterial material = mesh.material;
        if (material.doubleSided())
            GL.glDisable(GL.CULL_FACE);

        try {
            GLSLMeshShader.mesh.use();
            mesh.materialBuffer.bind();
            if (material.baseColorTexture() != ModelMaterial.NO_TEXTURE)
                textures[material.baseColorTexture()].bind();
            mesh.triangle.render();
        } finally {
            if (material.doubleSided())
                GL.glEnable(GL.CULL_FACE);
        }
    }

    public void dispose() {
        if (!initialized)
            return;
        initialized = false;

        for (RenderMesh mesh : meshes)
            mesh.dispose();
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
        BufVertex vertices = new BufVertex(indices.remaining());
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
        BufVertex vertices = new BufVertex(vertexCount);
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
        final ModelMaterial material;
        final GLSLMeshMaterial materialBuffer;
        final GLSLMesh triangle;
        final GLSLLine line;
        final GLSLShape points;
        final DirectBufVertex drawingVertices;
        final float centerX;
        final float centerY;
        final float centerZ;
        private float depth;

        RenderMesh(ModelMesh mesh, ModelMaterial _material, GLSLMeshMaterial _materialBuffer) {
            material = _material;
            materialBuffer = _materialBuffer;
            GLSLMesh _triangle = null;
            GLSLLine _line = null;
            GLSLShape _points = null;
            DirectBufVertex _drawingVertices = null;
            switch (mesh.primitive()) {
                case TRIANGLES -> _triangle = new GLSLMesh(mesh);
                case LINES -> {
                    _line = new GLSLLine(false);
                    _drawingVertices = createLineVertices(mesh, material);
                }
                case POINTS -> {
                    _points = new GLSLShape(false);
                    _drawingVertices = createPointVertices(mesh, material);
                }
            }
            triangle = _triangle;
            line = _line;
            points = _points;
            drawingVertices = _drawingVertices;
            if (!isTransparent()) {
                centerX = centerY = centerZ = 0;
                return;
            }

            FloatBuffer positions = mesh.positions();
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

        boolean isTransparent() {
            return triangle == null || material.alphaMode() == ModelMaterial.AlphaMode.BLEND;
        }

        void init() {
            if (triangle != null) {
                triangle.init();
            } else if (line != null) {
                line.init();
                line.upload(drawingVertices);
            } else {
                points.init();
                points.upload(drawingVertices);
            }
        }

        void dispose() {
            if (triangle != null)
                triangle.dispose();
            else if (line != null)
                line.dispose();
            else
                points.dispose();
        }

        void updateDepth(Matrix4fc view) {
            depth = view.m02() * centerX + view.m12() * centerY + view.m22() * centerZ;
        }

        float depth() {
            return depth;
        }
    }

}
