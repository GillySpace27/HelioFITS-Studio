package org.helioviewer.jhv.display;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.opengl.GLSLSolarShader;

// Orthographic mode renders directly in 3D, while non-orthographic modes project
// through an explicit map basis shared by rendering and mouse unprojection.
public enum MapMode {
    Orthographic(GLSLSolarShader.ortho),
    HPC(GLSLSolarShader.hpc),
    Latitudinal(GLSLSolarShader.lati),
    RadialWarp(GLSLSolarShader.warpSurface),
    RectWarp(GLSLSolarShader.rectWarp);

    public final GLSLSolarShader shader;

    public double baseCameraWidth(Camera camera) {
        return switch (this) {
            // RadialWarp is real geometry now, so its extent is physical (the warp's outer
            // radius) rather than a fixed normalized disk.
            case RadialWarp -> camera.baseCameraWidth();
            case RectWarp -> 1.0;
            case Orthographic, HPC, Latitudinal -> camera.baseCameraWidth();
        };
    }

    /**
     * Whether this mode draws a rotated 3D scene rather than a flat projected map.
     *
     * <p>RadialWarp joins Orthographic here because its warp is now geometry: the imagery is a
     * surface mesh, so it shares the ortho path's rotated MVP, depth buffer and world-space
     * layer rendering. RectWarp, HPC and Latitudinal stay flat.
     */
    public boolean rendersIn3D() {
        return this == Orthographic || this == RadialWarp;
    }

    public boolean usesWarpLambda() {
        return this == RadialWarp || this == RectWarp;
    }

    MapMode(GLSLSolarShader _shader) {
        shader = _shader;
    }

    public MapView createMapView(Camera camera, Position viewpoint, GridType gridType, MapScale[] scales) {
        return MapView.create(camera, viewpoint, gridType, this, scales);
    }
}
