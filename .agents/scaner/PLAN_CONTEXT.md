# PLAN_CONTEXT — журнал выполнения (сканер → метаданные фоном)

План: `.agents/scaner/PLAN.md`. Общий контекст проекта — в корневом CONTEXT.md (не
дублировать сюда). Формат: по шагам — статус, что изменено (файлы), ключевые решения
и подводные камни, которые НЕ вытекают из PLAN.md/CONTEXT.md.

## Состояние

- [x] 0. PLAN.md записан, решения зафиксированы (user_edited, файл-кэш обложек,
      CursorAdapter+observer, только-план-сначала)
- [x] 1. DB: миграция v2, Book-поля, upsertBasic/updateMetadata/needMeta,
      cursorAll/cursorRecent, статический WRITE_LOCK — 18 тестов зелёные
- [x] 2. Scanner: убрать MetaExtractor из buildBook — 114/114 зелёные
- [x] 3. CoverCache + CoverLoader
- [x] 4. MetaEnricher (+ класс BookProvider — см. подводные камни)
- [x] 5. BookProvider + MainActivity (Loader/CursorAdapter/стадии)
- [x] 6. DetailActivity fast path + EditMetaActivity userEdited
- [x] 7. layout/strings/манифест/доки + lint + полный прогон тестов
- [x] 8. REPORT.md

## Лог

### Шаг 0 — старт
- План согласован пользователем 01.10.2026.
- Build: `.\gradlew.bat testDebugUnitTest` / `:app:lintDebug` (Windows, PowerShell:
  запускать через `.\gradlew.bat`, NOT bare `gradlew.bat`).
- JDK: нет на PATH. JAVA_HOME = `D:\Program Files\Android\Android Studio\jbr` (JDK 21,
  достаточно для Gradle 8.7/AGP 8.5.2). SDK: `D:\Android\Sdk` (local.properties).
  В PowerShell перед gradlew: `$env:JAVA_HOME="D:\Program Files\Android\Android Studio\jbr"`.
  Ложный "Exited with code 1" из-за 2>&1 в PowerShell — смотреть на `BUILD SUCCESSFUL`
  или на `$LASTEXITCODE` после `| Out-Null`.
- Robolectric 4.12.2, @Config(sdk = 19). Ресурсы в тестах: includeAndroidResources.

### Шаг 2 — Scanner (Готово)
- `buildBook` теперь: path/format/size + title=titleFromName (без MetaExtractor).
- ПОДВОХНОК: titleFromName заменяет _ на пробелы, а MetaExtractor.extractText — нет
  → после enrich title "прыгал". Решение: extractText тоже делает replace('_',' ')
  (статии 1 и 2 дают одинаковый title). Обновлены тесты: MetaExtractorTest
  ("my plain novel", "no title page"), LibraryScannerTest ("scan me"),
  MainActivityTest импорт ("imported book"; toast "Imported imported_book.txt"
  не трогать — это display name из пикера).

### Шаг 1 — DB (Готово)
- `Book`: +`metaDone`, `userEdited` (Parcelable — два readByte/writeByte в конце).
- `BookDatabase`: DB_VERSION=2; onUpgrade — ALTER TABLE ADD COLUMN (не DROP!);
  `static final Object WRITE_LOCK` на ВСЕХ записях (upsert, upsertBasic, updateMetadata,
  markRead, deleteByPath, clear); +upsertBasic (update: только format+size_bytes),
  +updateMetadata(id, md, fileReadable) (user_edited → только пустые поля;
  meta_done=1; last_read не трогается), +needMeta(), +cursorAll/cursorRecent;
  upsert пишет user_edited/meta_done из модели; fromCursor читает оба флага.
- Тесты: +миграция (RAW openOrCreateDatabase по getDatabasePath — нужны mkdirs родителя
  и `PRAGMA user_version = 1`, иначе helper думает БД новая и ходит в onCreate;
  SQLiteFactory/SQLiteConnection — hidden API, не компилируется!), +upsertBasic×2,
  +updateMetadata×3, +needMeta, round-trip расширен. 18/18 зелёные.

### Шаг 3 — CoverCache + CoverLoader (Готово)
- `CoverCache` (новый): файл `getExternalFilesDir("covers")/<Long.toHexString(path.hashCode())>.img`;
  `save` — `.tmp` → `renameTo` (инвариант «не оставлять наполовину» и для кэша; при
  неудаче rename — delete target + retry), `load` — null при отсутствии; best-effort
  (исключения глотаются — кэш пропал = «ещё не подгружен», CoverLoader достанет сам).
- `CoverLoader.CoverTask.doInBackground`: сначала `CoverCache.load`, при null —
  `CoverExtractor.extract` + `CoverCache.save`. LruCache-2-й уровень не тронут.

### Шаг 4 — MetaEnricher (Готово)
- `MetaEnricher` (новый): `start(app, db, OnProgress)` — однопоточный AsyncTask-воркер
  по `db.needMeta()`; на каждой итерации `isCancelled()`; `enrichOneBook`: файл есть?
  `MetaExtractor.extract` : пустой MetaData → `db.updateMetadata(id, md, readable)` →
  если `CoverExtractor.canHaveCover(format)` → байты в `CoverCache.save`.
  `notifyChange(BookProvider.CONTENT_URI)` после каждой пачки (5 книг) и в конце.
  `enrichOne` — синхронный одновариант (импорт/fast path), идемпотентен.
  `cancel()` — глобальный (onDestroy MainActivity), идемпотентен.
  Колбэк OnProgress — main-Handler, колбэк сам проверяет isFinishing().
  **Отклонение от плана (сознательное):** в `start`/`enrichOne` передаётся `Book` (а не
  `File`) — нужен id строки для updateMetadata; URI берётся из `BookProvider` (см. ниже).
- **ПОДВОХНОК:** `MetaEnricher` ссылается на `BookProvider.CONTENT_URI` → класс
  `BookProvider` добавлен в этот же коммит (иначе шаг не компилируется); манифест и
  использование в UI — в шаге 5.

### Шаг 5 — BookProvider + MainActivity (Готово)
- `BookProvider` (новый ContentProvider): authorities `com.example.mylibrary.books`,
  `exported=false`; query: `…/books` → `db.cursorAll(filter)` (selection `format=?`
  пробрасывается), `…/books/recent` → `db.cursorRecent(200)`; CRUD намеренно не
  реализован (UnsupportedOperationException) — записи только через BookDatabase.
- `MainActivity`: implements `LoaderManager.LoaderCallbacks<Cursor>` (framework, НЕ
  AndroidX); `getLoaderManager().initLoader(LOADER_BOOKS, …)` в onCreate; spinner →
  `applyFilter(pos)` → `restartLoader`; `onLoadFinished` → `adapter.changeCursor(cursor)`
  + `updateEmptyView()`; ручная `reload()` удалена — список обновляется по notifyChange
  (scan, enricher, импорт). startScan: `upsertBasic` + notifyChange + `startEnrichment()`
  (статус-полоса `enrich_bar` видна, пока воркер работает; GONE по onFinished). Импорт:
  `scanSingle → upsertBasic → MetaEnricher.enrichOne` на том же бэграунд-потоке +
  notifyChange; ProgressDialog только на копию. `onDestroy` → `MetaEnricher.cancel()`.
- `BookAdapter extends CursorAdapter`: `newView`/`bindView` (инфлейт по mode, tag
  view_mode), `getItem` через `BookDatabase.fromCursor` (сделан public static),
  `getItemId` = `_id`.
- **ПОДВОХНОК (коммиты):** `activity_main.xml` + `strings.xml` (enrich_bar/enrich_status,
  enriching/enriching_progress) и `<provider>` в манифесте — в этот же коммит: без
  layout/strings MainActivity не собирается (R.id/R.string), а без `<provider>`
  CursorLoader в рантайме/тестах не находит провайдера. Планом они числились в шаге 7.
- **ПОДВОХНОК (тесты, Robolectric 4.12 + CursorAdapter):**
  1) `CursorAdapter`-конструктор с real-курсором ходит в `cursor.getWindow()` — фейк
     без окна (BaseCursor) падает; в тесте адаптер строится как в проде:
     `new BookAdapter(ctx, null)` + `changeCursor(…)`.
  2) Robolectric'ов `org.robolectric.fakes.BaseCursor` (артефакт shadows-framework)
     бросает `UnsupportedOperationException` в `registerContentObserver`/
     `unregisterContentObserver`/`registerDataSetObserver`/`unregisterDataSetObserver`/
     `getInt`/`getLong` — в тестовом фейке-курсоре все они переопределены (no-op/из
     getString). AOSP `CursorAdapter.init`/`swapCursor` вызывают регистрацию observers
     прямо на курсоре.

### Шаг 6 — Detail fast path + Edit userEdited (Готово)
- `DetailActivity`: если `book.metaDone == false` → AsyncTask: `MetaEnricher.enrichOne`
  (бэграунд) → `db.getById` → `onPostExecute`: `showBook(fresh)` (title/author/other;
  обложка уже асинхронная через CoverLoader). Двойной парсинг с bulk-воркером безопасен
  (чтение файла; запись — idempotent updateMetadata под статическим локом БД).
- `EditMetaActivity`: перед `db.upsert(updated)` — `updated.userEdited = true`.
- Тест: +fastPathEnrichesAnUnenrichedBookOnOpen (staging-строка meta_done=0 → после
  открытия meta_done=1, title TXT не меняется).

### Шаг 7 — доки, манифест, lint, полный прогон (Готово)
- Манифест: `<provider .db.BookProvider … exported=false/>` (в коммите шага 5, см. выше).
- Доки: CONTEXT.md «Main workflows» + class map и README.md «Data flows» + таблица
  классов переписаны под трёхстадийную схему (scan → display → enrich; new:
  BookProvider/MetaEnricher/CoverCache).
- `gradlew :app:lintDebug`: 0 errors (NewApi=error — всё ≤19), 66 предупреждений —
  прежний набор (DefaultLocale/UnusedResources/… — не эта фича).
- Полный прогон: **130/130 зелёные** (до фичи 114; +16: MetaEnricherTest×8,
  BookProviderTest×5, BookTest — новые поля, DetailActivityTest — fast path).

### Фиксы, найденные при проверке готового кода (до докоммита)
1. **БАГ (критичный):** в `Book.writeToParcel` не дописаны `writeByte` для
   `metaDone`/`userEdited`, а Parcel-конструктор их читал → при передаче модели между
   экраками — Parcel-исключение. Дописаны оба writeByte (план: «два writeByte в конце»).
   BookTest теперь проверяет round-trip обоих флагов.
2. В `MainActivityTest` остались отладочные `System.err.println` — убраны.
3. Написаны отсутствующие по §7 плана `MetaEnricherTest` (8 тестов: enrich ставит
   метаданные+meta_done, сохраняет last_read; user_edited — user-значения не затираются,
   пустые поля заполняются; PDF → meta_done=1, title = имя файла; байты обложки в
   CoverCache; идемпотентность; missing-file → meta_done=1; bulk-воркер досажает очередь;
   cancel) и `BookProviderTest` (5: query all/filter/recent-порядок, notifyChange доходит
   до ContentObserver, getType).

### Итог фичи
- 8 коммитов на feature/separate_scan_and_finding_meta: 1) DB v2, 2) fast scan,
  3) CoverCache, 4) MetaEnricher(+BookProvider-класс), 5) cursor-driven UI (+layout/
  strings/манифест), 6) Detail fast path + userEdited, 7) MetaEnricherTest+BookProviderTest,
  8) доки + этот журнал + REPORT.md.
- 130/130 тестов, lint 0 errors, minSdk 19/AndroidX не введены.
