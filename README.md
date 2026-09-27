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
| FB2.ZIP    | `.fb2.zip`   | —            | —   |
| FB3        | `.fb3`       | —            | —   |
| HTML/HTM   | `.html/.htm` | ✅ (`<title>`) | — |
| MOBI/AZW   | `.mobi/.azw` | —            | —   |
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
- **EPUB editing** rebuilds the archive, replacing the `content.opf` `<dc:*>` elements and
  inserting missing ones into `<metadata>`.
- **FB2 editing** patches the `description`/`title-info` elements in place and rewrites the
  file.
- Both writers first write a `.tmp`, then swap over the original with a `.bak` backup and
  restore it on failure — the source file is never left half-written.

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
├── meta/MetaExtractor.java  # reads metadata (EPUB/FB2/TXT/HTML)
├── meta/MetaWriter.java     # writes metadata (EPUB/FB2)
└── util/Openers.java        # MIME mapping + ACTION_VIEW intents
```

## Notes / possible extensions

- No third-party libraries are used (everything is Android framework + JDK classes), so
  the project builds offline and stays compatible with API 19. If you later want deeper
  parsing of CHM/DjVu/MOBI, a library such as Apache Commons Compress can be added to
  `app/build.gradle` and wired into `MetaExtractor` (pick a version that still supports
  `minSdk 19`).
- `READ_EXTERNAL_STORAGE` is declared for completeness; on Android 4.4 there is no runtime
  permission prompt.
- The "Recently read" timestamp is preserved across rescans (`BookDatabase.upsert`).
