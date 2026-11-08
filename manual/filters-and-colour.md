# Filters, colour and display

## RHEF

The corona gets dramatically fainter with height, so its outer structure tends to vanish into black.
The Radial Histogram Equalizing Filter (RHEF) flattens that falloff: at every radius it spreads that
ring's pixels out by their brightness rank, so faint outer features become visible without blowing out
the bright inner corona, and without a manual stretch. The method is described in Gilly & Cranmer
2025.

Select an image layer, open **Intensity** in its options, and choose **RHEF** in the **Filter** row.
The **Υ** button beside the list (greyed until RHEF is chosen) opens:

- **Shadows (Υ low)** and **Highlights (Υ high)**, which soften RHEF output below and above the median,
  each on its own, with a small curve that draws the shape they give;
- a **Look** menu that sets both at once (the defaults, or Neutral, 1.00 / 1.00, a straight line);
- **Hold to compare**, which shows that one layer without RHEF while you hold it.

:::{important}
RHEF output is a visualization, not a calibrated radiance. Measure on the original data, with the
filter set to None. A movie or PNG made with RHEF on records each RHEF setting it used in its
provenance.
:::

## Chaining filters

A layer can apply up to three filters in series: the one in the **Filter** row, then one or two more,
each applied to the result of the one before. Choose a filter in the **Filter** row, then press
**+ Add another filter in series**. Each added row has:

- the filter to apply (Multi-scale Gaussian normalization, Wavelet-optimized whitening or RHEF);
- a **Weight**, 0 to 100%. The step's result is `(1 - weight) * previous + weight * filtered`, so 0%
  leaves the previous result alone and 100% is the plain nested filter. The change is applied when you
  let go of the slider, because each value is a new pass over the frame;
- **⇅**, which swaps the filter with the one before it. The weight stays with its position, so MGN at
  60% on RHEF becomes RHEF at 60% on MGN;
- **✕**, which removes the step.

A line under the last row reads the chain back in words, for example "RHEF then 60% MGN (40% previous)".
That is `0.4 * RHEF(x) + 0.6 * MGN(RHEF(x))`, where x is the frame after clipping and stretching. Choosing
**No filter** in the first row removes the whole chain. The first filter always runs at full strength.

The filters do not commute, and the weighted mix of an RHEF rank with MGN output (which stays near the
input's scale) looks different in each order, so look at both orders on your data before you settle on one.
Where RHEF is in the chain, the display blends the instrument response factor and the Υ curve toward
their RHEF values in proportion to the RHEF fraction of the result (RHEF alone, or on top at 100%,
is exactly the old RHEF display); this blending of display constants is Studio's choice and not part of
the filters. The Υ button is live whenever RHEF takes part.

A chain costs the sum of its filters, plus one cheap mix per added step, and every weight change
re-decodes the layer's frames. Two settings change the arithmetic, and both are written into the export
record so a chain can be redone: `-Djhv.filterChain.maxStages=` (2 to 4 filters in all, default 3) and
`-Djhv.filterChain.rhefInput=` (`half`, the default, hands a later RHEF stage its input rounded to
half floats, as a single RHEF receives it; `float` ranks the full precision values by sorting, which
is slower). Intermediate results are always kept as 32-bit floats; the final picture is a half float
clamped to 0 to 1.

:::{important}
A chain that contains RHEF is a visualization too. The export record lists every stage in order with its
weight, the formula, the settings above and the RHEF stamp. Sessions keep the chain beside the first
filter (`filter` and `filterChain`); a Studio without chains opens such a session with the first filter only.
From a script, `filterSet` takes `chain`, a list of `{filter, strength}`, and `layerList` reports it.
:::

## The Filters palette

**Filters**, on the toolbar or in its More menu, opens a palette for one layer. RHEF comes first and works frame by frame.
Below it, the Fourier filter and the noise gate work over the whole movie.

## Colour table legend

Any layer that can choose a colour table can show that table along the bottom of the view. The button
beside the **Color** dropdown in a layer's options toggles it, and each layer has its own, so two can
be shown at once. For a categorical map (such as a hand-drawn synoptic chart) the legend is the key to
the colours, so it appears automatically; it shows one labelled block per feature.

Hovering a swatch tells you what it stands for. On a categorical map you get the raw pixel index and
its label. On an ordinary ramp you get the value that colour represents: for a layer opened directly
from a FITS file it is the number stored in the file (shown without a unit, since the unit is not yet
read from the header). Where that cannot be worked back (streamed Helioviewer movies, which never
carried the original FITS numbers to your computer, and RHEF or difference views) the reading falls
back to a percentage of the display range. The ramp carries no printed numbers on purpose: a printed
scale would freeze one moment's brightness and contrast.

```{figure} ../release/guide_assets/fig_colorbar.png
:width: 80%

The legend for a hand-drawn synoptic chart, its labels taken from the analyst's own colour table.
```

## Deep colour and HDR

On macOS the canvas renders into a 10-bit surface, four times the levels per channel of the old 8-bit
canvas, so smooth coronal gradients do not band. It is on by default; to compare against the old
canvas, add `display.deepColorCanvas=false` to `~/HFStudio/Settings/user.properties` and relaunch.

**HDR**, on the toolbar or in its More menu, sets how the whole view is mapped into the extended range of a display that
has one: headroom, mapping, knee, in-range share and clipped pixels.

## Making the interface easier to read

{menuselection}`View --> Display Scaling…` sets the text size and interface scale.

The Visibility section of {menuselection}`Managers --> Theme Manager…` makes the sidebar sections easier to find. It is on by default; untick **Easier to find panels** for the previous look. Under it, each can be turned off on its own: stronger section headers (a stronger band, a thicker rule and more space between sections), an accent stripe on each header, numbers in front of each section title in the order they appear, and a highlight on the header under the pointer. The options are kept in the settings, not in a theme, so they stay when you change or derive a theme.
