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
#ifdef HFS_DEBUG_WARP
#if HFS_DEBUG_WARP == 7
    // Diagnostic stage 7: solid green wherever this program is rasterized, before any discard.
    outColor = vec4(0., 1., 0., 1.);
    return;
#endif
#if HFS_DEBUG_WARP == 10
    // Stage 10: the pieces of warpHpcXY, no sampling. red = t, green = 2 * viewRadius (equals red
    // when right), blue = unwarpRadius(t) / 4.
    {
        vec2 w10 = getViewPosition();
        float r10 = length(w10);
        float t10 = 2. * r10;
        if (t10 > 1.)
            discard;
        outColor = vec4(clamp(t10, 0., 1.), clamp(2. * r10, 0., 1.), clamp(unwarpRadius(t10) / 4., 0., 1.), 1.);
        return;
    }
#elif HFS_DEBUG_WARP == 11
    // Stage 11: warpHpcXY itself. red = length / 4, green and blue = x and y / 4 + 0.5.
    {
        vec2 h11 = warpHpcXY();
        outColor = vec4(clamp(length(h11) / 4., 0., 1.), clamp(h11 / 4. + .5, 0., 1.), 1.);
        return;
    }
#endif
#endif
    // Into a local first, not passed straight through as a call argument. On ANGLE D3D11 the
    // nested form reached sampleWarpedHpcColor as garbage (helioradial-diag run 41787078939:
    // warpHpcXY painted correctly on its own, stage 10 and 11, but read inside the callee,
    // stage 9, it was not), which is what drew the helioradial imagery black on Windows.
    vec2 hpcXY = warpHpcXY();
    outColor = sampleWarpedHpcColor(hpcXY);
}
