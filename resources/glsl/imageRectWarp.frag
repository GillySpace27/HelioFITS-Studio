vec2 warpHpcXY() {
    vec2 mapPos = getNormalizedMapPos();

    float angle = mapPos.x * TWOPI;
    float radialCoordinate = unwarpRadius(mapPos.y);
    return radialCoordinate * vec2(-sin(angle), cos(angle));
}

void main(void) {
#ifdef HFS_DEBUG_WARP
#if HFS_DEBUG_WARP == 7
    // Diagnostic stage 7: solid green wherever this program is rasterized, before any discard.
    outColor = vec4(0., 1., 0., 1.);
    return;
#endif
#endif
    // Into a local first, not passed straight through as a call argument. On ANGLE D3D11 the
    // nested form reached sampleWarpedHpcColor as garbage (helioradial-diag run 41787078939:
    // warpHpcXY painted correctly on its own, stage 10 and 11, but read inside the callee,
    // stage 9, it was not), which is what drew the helioradial imagery black on Windows.
    vec2 hpcXY = warpHpcXY();
    outColor = sampleWarpedHpcColor(hpcXY);
}
