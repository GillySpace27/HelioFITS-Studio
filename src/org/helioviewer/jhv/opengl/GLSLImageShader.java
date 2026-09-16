package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.display.SurfaceTransition;
import org.helioviewer.jhv.math.Mat2;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.wcs.WcsHeader;

public final class GLSLImageShader extends GLSLScreenShader {

    private static final String COMMON_FRAGMENT = "/glsl/imageCommon.frag";

    private static final GLSLImageShader ortho = new GLSLImageShader("/glsl/imageOrtho.frag");
    private static final GLSLImageShader hpc = new GLSLImageShader("/glsl/imageHpc.frag");
    private static final GLSLImageShader lati = new GLSLImageShader("/glsl/imageLati.frag");
    private static final GLSLImageShader radialWarp = new GLSLImageShader("/glsl/imageRadialWarp.frag");
    private static final GLSLImageShader rectWarp = new GLSLImageShader("/glsl/imageRectWarp.frag");
    // The observer's sky, aimed anywhere rather than centred on the Sun. Reduces its page to a
    // helioprojective direction and then takes the ordinary sight-line path.
    private static final GLSLImageShader sky = new GLSLImageShader("/glsl/solarSky.frag");
    // The colour-table legend: getColor() over a one-row ramp, so the bar is drawn by the code
    // that draws the picture and cannot disagree with it. See solarLegend.frag.
    private static final GLSLImageShader legend = new GLSLImageShader("/glsl/solarLegend.frag");
    // Draws a mesh rather than a full-screen quad: the warp is geometry here, so the scene can
    // be rotated and overlays can be registered against it. See warpSurface.vert.
    private static final GLSLImageShader warpSurface = new GLSLImageShader("/glsl/warpSurface.vert", "/glsl/warpSurface.frag");

    private static final GLSLImageShader[] imagePrograms = {ortho, hpc, lati, radialWarp, rectWarp, sky, legend, warpSurface};

    // The program useImage() last selected, which is what the bind and draw calls after it act on.
    // It tracks GL's own bound-program state rather than inventing state of its own, which is what
    // lets a caller select once and then bind and draw in separate steps.
    private static GLSLImageShader current = ortho;

    private int pv0Ref;
    private int pv1Ref;
    // Only the sky program declares these, and only the warp surface declares the four below.
    // glUniform on -1 is a documented no-op, so the binding calls stay safe on any program.
    private int skyLookRef;
    private int skyWarpRef;
    private int mvpRef;
    private int observerDistanceRef;
    private int surfaceModelRef;
    private int cropRadiusRef;

    private static final float[] skyLookBuf = new float[3];
    private static final float[] skyWarpBuf = new float[3];

    private GLSLImageShader(String fragment) {
        super(COMMON_FRAGMENT, fragment);
    }

    private GLSLImageShader(String vertex, String fragment) {
        super(vertex, new String[]{COMMON_FRAGMENT, fragment});
    }

    private static final UniformBufferObject imageBuffer = new UniformBufferObject(UniformBlockLayout.IMAGE, GL.STREAM_DRAW);
    private static final UniformBufferObject displayBuffer = new UniformBufferObject(UniformBlockLayout.DISPLAY, GL.STREAM_DRAW);

    static void init() {
        try {
            imageBuffer.init();
            displayBuffer.init();
            imageBuffer.bind();
            displayBuffer.bind();
            for (GLSLImageShader program : imagePrograms)
                program._init();
            WarpSurfaceMesh.mesh.init();
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    @Override
    protected void initUniforms(int id) {
        super.initUniforms(id);
        // Optional, not required: the legend program draws the colour table alone and names no PV
        // array, so GLSL strips the uniform and there is no location to find. A -1 location is
        // ignored by glUniform, which is what the image programs above rely on staying true.
        pv0Ref = optionalUniform(id, "pv0");
        pv1Ref = optionalUniform(id, "pv1");
        skyLookRef = optionalUniform(id, "skyLook");
        skyWarpRef = optionalUniform(id, "skyWarp");
        mvpRef = optionalUniform(id, "ModelViewProjectionMatrix");
        observerDistanceRef = optionalUniform(id, "observerDistance");
        surfaceModelRef = optionalUniform(id, "surfaceModel");
        cropRadiusRef = optionalUniform(id, "cropRadius");
        setupUniformBlock(id, UniformBlockLayout.IMAGE);
        setupUniformBlock(id, UniformBlockLayout.DISPLAY);
        setTextureUnit(id, "image", GLTexture.Unit.ZERO);
        setTextureUnit(id, "lut", GLTexture.Unit.ONE);
        setTextureUnit(id, "diffImage", GLTexture.Unit.TWO);
        setTextureUnit(id, "mask", GLTexture.Unit.THREE);
    }

    static void dispose() {
        WarpSurfaceMesh.mesh.dispose();
        for (GLSLImageShader program : imagePrograms)
            program._dispose();
        imageBuffer.dispose();
        displayBuffer.dispose();
    }

    public static void bindImages(
            Region r0, Mat2 planeToImage0, float[] crval0, WcsHeader wcs0,
            float observerDistance0, float deltaT0, Quat cameraDiff0, Quat sourceView0,
            Region r1, Mat2 planeToImage1, float[] crval1, WcsHeader wcs1,
            float observerDistance1, float deltaT1, Quat cameraDiff1, Quat sourceView1) {
        FloatBuffer values = imageBuffer.begin();
        putImage(values, r0, planeToImage0, crval0, wcs0, observerDistance0, deltaT0, cameraDiff0, sourceView0);
        putImage(values, r1, planeToImage1, crval1, wcs1, observerDistance1, deltaT1, cameraDiff1, sourceView1);

        imageBuffer.uploadIfChanged();
    }

    private static void putImage(FloatBuffer values, Region r, Mat2 planeToImage, float[] crval, WcsHeader wcs,
                                 float observerDistance, float deltaT, Quat cameraDiff, Quat sourceView) {
        values.put(r.glslArray);
        planeToImage.setFloatBuffer(values);
        values.put(crval).put((float) wcs.unitsPerRad).put(wcs.projection.ordinal());
        values.put((float) wcs.zpnUpperEta).put(observerDistance).put(deltaT).put(0);
        cameraDiff.setFloatBuffer(values);
        sourceView.setFloatBuffer(values);
    }

    /**
     * Must mirror the DisplayBlock member order in imageCommon.frag float for float, and
     * UniformBlockLayout.DISPLAY must carry that block's std140 size.
     */
    static void bindDisplay(float[] color,
                            float shWidth, float shHeight, float shWeight, int isDiff,
                            float bOffset, float bScale,
                            float upsilonLow, float upsilonHigh,
                            float userSectorCenter, float userSectorHalfWidth, float metadataSectorCenter, float metadataSectorHalfWidth,
                            float cutOffX, float cutOffY, float cutOffVal, int calculateDepth,
                            float innerRadius, float outerRadius,
                            float slitLeft, float slitRight,
                            float enhanced,
                            float indexed, float skipDither, float showClipping, float rawOutput,
                            float hdrGain, float hdrMode, float hdrKnee, float hdrInRange) {
        FloatBuffer values = displayBuffer.begin();
        values.put(color);
        values.put(shWidth).put(shHeight).put(shWeight).put(isDiff);
        values.put(bOffset).put(bScale).put(upsilonLow).put(upsilonHigh);
        values.put(userSectorCenter).put(userSectorHalfWidth).put(metadataSectorCenter).put(metadataSectorHalfWidth);
        values.put(cutOffX).put(cutOffY).put(cutOffVal).put(calculateDepth);
        values.put(innerRadius).put(outerRadius).put(slitLeft).put(slitRight);
        values.put(enhanced).put(indexed).put(skipDither).put(showClipping);
        values.put(rawOutput).put(hdrGain).put(hdrMode).put(hdrKnee);
        values.put(hdrInRange).put(0).put(0).put(0); // std140 rounding
        displayBuffer.uploadIfChanged();
    }

    /**
     * The program this mode draws with.
     *
     * <p>Helioradial has two implementations. Flat, it is a fragment-space inverse map on a
     * full-screen quad (imageRadialWarp.frag), which is the original and the one the published
     * figures come from. In 3D it is a surface mesh. They are not interchangeable: the mesh shader
     * expects a rotated MVP and a per-vertex world position, so the render path and the shader
     * have to be switched together. MapMode.usesWarpSurface() is the single place that decides it.
     */
    private static GLSLImageShader shaderFor(MapMode mode) {
        if (mode.usesWarpSurface())
            return warpSurface;
        return switch (mode) {
            case Orthographic -> ortho;
            case HPC -> hpc;
            case Latitudinal -> lati;
            case Helioradial -> radialWarp;
            case HelioradialUnrolled -> rectWarp;
            case ObserverSky -> sky;
        };
    }

    /**
     * Select and bind the program for this projection. Every other call below acts on whatever
     * this last selected, so a caller selects once and then binds and draws in separate steps.
     */
    public static void useImage(MapMode mode, float[] pv0, float[] pv1) {
        current = shaderFor(mode);
        current.use();
        GL.glUniform1fv(current.pv0Ref, pv0);
        GL.glUniform1fv(current.pv1Ref, pv1);
    }

    /**
     * Where the observer-sky view is aimed, and in which projection.
     *
     * <p>Only the sky program has this uniform; every other program returns -1 for it and
     * glUniform on -1 is a no-op, so this is safe to call unconditionally.
     */
    public static void bindSkyLook(float lon, float lat, float projectionCode) {
        skyLookBuf[0] = lon;
        skyLookBuf[1] = lat;
        skyLookBuf[2] = projectionCode;
        GL.glUniform3fv(current.skyLookRef, skyLookBuf);
    }

    /** The radial scale the observer's sky is composed with; a non-positive outer radius turns it off. */
    public static void bindSkyWarp(float outerRadius, float limb, float lambda) {
        skyWarpBuf[0] = outerRadius;
        skyWarpBuf[1] = limb;
        skyWarpBuf[2] = lambda;
        GL.glUniform3fv(current.skyWarpRef, skyWarpBuf);
    }

    /** Draw the full-screen quad, which is every projection except the warped surface. */
    public static void drawImage() {
        current.draw();
    }

    /**
     * Draw the warped surface mesh.
     *
     * <p>The caller owns the view transform: the mesh is built in the observer's frame, so the
     * viewpoint rotation carried by the shared view matrix has to be taken back off around this
     * call, leaving the drag rotation alone.
     *
     * @param surfaceModel the model being moved toward. The value actually drawn is
     *                     {@link SurfaceTransition#blend()}, because a morph in progress is a real
     *                     surface partway between the two and drawing the destination instead
     *                     would skip the movement.
     */
    public static void renderWarpSurface(double observerDistance, SurfaceModel surfaceModel) {
        GL.glUniformMatrix4fv(current.mvpRef, false, Transform.get());
        GL.glUniform1f(current.observerDistanceRef, (float) observerDistance);
        // The family parameter k = D / L rather than a flag: 0 is plane of sky, 1 the Thomson
        // sphere, 1/2 the celestial sphere, and every value between is a real sphere through the
        // Sun, which is what makes the morph a movement rather than a dissolve.
        GL.glUniform1f(current.surfaceModelRef, (float) SurfaceTransition.blend());
        // The user's Crop, NOT the field the warp is normalized over. Zero when the crop is on
        // auto, which is no crop at all rather than a crop at the full field.
        GL.glUniform1f(current.cropRadiusRef, (float) org.helioviewer.jhv.display.Display.getWarpOuterRadius());
        // The surface is single-sided but orbitable: once the camera swings past its edge the
        // back faces are what you are looking at, so culling them would make the imagery vanish
        // halfway through a rotation. GLRenderer.init enables back-face culling globally.
        GL.glDisable(GL.CULL_FACE);
        WarpSurfaceMesh.mesh.render();
        GL.glEnable(GL.CULL_FACE);
    }

    /**
     * The legend is drawn by the picture's own pipeline rather than painted from the table's
     * entries, so Colorbar drives it in two steps: select this program, bind the layer's display
     * state and colour table through GLSLImage, then draw into the bar's viewport.
     */
    public static void useLegend() {
        current = legend;
        legend.use();
    }

    public static void drawLegend() {
        legend.draw();
    }

}
