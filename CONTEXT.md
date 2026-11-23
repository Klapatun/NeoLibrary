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
| `MainActivity` | catalog: 3-stage load (fast scan → display → background enrich), browse, the filter modal opened by the funnel header icon (`ic_filter`/`ic_filter_active`, empty selection = All) — framework **`TabHost`** (API 19, no ViewPager) with two `FilterTab` pages (package-private class `FilterTab.java`, not a nested type): **Formats** (static `Formats.ALL` vocabulary) and **Genres** (dynamic vocabulary — `db.distinctGenres()` on open; **locked while the stage-2 worker runs**: checkboxes untouchable, search/select-all/clear disabled, "Enriching metadata…" hint; unlocked with a fresh reference on `onFinished()`/`onCancelled()`); draft semantics (OK commits both tabs + resets `recentView`, Cancel discards, re-open lands on Genres if the draft has genres); each tab label carries an 8dp oval `#E74C3C` (`filter_tab_applied`) on the right while at least one item is checked in that tab's draft; the funnel dot remains `isActive()` of the committed state; "Recently read" — checkable item in the kebab menu (popup composition: `develop` skill `references/icons.md`), SAF import; list is cursor-driven (framework `LoaderManager` + `CursorLoader`) |
| `FilterTab` | one page of the filter dialog (Formats or Genres): search, select-all/clear, list; the draft dot through `bindAppliedMarker`; the genre lock is `setLoading`; vocabulary rebuild is `refresh` (the selection is stored by name). No Activity of its own. The dialog is assembled by `FilterTab.show`; OK calls `MainActivity.commitFilterDraft`. |
| `FilterState` | the single "state → query" conversion point for the catalog: `selectedFormats` (canonical `Formats.ALL` values) + `selectedGenres` (dynamic reference, first-added order — genres have no canonical list) + `recentView` (wins over both in the query: `RECENT_URI`, no selection); `querySelection()`/`queryArgs()` → the provider grammar (`format IN (…)`, `genre IS NOT NULL AND genre IN (…)`, both AND-ed, formats first), `formatFilterArgs()`/`genreFilterArgs()` for the direct `BookDatabase.cursorAll(String[], String[])` path (initial cursor / rescan / remove rebinds bypass the provider); session-only: `save()`/`restore()` via the instance state (survives rotation, not the app closing), `copy()` = the dialog's draft, `isActive()` drives the funnel dot |
| `DetailActivity` | book details and open in Neo Reader; the "other" block lists all genres comma-separated (or an em dash when none); fast-path enrichment of a not-yet-enriched book on open refreshes that block; the Edit metadata button (`@string/edit_meta`) above Open opens `EditMetaActivity` with `EXTRA_BOOK`; `onResume` re-reads the book by id; when `FileLocator.locateWork` is `ABSENT`, the `detail_missing` line with `file_not_found` is visible; `READY`, `DOWNLOADING`, and `FAILED` leave it hidden; the check runs on first show, in `onResume`, and after the fast-path update; the catalog and the cover cache are not written; the Holo action bar shows the up caret (`setDisplayHomeAsUpEnabled`); the home item calls `finish()` and does not start another Activity; there is no `parentActivityName`; system Back is the framework `finish()`, `onBackPressed` is not overridden |
| `EditMetaActivity` | metadata form (title, author, publisher, dynamic genre fields + "Add genre", a `\u2715` on each row: several rows removes that row, the only non-empty row clears the text, the only empty row hides it; "Add genre" is disabled while any row is empty after trim, description) → writes file (EPUB/FB2) + always updates catalog (`userEdited = true`); genres normalized to `Locale.ROOT` lower case on save; empty fields drop out of the list; the Holo action bar shows the up caret (`setDisplayHomeAsUpEnabled`); the home item calls `finish()` and does not start another Activity; there is no `parentActivityName`; system Back is the framework `finish()`, `onBackPressed` is not overridden |
| `Book` | Parcelable model (`genres` — ordered list from `work_genres`, empty list = no genre; cursor rows leave it empty until `getById`; plus `metaDone`, `metaFailed`, `userEdited` flags) |
| `db/Catalog` | the **only** write entry for the catalog from screens and `MetaEnricher`. One-shot doors (`saveScanBatch`, `removeMissing`, `importFile`, `saveUserEdit` (user edit of an existing work by id, one `notifyChangeAll` even when 0 rows: extra refresh, not an insert, no INSERT), `saveUserEditAtNewPath` (user edit and `files.path` change by work id, one `notifyChangeAll`, no INSERT), `markRead`, `deleteByPath`, `clear`, `applyEnrichmentBatch`, `applyEnrichmentOne`, `markMetaFailed`) end in `BookProvider.notifyChangeAll` (list + recently read). The stage-2 loop writes through `persistEnrichmentBatch` / `persistMetaFailed` (no notify) and announces with throttled `announceChangeAll` (both URIs, no write). Reads stay on `BookDatabase` |
| `db/BookDatabase` | SQLite catalog (**DB v10**: `works` + `files` + `work_genres`; cursor `_id` = work id, local scan still 1 file = 1 work; server columns exist but are not written yet; `files.adopted_from` is the previous absolute path when an EPUB/FB2 was copied into the app directory, otherwise NULL — not written into `remote_key` / `content_hash` and not a field on `Book`; upgrade to 9 resets `meta_done` and `meta_failed` on EPUB/FB2/FB2ZIP files whose work is `deleted=0`, `user_edited=0` and has no `work_genres` rows, no new column; upgrade to 10 resets `meta_done` and `meta_failed` on FB3 files whose work is `deleted=0` and `user_edited=0`, no new column); the locator reads an edition with `getFileEdition` / `listFileEditions` (id, path, availability, remote_key, format) and does not write those columns; **`upsert`/`upsertBasic`/`updateMetadata` preserve `last_read`** (`updateMetadata` writes author/publisher/description/series only for a non-null value and `fileReadable`, genres only when `md.genres != null`, both with the same merge of `user_edited` / a blank field; `works.title` only when `md.found`; a null field and null genres do not wipe the catalog; `meta_done=1` is still set even when no field was written); `cursorAll` genre filter = EXISTS on `work_genres`; `distinctGenres()` reads `work_genres` in name order; `needMeta()` = stage-2 queue on `files.meta_done` (un-enriched last); hasPendingMeta() is whether that queue is non-empty (same rows: works.deleted=0 and files.meta_done=0) without building Book rows; MainActivity.startEnrichment calls it before MetaEnricher.start; static write lock — **not** called directly from Activity/`MetaEnricher` for writes |
| `db/BookProvider` | read-only `ContentProvider` — the `ContentObserver` channel for the `CursorLoader` (all / recent / `format IN (?,…)` / `genre IS NOT NULL AND genre IN (?,…)` / both combined — the combined selection args are split on the format-count boundary: `FilterState.queryArgs()` = formats in `Formats.ALL` order, then genres in first-added order) |
| `scan/Formats` | canonical formats + ext→id map (handles `.fb2.zip`); `enrichmentPriority` tier 0 is EPUB, FB2 and FB3 (FB3 is the same cheap ZIP+XML as EPUB), then FB2ZIP, MOBI, the rest |
| `scan/LibraryScanner` | stage-1 fast scan (skeleton books, no in-file meta) + `scanSingle()`; the stage-1 walk does not add a path already stored in `files.adopted_from` (canonical match, the set is passed by `ScanTask`) and does not enter the app external-files directory (`ScanTask` passes `getExternalFilesDir(null)`); `scanSingle()` does not hide paths |
| `ScanTask` | package-private `AsyncTask` in its own file; holds `Catalog` / `BookDatabase` and the screen through `WeakReference`; background work passes `adoptedFromPaths` and `getExternalFilesDir(null)` into `LibraryScanner.scanDetailed`; the UI tail is `onScanProgressDone` / `onScanPosted` |
| `ImportTask` | package-private `AsyncTask`; app context for the copy; Activity and `ProgressDialog` held weakly; `dismiss` even if the screen is already dead; then `onImportPosted`. `launchImport` / `onActivityResult` / `queryDisplayName` / `importToLibrary` stay on `MainActivity` |
| `meta/MetaEnricher` | stage-2 background worker: per-book `MetaExtractor` + `CoverExtractor` → `Catalog` (persist batch + throttled `announceChangeAll`, both URIs) + `CoverCache`; `enrichOne` for import/fast path; the fast path joins an id already inside `enrichOneBook` (waits and may persist that result itself); import calls `enrichOne(..., false)` and still parses when that id is busy; the bulk loop skips a claimed id and does not mark that skip failed; **per-file parse budget of 2 min** — a parse that overruns it is abandoned and the book is marked un-enriched (`meta_failed=1`, stays `meta_done=0`, retried last on every rescan); single-pass (one file session, metadata and cover) for EPUB, FB2, FB2ZIP, MOBI and FB3; the pass **works the queue cheapest-format-first** (EPUB/FB2/FB3 → FB2ZIP → MOBI → the rest, `Formats.enrichmentPriority`; un-enriched books still last of all); the `OnProgress` contract: `onFinished()` fires on a finished pass and is **never** sent after `cancel()` — a cancel fires `onCancelled()` instead (the listener is held weakly, a fresh `start()` reassigns it, so a `start()`-internal cancel can't clear the new pass's state) |
| `meta/XmlText` | package-private FB2/OPF bytes: BOM, otherwise the prologue `encoding` attribute, otherwise UTF-8; a decode or encode failure returns null; the declaration is left as it is and the file is not converted to UTF-8 |
| `meta/MetaExtractor` | read meta: EPUB/FB2/FB2ZIP (inner `.fb2` entry)/MOBI/FB3/TXT/HTML; FB2, OPF and FB3 description bytes go through `XmlText`; FB3 title is the first direct `fb3-description/title` (`main`, else a direct `p`; not a `title` inside `sequence` / `periodical` / `subject` / `object`), author and publisher are `fb3-relations/subject` with `link` `author` / `publisher`, description is the direct `annotation`, genres are each `fb3-classification/subject` via `normalizeGenres` (non-null after a finished `fb3-description`, null when that root was not read), series is the first direct `sequence` title, language is the direct `lang`; FB3 `found` is only that non-empty title, then the file name may fill `md.title` without raising `found`; FB2 all `<genre>` in `title-info` (or until `</description>` when unclosed), EPUB all `dc:subject` → normalized `md.genres`; FB2 takes the first `book-title` in `title-info`, otherwise the first `title` there, and does not take a `<title>` after `<body>` or a `book-title` from `src-title-info`; FB2 `found` is that non-empty tag; after a finished parse the file name is written into `md.title` and is not part of `found` |
| `meta/MobiParser` | shared MOBI/AZW binary reader (package-private): PalmDB record table + MOBI header + EXTH |
| `meta/Fb3Package` | package-private FB3 reader on an already-open `ZipFile`: one read returns `MetaData` and cover bytes; the description part is the first non-external `Book` relationship in root `_rels/.rels` (not `[Content_Types].xml`, not a guessed `description.xml`, not `body.xml`); the cover is the first root thumbnail relationship, otherwise the description part's first `cover` relationship; an external thumbnail or cover does not select a later image; targets are percent-decoded and `\` in an entry name matches `/` |
| `meta/MetaWriter` | write meta EPUB/FB2 through `XmlText`, non-destructive (`.tmp`→swap→`.bak`); null `title`/`author`/`publisher`/`description` leaves that field untouched; a non-empty genre list is placed in `title-info` / `metadata` and that block is created when it is missing; if there is nowhere to put it, `write` returns false and the file bytes stay the same; `md.genres == null` leaves file genres alone, empty list clears them |
| `meta/CoverExtractor` | cover bytes: EPUB (`content.opf`→manifest), FB2 (`coverpage`→`<binary>`), FB2ZIP (inner `<binary>`, else loose image entry like `cover.jpg`), MOBI (EXTH record 201, JPEG trimmed at EOI), FB3 (first root `_rels/.rels` thumbnail, else the description part's first cover relationship; an external thumbnail yields no cover; bytes from `readEntryBytes`, an oversized entry is null and does not drop metadata; `canHaveCover` includes FB3) |
| `util/CoverCache` | durable file cache of cover **bytes** (`getExternalFilesDir("covers")/<sha1>.img`, SHA-1 of the path's UTF-8 bytes, 40 lowercase hex; atomic `.tmp`→rename); warmed by the enricher |
| `util/CoverLoader` | async cover bitmap: `CoverCache` first, in-file extraction second, LruCache; hidden badge on success |
| `BookAdapter` | `CursorAdapter`; list + grid/tile view modes (grid loads covers); `getItem()` maps the cursor row to a `Book`; optional **client-side pagination** — `setPagination()` windows the whole cursor (the adapter keeps the full cursor, so every catalog rebind works unchanged) and serves one page; `changeCursor` walks the page back to the last existing one **before** the swap so the dataset callbacks (the screen's pager bar) see the clamped page. Layout numbers (px tiles, page sizes, swipe) — `develop` skill `references/ui.md` |
| `util/FileLocator` | before open: `locateFile` (one `files` row) or `locateWork` (a work). Reads path, availability, remote_key, format and `File.exists()`. `LocateResult` status is an int: `READY` 0, `ABSENT` 1, `DOWNLOADING` 2, `FAILED` 3. `downloading` wins even when a file is already on disk. `locateWork` prefers an edition that is `local` and still on disk; if none is, `downloading` wins over `failed`, and `failed` wins over `absent` (a later download is not reported as a missing file). When every edition is absent, the result is the smallest `files._id`. Does not write the DB, does not queue or start a download |
| `util/Openers` | MIME map + `ACTION_VIEW` intent (`Uri.fromFile`). Screens call it only after the locator returns `READY` |
| `remote/LibraryClient` | the only HTTP client (`HttpURLConnection`). Base URL and bearer token are `BuildConfig.API_BASE_URL` / `API_TOKEN`: debug is `http://10.0.2.2:8080` and `dev-library-token`, release leaves both empty. Every request sends `Authorization: Bearer …` and a path under `/v1` (cold start prefetches `GET /v1/index` and does not write the catalog). The header line `connection_status` shows Connecting… / Connected / No connection / `HTTP <code>`; it stays hidden when the build has no URL. Redirects are off. Loopback is refused; a debug build also refuses any endpoint other than `http://10.0.2.2:8080`. Cleartext is the debug manifest only. Robolectric does not open a socket |

### UI: view modes / pagination / swipe — pointer

The full layout/gesture detail lives in `.agents/skills/develop/references/ui.md`:
the list/tile toggle (`setViewMode`, `library_prefs`
key `view_mode`, tiles as the default view), the grid px sizing for the ONYX Boox
Volta 3 (242px tiles, `auto_fit`, paddings, 242×328 cover, 155px `bg_circle_red`
initial placeholder), pagination (kebab `main_menu_pagination`, `pagination_bar`,
client-side window over the whole cursor, 6-tile grid pages / `computeListPageSize()`
list pages, the `OnLayoutChangeListener` posted recompute, `pagination_enabled`
persistence, the `changeCursor` page-clamp), and the horizontal swipe
(`PagedListView`/`PagedGridView` → `PageSwipeControl` → `PageSwipeTracker`, posted `showPage` via the view's `post`, the API-19
row-prepress intercept pitfalls). One-liners kept here: **tiles are the default view**;
the view mode and the pagination on/off toggle **persist in `library_prefs`**; when
pagination is on, a horizontal swipe also turns pages. The current page (pagination
on) and the scroll position (pagination off: the first visible row and its top)
live in the instance state, like `FilterState`; they survive rotation and a return
from details or the editor after the catalog is recreated; they are not written to
`library_prefs`; a cold start is still page 0 and the top of the list.

## Main workflows

- **Scan (stage 1, fast)** → `startScan()` → AsyncTask → `LibraryScanner.scan()`
  (file walk only, skeleton book: path/format/size/title from the file name) →
  `Catalog.saveScanBatch()` / `removeMissing()` → `notifyChangeAll` → the
  `CursorLoader` re-queries and the list is on screen immediately; the user can
  already interact with it. A path already stored in `files.adopted_from` does not
  enter `saveScanBatch` (the match is canonical, so another prefix of the same
  file is still the original) and does not become a second book while the original
  file is still in shared storage. The walk does not enter the app external-files
  directory, so a private copy there is not discovered as a new book.
  `removeMissing` still runs only for a root whose walk finished (`complete`) and
  only for paths under that root that the walk did not see and that are not a
  file. Paths outside those roots are not deleted: an unmounted volume is not
  distinguishable from a deletion. A rename without a scan does not move the
  work: until the next full scan of that root the old row stays, and the detail
  mark shows `ABSENT` when the file is gone; after the scan the new path is a
  new work. There is no full `File.exists()` pass over the `files` table on a scan.
- **Enrich (stage 2, background)** → `MetaEnricher.start()` — a single worker over
  `db.needMeta()`, which is split by format type and worked cheapest-first
  (EPUB, FB2, FB3 → FB2ZIP → MOBI → the rest; the un-enriched/timed-out books stay last
  of all, `Formats.enrichmentPriority`): per book `MetaExtractor` + `CoverExtractor`
  → `Catalog.persistEnrichmentBatch()` / `applyEnrichmentOne()` (never clobbers
  `user_edited` values, keeps `last_read`) + `CoverCache` file write → throttled
  `Catalog.announceChangeAll()` every batch (both URIs) → the cursor re-queries and
  rows refresh in place.
  A book with `meta_done=1` does not return to the queue by itself; upgrade v9 once
  returns EPUB/FB2/FB2ZIP files with no genres and no user edit, so genres from the
  file reach the UI. A missing canonical title no longer blocks genres and the other
  non-null fields.
  Each file's parse (meta + cover) runs on a throwaway thread with a **2-minute
  budget**: a parse that overruns it is abandoned, the book is marked un-enriched
  (`Catalog.persistMetaFailed`, `meta_done` stays 0) and the worker moves on to the
  next file. On every rescan the un-enriched books are retried but taken last
  (`needMeta()` orders `meta_failed` last). The bulk loop does not start a second
  parse for an id already inside `enrichOneBook`; that skip is not a failure and
  does not set `meta_failed`.
- **Open** → `DetailActivity` / list confirmation → `FileLocator.locateWork()`
  first. Only `READY` continues to `Openers.openFile()` → `ACTION_VIEW` →
  `Catalog.markRead()` (last_read is set only after the viewer actually launches).
  `ABSENT` shows the same "File not found" toast as a missing file. `DOWNLOADING`
  and `FAILED` show a short toast and stop. The toast on the Open button is not
  replaced by the on-screen mark. `ABSENT`, `DOWNLOADING`, and `FAILED` still do
  not call `Openers` and do not set last_read. There is no Download button: this
  screen does not start a download and does not put one on a queue. Fast path: a
  book still not enriched (opened before stage 2 reached it) is enriched on open,
  off the UI thread, and the detail screen refreshes itself. If that id is already
  being parsed, this path does not read the file again; it waits for that parse
  and writes the single-book result so the screen does not wait for the bulk batch.
- **Edit** → `EditMetaActivity`: for EPUB/FB2, if the file's directory accepts a
  create-and-delete probe, `MetaWriter.write` on that file (full genre list: EPUB
  `dc:subject`, FB2 `<genre>`). If the probe fails, the book is copied into
  `getExternalFilesDir("books")`, the original stays in place, `MetaWriter.write`
  runs on the copy, and the same catalog transaction changes this work's
  `files.path` to the copy and stores the previous absolute path in
  `files.adopted_from`. No new work row is inserted. `last_read` is unchanged.
  A failed copy does not change the path and does not leave a partial file.
  Catalog edits always go through `Catalog` (`saveUserEdit` or
  `saveUserEditAtNewPath`). `saveUserEdit` updates this work and its file by
  id and does not insert a work or a file when the row is already gone.
  `write == false` still saves the catalog on the
  current path in `SaveTask` and leaves the toast "Could not write into the
  file"; that false does not start a second copy. The enricher does not clobber
  these fields. Genres live in `work_genres` (normalization unchanged).
- **Import** → `ACTION_OPEN_DOCUMENT` (SAF) → validate → copy to `getExternalFilesDir("books")`
  → `scanSingle()` → `Catalog.importFile()` → `MetaEnricher.enrichOne()` on the same
  background thread (each step announces via the facade).

## Invariants (don't break)

- Keep **minSdk 19** working; **no APIs above 19**, **Holo** theme.
- **No AndroidX / no extra deps**.
- Manifest: **no `package` attribute**, every `<activity>` has `android:exported`,
  `namespace com.example.mylibrary` in `app/build.gradle`.
- Never leave a book file half-written (private copy: `.copying`→rename, then
  MetaWriter `.tmp`→swap→`.bak`; a failed copy leaves no partial file).
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
