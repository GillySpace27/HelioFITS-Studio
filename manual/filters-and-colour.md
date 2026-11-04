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

## The Filters palette

**Filters**, on the toolbar, opens a palette for one layer. RHEF comes first and works frame by frame.
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

**HDR**, on the toolbar, sets how the whole view is mapped into the extended range of a display that
has one: headroom, mapping, knee, in-range share and clipped pixels.

## Making the interface easier to read

{menuselection}`View --> Display Scaling…` sets the text size and interface scale.
