# CONTEXT — quick-start for a fresh session

Use this file to get up to speed on the **Neo Library** project in one read. For the
full detail see `README.md` (architecture, workflows, invariants, build).

## What it is

A native **Android 4.4.4 (API 19)** library app for **Neo Reader 3.0**. It **scans
storage, browses, opens, and edits the metadata** of Neo Reader's supported documents.

## Supported formats (single list in `scan/Formats.ALL`)

`.CHM`, `.DOC`, `.DOCX`, `.DjVu`, `.EPub`, `.FB2`, `.FB2.ZIP` (compound ext!), `.FB3`,
`.HTML/.HTM`, `.MOBI/.AZW`, `.PDB`, `.PDF`, `.PRC`, `.RTF`, `.TXT`.

## Root path & stack

- Root: `D:\Projects\myLibrary`
- Java package: `com.example.mylibrary` under `app/src/main/java/`
- Toolchain: **AGP 8.5.2 · Gradle 8.7 · compileSdk/targetSdk 34 · minSdk 19 · JDK 17+**
- **No AndroidX, no support library, no third-party runtime deps** — only Android
  framework + JDK classes (`java.util.zip`, `XmlPullParser`). Builds offline.
- **Holo theme** (`android:Theme.Holo.Light`) because Material needs API 21+.

## Key architectural idea — two-layer metadata model

Every book's metadata is stored in **two places**:

1. **In-file** — only **EPUB** (`content.opf` OPF doc) and **FB2** (XML `description`
   block) are rewritten in place, so Neo Reader/other apps see edited values.
2. **In-catalog** — **all** formats always persist editable fields in a local **SQLite**
   DB (`db/BookDatabase`). This is the fallback for the other 13 formats (their binary
   metadata isn't safely rewritable here).

## Class map

| Class | Role |
|---|---|
| `MainActivity` | catalog: 3-stage load (fast scan → display → background enrich), browse, filter, "Recently read", SAF import; list is cursor-driven (framework `LoaderManager` + `CursorLoader`) |
| `DetailActivity` | open in Neo Reader / edit meta / remove; fast-path enrichment of a not-yet-enriched book on open |
| `EditMetaActivity` | metadata form → writes file (EPUB/FB2) + always updates catalog (`userEdited = true`) |
| `Book` | Parcelable model (`metaDone`, `metaFailed`, `userEdited` flags) |
| `db/BookDatabase` | SQLite catalog; **`upsert`/`upsertBasic`/`updateMetadata` preserve `last_read`**; `needMeta()` = stage-2 queue (un-enriched/`meta_failed` books ordered last); `markMetaFailed()`; static write lock |
| `db/BookProvider` | read-only `ContentProvider` — the `ContentObserver` channel for the `CursorLoader` (all / recent / `format=?`) |
| `scan/Formats` | canonical formats + ext→id map (handles `.fb2.zip`) |
| `scan/LibraryScanner` | stage-1 fast scan (skeleton books, no in-file meta) + `scanSingle()` |
| `meta/MetaEnricher` | stage-2 background worker: per-book `MetaExtractor` + `CoverExtractor` → DB + `CoverCache`, `notifyChange` per batch; `enrichOne` for import/fast path; **per-file parse budget of 2 min** — a parse that overruns it is abandoned and the book is marked un-enriched (`meta_failed=1`, stays `meta_done=0`, retried last on every rescan) |
| `meta/MetaExtractor` | read meta: EPUB/FB2/FB2ZIP (inner `.fb2` entry)/MOBI/TXT/HTML |
| `meta/MobiParser` | shared MOBI/AZW binary reader (package-private): PalmDB record table + MOBI header + EXTH |
| `meta/MetaWriter` | write meta EPUB/FB2, non-destructive (`.tmp`→swap→`.bak`) |
| `meta/CoverExtractor` | cover bytes: EPUB (`content.opf`→manifest), FB2 (`coverpage`→`<binary>`), FB2ZIP (inner `<binary>`, else loose image entry like `cover.jpg`), MOBI (EXTH record 201, JPEG trimmed at EOI) |
| `util/CoverCache` | durable file cache of cover **bytes** (`getExternalFilesDir("covers")/<hash>.img`, atomic `.tmp`→rename); warmed by the enricher |
| `util/CoverLoader` | async cover bitmap: `CoverCache` first, in-file extraction second, LruCache; hidden badge on success |
| `BookAdapter` | `CursorAdapter`; list + grid/tile view modes (grid loads covers); `getItem()` maps the cursor row to a `Book`; optional **client-side pagination** — `setPagination()` windows the whole cursor (the adapter keeps the full cursor, so every catalog rebind works unchanged) and serves one page; `changeCursor` walks the page back to the last existing one **before** the swap so the dataset callbacks (the screen's pager bar) see the clamped page |
| `util/Openers` | MIME map + `ACTION_VIEW` intent (`Uri.fromFile`) |

### List / tile view toggle

`MainActivity` shows the same `BookAdapter` in both a `ListView` and a `GridView`
(`activity_main.xml`). The toolbar button (`@+id/toggle_view`) calls `setViewMode()` which
swaps visibility and tells the adapter which layout to inflate. **Tiles are the
default view** (the grid starts visible, the list hidden, and the adapter attaches
to the grid at startup), and the chosen mode is **persisted in `SharedPreferences`**
(file `library_prefs`, key `view_mode`), so it survives rotation *and* the app
being closed. In **tile** mode
(3-column `GridView`) each `item_book_grid.xml` tile is one clickable unit: the cover
on top, a small uniform-width format label (`bg_format_badge`) at the cover's
bottom-left corner, the book title under the cover (fixed two lines) — all tiles
exactly the same size. The grid is sized in **px** for the ONYX Boox Volta 3
(6", 1024x758): fixed 242px tiles (`stretchMode=none`, `columnWidth=242px`),
8px gaps between columns and between rows, and `numColumns="auto_fit"`
so the columns fit the screen width — portrait 758px → 3 per row
(3×242 + 2×8 = 742px), landscape 1024px → 4 (4×242 + 3×8 = 992px),
wider → more. The books' container `FrameLayout` has a 16px top padding
and 8px left/right padding. Cover 242x387px (5:8), 155px red circle
(`bg_circle_red`) with the
title's initial for books without a cover; the scrollbar is `insideOverlay` so
it never steals column width.

### Pagination (the kebab's "Add/Remove pagination")

A checkable item in the header kebab (`main_menu_pagination`) switches the
catalog between one long scroll and fixed pages. When on, the strip
(`@+id/pagination_bar`: Prev / "X / Y" / Next) is shown and the adapter serves
one page at a time — a **client-side window over the whole cursor**, so every
catalog change (scan, enrich, import, remove, filter, clear) still rebinds in
place and the screen's direct-rebind paths work unchanged. The page size is
**6 tiles** in grid mode or **as many list rows as fit on the screen** in list
mode (`computeListPageSize()` measures the laid-out books-area height against
one measured row). Because that height changes under the user's fingers (the
stage-2 strip appearing/disappearing, the pager strip itself, the first layout
after a cold start), the `bookContainer` carries an
`OnLayoutChangeListener` that re-measures the list page size whenever the
container re-lays out at a **new height** (posted, so it reads the settled
height and never notifies the adapter mid-layout-pass). The on/off choice is
persisted in `SharedPreferences` (key `pagination_enabled`, like `view_mode`)
and is applied *after* the view mode, since the page size depends on it.
`changeCursor` walks the page back to the last existing one *before* the swap
so the `DataSetObserver` that refreshes the strip sees the clamped page; an
empty catalog hides the strip (0 pages).

While pagination is on, a page can also be turned by a **horizontal swipe**
over the list/grid (left = next, right = previous). The catalog's views are
`PagedListView`/`PagedGridView` (subclassing `ListView`/`GridView`): they
detect the gesture in a `dispatchTouchEvent` override feeding
`PageSwipeTracker` (a swipe = at release, |dx| ≥ 3×touchSlop and |dx| ≥ 2×|dy|;
a second finger cancels), and the page turn is *posted* so it runs after the
touch sequence has fully unwound — the same `showPage()` path as the buttons.
An `OnTouchListener` on the list would never fire for a swipe started on a
book: `ViewGroup.dispatchTouchEvent` hands the gesture to the row/tile first
and the (clickable) row consumes it, so the list's own listener only sees
touches on the bare padding. The views also `onInterceptTouchEvent` a gesture
the moment it qualifies as a swipe — on API 19 a row keeps its pre-pressed
state until the finger leaves its *bounds* (not distance from the down
point), and the list itself only intercepts *vertical* movement, so a swipe
staying inside a row would otherwise release into a row click ("Open this
book?"); the framework then sends the row `ACTION_CANCEL` and the remaining
events are consumed in `onTouchEvent`. Taps and wiggles under the threshold
are never intercepted.

## Main workflows

- **Scan (stage 1, fast)** → `startScan()` → AsyncTask → `LibraryScanner.scan()`
  (file walk only, skeleton book: path/format/size/title from the file name) →
  `db.upsertBasic()` per book → `notifyChange` → the `CursorLoader` re-queries and the
  list is on screen immediately; the user can already interact with it.
- **Enrich (stage 2, background)** → `MetaEnricher.start()` — a single worker over
  `db.needMeta()`: per book `MetaExtractor` + `CoverExtractor` → `db.updateMetadata()`
  (never clobbers `user_edited` values, keeps `last_read`) + `CoverCache` file write →
  `notifyChange` every batch → the cursor re-queries and rows refresh in place.
  Each file's parse (meta + cover) runs on a throwaway thread with a **2-minute
  budget**: a parse that overruns it is abandoned, the book is marked un-enriched
  (`db.markMetaFailed`, `meta_done` stays 0) and the worker moves on to the next
  file. On every rescan the un-enriched books are retried but taken last
  (`needMeta()` orders `meta_failed` last).
- **Open** → `DetailActivity` → `Openers.openFile()` → `ACTION_VIEW` → `markRead`.
  Fast path: a book still not enriched (opened before stage 2 reached it) is enriched
  on open, off the UI thread, and the detail screen refreshes itself.
- **Edit** → `EditMetaActivity`: if EPUB/FB2 → `MetaWriter.write(file,md)`; always →
  upsert with `userEdited = true` (the enricher will never overwrite those fields).
- **Import** → `ACTION_OPEN_DOCUMENT` (SAF) → validate → copy to `getExternalFilesDir("books")`
  → `scanSingle()` → `upsertBasic` → `MetaEnricher.enrichOne()` on the same background
  thread → `notifyChange` (the list picks the book up without a manual reload).

## Invariants (don't break)

- Keep **minSdk 19** working; **no APIs above 19**, **Holo** theme.
- **No AndroidX / no extra deps**.
- Manifest: **no `package` attribute**, every `<activity>` has `android:exported`,
  `namespace com.example.mylibrary` in `app/build.gradle`.
- Never leave a book file half-written (always `.tmp`→swap→`.bak`).
- `BookDatabase.upsert` must preserve `last_read`.
- `Formats.ALL` is the single source of truth for scanning/filter/badge.
- A single slow/hung file must never block the enrichment queue: the per-file parse
  budget is 2 minutes; overruns are abandoned, the book is marked un-enriched and
  retried last on every rescan.

## Header icons (SVG sources → committed PNGs)

The five `MainActivity` header icons (`ic_import`, `ic_refresh`, `ic_grid`,
`ic_list`, `ic_overflow`) are authored as SVG in `app/icons/` (48×48, white,
transparent). `ic_overflow` is the kebab that replaced the standalone import /
rescan header buttons: the kebab sits at the header's right corner and its popup
(`res/menu/main_menu.xml`) carries **Import**, **Rescan**, a checkable
**Add/Remove pagination** toggle (title + checkmark mirror the state, set in
`showHeaderMenu`; the mode itself is documented under "List / tile view
toggle" → Pagination) and **Clear library** (the latter wipes the whole catalog
— all rows + cover cache, on-disk files untouched — after a confirmation dialog;
`BookDatabase.clear()` is the backend).
The runtime PNGs in `app/src/main/res/drawable/` (32×32, the 32dp header
button size) are generated from them via
`tools\generate-icons.ps1` (drives `tools/SvgToPng`, a small dependency-free
.NET rasterizer — the OS WIC SVG path crashes on this machine, and the "no
extra deps" rule rules out third-party rasterizers). The generator
**supersamples**: it renders each SVG at 96×96 (2× the 48×48 source) and
reduces to 32 with an exact 3×3 area average, so the 32px edges are as crisp
as the vector source — no resampling softness. **The PNGs are
committed**, so the Android build stays 100% offline; the tool only runs when
an icon is redesigned. VectorDrawable is off the table at runtime (API 19 < 21).

## Build / run

- Android Studio 2024.2.2 (bundles JDK 17), SDK Platform 34. Sync → Run.
- CLI: `gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Lint: `gradlew.bat :app:lint` (Android Lint bundled with AGP, rules in `app/lint.xml`;
  reports in `app/build/reports/lint-results-*.{txt,xml,html}`). CI runs `:app:lintDebug`
  on every PR.
- Git hooks: `git config core.hooksPath git-hooks` → `pre-commit` runs Android Lint before
  every commit, `pre-push` blocks direct pushes to master and runs the test suite.
- If Studio reports a missing wrapper jar: **File ▸ Sync Project with Gradle Files**.
