package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Viewport;

public class GLSLLine extends VAO implements GLSLVertexReceiver {

    public static final double LINEWIDTH_BASIC = 0.002;

    private static final int POSITION_COMPONENTS = 4;
    private static final int POSITION_BYTES = POSITION_COMPONENTS * Float.BYTES;
    private static final int COLOR_COMPONENTS = 4;
    private static final int COLOR_BYTES = COLOR_COMPONENTS * Byte.BYTES;

    private int count;

    public GLSLLine(boolean _dynamic) {
        super(_dynamic,
                new VertexAttribute[]{
                        VertexAttribute.instancedFloats(0, POSITION_COMPONENTS, 0, 0),
                        VertexAttribute.instancedFloats(2, POSITION_COMPONENTS, 0, POSITION_BYTES),
                        VertexAttribute.instancedFloats(4, POSITION_COMPONENTS, 0, 2 * POSITION_BYTES),
                        VertexAttribute.instancedFloats(6, POSITION_COMPONENTS, 0, 3 * POSITION_BYTES)},
                new VertexAttribute[]{
                        VertexAttribute.instancedNormalizedUnsignedBytes(1, COLOR_COMPONENTS, 0, 0),
                        VertexAttribute.instancedNormalizedUnsignedBytes(3, COLOR_COMPONENTS, 0, COLOR_BYTES),
                        VertexAttribute.instancedNormalizedUnsignedBytes(5, COLOR_COMPONENTS, 0, 2 * COLOR_BYTES),
                        VertexAttribute.instancedNormalizedUnsignedBytes(7, COLOR_COMPONENTS, 0, 3 * COLOR_BYTES)});
    }

    @Override
    public void upload(BufVertex vexBuf) {
        count = vexBuf.getCount();
        upload(vexBuf.toVertexBuffer(), vexBuf.toColorBuffer());
    }

    @Override
    public void upload(DirectBufVertex vexBuf) {
        count = vexBuf.count();
        upload(vexBuf.vertexBuffer(), vexBuf.colorBuffer());
    }

    private void upload(ByteBuffer vertices, ByteBuffer colors) {
        if (count == 0)
            return;
        vertexBuffer(0).setBufferData(vertices.remaining(), vertices);
        vertexBuffer(1).setBufferData(colors.remaining(), colors);
        if (count < 4) {
            Log.warn("GLSLLine requires at least two visible vertices padded by transparent sentinels; count=" + count + ", emitter=" + getEmitter());
            count = 0;
        } else
            count -= 3;
    }

    private static String getEmitter() {
        String self = GLSLLine.class.getName();
        String receiver = GLSLVertexReceiver.class.getName();
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(self) && !frame.getClassName().equals(receiver))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                .orElse("|unknown|"));
    }

    public void renderLine(Viewport vp, double thickness) {
        renderLine(vp, thickness, Transform.get());
    }

    void renderLine(Viewport vp, double thickness, FloatBuffer mvp) {
        if (count == 0)
            return;

        GLSLLineShader.line.use();
        GLSLLineShader.line.bindParams(vp, thickness, mvp);

        bind();

        // Let fully opaque line cores occlude later geometry. The second pass
        // adds translucent colors and antialiasing without writing their depth.
        GLSLLineShader.line.bindOpaquePass(true);
        GL.glDrawArraysInstanced(GL.TRIANGLE_STRIP, 0, 4, count);

        GLSLLineShader.line.bindOpaquePass(false);
        GL.glDepthMask(false);
        GL.glDrawArraysInstanced(GL.TRIANGLE_STRIP, 0, 4, count);
        GL.glDepthMask(true);
    }

}
