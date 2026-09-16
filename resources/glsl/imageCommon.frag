#version 300 es

precision highp float;

#define NODIFFERENCE 0.
#define PI 3.1415926535897932384626433832795
#define HALFPI (PI / 2.)
#define TWOPI  (2. * PI)

#define BOOST 1. / (0.2 * 2.)

const float WCS_PROJECTION_TAN = 0.;
const float WCS_PROJECTION_ARC = 1.;
const float WCS_PROJECTION_AZP = 2.;
const float WCS_PROJECTION_ZPN = 3.;
const float WCS_PROJECTION_CAR = 4.;
const float WCS_PROJECTION_CEA = 5.;

out vec4 outColor;
in vec2 normalizedScreenpos;

struct Image {
    vec4 rect;
    vec4 planeToImage; // row-major 2x2 matrix
    vec2 crval;
    float planeUnitsPerRadian;
    float projectionCode;
    float zpnUpperEta;
    float observerDistance;
    float deltaT;
    float padding;
    vec4 cameraDiff;
    vec4 sourceViewQuat;
};

layout(std140) uniform ImageBlock {
    Image images[2];
};

layout(std140) uniform ScreenBlock {
    mat4 inverseMVP;
    vec4 mapBounds; // xStart, xStop, yStart, yStop
    vec2 latiOrigin;
    float iaspect;
    float lambda;
    // Fraction of the radial axis occupied by the linear disk. Zero means "use 1 / outerRadius",
    // the geometric limb; the Box-Cox scale supplies a non-trivial value so the disk keeps a
    // usable share of the page as lambda compresses the corona. See MapScale.BoxCoxRadialScale.
    float limb;
    float padding0;
    float padding1;
    float padding2;
} screen;

layout(std140) uniform DisplayBlock {
    vec4 color;
    vec3 sharpen;
    float isDiff;
    vec2 brightness;
    vec2 upsilon;
    vec2 userSector;
    vec2 metadataSector;
    vec3 cutOff;
    float calculateDepth;
    vec2 radii;
    vec2 slit;
    float enhanced;
    // Non-zero for index-coded categorical images, whose pixel value selects a LUT entry rather
    // than a position on a ramp. The Java-side put() sequence (GLSLImageShader.bindDisplay) must
    // mirror this member order byte-for-byte, and UniformBlockLayout.DISPLAY must carry this
    // block's std140 size rounded up to a multiple of 4 floats.
    float indexed;
    // Non-zero while capturing to a high-bit-depth destination. Dither exists to hide 8-bit
    // banding; writing it into a 16-bit file would just be recording the noise.
    float skipDither;
    // Non-zero to paint clipped pixels in flag colours instead of their LUT colour.
    float showClipping;
    // Non-zero while the export reads a layer as numbers: the value that would index the colour
    // table is written out as is, grey, alpha 1.
    float rawOutput;
    // Multiplies an image layer's RGB after the colour table, on the EDR canvas: 1 means
    // interface white, the screen's headroom means its peak. Alpha is never scaled.
    float hdrGain;
    // How the gain is applied. The knee modes are driven by the DATA value that indexed the
    // colour table, not by how bright the table's colour happens to be: a table that lives
    // near white (PUNCH) would otherwise put most of its field over white, and a saturated one
    // (LASCO blue) would go neon. Only the brightest data gets the headroom.
    //   0  linear:    every pixel's colour scaled by the gain;
    //   1  hard knee: unchanged up to hdrKnee of the data range, then expansion rising on a
    //                 straight line to the gain at the top;
    //   2  soft knee: as 1 with a curve that leaves the knee flat, and the colour rolling
    //                 toward white as it climbs, which is what bright light looks like;
    //   3  beyond range: the display range is the SDR picture, untouched; data ABOVE its top
    //                 (the texture keeps them, linear in physical units past 1) shine over
    //                 white in proportion, rolling to white. Nothing inside the range moves,
    //                 and what used to be a flat plateau is graded again;
    //   4  uniform:   CIE lightness runs in a straight line from black to the display's peak
    //                 over the whole range, in place of the table's own lightness. A gain of 2
    //                 in light is only ~30 L*, so the linear modes leave the top of a table
    //                 within a few percent of white; this is the mode where the legend reads
    //                 as one even ramp.
    float hdrMode;
    float hdrKnee;
    // Uniform only: the share of the headroom spent inside the display range rather than above it.
    float hdrInRange;
    float displayPadding0;
    float displayPadding1;
    float displayPadding2;
} display;

uniform sampler2D image;
uniform sampler2D diffImage;
uniform sampler2D lut;
uniform sampler2D mask;

uniform float pv0[6]; // kept as plain uniforms for simple indexed access
uniform float pv1[6];

#define BLUR_TAP_COUNT (3 * 3)
// float[] bc = { 0.06136, 0.24477, 0.38774, 0.24477, 0.06136 }
// https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/
const float[] bc = float[](.30613, .38774, .30613);
const float[] blurKernel = float[](
    bc[0] * bc[0], bc[0] * bc[1], bc[0] * bc[2],
    bc[1] * bc[0], bc[1] * bc[1], bc[1] * bc[2],
    bc[2] * bc[0], bc[2] * bc[1], bc[2] * bc[2]
);

const float[] bo = float[](-1.2004377, 0., 1.2004377);
const vec2[] blurOffset = vec2[](
    vec2(bo[0], bo[0]), vec2(bo[1], bo[0]), vec2(bo[2], bo[0]),
    vec2(bo[0], bo[1]), vec2(bo[1], bo[1]), vec2(bo[2], bo[1]),
    vec2(bo[0], bo[2]), vec2(bo[1], bo[2]), vec2(bo[2], bo[2])
);

// https://shader-tutorial.dev/advanced/color-banding-dithering/
const float NOISE_GRANULARITY = 1. / 255.;
const vec2 nvec = vec2(12.9898, 78.233);

float dither(const vec2 coord) {
    float random = fract(sin(dot(coord, nvec)) * 43758.5453);
    return mix(-NOISE_GRANULARITY, NOISE_GRANULARITY, random);
}

float fetch(const sampler2D tex, const vec2 coord, const vec2 bright) {
    return texture(tex, coord).r * bright.y + bright.x;
}

vec4 getColor(const vec2 texcoord, const vec2 difftexcoord, const float factor) {
    if (texture(mask, texcoord).r == 0.)
        discard;

    vec2 brightness = display.brightness;
    if (display.enhanced != 0. && factor != 1.)
        brightness.y *= pow(factor, display.enhanced);

    float value;
    bool diffMode = display.isDiff != NODIFFERENCE;
    if (!diffMode) {
        value = fetch(image, texcoord, brightness);
    } else {
        value = fetch(image, texcoord, brightness) - fetch(diffImage, difftexcoord, brightness);
        value = value * BOOST + 0.5;
    }

    vec2 sharpenStep = display.sharpen.xy;
    float sharpenMix = display.sharpen.z;
    if (sharpenMix != 0.) {
        float blurredValue = 0.;
        if (!diffMode) {
            for (int i = 0; i < BLUR_TAP_COUNT; i++) {
                vec2 offset = blurOffset[i] * sharpenStep;
                blurredValue += fetch(image, texcoord + offset, brightness) * blurKernel[i];
            }
        } else {
            for (int i = 0; i < BLUR_TAP_COUNT; i++) {
                vec2 offset = blurOffset[i] * sharpenStep;
                blurredValue += (fetch(image, texcoord + offset, brightness) - fetch(diffImage, difftexcoord + offset, brightness)) * blurKernel[i];
            }
            blurredValue = blurredValue * BOOST + 0.5;
        }
        value = mix(value, blurredValue, sharpenMix);
    }

    if (display.upsilon.x != 1. || display.upsilon.y != 1.) {
        // Two-sided gamma about the median (Gilly & DeForest Eq. 2): upsilonLow and
        // upsilonHigh independently set the curvature below and above I = 0.5.
        //
        // The curve is only defined on [0, 1] (above 1 the second branch's base 2 - 2v goes
        // negative and pow returns NaN), so it is applied to the part of the value that lies
        // there and whatever lies outside keeps its distance from the end it passed. This used to
        // clamp instead, which pinned everything at and above 1 to exactly 1: with RHEF on, the
        // whole over-range section of the legend went flat and every over-range pixel in the
        // picture rendered as if it were at the top of the range. The curve reaches 1 at v = 1,
        // so adding the excess back is continuous there.
        float over = max(value - 1., 0.), under = min(value, 0.);
        float v = clamp(value, 0., 1.);
        v = v < .5 ? .5 * pow(2. * v, display.upsilon.x) : 1. - .5 * pow(2. - 2. * v, display.upsilon.y);
        value = v + over + under;
    }

    // A data export wants the number, not the colour table's 8-bit rendering of it (LINEAR
    // sampling of 256 entries at texel-edge coordinates is an affine map with a 0.4 percent
    // gain, which is fine for a picture and a defect in a data channel).
    if (display.rawOutput != 0.)
        return vec4(value, value, value, 1.);

    // Clipping flags, tested BEFORE the dither so a +/-1/255 nudge is never reported as
    // clipping. This is the only place the transfer function's range is knowable: fetch()
    // applies Levels and the response factor without clamping, and the LUT texture is
    // CLAMP_TO_EDGE, so everything at or past the ends silently renders as the end colour.
    // Magenta and green because no solar colour table contains either.
    // Skipped for categorical layers, where the value is an index and "range" means nothing.
    // Strictly outside, not merely at the end. A pixel AT the top of the range has lost nothing;
    // a pixel pushed PAST it has. The difference is the whole diagnostic, and >= / <= got it
    // wrong twice over:
    //
    //   RHEF's output is a rank, so every annulus legitimately contains a pixel at exactly 0 and
    //   one at exactly 1. With thousands of annuli that flagged thousands of scattered pixels,
    //   which is the green and magenta salt-and-pepper measured over 4% of an exported PUNCH
    //   frame on 2026-09-06 and read, reasonably, as the picture being corrupt.
    //
    //   Missing data is stored as exactly 0 (FITSImage.convertPixels), so every masked or bad
    //   pixel came out green. Missing is not clipped.
    //
    // Levels are applied without clamping before this, so anything the window genuinely pushes
    // out of range still lands strictly outside and is still flagged.
    if (display.showClipping != 0. && display.indexed == 0.) {
        if (value > 1.)
            return vec4(1., 0., 1., 1.) * display.color;
        if (value < 0.)
            return vec4(0., 1., 0., 1.) * display.color;
    }

    // Dither breaks up banding in continuous ramps, but on a categorical LUT a +/-1/255 nudge
    // lands on a neighbouring legend entry, turning flat regions into salt-and-pepper noise.
    if (display.indexed == 0. && display.skipDither == 0.)
        value += dither(texcoord);

    vec4 colour = texture(lut, vec2(value, 0.5)) * display.color;
    if (display.hdrGain == 1. || display.indexed != 0.)
        return colour;
    // The gain is a multiple of SDR white in LIGHT, so it is applied to linear values: the colour
    // table is sRGB-encoded, and multiplying the encoded value by 6 would be 6^2.2 in light, which
    // clips the whole top of the image at the panel's peak. Decode, scale, re-encode with the
    // curve extended past 1.0 (monotonic there), which the Metal presenter inverts exactly.
    vec3 lin = mix(colour.rgb / 12.92, pow((colour.rgb + 0.055) / 1.055, vec3(2.4)), step(0.04045, colour.rgb));
    float G = display.hdrGain, k = display.hdrKnee;
    float E = G; // expansion, a multiple of SDR white in light
    if (display.hdrMode == 3.)
        E = clamp(value, 1., G);
    else if (display.hdrMode == 4.) {
        // Uniform: CIE lightness, not light, is what runs in a straight line, and it does not stop
        // at the top of the display range.
        //
        // Inside the range the target is L* = 100 v: the picture keeps the brightness it has with
        // no headroom at all, and only the table's OWN unevenness is taken out (a table already
        // linear in L*, like PUNCH, is left exactly as it was). Above the range the same straight
        // line carries on into the headroom, L* 100 at v = 1 up to 116 G^(1/3) - 16 at v = G. That
        // is the half that stops the legend plateauing: every other mode maps everything at and
        // above v = 1 to one output, so the whole over-range section is a single flat white.
        //
        // Hue survives because this is a scale of the table's own colour in LINEAR light, which
        // moves luminance and leaves chromaticity alone. Pure black is the one colour it cannot
        // lift: there is no hue in it to scale.
        // hdrInRange decides where the top of the display range lands on that line: 0 leaves it at
        // SDR white and spends the whole headroom above the range, 1 takes it all the way to the
        // display's peak and leaves nothing for over-range data. The line is continuous across
        // v = 1 either way, so there is no step where the range ends.
        float Lmax = 116. * pow(G, 1. / 3.) - 16.;
        float Lin = 100. + display.hdrInRange * (Lmax - 100.);
        float Lt = value <= 1. ? Lin * max(value, 0.)
                               : Lin + (Lmax - Lin) * min((value - 1.) / max(G - 1., 1e-4), 1.);
        float Yt = Lt > 8. ? pow((Lt + 16.) / 116., 3.) : Lt / 903.3;
        float Y0 = dot(lin, vec3(0.2126, 0.7152, 0.0722));
        E = Y0 > 1e-6 ? Yt / Y0 : 1.; // the target luminance is <= G by construction, so E needs no cap
    }
    else if (display.hdrMode != 0.) {
        float t = clamp((clamp(value, 0., 1.) - k) / (1. - k), 0., 1.);
        E = display.hdrMode == 1. ? 1. + t * (G - 1.) : 1. + (G - 1.) * t * t;
    }
    lin *= E;
    if (display.hdrMode == 2. || display.hdrMode == 3.) {
        // Roll to white: the further over SDR white, the closer to a neutral of the same
        // luminance. Without this a saturated table colour at 4x is neon, not bright.
        //
        // Soft knee and beyond-range only. This used to read hdrMode >= 2, which also caught
        // Uniform (ordinal 4), whose whole point is that it moves luminance and leaves the colour
        // alone: its E is largest exactly where the mix factor is largest, so it desaturated
        // hardest at the low end and came out as a grey copy of Linear.
        float Y = dot(lin, vec3(0.2126, 0.7152, 0.0722));
        lin = mix(lin, vec3(Y), (E - 1.) / max(G - 1., 1e-4));
    }
    vec3 enc = mix(lin * 12.92, 1.055 * pow(lin, vec3(1. / 2.4)) - 0.055, step(0.0031308, lin));
    return vec4(enc, colour.a);
}

void clipNormalizedCoord(const vec2 coord) {
    if (coord.x < display.slit.x || coord.y < 0. || coord.x > display.slit.y || coord.y > 1.)
        discard;
}

// Convert normalized screen coordinates to the view-aligned plane in scene units.
// The projection is orthographic, so xy is independent of clip-space z and needs no perspective divide.
vec2 getViewPosition(void) {
    return (screen.inverseMVP * vec4(normalizedScreenpos, -1., 1.)).xy;
}

float getDepth(const float viewZ) {
    return 0.5 * (1. + viewZ / screen.inverseMVP[2][2]);
}

// Map the centered view plane to the [0, 1] map domain: remove the viewport's
// horizontal aspect scaling, move the origin to the lower-left, and discard outside it.
vec2 getNormalizedMapPos(void) {
    vec2 pos = getViewPosition();
    pos = vec2(screen.iaspect * pos.x, pos.y) + .5;
    clipNormalizedCoord(pos);
    return pos;
}

// The map coordinate as an angle, which is what the flat sky-facing modes are parameterised in.
// Shared rather than written out per mode: HPC and the observer sky both lay their page out in
// degrees and differ only in what they do with the resulting direction.
vec2 normalizedMapToHelioprojective(const vec2 mapPos) {
    return vec2(
        radians(screen.mapBounds.x + mapPos.x * (screen.mapBounds.y - screen.mapBounds.x)),
        radians(screen.mapBounds.z + mapPos.y * (screen.mapBounds.w - screen.mapBounds.z)));
}

// Convert a normalized warp radius back to radial distance in solar radii, against an explicitly
// supplied scale. The disk is linear; only distances beyond the limb use Box-Cox scaling.
//
// Parameterised rather than reading the screen block, because the composed observer sky needs the
// radial scale of the mode it is composing WITH while its own screen block carries the sky page's
// degrees. Every other mode wants its own scale and calls unwarpRadius() below.
float unwarpRadiusWith(float normalizedRadius, float outerRadius, float limbPosition, float lambda) {
    if (outerRadius <= 1. || normalizedRadius <= limbPosition)
        return normalizedRadius / limbPosition;

    float u = (normalizedRadius - limbPosition) / (1. - limbPosition);
    return lambda == 0.
            ? pow(outerRadius, u)
            : pow(1. + u * (pow(outerRadius, lambda) - 1.), 1. / lambda);
}

// The forward direction, radius to normalized page position: MapScale.BoxCoxRadialScale.toUnitY.
float warpUnitWith(float radius, float outerRadius, float limbPosition, float lambda) {
    if (outerRadius <= 1. || radius <= 1.)
        return radius * limbPosition;

    float u = lambda == 0.
            ? log(radius) / log(outerRadius)
            : (pow(radius, lambda) - 1.) / (pow(outerRadius, lambda) - 1.);
    return limbPosition + u * (1. - limbPosition);
}

// The current mode's own scale, which is what every mode but the composed sky wants.
float unwarpRadius(float normalizedRadius) {
    float outerRadius = screen.mapBounds.w;
    float limbPosition = screen.limb > 0. ? screen.limb : 1. / outerRadius;
    return unwarpRadiusWith(normalizedRadius, outerRadius, limbPosition, screen.lambda);
}

// Twin of display/SurfaceModel.java. Where a line of sight is taken to have originated:
// the plane of sky (r = D tan e, z = 0), the Thomson sphere of 90-degree scattering
// (r = D sin e, z = r^2 / D), or the celestial sphere centred on the observer
// (r = 2D sin(e/2), z = r^2 / 2D). The model value is the family parameter k = D / L for the
// sphere of diameter L through the Sun. A placement model, not a measured depth. Keep the two in
// step -- the mesh is built in Java and sampled here, so a divergence shows up as imagery
// sliding off its own geometry.
#define SURFACE_PLANE_OF_SKY 0.
#define SURFACE_THOMSON_SPHERE 1.
#define SURFACE_CELESTIAL_SPHERE .5
// Both models degenerate at 90 degrees; clamp rather than divide. Matches
// SurfaceModel.MAX_ELONGATION.
#define MAX_ELONGATION 1.5533431

float surfaceHeliocentricRadius(const float elongation, const float observerDistance, const float model) {
    float e = clamp(elongation, 0., MAX_ELONGATION);
    if (model == SURFACE_THOMSON_SPHERE)
        return observerDistance * sin(e);
    if (model == SURFACE_CELESTIAL_SPHERE)
        return observerDistance * 2. * sin(.5 * e);
    return observerDistance * tan(e);
}

float surfaceDepth(const float heliocentricRadius, const float observerDistance, const float model) {
    if (model <= 0. || observerDistance <= 0.)
        return 0.;
    float reach = observerDistance / model; // the sphere's diameter L = D / k
    float r = min(heliocentricRadius, reach);
    return r * r / reach;
}

float surfaceElongation(const float heliocentricRadius, const float observerDistance, const float model) {
    if (observerDistance <= 0.)
        return 0.;
    float ratio = heliocentricRadius / observerDistance;
    if (model == SURFACE_THOMSON_SPHERE)
        return asin(clamp(ratio, -1., 1.));
    if (model == SURFACE_CELESTIAL_SPHERE)
        return 2. * asin(clamp(.5 * ratio, -1., 1.));
    return atan(ratio);
}

vec3 rotate_vector_inverse(const vec4 quat, const vec3 vec) {
    return vec + 2. * cross(cross(vec, quat.xyz) + quat.w * vec, quat.xyz);
}

vec3 rotate_vector(const vec4 quat, const vec3 vec) {
    return vec + 2. * cross(quat.xyz, cross(quat.xyz, vec) + quat.w * vec);
}

vec2 transform_plane_to_image(const vec4 transform, const vec2 vec) {
    return vec2(
        transform.x * vec.x + transform.y * vec.y,
        transform.z * vec.x + transform.w * vec.y);
}

// Differential solar rotation.
float differentialRotation(const float dt, const float sinLatitude) {
    float sinLat2 = sinLatitude * sinLatitude;
    // Snodgrass, Table 1 Magnetic - http://articles.adsabs.harvard.edu/pdf/1990ApJ...351..309S
    return dt * (0.01367 - 0.339 * sinLat2 - 0.485 * sinLat2 * sinLat2); // 2.879 urad/s - 14.1844 deg/86400s (not fully right: 1st SI, 2nd TDB)
}

vec3 differential(const float dt, const vec3 v) {
    float delta = differentialRotation(dt, v.y);
    float sinDelta = sin(delta);
    float cosDelta = cos(delta);
    return vec3(
        v.x * cosDelta - v.z * sinDelta,
        v.y,
        v.z * cosDelta + v.x * sinDelta);
}

// Observer-centred helioprojective geometry.
vec2 worldToHelioprojective(const vec3 world, const float observerDistance) {
    float zeta = observerDistance - world.z;
    return vec2(
        atan(world.x, zeta),
        atan(world.y, sqrt(world.x * world.x + zeta * zeta)));
}

vec3 helioprojectiveToObserverRay(const vec2 helioprojective) {
    float phi = helioprojective.x;
    float theta = helioprojective.y;
    float cosPhi = cos(phi);
    float cosTheta = cos(theta);
    float raySign = cosPhi * cosTheta < 0. ? -1. : 1.;
    return vec3(raySign * sin(phi) * cosTheta, raySign * sin(theta), -raySign * cosPhi * cosTheta);
}

// The sight line's intersection with the plane of sky, which is the coordinate the radial masks,
// the sector cuts and the off-limb enhancement factor are all defined on.
vec2 helioprojectiveToHpcXY(const vec2 helioprojective, const float observerDistance) {
    vec3 ray = helioprojectiveToObserverRay(helioprojective);
    if (ray.z >= 0.)
        discard;
    return -observerDistance * ray.xy / ray.z;
}

// Native zenithal coordinates for TAN/ARC/AZP/ZPN forward projection.
vec3 nativeZenithalCoordinates(const vec2 helioprojective, const Image img) {
    float phi = helioprojective.x;
    float theta = helioprojective.y;
    vec2 referenceAngles = img.crval / img.planeUnitsPerRadian;
    float phi0 = referenceAngles.x;
    float theta0 = referenceAngles.y;

    float sinLat = sin(theta);
    float cosLat = cos(theta);
    float sinLat0 = sin(theta0);
    float cosLat0 = cos(theta0);
    float deltaLon = phi - phi0;
    float sinDeltaLon = sin(deltaLon);
    float cosDeltaLon = cos(deltaLon);

    return vec3(
        cosLat * sinDeltaLon,
        cosLat0 * sinLat - sinLat0 * cosLat * cosDeltaLon,
        sinLat0 * sinLat + cosLat0 * cosLat * cosDeltaLon);
}

vec2 projectTanToWcsPlane(const vec2 helioprojective, const Image img) {
    vec3 nativeCoords = nativeZenithalCoordinates(helioprojective, img);
    if (nativeCoords.z <= 0.)
        discard;

    float scale = img.planeUnitsPerRadian / nativeCoords.z;
    return scale * nativeCoords.xy;
}

vec2 projectArcToWcsPlane(const vec2 helioprojective, const Image img) {
    vec3 nativeCoords = nativeZenithalCoordinates(helioprojective, img);
    float nativeRadius = length(nativeCoords.xy);
    if (nativeRadius == 0.)
        return vec2(0.);

    float nativeDistance = atan(nativeRadius, nativeCoords.z);
    float scale = img.planeUnitsPerRadian * nativeDistance / nativeRadius;
    return scale * nativeCoords.xy;
}

vec2 projectAzpToWcsPlane(const vec2 helioprojective, const Image img, const float[6] PV) {
    float mu = PV[1];
    float gamma = radians(PV[2]);

    vec3 nativeCoords = nativeZenithalCoordinates(helioprojective, img);
    if (nativeCoords.x == 0. && nativeCoords.y == 0.)
        return vec2(0.);

    // For the non-slanted AZP case, mu > 1 folds back once dR/dtheta changes sign.
    // Keep only the primary forward branch.
    if (gamma == 0. && mu > 1. && mu * nativeCoords.z + 1. <= 0.)
        discard;

    float denom = mu + nativeCoords.z - nativeCoords.y * tan(gamma);
    if (denom <= 0.)
        discard;

    float scale = img.planeUnitsPerRadian * (mu + 1.) / denom;
    return scale * vec2(nativeCoords.x, nativeCoords.y / cos(gamma));
}

float zpnRadial(const float eta, const float[6] PV) {
    float radial = PV[5];
    for (int i = 4; i >= 0; --i)
        radial = radial * eta + PV[i];
    return radial;
}

vec2 projectZpnToWcsPlane(const vec2 helioprojective, const Image img, const float[6] PV) {
    vec3 nativeCoords = nativeZenithalCoordinates(helioprojective, img);
    float nativeRadius = length(nativeCoords.xy);
    if (nativeRadius == 0.)
        return vec2(0.);

    float nativeDistance = atan(nativeRadius, nativeCoords.z);
    if (nativeDistance > img.zpnUpperEta)
        discard;

    float radial = zpnRadial(nativeDistance, PV);
    if (radial < 0.)
        discard;

    float scale = img.planeUnitsPerRadian * radial / nativeRadius;
    return scale * nativeCoords.xy;
}

float wrapDeltaLongitude(float lon, float lon0) {
    return mod(lon - lon0 + PI, TWOPI) - PI;
}

// Projection-space to texture-space mapping.
vec2 projectHelioprojectiveToWcsPlane(const vec2 helioprojective, const Image img, const float[6] PV) {
    if (img.projectionCode == WCS_PROJECTION_TAN)
        return projectTanToWcsPlane(helioprojective, img);
    if (img.projectionCode == WCS_PROJECTION_ARC)
        return projectArcToWcsPlane(helioprojective, img);
    if (img.projectionCode == WCS_PROJECTION_AZP)
        return projectAzpToWcsPlane(helioprojective, img, PV);
    if (img.projectionCode == WCS_PROJECTION_ZPN)
        return projectZpnToWcsPlane(helioprojective, img, PV);

    return projectTanToWcsPlane(helioprojective, img);
}

vec2 wcsPlaneToUnclampedTexcoord(const vec2 plane, const Image img) {
    vec2 centered = transform_plane_to_image(img.planeToImage, plane);
    vec4 rect = img.rect;
    return rect.zw * vec2(centered.x - rect.x, -centered.y - rect.y);
}

vec2 wcsPlaneToTexcoord(const vec2 plane, const Image img) {
    vec2 texcoord = wcsPlaneToUnclampedTexcoord(plane, img);
    clipNormalizedCoord(texcoord);
    return texcoord;
}

vec2 helioprojectiveToTexcoord(const vec2 helioprojective, const Image img, const float[6] PV) {
    vec2 plane = projectHelioprojectiveToWcsPlane(helioprojective, img, PV);
    return wcsPlaneToTexcoord(plane, img);
}

vec2 wcsPlaneToWrappedXTexcoord(const vec2 plane, const Image img) {
    vec2 texcoord = wcsPlaneToUnclampedTexcoord(plane, img);
    texcoord.x = fract(texcoord.x);
    clipNormalizedCoord(texcoord);
    return texcoord;
}

bool isSurfaceMap(const Image img) {
    return img.projectionCode == WCS_PROJECTION_CAR
        || img.projectionCode == WCS_PROJECTION_CEA;
}

vec2 sampleSurfaceMapTexcoord(const vec3 world, const Image img, const float[6] PV) {
    float longitude = atan(world.x, world.z);
    float sinLatitude = clamp(world.y / length(world), -1., 1.);
    float latitudeCoordinate;
    if (img.projectionCode == WCS_PROJECTION_CAR)
        latitudeCoordinate = asin(sinLatitude);
    else
        latitudeCoordinate = sinLatitude / max(abs(PV[1]), 1e-12);
    float planeUnitsPerRadian = img.planeUnitsPerRadian;
    vec2 referenceCoordinate = img.crval / planeUnitsPerRadian;
    vec2 plane = vec2(
        planeUnitsPerRadian * wrapDeltaLongitude(longitude, referenceCoordinate.x),
        planeUnitsPerRadian * (latitudeCoordinate - referenceCoordinate.y));
    return wcsPlaneToWrappedXTexcoord(plane, img);
}

bool helioprojectiveToWorld(const vec2 helioprojective, const float observerDistance, out vec3 world) {
    vec3 ray = helioprojectiveToObserverRay(helioprojective);
    float b = observerDistance * ray.z;
    float c = observerDistance * observerDistance - 1.;
    vec3 observer = vec3(0., 0., observerDistance);
    float discriminant = b * b - c;
    if (discriminant < 0.) {
        world = vec3(0.);
        return false;
    }

    float root = sqrt(discriminant);
    float tNear = -b - root;
    float tFar = -b + root;
    float t = tNear > 0. ? tNear : tFar;
    if (t <= 0.) {
        world = vec3(0.);
        return false;
    }

    world = observer + t * ray;
    return true;
}

/**
 * Sample a CAR/CEA surface map (the indexed Carrington synoptic map, among others) along a
 * helioprojective line of sight.
 *
 * A surface map is a map OF THE SPHERE: every texel is a longitude/latitude, so the only way to
 * sample it is to intersect the line of sight with the solar surface and ask where that point
 * lands on the map. That is why this discards off the limb rather than falling back to a plane:
 * a sight line that misses the Sun has no surface point, and inventing one is what produced a
 * smear across the page.
 *
 * The orthographic and latitudinal modes reach sampleSurfaceMapTexcoord directly, because they
 * already hold a world position. Every mode that reconstructs its picture from sight lines (HPC,
 * Helioradial in both implementations, Helioradial Unrolled, Observer Sky) needs this entry
 * instead, and reaching it from inside sampleHpcTexcoord is what stops the list going stale: the
 * previous arrangement left three of those five calling the generic path, which has no CAR/CEA
 * branch and silently treats them as TAN.
 *
 * The frame correction mirrors imageOrtho.frag: sight lines are built in the observer frame while
 * the map is glued to the Sun, so sourceViewQuat (which ImageLayer fills with the view rotation
 * for surface maps specifically) undoes the view.
 */
vec2 sampleSightLineSurfaceMapTexcoord(const vec2 helioprojective, const Image img, const float[6] PV) {
    vec3 world;
    if (!helioprojectiveToWorld(helioprojective, img.observerDistance, world))
        discard;
    return sampleSurfaceMapTexcoord(rotate_vector_inverse(img.sourceViewQuat, world), img, PV);
}

void clipSectorOpening(const float theta, const vec2 sector) {
    if (sector.y <= 0.)
        return;

    float delta = abs(theta - sector.x);
    float angularDistance = min(delta, TWOPI - delta);
    if (angularDistance < sector.y)
        discard;
}

void clipSectors(const vec2 point) {
    if (display.metadataSector.y <= 0. && display.userSector.y <= 0.)
        return;

    float theta = atan(point.y, point.x);
    clipSectorOpening(theta, display.metadataSector);
    clipSectorOpening(theta, display.userSector);
}

void clipPlanarMasks(const vec2 point) {
    clipSectors(point);

    float radial2 = dot(point, point);
    float minRadius2 = display.radii.x * display.radii.x;
    float maxRadius2 = display.radii.y * display.radii.y;
    if (radial2 > maxRadius2 || radial2 < minRadius2)
        discard;

    if (display.cutOff.z >= 0.) {
        float flatDist = abs(dot(point, display.cutOff.xy));
        vec2 cutOffAlt = vec2(-display.cutOff.y, display.cutOff.x);
        float flatDistAlt = abs(dot(point, cutOffAlt));
        if (flatDist > display.cutOff.z || flatDistAlt > display.cutOff.z)
            discard;
    }
}

vec2 sampleHpcTexcoord(const Image img, vec2 helioprojective, const vec2 hpcXY, const float[6] PV, out float enhancementFactor) {
    enhancementFactor = 1.;
    // A map of the sphere cannot answer through the observer-image path; see above.
    if (isSurfaceMap(img))
        return sampleSightLineSurfaceMapTexcoord(helioprojective, img, PV);

    float observerDistance = img.observerDistance;

    vec3 world;
    if (helioprojectiveToWorld(helioprojective, observerDistance, world)) {
        if (img.deltaT != 0.) {
            vec3 rotatedWorld = differential(img.deltaT, world);
            helioprojective = worldToHelioprojective(rotatedWorld, observerDistance);
        }
    } else {
        enhancementFactor = max(1., length(hpcXY));
    }

    return helioprojectiveToTexcoord(helioprojective, img, PV);
}

vec4 sampleWarpedHpcColor(const vec2 hpcXY) {
    vec2 helioprojective = worldToHelioprojective(vec3(hpcXY, 0.), images[0].observerDistance);
    clipPlanarMasks(hpcXY);
    float enhancementFactor;
    vec2 texCoord = sampleHpcTexcoord(images[0], helioprojective, hpcXY, pv0, enhancementFactor);
    if (display.isDiff == NODIFFERENCE)
        return getColor(texCoord, texCoord, enhancementFactor);

    vec2 diffHelioprojective = worldToHelioprojective(vec3(hpcXY, 0.), images[1].observerDistance);
    float diffEnhancementFactor;
    vec2 diffTexCoord = sampleHpcTexcoord(images[1], diffHelioprojective, hpcXY, pv1, diffEnhancementFactor);
    return getColor(texCoord, diffTexCoord, max(enhancementFactor, diffEnhancementFactor));
}
