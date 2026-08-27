package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

/**
 * The geometry the warped projection is painted on: a unit grid in (position angle, warped
 * radius).
 *
 * <p>Deliberately parameter space rather than world space. Both the coronagraph surface and the
 * Box-Cox warp are applied in {@code warpSurface.vert}, so this mesh is built once at start-up
 * and never rebuilt: dragging the lambda slider or switching between the plane of sky and the
 * Thomson sphere costs a uniform update, not a geometry upload.
 *
 * <p>Sampling is uniform in <em>warped</em> radius, which is the axis that matters for
 * tessellation quality. Uniform spacing in physical radius would crowd the outer corona, where
 * the Box-Cox law compresses hardest, and starve the disk, which is the part anyone is looking
 * at.
 */
class WarpSurfaceMesh extends VAO1 {

    static final WarpSurfaceMesh mesh = new WarpSurfaceMesh();

    // Around the disk, and out along the radius. The radial gradient of the Box-Cox law is
    // steepest just beyond the limb, so that axis carries the artifacts; raise RADIAL first if
    // faceting shows. Costs 240 * 192 * 6 vertices at 16 bytes, about 4.4 MB, uploaded once.
    private static final int ANGULAR = 240;
    private static final int RADIAL = 192;
    private static final int VERTEX_COUNT = ANGULAR * RADIAL * 6;

    private WarpSurfaceMesh() {
        super(false, new VAA[]{new VAA(0, 4, false, 0, 0, 0)});
    }

    void render() {
        bind();
        GL.glDrawArrays(GL.TRIANGLES, 0, VERTEX_COUNT);
    }

    @Override
    public void init() {
        super.init();
        FloatBuffer buf = BufferUtils.newFloatBuffer(VERTEX_COUNT * 4);
        for (int i = 0; i < ANGULAR; i++) {
            float a0 = i / (float) ANGULAR;
            float a1 = (i + 1) / (float) ANGULAR;
            for (int j = 0; j < RADIAL; j++) {
                float t0 = j / (float) RADIAL;
                float t1 = (j + 1) / (float) RADIAL;
                // Two triangles per cell. Wound consistently; the renderer culls back faces, and
                // a warped surface seen from behind after a rotation still needs to draw, so the
                // caller disables culling rather than this trying to be two-sided.
                put(buf, a0, t0);
                put(buf, a1, t0);
                put(buf, a1, t1);
                put(buf, a0, t0);
                put(buf, a1, t1);
                put(buf, a0, t1);
            }
        }
        buf.flip();
        vbo.setBufferData(VERTEX_COUNT * 16, buf); // capacity in bytes: 4 floats per vertex
    }

    private static void put(FloatBuffer buf, float angle, float radius) {
        buf.put(angle).put(radius).put(0).put(1);
    }

}
