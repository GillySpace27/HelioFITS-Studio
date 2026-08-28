// Samples the source image for the warped surface mesh.
//
// Identical in substance to solarRadialWarp.frag, with one difference that is the whole point of
// this phase: the helioprojective direction comes from an interpolated world position supplied by
// the vertex stage, not from reconstructing a screen position through inverseMVP. That is what
// lets the geometry be rotated -- a screen-space inverse map has no surface for a camera to
// orbit, so it can only ever face the viewer.

in vec3 vWorld;

void main(void) {
    vec4 color;
    float observerDistance = projection[0].observerDistance;

    // Sampled straight from the mesh position, with NO camera-rotation compensation, and that
    // is deliberate. The surface is a physical placement of the observed brightness: the mesh
    // sits still in the observer frame of the image it carries, and dragging orbits the camera
    // around it. The texture is therefore glued to the surface, exactly as it would be on any
    // other textured object. Applying wcs.cameraDiff here (as solarOrtho.frag must, because it
    // reconstructs its hit point from screen space) would counter-rotate the texture against
    // the mesh as soon as the view was dragged.
    vec3 sampleWorld = vWorld;

    // The surface point already carries its depth, so ask for its true helioprojective direction
    // rather than assuming the plane of sky the way hpcXYToHelioprojective() does.
    vec2 helioprojective = worldToHelioprojective(sampleWorld, observerDistance);

    // hpcXY is still wanted for clipping and the off-limb enhancement factor, both of which are
    // defined on the plane. Project the surface point onto it along the line of sight.
    float zeta = max(observerDistance - sampleWorld.z, 1e-6);
    vec2 hpcXY = (observerDistance / zeta) * sampleWorld.xy;

    float enhancementFactor;
    bool diffMode = display.isDiff != NODIFFERENCE;
    clipHpcGeometry(hpcXY);
    vec2 texCoord = sampleHpcTexcoord(wcs[0], projection[0], helioprojective, hpcXY, wcs[0].deltaT, pv0, enhancementFactor);
    if (!diffMode) {
        color = getColor(texCoord, texCoord, enhancementFactor);
    } else {
        vec2 diffHelioprojective = worldToHelioprojective(vWorld, projection[1].observerDistance);
        float diffEnhancementFactor;
        vec2 diffTexCoord = sampleHpcTexcoord(wcs[1], projection[1], diffHelioprojective, hpcXY, wcs[1].deltaT, pv1, diffEnhancementFactor);
        color = getColor(texCoord, diffTexCoord, max(enhancementFactor, diffEnhancementFactor));
    }
    outColor = color;
}
