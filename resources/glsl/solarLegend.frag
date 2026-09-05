
// The colour-table legend, drawn by the same code that draws the picture.
//
// The bar used to be built on the CPU from the table's 256 entries and painted as an overlay,
// which meant two things could not be true of it: it could not be brighter than interface white,
// because overlays are never given the HDR gain, and it could not show what the knee modes do,
// because that curve lived in getColor() and the bar never went through getColor(). Both fixes
// are the same fix. The Java side uploads a one-row texture holding the values the bar stands
// for and binds this layer's display block, colour table and a blank mask; this shader runs the
// pipeline the image runs. Whatever the picture does with a value, the legend does too, by
// construction rather than by imitation.
//
// The quad is the shared full-screen strip drawn into a viewport set to the bar's rectangle, so
// normalizedScreenpos.x runs from -1 at the bar's left edge to +1 at its right.
void main(void) {
    float u = (normalizedScreenpos.x + 1.) * .5;
    vec2 texcoord = vec2(u, .5);
    outColor = getColor(texcoord, texcoord, 1.);
}
