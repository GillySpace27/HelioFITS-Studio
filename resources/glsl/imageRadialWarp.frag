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
#endif
    outColor = sampleWarpedHpcColor(warpHpcXY());
}
