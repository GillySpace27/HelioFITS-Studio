# Movies

## Playing

Play runs the movie and the arrows beside it step one frame at a time
({menuselection}`Movie --> Play/Pause Movie`, {menuselection}`Movie --> Step to Previous Frame`,
{menuselection}`Movie --> Step to Next Frame`). Drag the slider to move through time. **J**, **K**
and **L** play backward, stop and play forward from anywhere in the window.

Under **Playback and Recording** in the sidebar, **Play** sets the rate, in frames per second or in
solar minutes, hours or days per second, and **Loop**, **Stop** and **Swing** choose what happens at
the end. The Clock row's **Achieved** box, off by default, shows the frame rate playback actually
reaches beside the rate it asks for.

## Trimming

{menuselection}`Movie --> Trim Start Here (I)` and {menuselection}`Movie --> Trim End Here (O)` set
the movie's in and out points at the current frame; {menuselection}`Movie --> Reset Trim` clears them.
The movie bar also has grab handles: drag an end to trim, or drag the middle to slide the window
along, so you can loop just the interval you care about without reloading.

## Recording

Under Playback and Recording, **Record** chooses **One loop** (the movie once through),
**Screenshot** (a single still) or **Unlimited** (until you stop it). Below that are the aspect ratio,
the pixels on the long side, and the format: the codec and container, and how much colour detail and
bit depth the file keeps. Locking an aspect letterboxes the canvas to exactly what the export will
contain, so you can compose before recording. A preset saves a set of these under a name; a fresh
install records with **Share anywhere** (H.264, 8-bit 4:2:0), which plays in every player, browser
and slide deck.

The red dot ({menuselection}`Movie --> Start/Stop Recording`) starts recording with those settings;
click it again to stop. Files are saved in `~/HFStudio/Exports`, which
{menuselection}`File --> Open Exports Folder` shows.

{menuselection}`File --> Quick Share` records one loop with the Share anywhere preset, at up to 1920
pixels on the long side, and shows the file in the file manager; your own recording settings are not
changed.

## Presenting

{menuselection}`View --> Present` shows the output only, full screen, for a talk; Esc leaves. Hold the
**Present** button for its settings.
