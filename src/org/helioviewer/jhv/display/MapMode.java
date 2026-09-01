package org.helioviewer.jhv.display;

// Orthographic mode renders directly in 3D, while non-orthographic modes project
// through an explicit map basis shared by rendering and mouse unprojection.
public enum MapMode {
    Orthographic,
    HPC,
    Latitudinal,
    RadialWarp,
    RectWarp;

    public double baseCameraWidth(Camera camera) {
        return switch (this) {
            case RadialWarp -> 1.1;
            case RectWarp -> 1.0;
            case Orthographic, HPC, Latitudinal -> camera.baseCameraWidth();
        };
    }

    public boolean usesWarpLambda() {
        return this == RadialWarp || this == RectWarp;
    }
}
