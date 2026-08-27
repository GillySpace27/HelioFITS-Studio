package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.display.MapScale;

/**
 * The warp parameters the overlay vertex shaders read.
 *
 * <p>One uniform block, uploaded once per frame, shared by every shader that draws world-space
 * geometry (line, point, shape). That is what lets a single {@code warpWorld()} in
 * {@code warpCommon.vert} cover point clouds, PFSS field lines, the grid, FOV boxes and
 * annotations at once, rather than teaching each layer about the warp.
 *
 * <p>Disabled means {@code enabled = 0}, and the shader returns the vertex untouched. Every
 * projection except RadialWarp leaves it that way, as does the miniview, which is meant to stay
 * an undistorted context view even while the main scene is warped.
 */
public final class GLSLWarp {

    private static GLBO warpBO;
    private static final FloatBuffer buf = BufferUtils.newFloatBuffer(4);
    private static final int SIZE = buf.capacity() * 4;

    static void init() {
        warpBO = new GLBO(GL.UNIFORM_BUFFER, GL.STREAM_DRAW);
        disable(); // a sane block before any frame has been drawn
    }

    static void dispose() {
        if (warpBO != null) {
            warpBO.delete();
            warpBO = null;
        }
    }

    static void setupBlock(int programID) {
        GLSLShader.setupUBO(programID, "WarpBlock", warpBO.getID(), GLSLShader.UBO.WARP);
    }

    /** Warp overlays with this scale's Box-Cox law, out to {@code outerRadius}. */
    public static void enable(MapScale scale, double outerRadius) {
        upload((float) scale.warpLambda(), (float) scale.warpLimb(), (float) outerRadius, 1);
    }

    /** Pass overlay geometry through untouched. */
    public static void disable() {
        upload(1, 0, 1, 0);
    }

    private static void upload(float lambda, float limb, float outerRadius, float enabled) {
        if (warpBO == null)
            return;
        buf.clear();
        buf.put(lambda).put(limb).put(outerRadius).put(enabled);
        buf.flip();
        warpBO.setBufferData(SIZE, buf);
    }

    private GLSLWarp() {}

}
