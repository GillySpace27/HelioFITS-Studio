# First steps

When HelioFITS Studio opens you see a 3-D view of the Sun in the centre (the image canvas), a
toolbar of view controls across the top, the movie controls along the bottom, and a sidebar that
lists your image **layers**: each data source you load is one layer. Select a layer in the list and
its options (colour table, brightness, filters and so on) appear below the list. Most of what this
manual describes acts on the selected layer or on the whole view.

## Your first picture

With nothing of your own to hand, {menuselection}`Help --> Open Demo Scene` opens a new session with
a bundled SDO/AIA 171 image and RHEF on. Nothing downloads, so it works offline.

To load real data, click **New Layer** in the sidebar (or choose
{menuselection}`Layers --> New Image Layer…`). One window opens with a tab per source:

HV (JPEG 2000)
: Helioviewer's streamed browse images. Fast, 8-bit.

VSO (FITS)
: Calibrated files at full bit depth through the Virtual Solar Observatory.

Direct (FITS)
: A mission's own archive, such as LASCO C2 and C3 from NRL.

Local FITS Library
: FITS files already on this computer, when HelioFITS Core or the HelioFITS Cache Manager keeps a
  library here (see {ref}`fits-library`).

Pick a dataset, for example SDO/AIA 171 on the HV tab, and click **Add**. The layer covers the
movie's time range, one frame per time step. You can also drop FITS or JPEG 2000 files anywhere on
the window, or use {menuselection}`Layers --> Open Image Layer...`; a dropped `.jhv` file opens that
session.

## Moving around

- Scroll on the canvas to zoom. Drag to rotate or pan; the **Rotate** and **Pan** buttons on the
  toolbar choose which. Double-click the canvas, or choose {menuselection}`View --> Reset View`, to
  go back to the default view.
- Play runs the movie and the arrows beside it step one frame at a time. Drag the slider to move
  through time. **J**, **K** and **L** play backward, stop and play forward from anywhere in the
  window.
- In the layer list, the checkbox shows or hides a layer, the x removes it, and dragging a row
  reorders the layers. Double-click a layer's name to change its dataset.
- Under **Display**, **Color** picks the colour table; under **Intensity**, the two **Levels**
  handles set the brightness window.
- Each sidebar section carries a number in front of its title. Cmd-1 to Cmd-8 (Ctrl-1 to Ctrl-8
  elsewhere) fold or show the section with that number in the left sidebar, and Cmd-Option-1 to
  Cmd-Option-8 (Ctrl-Shift-1 to Ctrl-Shift-8) do the same in the right one. Moving a section changes
  its number, and so its key. The **Left Sidebar Panels** and **Right Sidebar Panels** submenus of
  **View** list what each key reaches now, and Settings > Keybindings can change the keys.

## Finding things

- {menuselection}`Help --> Search Controls...` (Cmd-K on a Mac, Ctrl-K elsewhere) finds any command
  or control by name, and runs it or shows you where it is.
- Rest the pointer on any button, box or slider for a moment and a tooltip says what it does.
- {menuselection}`Help --> Tours --> Getting started` steps through the main controls one at a time;
  {doc}`generated/tours` has every tour written out.
- {menuselection}`Help --> Tip of the Day...` shows a tip you have not seen yet, with **Show Me** to
  point at the control it is about. All of them are in {doc}`generated/tips`.
- {menuselection}`Help --> Welcome to HelioFITS Studio...` opens the Welcome window again. Its large
  button does the likely thing (the Getting Started tour the first time; later Continue, your last
  session, or **Open Session...**), with **Start Blank**, **Open Session...** and **Open Demo** under
  it and your recent sessions below. **Search Controls**, **Tours** and **User Manual** are always
  there; **More help** opens Tip of the Day, Send Feedback and the docs for the MCP server and the
  Local API. Hover any of them, or let a screen reader read it, for what it does. **What's New** is
  at the top right, highlighted the first time after an update. **Show this window** at the bottom picks when it opens
  at startup: **Automatically** (the first time, after an update, and when Studio starts with
  nothing open), **Always** or **Never**. At startup a click in the main window closes it.
- {menuselection}`Edit --> Undo` takes back the last change to the scene.
