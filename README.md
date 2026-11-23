# Infinite Canvas (Android tablet + Windows)

An infinite drawing canvas for an Android tablet with a pen and a Windows PC, built with Kotlin and Compose. Drawings sync between the two through **Syncthing**, with no server needed.

## Drawing on the tablet
- **Pen** draws with the selected tool: Pen, **Lasso fill** or Eraser. Flipping to the stylus's eraser end erases.
- **Fingers navigate**: one finger pans. Two fingers pan, pinch-zoom (1% – 10,000%) and twist to rotate. Fingers never draw, and palm touches are ignored while the pen is down.
- **Two-finger tap** undoes, **three-finger tap** redoes, and holding two fingers still keeps undoing.

## Drawing on Windows
| Action | Mouse / pen tablet | Keyboard |
|---|---|---|
| Draw with current tool | Left-drag / pen tip | B or P = pen, F = fill, E = eraser |
| Pan | Right- or middle-drag, or Space + drag | |
| Zoom | Mouse wheel | + / − |
| Rotate | Shift + wheel | [ and ] (15° steps) |
| Reset view / fit drawing | | 0 / Home |
| Undo / redo | | Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) |
| New / Drawings / Save / Save as | | Ctrl+N / Ctrl+O / Ctrl+S / Ctrl+Shift+S |

Pen tablets act as a mouse on Windows (pressure isn't used).

## Both apps
- **Tools**: 7 colours, 4 pen sizes, lasso fill with opacity, stroke eraser, undo/redo and clear (clear can be undone).
- **View options** (☰ → View): *Snap rotation to 90°* and *Lock rotation*. The zoom chip shows zoom and angle; tap it to reset both.
- **☰ menu**: New drawing, Drawings…, Save, Save as…, Open other file…, Export SVG… / PDF…, and Drawings folder.
- **Export** produces true vector SVG and PDF files, cropped to the drawing, with a transparent background and no grid.

## Syncing with Syncthing
1. Install **Syncthing** on the PC and **Syncthing-Fork** on the tablet (from F-Droid). Share one folder between them, e.g. `Drawings`.
2. In each app, open ☰ → **Drawings folder** and pick that shared folder.
3. Save drawings there (☰ → Save). Every change is then saved automatically about 1 s later, and Syncthing carries it to the other device.

How the apps keep sync safe:
- **Changes from the other device** are picked up automatically (checked every few seconds) and merged into what's on screen.
- **Conflicts**: if both devices changed a drawing before syncing, Syncthing keeps a `…sync-conflict-….canvas` copy. The app merges it automatically (keeping strokes from both sides) and deletes the copy.
- **Erasing syncs too**: each file remembers which strokes were erased, so merging never brings them back.
- **Zoom, position and angle are kept per device**, so just looking at a drawing never changes the file.

## Getting the apps
Every push to `main` builds both apps with GitHub Actions. Open **Actions** → latest **Build** run → **Artifacts**:
- **InfiniteCanvas-apk**: unzip it and install the `.apk` on the tablet. It installs as an update over older versions.
- **InfiniteCanvas-windows-msi**: unzip it and run the `.msi`. It installs per user (no admin needed), adds a Start-menu entry, and newer installers upgrade the installed app.

The APK is signed with `app/signing.keystore`, a fixed key kept in the repo, so updates install over each other. This is fine for a personal app.

## Project layout
| Folder | Contents |
|---|---|
| `shared/src` | Code used by both apps: canvas model, rendering, toolbar and menus, `.canvas` format, SVG export, sync logic (`AppController`) |
| `app/` | Android app: pen/finger input, folder and file access, PDF export |
| `desktop/` | Windows app: mouse/keyboard input, file dialogs, PDF export (PDFBox), MSI packaging |

### The `.canvas` format (v2)
JSON with `strokes` (each has an `id`, colour `c`, width `w`, points `p`, and `f: 1` for fills) and `deleted` (IDs of erased strokes). Version 1 files from older builds still open.
