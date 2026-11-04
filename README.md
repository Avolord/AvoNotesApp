# AvoNotes

A small, native Android note-taking app: an infinite canvas, stylus ink, PDF import, save/load.
No accounts, no network, no permissions, no dependencies beyond the Kotlin stdlib.

## Using it

- **Stylus** draws. The S Pen side button or an eraser tip erases whole strokes.
- **One finger** pans, **two fingers** pinch-zoom. While the pen is down, touches are ignored (palm rejection).
- Tap the active pen or highlighter again, or tap the color dot, to change color and width.
- **Lasso** tool: draw a loop around ink to select it (a stroke counts if at least half of it is inside; PDF pages count if fully inside).
- **Long-press a PDF page** with a finger (or the pen, in lasso mode) to pick it up; keep dragging to move it. Ink on the page moves with it.
- Drag inside a selection (pen or finger) to move it. Tap **Delete** above it, or press Del, to delete. Tap outside to deselect. Moves and deletes can be undone.
- **More** menu: finger drawing, dot grid, zoom to fit, reset zoom.
- Keyboard: Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z. Mouse: wheel pans, Ctrl+wheel zooms.
- Notes save automatically (1.5 s after each change and when you leave the app).
- In the library, long-press a note to rename, export (`.avonote`), or delete it. **Import** loads an `.avonote` file.

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # onto a connected device
```

Needs JDK 17+ (Android Studio's bundled JBR works) and Android SDK 36. Minimum Android 10 (API 29).

## How it's put together

| File | What it does |
| --- | --- |
| `canvas/InfiniteCanvasView.kt` | Input (stylus, palm rejection, pan/zoom), undo/redo, frame rendering |
| `canvas/TileIndex.kt` | Strokes bucketed into 1024-unit world tiles, each recorded once into a GPU `RenderNode`. Pan/zoom replays tiles without per-stroke CPU work; an edit re-records one tile. |
| `canvas/StrokeRenderer.kt` | Pressure-sensitive ink: runs of similar width drawn as smoothed round-capped paths |
| `pdf/PdfPageCache.kt` | Platform `PdfRenderer` on a worker thread: power-of-two LOD bitmaps in an LRU cache, plus sharp screen-resolution patches once you stop moving at deep zoom. Newest request first; off-screen requests are dropped. |
| `io/NoteStore.kt` | One folder per note: binary `note.avn` (atomic write), `title.txt`, `pdf/`. Export is a zip of that folder. |
| `ui/` | Library list and canvas activities (platform widgets, no AppCompat) |

World units are PDF points (1/72"). At 100% zoom, 1 unit = 1 dp.

## Known limits / ideas

- The eraser removes whole strokes; there is no partial (pixel) eraser.
- Selections can be moved and deleted, but not resized, rotated, copied or recolored.
- With very many strokes all on screen at once (zoomed far out), a cached-bitmap level of detail would help.
- No export to PDF yet.
