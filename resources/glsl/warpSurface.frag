// Samples the source image for the warped surface mesh.
//
// Identical in substance to solarRadialWarp.frag, with one difference that is the whole point of
// this phase: the helioprojective direction comes from an interpolated world position supplied by
// the vertex stage, not from reconstructing a screen position through inverseMVP. That is what
// lets the geometry be rotated -- a screen-space inverse map has no surface for a camera to
// orbit, so it can only ever face the viewer.

in vec3 vWorld;
in float vSurfaceExcess;
in float vCropExcess;

// Twin of solarRadialWarp.frag's helper: CAR/CEA sample the solar surface along the sight line,
// everything else keeps the plane-of-sky path. On this mesh the vertex stage already places
// on-disk vertices on the unit sphere, so re-deriving the surface point from the sight line
// agrees with the geometry there and correctly finds no surface beyond the limb, where the mesh
// has left the sphere for the plane-of-sky/Thomson surface and a synoptic map has nothing to say.
vec2 sampleWarpTexcoord(const WCS wcs, const ProjectionParams projection, const vec2 helioprojective, const vec2 hpcXY, const float dt, const float[6] PV, out float enhancementFactor) {
    if (isSurfaceMapProjection(projection)) {
        vec2 surfaceTexCoord;
        if (!sampleSurfaceMapTexcoord(helioprojective, wcs, projection, PV, surfaceTexCoord))
            discard;
        enhancementFactor = 1.;
        return surfaceTexCoord;
    }
    return sampleHpcTexcoord(wcs, projection, helioprojective, hpcXY, dt, PV, enhancementFactor);
}

void main(void) {
    // Past the observer's own distance the Thomson sphere has no surface, so there is nowhere to
    // put this brightness. Dropped rather than drawn flat: the flat version is indistinguishable
    // from corona correctly placed on a plane, which makes it a picture that lies. Throwing the
    // outer field away is a real cost, and it is the honest one.
    if (vSurfaceExcess > 1.)
        discard;

    // The Edge crop: cut to a circle at the chosen radius, leaving the mapping and the camera
    // alone. That is the whole difference between this and the Zoom slider beside it.
    if (vCropExcess > 1.)
        discard;

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
    vec2 texCoord = sampleWarpTexcoord(wcs[0], projection[0], helioprojective, hpcXY, wcs[0].deltaT, pv0, enhancementFactor);
    if (!diffMode) {
        color = getColor(texCoord, texCoord, enhancementFactor);
    } else {
        vec2 diffHelioprojective = worldToHelioprojective(vWorld, projection[1].observerDistance);
        float diffEnhancementFactor;
        vec2 diffTexCoord = sampleWarpTexcoord(wcs[1], projection[1], diffHelioprojective, hpcXY, wcs[1].deltaT, pv1, diffEnhancementFactor);
        color = getColor(texCoord, diffTexCoord, max(enhancementFactor, diffEnhancementFactor));
    }
    outColor = color;
}
