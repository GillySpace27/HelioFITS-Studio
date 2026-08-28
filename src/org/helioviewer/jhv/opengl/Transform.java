package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.math.Quat;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

public final class Transform {

    private static final float CLIP_NARROW = (float) (32 * Sun.Radius); // bit more than LASCO C3
    private static final float CLIP_WIDE = (float) (50 * Sun.MeanEarthDistance); // bit further than Pluto

    private static final FloatBuffer fb = BufferUtils.newFloatBuffer(16);
    private static final FloatBuffer mvp = BufferUtils.newFloatBuffer(16);
    private static final FloatBuffer inv = BufferUtils.newFloatBuffer(16);

    private static final Matrix4fStack proj = new Matrix4fStack(2);
    private static final Matrix4fStack view = new Matrix4fStack(3);
    private static final Matrix4f mul = new Matrix4f();
    private static final Matrix4f invTrans = new Matrix4f();
    private static final Quaternionf quat = new Quaternionf();

    private static int projDepth;
    private static int viewDepth;

    static int projDepth() {
        return projDepth;
    }

    static int viewDepth() {
        return viewDepth;
    }

    /**
     * Pop back to the given depths. Used by RenderGuard after a failure: the stacks are only two
     * and three deep, so a push left unpaired by a throw would exhaust them almost immediately.
     */
    static void unwindTo(int projTarget, int viewTarget) {
        while (projDepth > projTarget)
            popProjection();
        while (viewDepth > viewTarget)
            popView();
    }

    public static void pushProjection() {
        proj.pushMatrix();
        projDepth++;
    }

    public static void popProjection() {
        proj.popMatrix();
        projDepth--;
    }

    public static void pushView() {
        view.pushMatrix();
        viewDepth++;
    }

    public static void popView() {
        view.popMatrix();
        viewDepth--;
    }

    public static void setOrtho2DProjection(float left, float right, float bottom, float top) {
        proj.setOrtho2D(left, right, bottom, top);
    }

    public static void setIdentityView() {
        view.identity();
    }

    public static void rotateViewInverse(Quat q) {
        view.rotateAffine(quat.set((float) q.x, (float) q.y, (float) q.z, (float) -q.w));
    }

    /**
     * Uniformly scale the view.
     *
     * <p>Exists for geometry that cannot go through the vertex-stage warp. SDF text is the case
     * that forced it: the text shader has no warpCommon splice, so grid labels are drawn at
     * their raw positions while the lines they belong to are warped, leaving the labels bunched
     * near the origin. Scaling the view by the warp factor of the surface the labels sit on puts
     * them back on their lines.
     */
    public static void scaleView(double factor) {
        view.scale((float) factor);
    }

    public static void rotateView(Quat q) {
        view.rotateAffine(quat.set((float) q.x, (float) q.y, (float) q.z, (float) q.w));
    }

    static void ortho2D(double aspect, double width, double tx, double ty) {
        setup((float) (width * aspect), (float) width, -1, 1, (float) tx, (float) ty);
        cacheMVP();
    }

    static void ortho(double aspect, double width, double tx, double ty, Quat rotation) {
        float clip = width < 32 ? CLIP_NARROW : CLIP_WIDE;
        setup((float) (width * aspect), (float) width, -clip, clip, (float) tx, (float) ty);
        rotateView(rotation);
        cacheMVP();
    }

    private static void setup(float width, float height, float zNear, float zFar, float x, float y) {
        proj.setOrthoSymmetric(width, height, zNear, zFar);
        view.translation(x, y, 0);
        proj.invertOrtho(invTrans).translateLocal(-x, -y, 0).get(inv);
    }

    private static void cacheMVP() {
        proj.mulOrthoAffine(view, mul); // assumes ortho
        mul.get(mvp);
    }

    static FloatBuffer get() {
        if (projDepth == 0 && viewDepth == 0) {
            //System.out.println(">> hit");
            //Thread.dumpStack();
            return mvp;
        }

        proj.mulOrthoAffine(view, mul); // assumes ortho
        mul.get(fb);
        return fb;
    }

    static FloatBuffer getInverse() {
        return inv;
    }

    private Transform() {}
}
