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
    outColor = sampleWarpedHpcColor(warpHpcXY());
}
