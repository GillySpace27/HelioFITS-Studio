vec2 warpHpcXY() {
    vec2 w = getViewPosition();
    float viewRadius = length(w);
    float t = 2. * viewRadius;
    if (t > 1. || t == 0.)
        discard;

    float angle = atan(-w.x, w.y);
    if (angle < 0.)
        angle += TWOPI;
    clipNormalizedCoord(vec2(angle / TWOPI, t));

    float radialCoordinate = unwarpRadius(t);
    return (radialCoordinate / viewRadius) * w;
}

void main(void) {
    // Into a local first, not passed straight through as a call argument. On ANGLE D3D11
    // (Windows) the nested form reached sampleWarpedHpcColor as garbage, which drew the
    // helioradial imagery black (2026-11-04, helioradial-diag on PR #153: warpHpcXY painted
    // correctly on its own but not when read inside the callee; with the local, both
    // helioradial modes draw the AIA 171 disk on the Windows runner).
    vec2 hpcXY = warpHpcXY();
    outColor = sampleWarpedHpcColor(hpcXY);
}
