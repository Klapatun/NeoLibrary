# CONTEXT — quick-start for a fresh session

Use this file to get up to speed on the **Neo Library** project in one read. For the
full detail see `README.md` (architecture, workflows, invariants, build). UI
layout/gesture numbers and the icon toolchain live next to the `develop` skill
(`.agents/skills/develop/references/ui.md` and `references/icons.md`) — this file keeps only the pointers.

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
| `MainActivity` | catalog: 3-stage load (fast scan → display → background enrich), browse, the filter modal opened by the funnel header icon (`ic_filter`/`ic_filter_active`, empty selection = All) — framework **`TabHost`** (API 19, no ViewPager) with two `FilterTab` pages: **Formats** (static `Formats.ALL` vocabulary) and **Genres** (dynamic vocabulary — `db.distinctGenres()` on open; **locked while the stage-2 worker runs**: checkboxes untouchable, search/select-all/clear disabled, "Enriching metadata…" hint; unlocked with a fresh reference on `onFinished()`/`onCancelled()`); draft semantics (OK commits both tabs + resets `recentView`, Cancel discards, re-open lands on Genres if the draft has genres); "Recently read" — checkable item in the kebab menu (popup composition: `develop` skill `references/icons.md`), SAF import; list is cursor-driven (framework `LoaderManager` + `CursorLoader`) |
| `FilterState` | the single "state → query" conversion point for the catalog: `selectedFormats` (canonical `Formats.ALL` values) + `selectedGenres` (dynamic reference, first-added order — genres have no canonical list) + `recentView` (wins over both in the query: `RECENT_URI`, no selection); `querySelection()`/`queryArgs()` → the provider grammar (`format IN (…)`, `genre IS NOT NULL AND genre IN (…)`, both AND-ed, formats first), `formatFilterArgs()`/`genreFilterArgs()` for the direct `BookDatabase.cursorAll(String[], String[])` path (initial cursor / rescan / remove rebinds bypass the provider); session-only: `save()`/`restore()` via the instance state (survives rotation, not the app closing), `copy()` = the dialog's draft, `isActive()` drives the funnel dot |
| `DetailActivity` | book details and open in Neo Reader; the "other" block always shows genre (the parsed value, or an em dash when metadata parsing found none); fast-path enrichment of a not-yet-enriched book on open refreshes that block |
| `EditMetaActivity` | metadata form → writes file (EPUB/FB2) + always updates catalog (`userEdited = true`) |
| `Book` | Parcelable model (`genre` field — from FB2 enrichment, plus `metaDone`, `metaFailed`, `userEdited` flags) |
| `db/BookDatabase` | SQLite catalog (**DB v6**; the `genre TEXT` column added by the v5→v6 migration — idempotent `ALTER TABLE` guard like `meta_failed` v3; nullable = no genre); **`upsert`/`upsertBasic`/`updateMetadata` preserve `last_read`** (`updateMetadata` writes `genre` with the same merge rule as `series`: enrichment fills only the blank, never clobbers `user_edited`); `cursorAll(formatFilter, genreFilter)` — each dimension null/empty = unrestricted, genres as `genre IS NOT NULL AND genre IN (…)`; `distinctGenres()` = the dynamic genre reference (`SELECT DISTINCT genre … WHERE genre IS NOT NULL ORDER BY genre`); `needMeta()` = stage-2 queue (un-enriched/`meta_failed` books ordered last); `markMetaFailed()`; static write lock |
| `db/BookProvider` | read-only `ContentProvider` — the `ContentObserver` channel for the `CursorLoader` (all / recent / `format IN (?,…)` / `genre IS NOT NULL AND genre IN (?,…)` / both combined — the combined selection args are split on the format-count boundary: `FilterState.queryArgs()` = formats in `Formats.ALL` order, then genres in first-added order) |
| `scan/Formats` | canonical formats + ext→id map (handles `.fb2.zip`) |
| `scan/LibraryScanner` | stage-1 fast scan (skeleton books, no in-file meta) + `scanSingle()` |
| `meta/MetaEnricher` | stage-2 background worker: per-book `MetaExtractor` + `CoverExtractor` → DB + `CoverCache`, `notifyChange` per batch; `enrichOne` for import/fast path; **per-file parse budget of 2 min** — a parse that overruns it is abandoned and the book is marked un-enriched (`meta_failed=1`, stays `meta_done=0`, retried last on every rescan); the pass **works the queue cheapest-format-first** (EPUB/FB2 → FB2ZIP → MOBI → the rest, `Formats.enrichmentPriority`; un-enriched books still last of all); the `OnProgress` contract: `onFinished()` fires on a finished pass and is **never** sent after `cancel()` — a cancel fires `onCancelled()` instead (the listener is held weakly, a fresh `start()` reassigns it, so a `start()`-internal cancel can't clear the new pass's state) |
| `meta/MetaExtractor` | read meta: EPUB/FB2/FB2ZIP (inner `.fb2` entry)/MOBI/TXT/HTML |
| `meta/MobiParser` | shared MOBI/AZW binary reader (package-private): PalmDB record table + MOBI header + EXTH |
| `meta/MetaWriter` | write meta EPUB/FB2, non-destructive (`.tmp`→swap→`.bak`) |
| `meta/CoverExtractor` | cover bytes: EPUB (`content.opf`→manifest), FB2 (`coverpage`→`<binary>`), FB2ZIP (inner `<binary>`, else loose image entry like `cover.jpg`), MOBI (EXTH record 201, JPEG trimmed at EOI) |
| `util/CoverCache` | durable file cache of cover **bytes** (`getExternalFilesDir("covers")/<hash>.img`, atomic `.tmp`→rename); warmed by the enricher |
| `util/CoverLoader` | async cover bitmap: `CoverCache` first, in-file extraction second, LruCache; hidden badge on success |
| `BookAdapter` | `CursorAdapter`; list + grid/tile view modes (grid loads covers); `getItem()` maps the cursor row to a `Book`; optional **client-side pagination** — `setPagination()` windows the whole cursor (the adapter keeps the full cursor, so every catalog rebind works unchanged) and serves one page; `changeCursor` walks the page back to the last existing one **before** the swap so the dataset callbacks (the screen's pager bar) see the clamped page. Layout numbers (px tiles, page sizes, swipe) — `develop` skill `references/ui.md` |
| `util/Openers` | MIME map + `ACTION_VIEW` intent (`Uri.fromFile`) |

### UI: view modes / pagination / swipe — pointer

The full layout/gesture detail lives in `.agents/skills/develop/references/ui.md`:
the list/tile toggle (`setViewMode`, `library_prefs`
key `view_mode`, tiles as the default view), the grid px sizing for the ONYX Boox
Volta 3 (242px tiles, `auto_fit`, paddings, 242×328 cover, 155px `bg_circle_red`
initial placeholder), pagination (kebab `main_menu_pagination`, `pagination_bar`,
client-side window over the whole cursor, 6-tile grid pages / `computeListPageSize()`
list pages, the `OnLayoutChangeListener` posted recompute, `pagination_enabled`
persistence, the `changeCursor` page-clamp), and the horizontal swipe
(`PagedListView`/`PagedGridView` → `PageSwipeTracker`, posted `showPage`, the API-19
row-prepress intercept pitfalls). One-liners kept here: **tiles are the default view**;
the view mode and the pagination on/off toggle **persist in `library_prefs`**; when
pagination is on, a horizontal swipe also turns pages.

## Main workflows

- **Scan (stage 1, fast)** → `startScan()` → AsyncTask → `LibraryScanner.scan()`
  (file walk only, skeleton book: path/format/size/title from the file name) →
  `db.upsertBasic()` per book → `notifyChange` → the `CursorLoader` re-queries and the
  list is on screen immediately; the user can already interact with it.
- **Enrich (stage 2, background)** → `MetaEnricher.start()` — a single worker over
  `db.needMeta()`, which is split by format type and worked cheapest-first
  (EPUB, FB2 → FB2ZIP → MOBI → the rest; the un-enriched/timed-out books stay last
  of all, `Formats.enrichmentPriority`): per book `MetaExtractor` + `CoverExtractor`
  → `db.updateMetadata()` (never clobbers `user_edited` values, keeps `last_read`) +
  `CoverCache` file write → `notifyChange` every batch → the cursor re-queries and
  rows refresh in place.
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
- `Formats.ALL` is the single source of truth for scanning/filter/badge. The genre
  reference has NO canonical list — it is formed dynamically from the enriched books
  (`db.distinctGenres()`), never hardcoded.
- Catalog filters are **session-only** (`FilterState` in the instance state): they
  survive a rotation, not the app closing; `recentView` wins over the format AND genre
  filters in the query (`RECENT_URI`, no selection) while the selection itself stays
  configured (the funnel dot).
- A single slow/hung file must never block the enrichment queue: the per-file parse
  budget is 2 minutes; overruns are abandoned, the book is marked un-enriched and
  retried last on every rescan.

## Header icons (SVG sources → committed PNGs)

The six `MainActivity` header icons (`ic_import`, `ic_refresh`, `ic_grid`,
`ic_list`, `ic_overflow` — the kebab at the header's right corner — and the filter
funnel pair `ic_filter` / `ic_filter_active`: the funnel, and the same funnel with
an accent dot in the top-right corner while a format filter is configured) are
authored as SVG in `app/icons/` (48×48, white, transparent — the dot is the only
accent-coloured element, `#E74C3C`). The runtime PNGs in
`app/src/main/res/drawable/` (32×32, the 32dp header button size) are generated from
them via `tools\generate-icons.ps1` and **committed**, so the Android build stays
100% offline — the tool runs only when an icon is redesigned. Toolchain detail
(the dependency-free `SvgToPng` rasterizer, why OS WIC is off the table, the 96→32
supersampling) — `.agents/skills/develop/references/icons.md`.

## Build / run

- Android Studio 2024.2.2 (bundles JDK 17), SDK Platform 34. Sync → Run.
- CLI: `gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Lint: `gradlew.bat :app:lint` (Android Lint bundled with AGP, rules in `app/lint.xml`;
  reports in `app/build/reports/lint-results-*.{txt,xml,html}`). CI runs `:app:lintDebug`
  on every PR.
- Git hooks: `git config core.hooksPath git-hooks` → `pre-commit` runs Android Lint before
  every commit, `pre-push` blocks direct pushes to master and runs the test suite.
- If Studio reports a missing wrapper jar: **File ▸ Sync Project with Gradle Files**.
