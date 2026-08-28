#version 300 es

// The radial warp as geometry rather than a screen-space inverse map.
//
// The mesh handed to this stage is a fixed unit grid in (position angle, warped radius) and
// never changes: the surface model and the Box-Cox lambda are uniforms, so dragging the lambda
// slider or switching the coronagraph surface costs a uniform update and no geometry rebuild.
//
// Vertex.x is the normalized position angle in [0, 1]; Vertex.y is the normalized warped radius
// in [0, 1]. Both the physical surface point (for sampling) and the warped display point (for
// placement) are derived here.
//
// Twin of display/WarpGeometry.java and display/SurfaceModel.java. Keep the three in step.

layout(location = 0) in vec4 Vertex;

out vec2 normalizedScreenpos; // solarCommon.frag declares this; it must be fed to link
out vec3 vWorld;              // the UNWARPED surface point, which is what the fragment samples by

uniform mat4 ModelViewProjectionMatrix;
uniform float observerDistance;
uniform float surfaceModel; // 0 = plane of sky, 1 = Thomson sphere; see SurfaceModel

// Must match the ScreenBlock in solarCommon.frag member for member.
layout(std140) uniform ScreenBlock {
    mat4 inverseMVP;
    float iaspect;
    float xStart;
    float xStop;
    float yStart;
    float yStop;
    float lambda;
    float limb;
} screen;

const float TWO_PI = 6.2831853;
const float SURFACE_THOMSON_SPHERE = 1.;

float limbPosition(const float outerRadius) {
    return screen.limb > 0. ? screen.limb : 1. / outerRadius;
}

// Normalized warp radius back to physical solar radii. Twin of unwarpRadius() in
// solarCommon.frag and of MapScale.BoxCoxRadialScale.toMapY.
float unwarpRadius(const float normalizedRadius) {
    float outerRadius = screen.yStop;
    float limbPos = limbPosition(outerRadius);
    if (outerRadius <= 1. || normalizedRadius <= limbPos)
        return normalizedRadius / limbPos;

    float u = (normalizedRadius - limbPos) / (1. - limbPos);
    float lambda = screen.lambda;
    return lambda == 0.
            ? pow(outerRadius, u)
            : pow(1. + u * (pow(outerRadius, lambda) - 1.), 1. / lambda);
}

void main(void) {
    float outerRadius = screen.yStop;
    float positionAngle = Vertex.x * TWO_PI;
    float t = Vertex.y; // normalized warped radius

    // Physical heliocentric distance this ring of the mesh stands for.
    float radius = unwarpRadius(t);

    vec3 warped;
    if (radius <= 1.) {
        // ON DISK: the photosphere, and it is a sphere. Unlike the corona's depth, which is a
        // placement model, this is simply where the emission comes from, so the disk is
        // deprojected onto the real solar surface: impact parameter rho = radius, and the near
        // hemisphere carries the rest. Rotating then shows a sphere with a limb, instead of a
        // flat picture foreshortening into an ellipse.
        //
        // Every point here has |p| = 1, so the whole sphere shares ONE warp factor and stays a
        // sphere rather than being sheared into a cone. That factor is the warped position of
        // the limb, which is also where the corona branch below starts, so the two meet exactly
        // at r = 1. The surface NORMAL jumps there (sphere against a near-flat corona) and that
        // discontinuity is honest: it is the boundary between a measured surface and a modelled
        // one, and it is invisible face-on because both project to the same circle.
        float z = sqrt(max(0., 1. - radius * radius));
        vWorld = vec3(-radius * sin(positionAngle), radius * cos(positionAngle), z);
        warped = vWorld * (limbPosition(outerRadius) * outerRadius);
    } else {
        // OFF DISK: a placement model, not a measurement. On the Thomson sphere the surface
        // curves toward the observer by z = r^2 / D; the plane of sky stays at z = 0.
        float depth = 0.;
        if (surfaceModel == SURFACE_THOMSON_SPHERE && observerDistance > 0.)
            depth = min(radius, observerDistance) * min(radius, observerDistance) / observerDistance;
        // rho^2 = r^2 - z^2: the in-plane radius shrinks as the surface curves away from the plane.
        float rho = sqrt(max(0., radius * radius - depth * depth));

        // Polar basis: 0 at north, increasing anti-clockwise. Matches math/PolarBasis.java.
        vWorld = vec3(-rho * sin(positionAngle), rho * cos(positionAngle), depth);

        // The displayed point keeps that direction and takes the warped radius. Because the mesh
        // is parameterised in warped space already, the warped distance is simply t * outerRadius
        // -- no need to warp the physical radius back again.
        float len = length(vWorld);
        warped = len > 0. ? vWorld * (t * outerRadius / len) : vec3(0.);
    }

    vec4 clip = ModelViewProjectionMatrix * vec4(warped, 1.);
    normalizedScreenpos = clip.xy / clip.w;
    gl_Position = clip;
}
