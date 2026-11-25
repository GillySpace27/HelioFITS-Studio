# Event-interval lanes in the timeline

An interval layer draws labelled lanes of time intervals above the timeline plot, sharing its time
axis: one lane per kind of event (for example ICME, SIR, HSS), one coloured bar per interval, an
optional short label inside the bar. In stacked mode the lanes sit above the first panel.

## Loading

Send a request (a `.json` file through Open Timeline, or the `jhv.load.request` SAMP message) with
an `org.helioviewer.jhv.request.intervals` array:

```json
{"org.helioviewer.jhv.request.intervals": [
  {"name": "Solar wind events",
   "intervals": [
     {"lane": "ICME", "start": "2026-04-24T00:00:00", "end": "2026-04-25T12:00:00", "label": "1"},
     {"lane": "SIR", "start": "2026-04-26T00:00:00", "end": "2026-04-26T12:00:00", "label": "2"}]}
]}
```

- `start` and `end` are UTC: `YYYY-MM-DDTHH:MM:SS`, the same with a trailing `Z`, or a bare date.
- `lane` defaults to "Events". Lanes appear in order of first use; colours come from a fixed palette.
- `label` is optional and is drawn only where the bar is wide enough to hold it.
- An interval that ends before it starts refuses the whole request, with the interval's index.

The layer is saved with the session and can be hidden or deleted from the Timeline Layers table.
Code: `IntervalTimelineLayer`; check: `extra/test/IntervalTimelineLayerCheck.java`.
