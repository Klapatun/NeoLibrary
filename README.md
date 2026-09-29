# Neo Library — a book library app for Neo Reader 3.0 (Android 4.4.4)

A native **Android 4.4.4 (API 19)** library app built to **search, browse, open, and
edit the metadata** of the document set supported by **Neo Reader 3.0**.

## Supported formats

The app scans storage for and opens in Neo Reader every format Neo Reader 3.0 can read:

| Format | Extension | In-file metadata read | In-file metadata write |
|--------|-----------|----------------------|------------------------|
| CHM        | `.chm`       | —            | —   |
| DOC        | `.doc`       | —            | —   |
| DOCX       | `.docx`      | —            | —   |
| DjVu       | `.djvu`      | —            | —   |
| EPUB       | `.epub`      | ✅ (OPF)     | ✅ (OPF rebuild) |
| FB2        | `.fb2`       | ✅ (XML)     | ✅ (XML patch) |
| FB2.ZIP    | `.fb2.zip`   | ✅ (inner FB2) | —   |
| FB3        | `.fb3`       | —            | —   |
| HTML/HTM   | `.html/.htm` | ✅ (`<title>`) | — |
| MOBI/AZW   | `.mobi/.azw` | ✅ (PalmDB/EXTH) | —   |
| PDB        | `.pdb`       | —            | —   |
| PDF        | `.pdf`       | —            | —   |
| PRC        | `.prc`       | —            | —   |
| RTF        | `.rtf`       | —            | —   |
| TXT        | `.txt`       | ✅ (file name) | — |

**Metadata editing model:** every format gets its editing-capable fields stored in a
local SQLite catalog (title, author, publisher, description). On top of that, **EPUB**
and **FB2** are *rewritten in place* so Neo Reader and any other app see the edited
title/author/publisher/description *inside the file itself*.

## Features

- **Scan** — recursive walk of `Environment.getExternalStorageDirectory()` plus common
  secondary SD-card mount points, filtered to the supported extensions.
- **Browse** — list sorted by title, with a per-format filter and a *Recently read* view.
  Toggle between a **list** and a **tile/grid** layout (grid shows the embedded cover
  preview for EPUB/FB2/FB2.ZIP/MOBI, with a letter badge for formats without a cover).
- **Open in Neo Reader** — sends an `ACTION_VIEW` intent with the correct MIME type and a
  `file://` URI so Neo Reader (or any other viewer) opens the book; the file is marked
  as "recently read".
- **Edit metadata** — a form for title/author/publisher/description that writes back into
  EPUB/FB2 files and always updates the local catalog.
- **Import** — KitKat SAF `ACTION_OPEN_DOCUMENT` picker copies any supported file into the
  app's own storage when it isn't reachable by path.

## Metadata implementation notes

- **ZIP/EPUB** reading/rebuild uses only `java.util.zip` (`ZipInputStream`/`ZipOutputStream`);
  the OPF package path is resolved via `META-INF/container.xml`.
- **XML** parsing uses the platform `XmlPullParser` (`android.util.Xml`).
- **MOBI/AZW** is read natively: the PalmDB record table (byte 78) locates record 0, whose
  MOBI header gives the book's full name (the title) and code page; the optional EXTH block
  carries author/publisher/description/language and the cover offset (record 201, added to
  the first image index to find the cover JPEG, trimmed at its end-of-image marker).
- **EPUB editing** rebuilds the archive, replacing the `content.opf` `<dc:*>` elements and
  inserting missing ones into `<metadata>`.
- **FB2 editing** patches the `description`/`title-info` elements in place and rewrites the
  file.
- Both writers first write a `.tmp`, then swap over the original with a `.bak` backup and
  restore it on failure — the source file is never left half-written.

## Architecture

The app is native, no AndroidX/support libraries — built purely on the Android framework
and JDK classes, which guarantees API 19 compatibility. Code is split into layers by
responsibility:

```
┌────────────────────────── UI (plain Activity, Holo theme) ───────────────────────┐
│   MainActivity        browse list · filter by format · "Recently read" · import │
│   DetailActivity      open in Neo Reader · edit meta · remove from library      │
│   EditMetaActivity    metadata editing form (title/author/publisher/desc)        │
│   BookAdapter         ListView adapter                                          │
└───────────────────────────────┬──────────────────────────────────────────────────┘
                                 │ Book (Parcelable) — passed between activities
┌────────────────────────── LOGIC / format-handling layers ────────────────────────┐
│   scan/Formats          single source of truth: 15 formats + extension mapping  │
│   scan/LibraryScanner   recursive storage scan; builds Book from each file      │
│   meta/MetaExtractor    reads meta from EPUB/FB2/FB2.ZIP/MOBI/TXT/HTML (XML/zip)│
│   meta/MobiParser        shared MOBI/AZW binary reader (PalmDB/EXTH) + cover rec│
│   meta/MetaWriter       writes metadata into EPUB/FB2 (rebuild + .bak backup)   │
│   util/Openers          MIME mapping + ACTION_VIEW intent for Neo Reader        │
└───────────────────────────────┬──────────────────────────────────────────────────┘
                                 │ persists via
┌────────────────────────────── STORAGE ───────────────────────────────────────────┐
│   db/BookDatabase        SQLite catalog (title/author/publisher/desc, last_read)│
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Two-layer metadata model (the key idea)

Every book's metadata lives in **two places**:

1. **In-file** — for **EPUB** (the `content.opf` OPF document) and **FB2** (the XML
   `description` block), metadata is *also* written into the file itself, so Neo Reader
   and any other app see the edited values.
2. **In-catalog** — *all* formats always persist editing-capable fields in the local
   **SQLite** catalog. This is the fallback for the other 13 formats whose binary
   structures are not safely rewritable here (CHM/DjVu/MOBI/PDF/DOCX...).

### Responsibilities per class

| Class | Responsibility | Key detail |
|---|---|---|
| `MainActivity` | catalog screen: scan, browse, filter, recently-read, SAF import | never clears the DB on rescan → `last_read` survives |
| `DetailActivity` | one book: open / edit / remove | open fires `ACTION_VIEW`, then `markRead` |
| `EditMetaActivity` | metadata form | writes file for EPUB/FB2, always updates catalog |
| `Book` | Parcelable model | `initial()`, `displayFormat()` helpers |
| `BookDatabase.upsert` | insert-or-update by path | preserves `last_read` (rejects REPLACE) |
| `Formats` | canonical format ids + extension map | `.fb2.zip` handled as compound extension |
| `LibraryScanner` | recursive scan + `scanSingle()` | primary ext storage + common SD mounts |
| `MetaExtractor` | read meta | EPUB via `container.xml`→OPF; FB2 author split; FB2.ZIP via the inner `.fb2` entry; MOBI via `MobiParser` |
| `MobiParser` | read MOBI/AZW (package-private) | PalmDB record table + MOBI header + EXTH; cover = first image + EXTH 201 |
| `MetaWriter` | write meta | non-destructive: `.tmp` → swap → `.bak` recovery |
| `CoverExtractor` | read cover image bytes | EPUB via `content.opf`→manifest; FB2 via `coverpage`→`<binary>`; FB2.ZIP via the inner `<binary>` or a loose image entry (e.g. `cover.jpg`); MOBI via EXTH record 201 (JPEG trimmed at EOI) |
| `CoverLoader` | async cover bitmap + LruCache | hides letter badge once cover shows |
| `BookAdapter` | list + grid/tile view modes | re-inflates on mode switch; grid loads covers |
| `Openers` | MIME map + `ACTION_VIEW` | `Uri.fromFile` (ok on KitKat) |

## Data flows (workflows)

The main user paths:

- **Scan** → `MainActivity.startScan()` → `AsyncTask` → `LibraryScanner.scan()` (recursive
  walk + `MetaExtractor`) → `onPostExecute` upserts each book into `BookDatabase` → `reload()`.
- **Open** → list click → `DetailActivity` loads `Book` by id → button → `Openers.openFile()`
  returns `ACTION_VIEW` → `startActivity()` → `db.markRead(id)` (feeds "Recently read").
- **Edit** → `EditMetaActivity` form → save in `AsyncTask`: if format is EPUB/FB2 and file
  exists → `MetaWriter.write(file, md)` (in-file); **always** → `db.upsert(book)` (catalog).
- **Import** → `ACTION_OPEN_DOCUMENT` (SAF) → validate via `Formats.isSupported()` → copy
  the picked file into `getExternalFilesDir("books")` → `LibraryScanner.scanSingle()` → upsert.

## Invariants (do not break)

- **`minSdk 19`** — the whole app must keep working on **Android 4.4.4 (API 19)**: no
  APIs above 19, **Holo theme** (Material/AndroidX themes require API 21+).
- **No AndroidX / no support library / no third-party runtime deps** — only Android
  framework + JDK classes, so it builds offline against API 19.
- **`namespace com.example.mylibrary`**, `compileSdk 34`, `targetSdk 34` (AGP 8.5.2,
  Gradle 8.7). Manifest has **no `package` attribute** and every `<activity>` has
  `android:exported`.
- **Never leave a book file half-written** when editing — always `.tmp` → swap → `.bak`.
- **`BookDatabase.upsert` must preserve `last_read`** across rescans.
- **`Formats.ALL`** is the single source of truth — scanning, the filter spinner and the
  per-book format badge must never diverge from it.

## Build requirements

This project uses a **modern AGP 8 / Gradle 8 toolchain** so it opens and builds cleanly
in **Android Studio 2024.2.2** (the previous Gradle 4.x runtime is not compatible with
Studio 2024.x). Requirements:

- **Android Studio 2024.2.2** (bundles **JDK 17/21**).
- **Android SDK Platform 34** and **Build-Tools** (Studio will prompt to install the SDK
  Platform if it is missing).

the toolchain versions are pinned as:

| Component     | Version |
|---------------|---------|
| Gradle        | 8.7     |
| AGP           | 8.5.2   |
| compileSdk    | 34      |
| targetSdk     | 34      |
| minSdk        | 19      |
| JDK           | 17+     |

Open the folder in Android Studio, let it sync (it will download Gradle 8.7 and, if
needed, the SDK Platform), then **Run** on a device/emulator running Android 4.4.4.

> If Studio reports a missing Gradle wrapper jar/scripts, use **File ▸ Sync Project with
> Gradle Files** or enable "Gradle wrapper" — Studio will generate the missing pieces
> from `gradle/wrapper/gradle-wrapper.properties`.

### If you prefer the command line

```bash
# Requires ANDROID_HOME set and JDK 17 on PATH
./gradlew assembleDebug        # on Windows: gradlew.bat assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## CI and branch protection (GitHub)

`github.com/Klapatun/NeoLibrary` runs **GitHub Actions** (`.github/workflows/ci.yml`) on
every PR into `master` and every push to `master`:

1. **`Unit tests (JUnit + Robolectric)`** — `./gradlew testDebugUnitTest` on JDK 17 with
   Android SDK Platform 34 (all tests from `app/src/test`). Test reports are uploaded as
   an artifact.
2. **`Assemble debug APK`** — `./gradlew assembleDebug` (runs only if the tests pass);
   the APK is uploaded as an artifact.

### Enforcing "no merge on red tests"

The workflow alone doesn't block merges — the enforcement lives in the **branch
protection rule** for `master`:

1. Repo → **Settings ▸ Branches** → **Add branch protection rule** → name: `master`.
2. Under *Require pull request before merging*: enable **Require a pull request before
   merging** (recommended).
3. Under *Status checks*: switch on **Require status checks to pass before merging**
   and select the **`Unit tests (JUnit + Robolectric)`** check.
4. (Optional) enable **Include administrators** so the rule also applies to you.
5. Save. From then on, a PR with failing tests gets a red check and the **Merge**
   button stays disabled.

> The check must have run at least once (open any PR or push to `master`) before it
> appears in the status-checks selector.

### Local pre-push hook (master guard + tests before every push)

The repo ships a **`pre-push`** hook (versioned in `git-hooks/`). Before **any** push it:

1. **Refuses direct pushes to `master`** — push a feature branch and open a pull
   request instead. Emergency bypass: `ALLOW_PUSH_MASTER=1 git push ...`
   (tests still run).
2. **Runs the unit tests** — the same task CI runs, `./gradlew testDebugUnitTest` —
   and aborts the push if a test fails.

Enable it once per clone (the script lives in the repo, so it updates with the code):

```bash
git config core.hooksPath git-hooks
```

Notes: on machines where `java` is not on `PATH` the hook auto-detects the JDK 17
bundled with Android Studio (the `jbr` folder); the Android SDK is read from
`local.properties` / `ANDROID_HOME`. The hook is POSIX `sh`, so `.gitattributes`
keeps `git-hooks/*` with LF endings on every platform.

## Project layout

```
app/src/main/java/com/example/mylibrary/
├── MainActivity.java        # scan, browse, filter, recently-read, import
├── DetailActivity.java      # book details: open / edit / remove
├── EditMetaActivity.java    # metadata editing form
├── BookAdapter.java         # list adapter
├── model/Book.java          # Parcelable book model
├── db/BookDatabase.java     # SQLite catalog
├── scan/Formats.java        # canonical format list + extension mapping
├── scan/LibraryScanner.java # recursive storage scan
├── meta/MetaExtractor.java  # reads metadata (EPUB/FB2/FB2.ZIP/MOBI/TXT/HTML)
├── meta/MobiParser.java     # shared MOBI/AZW binary reader (PalmDB + EXTH)
├── meta/MetaWriter.java     # writes metadata (EPUB/FB2)
├── meta/CoverExtractor.java # reads cover image (EPUB/FB2/FB2.ZIP/MOBI)
├── util/CoverLoader.java    # async cover loading + LruCache
└── util/Openers.java        # MIME mapping + ACTION_VIEW intents
```

## Notes / possible extensions

- No third-party libraries are used (everything is Android framework + JDK classes), so
  the project builds offline and stays compatible with API 19. Basic MOBI/AZW reading
  (metadata + cover) is already implemented natively; if you later want deeper parsing of
  CHM, DjVu or MOBI (table of contents, full text), a library such as Apache Commons
  Compress can be added to `app/build.gradle` and wired into `MetaExtractor` (pick a
  version that still supports `minSdk 19`).
- `READ_EXTERNAL_STORAGE` is declared for completeness; on Android 4.4 there is no runtime
  permission prompt.
- The "Recently read" timestamp is preserved across rescans (`BookDatabase.upsert`).
