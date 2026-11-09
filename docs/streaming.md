# Stream (memory-mapped)

The Load Whole Dataset question (FITS Library tab, a dataset with nothing in the master time range)
has a third answer, **Stream (memory-mapped)**, beside Load all and Cancel. Code: `StreamStore`
(`src/org/helioviewer/jhv/view/uri/`), used by `URIView`.

## What it does

HelioFITS Core FITS files are Rice tile-compressed, so the file cannot be mapped. A frame from a
streamed layer is decoded once, written to `Cache/stream/<digest>.f16` (a 64-byte header, then raw
half floats), and every later request maps that file with `FileChannel.map`. One file per frame
keeps each map far under the 2 GiB limit of a `MappedByteBuffer`. The OS pages the maps in and out.
The existing 16-frame lookahead in the playhead's direction (`ManyView`) fills the store ahead of
playback. Playing faster than the disk or decoder can serve holds the last frame on screen (the
layer's `LatestWorker` drops stale requests); nothing blocks the interface or queues without limit
(the lookahead queue is capped at 64).

## Settings (Settings > Network > Streaming)

| Key | Default | Meaning |
|---|---|---|
| `stream.memoryMB` | 2048 | Window of mapped frames kept in memory, least recently used out first. |
| `stream.diskGB` | 100 | Disk the decoded frames may take; oldest files removed first, never the newest. |
| `stream.choice` | `ask` | `all`, `stream`, or `ask`: the remembered answer to the dialog's checkbox. |

Streaming frames do not use the decoded-frame cache (`MemoryBudget`), so they do not compete with it.

## What it does not do

- It does not skip the load-time pass: opening a layer still reads and decompresses each file once
  to take its clip set (about 30 to 80 ms a PUNCH CAM L3 frame, measured). Only pixels are streamed.
- The first lap still decodes every frame once.
- The stretch is baked into the stored half floats, so a changed display range or scaling decodes
  again under a new name; the old files age out under the disk budget.
- A frame with a filter on, a LASCO background taken off, 8 bits, or provisional is not stored and
  uses the ordinary cache.
- The streaming mark lasts one launch; a restored session loads normally.
- The decoded set is bigger than the download: 4096 x 4096 x 2 bytes is 32 MiB a frame.
