# Infinite Canvas (Android tablet + pen)

An infinite drawing canvas for Android tablets with a stylus. Built with Kotlin and Jetpack Compose.

## How it works
- **Pen draws**: the pen uses the selected tool (Pen or Eraser). Flipping to the stylus's eraser end erases automatically.
- **Fingers navigate**: one finger pans, and two fingers pan and pinch-zoom (1% – 10,000%). Fingers never draw.
- **Palm rejection**: while the pen is down, every finger and palm touch is ignored.
- **Tools**: Pen (7 colours, 4 sizes), **Lasso fill**, Eraser, undo/redo and clear (clear can be undone).
- **Lasso fill**: with the Fill tool, draw a shape with the pen. When you lift it, the shape closes and fills with the current colour. Use the slider to set the opacity. Fills always sit under the ink, and the eraser removes them like strokes.
- **Undo / redo gestures**: tap with two fingers to undo and with three fingers to redo. Hold two fingers still to keep undoing until you lift them. A small label confirms each action.
- **Zoom chip**: tap to go back to 100%. The ⌖ button fits the view to your drawing.

## Files (☰ menu, top left)
- **New / Open… / Save / Save as…** use Android's file picker, so files can go to local storage, an SD card, or Google Drive and other cloud folders.
- Drawings are `.canvas` files (JSON). They keep every stroke and the view position.
- **Autosave**: about 1 s after any change, the open `.canvas` file is updated automatically. The app also keeps a hidden backup copy, so a drawing that has never been saved survives the app closing. A **•** next to the name means the drawing isn't saved to a file yet.
- **Export SVG… / Export PDF…** produce true vector files. They are cropped to the drawing, with a transparent background and no grid.

## Getting the APK
Every push to `main` builds the app with GitHub Actions.
1. Open the repo's **Actions** tab and select the latest **Build APK** run.
2. Download **InfiniteCanvas-apk** from the run's **Artifacts** and unzip it.
3. Copy the `.apk` to the tablet and open it. Allow "install unknown apps" if Android asks.

Builds are signed with `app/signing.keystore`, a fixed key kept in the repo, so each new APK installs as an update and your drawings are kept. This is fine for a personal app. Use a private key if you ever publish it.

Requires Android 8.0+.

## Code map
| File | Purpose |
|---|---|
| `CanvasState.kt` | Strokes (world coordinates), camera, pen/eraser logic, undo/redo |
| `InfiniteCanvas.kt` | Rendering and pen/finger input handling |
| `Toolbar.kt` | Bottom toolbar, colours and sizes, zoom controls, file menu |
| `Storage.kt` | `.canvas` format, hidden autosave, reading and writing user files |
| `VectorExport.kt` | SVG and PDF export |
| `MainActivity.kt` | File pickers, open/save/export, autosave |
