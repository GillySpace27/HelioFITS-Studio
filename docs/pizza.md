# Sun pizza (HS-25)

Pizza, on the toolbar, cuts every shown image layer into its own wedges (or tiles) of one Sun, so
the same patch of the Sun is seen through every filter side by side. Nothing is resampled or
changed: each layer is loaded and drawn whole as always, and the fragment shader keeps only that
layer's cells (`resources/glsl/imageCommon.frag`, `clipPizza`). Off draws every layer whole.

## The cut

| Control | What it does |
|---|---|
| Cut | Off, Wedges, Squares or Polar tiles |
| Space | Sun: the cut follows each image's solar north (position angle, counterclockwise from north through east), so it stays on the Sun as the camera moves and in the 3D canvas. Screen: the cut is fixed to the viewport, for the 2D canvas |
| Repeats | How many times the channels go round. N layers with R repeats give N times R wedges |
| Turn | Where the first wedge starts, in degrees. Animatable (key `pizza.turn`) |
| Gap | Black between cells: degrees between wedges, or a share of each tile |
| Edge | Cuts every layer off at this many solar radii (0: each layer keeps its own field) |
| Jitter | Spokes of uneven length; needs an Edge |
| Shuffle | 0 keeps the channels in the same order every repeat; any other number turns repeat r on by r times the number |
| Tile, Rows, Row shift | Squares and polar tiles: tile size (solar radii, or hundreds of pixels in Screen space), polar rows in Helioradial, and how far each row moves the channels along |

The slots are the enabled image layers in the layer list's order, less any unticked under
Channels. A hidden layer takes no wedges.

## Projections

Orthographic, HPC, Helioradial and Helioradial Unrolled. In Helioradial the cut goes through the
radial warp with the image, so a coronagraph wedge lines up with its disk wedge. In Helioradial
Unrolled wedges become vertical strips, and Squares become tiles counted on the warped radius, so
they are squares on the unrolled page. Latitudinal and Observer Sky draw every layer whole.

## Coronagraph ring

Coronagraph ring gives coronagraphs a ring of their own outside the disk. A layer is a
coronagraph when its metadata masks the Sun out to at least 1.05 solar radii; the Inner/Outer
choice beside each channel overrides that. Boundary sets where the rings meet; the outer ring has
its own repeats and can turn the other way.

## Labels

Labels puts each channel's wavelength on its wedges (every wedge, or the first repeat only), in
nanometres or angstrom, optionally with a colour name typed beside the channel.

## Spin

| Spin | What turns |
|---|---|
| Still | Nothing; drag Turn, or animate `pizza.turn` |
| Live | The cut turns on screen at Speed degrees a second |
| With the movie | The cut turns Per frame degrees each movie frame, so a loop recording turns too |

Record Spin records one seamless turn in the given number of frames with the movie paused: the
data stands still and only the cut moves, so every part of the Sun passes under every filter. It
is a Free recording at your recording size and format. One seamless turn is one repeat (360/R
degrees) for one ring, 360/gcd(R inner, R outer) for two, and a full turn when shuffled; the last
frame stops one step short of the first, so the file loops without a repeated frame. The palette
prints the loop angle.

## Saved state and the processing record

A session saves the pizza under `pizza` while it is on (layer choices by layer id) and restores it
with the layers. The processing record's `scene.pizza` carries the same object, so a figure says
how it was cut.
