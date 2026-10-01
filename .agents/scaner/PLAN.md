# План: трёхстадийная загрузка библиотеки (scan → display → interact, метаданные фоном)

Цель: разделить «сканирование + отображение книг» и «извлечение метаданных/обложек»
на разные стадии. Сначала быстрый скан файлов → список показывается сразу → пользователь
может взаимодействовать; метаданные и обложки достаёт фоновый процесс без блокировки UI.

Зафиксированные решения:
- защита правок пользователя — флаг `user_edited`
- кэш обложек — файловый (`getExternalFilesDir("covers")`)
- обновление UI при фоновом извлечении — `CursorAdapter` + `ContentObserver`
  (framework `LoaderManager`/`CursorLoader`, НЕ AndroidX)

Итоговая архитектура:

```
СТАДИЯ 1 (быстрая):  LibraryScanner — только обход + «скелет» книги
                     (path/format/size/title=имя файла) → db.upsertBasic() →
                     notifyChange → список виден СРАЗУ
СТАДИЯ 2 (фон):      MetaEnricher (однопоточный воркер) по каждой pending-книге:
                     MetaExtractor + CoverExtractor → db.updateMetadata() +
                     CoverCache (файлы) → notifyChange → CursorLoader сам
                     перечитывает, CursorAdapter обновляет строки точечно
СТАДИЯ 3 (взаимодействие): клики/Detail/Edit работают в любой момент;
                     DetailActivity — fast path для одной не-расшифрованной книги
```

## 1. БД и модель

### db/BookDatabase.java
- Миграция 1 → 2. ВНИМАНИЕ: текущий onUpgrade делает DROP TABLE — потеряет
  last_read. Новый onUpgrade:
  `ALTER TABLE books ADD COLUMN meta_done INTEGER NOT NULL DEFAULT 0`
  `ALTER TABLE books ADD COLUMN user_edited INTEGER NOT NULL DEFAULT 0`
  (каждая в try/catch — идемпотентный апгрейд). onCreate — новая CREATE с обоими
  столбцами. DB_VERSION = 2.
- Статический write-lock: `private static final Object WRITE_LOCK = new Object();`
  Все записи (upsert, upsertBasic, updateMetadata, markRead, deleteByPath, clear)
  под `synchronized (WRITE_LOCK)`. Снять synchronized с самого метода upsert.
  (Решает проблему разных инстансов new BookDatabase(...) в разных Activity.)
- Новые методы:
  - `long upsertBasic(Book b)` — стадия 1. Вставка: path/format/size/title(от имени)/
    meta_done=0/user_edited=0. Обновление существующей строки: ТОЛЬКО format,
    size_bytes — title/author/…/meta_done/user_edited/last_read не трогаются.
  - `void updateMetadata(long id, MetaData md, boolean fileReadable)` — стадия 2.
    Если user_edited=1 — заполняет только пустые поля; иначе — перезаписывает
    метаданными из файла (title от имени файла остаётся, если md.found=false).
    В обоих случаях: meta_done=1, last_read не трогается.
  - `List<Book> needMeta()` — SELECT * FROM books WHERE meta_done = 0.
  - `Cursor cursorAll(String formatFilter)` / `Cursor cursorRecent(int limit)` —
    те же SQL, что у all()/recent(), но возвращают Cursor (нужен провайдеру).
- Существующие upsert/all/recent/getById/fromCursor: fromCursor читает два новых
  поля; upsert теперь также пишет user_edited/meta_done из модели (модель в точках
  вызова всегда свежая из БД).

### model/Book.java
- Новые поля: `public boolean metaDone;` `public boolean userEdited;`
- Parcelable: два дополнительных writeByte/readByte в КОНЦЕ (порядок: после exported).

## 2. Сканер (стадия 1)

### scan/LibraryScanner.java
- buildBook(File): УБРАТЬ вызов MetaExtractor.extract() →
  b.title = titleFromName(f.getName()) всегда. Обновить javadoc: метаданные
  извлекает стадия 2.
- scan(...), scanSingle(...), интерфейс Progress — сигнатуры без изменений
  (их используют тесты и импорт).

## 3. Фоновая стадия 2

### meta/MetaEnricher.java (новый класс)
- API (всё API ≤ 19):
  - `public static void start(Context app, BookDatabase db)` — стартует
    однопоточный воркер (AsyncTask): List<Book> pending = db.needMeta(); по одной:
    (1) файл есть? MetaExtractor.extract : skip-parse,
    (2) db.updateMetadata(id, md, readable),
    (3) если CoverExtractor.canHaveCover(format) → CoverExtractor.extract →
    CoverCache.save(app, path, bytes).
    После каждой пачки (≈5 книг или 150 мс) и в конце —
    app.getContentResolver().notifyChange(BookProvider.CONTENT_URI, null).
    Проверка isCancelled() на каждой итерации.
  - `public static void enrichOne(Context app, BookDatabase db, File file)` —
    одновариант (импорт, fast path Detail): синхронно, на том бэграунд-потоке,
    откуда вызвана. Идемпотентна.
  - `public static void cancel()` — глобально (вызывать в onDestroy MainActivity).
- Колбэк `OnProgress { onProgress(int done, int total); onFinished(); }` —
  доставка через new Handler(Looper.getMainLooper()); колбэк проверяет isFinishing().
- Двойной парсинг одной книги (bulk + fast path/импорт) безопасен: только чтение
  файла; запись в БД — один updateMetadata под статическим локом.

### util/CoverCache.java (новый класс)
- `static File fileFor(Context, String bookPath)` —
  getExternalFilesDir("covers")/<hash>.img, hash = Long.toHexString(bookPath.hashCode())
- `static void save(Context, String bookPath, byte[] bytes)` — .tmp → renameTo
  (инвариант «не оставлять наполовину» и для кэша)
- `static byte[] load(Context, String bookPath)` — null при отсутствии

### util/CoverLoader.java (мелкое изменение)
- CoverTask.doInBackground: сначала CoverCache.load(...); если null →
  CoverExtractor.extract → при успехе CoverCache.save. LruCache остаётся 2-м уровнем.

## 4. UI: CursorAdapter + ContentObserver

### db/BookProvider.java (новый ContentProvider)
- authorities = "com.example.mylibrary.books", android:exported="false" в манифесте
- URI: …/books — все (selection format=? пробрасывается в db.cursorAll),
  …/books/recent — db.cursorRecent(200)
- query() делегирует в BookDatabase (тот же process, та же БД). CRUD не
  реализуется (UI пишет через BookDatabase напрямую).

### MainActivity.java (перекомпоновка)
- getLoaderManager() (framework android.app.LoaderManager, НЕ AndroidX) +
  android.content.CursorLoader (API 11):
  - onCreateLoader(id) → CursorLoader с URI/аргументами по текущему фильтру
  - onLoadFinished → adapter.changeCursor(cursor) (учитывая, к какому view
    прикреплён адаптер)
  - Смена спиннера → restartLoader(id, null, this) с новыми аргументами
  - Убирается ручная reload(filterValue) — обновление списка автоматическое по
    notifyChange (scan, enricher, импорт)
- startScan() (стадия 1): как сейчас (AsyncTask + progressBar + toast «Found N
  book(s)»), но db.upsert(b) → db.upsertBasic(b); в onPostExecute — notifyChange
  + старт стадии 2 (MetaEnricher.start)
- Индикатор стадии 2 (не блокирующий): в activity_main.xml — компактный ProgressBar
  (indeterminate) + TextView @+id/enrich_status («Ищем метаданные… 12/340»), видны
  только пока MetaEnricher работает (OnProgress-колбэк)
- Импорт: в doInBackground после копирования — scanSingle → db.upsertBasic →
  notifyChange → MetaEnricher.enrichOne(...) прямо там (уже бэграунд-поток).
  ProgressDialog остаётся только на копию.
- onDestroy: MetaEnricher.cancel()

### BookAdapter.java (BookAdapter extends CursorAdapter)
- Имя класса оставляем, базовый — android.widget.CursorAdapter
- MODE_LIST/MODE_GRID, bindList/bindGrid — логика и layout'ы без изменений;
  поля читаются из Cursor
- newView/getView: инфлейт по mode (как сейчас), переиспользование convertView по
  tag view_mode — как сейчас
- getItem(int) → Book по позиции курсора (нужен onItemClickListener);
  getItemId → _id
- Перетаскивание адаптера между ListView/GridView — те же существующие правила
  (setAdapter(null) → setAdapter(adapter))

## 5. Detail / Edit (стадия 3)

### DetailActivity.java
- Fast path: в onCreate, если book.metaDone == false → AsyncTask:
  MetaExtractor.extract(file) → db.updateMetadata(id, md, ...) → onPostExecute:
  обновить detail_title/detail_author/detail_other (покрывает случай «открыл книгу
  до конца стадии 2»). Обложка — без изменений (CoverLoader уже асинхронный)
- Остальное (open/edit/delete/markRead) — без изменений

### EditMetaActivity.java
- Перед db.upsert(updated) — updated.userEdited = true; (флаг теперь
  персистится upsert'ом). Остальное не меняется.

## 6. Манифест и ресурсы

- AndroidManifest.xml: +<provider android:name=".db.BookProvider"
  android:authorities="com.example.mylibrary.books" android:exported="false"/>
  (инвариант «без package-атрибута» не трогается)
- activity_main.xml: +малый ProgressBar + TextView @+id/enrich_status (над списком,
  видимость только в стадии 2)
- strings.xml: +2 строки для статуса (англ., как и остальной UI)
- API-аудит (lint NewApi=error): LoaderManager/CursorLoader (11),
  getExternalFilesDir (9), notifyChange (1), Handler(Looper.getMainLooper()) (1) —
  всё ≤ 19. AndroidX не вводится (framework-классы android.app.*/android.content.*)

## 7. Тесты

| Файл | Что делать |
|---|---|
| LibraryScannerTest | Переписать scanPrefersEmbeddedTitleAndFallsBackToFileName: сканер теперь всегда даёт title от имени файла (guide.html → guide). Остальные тесты проходят (TXT/PDF-кейсы дают те же значения). |
| BookDatabaseTest | +тест миграции: создать БД старой схемы (raw SQL v1 + данные + last_read) → открыть новым хелпером → столбцы добавлены, данные и last_read целы. +upsertBasic сохраняет title/meta_done/user_edited; +updateMetadata сохраняет last_read и уважает user_edited; +needMeta(). |
| MetaEnricherTest (новый, Robolectric sdk 19) | enrich ставит метаданные + meta_done=1, сохраняет last_read; user_edited-книга: user-title не затирается, пустые поля заполняются; PDF → meta_done=1, title = имя файла; байты обложки появляются в CoverCache; повторный enrich идемпотентен. |
| BookProviderTest (новый) | query — все / по фильтру / recent; notifyChange приходит наблюдателю. |
| MainActivityTest | Существующие кейсы (4 книги, toast, прогресс GONE, toggle, spinner, импорт) должны проходить, но каст (BookAdapter) — теперь CursorAdapter (count/getItem через него). +новый кейс: после запуска стадия 2 доводит needMeta() до 0. Импорт-кейс: title imported_book совпадает (TXT = имя файла), meta_done становится 1. |
| BookAdapterTest | Переписать под CursorAdapter (биндинг list/grid по fake-курсорам) или закрыть через MainActivityTest. |

Доки: обновить «Main workflows → Scan» в CONTEXT.md и «Data flows» в README.md
под трёхстадийную схему.

## 8. Порядок реализации (каждый шаг — с зелёными тестами)

1. DB: колонки + миграция + Book-поля + upsertBasic/updateMetadata/needMeta/
   cursorAll/cursorRecent + статический лок → тесты БД.
2. Scanner: убрать MetaExtractor из buildBook → обновить LibraryScannerTest.
3. CoverCache + интеграция в CoverLoader.
4. MetaEnricher (bulk + enrichOne + OnProgress).
5. BookProvider + перекомпоновка MainActivity (Loader/CursorAdapter/стадии/статус)
   → MainActivityTest.
6. DetailActivity fast path + EditMetaActivity userEdited.
7. Layout/strings, манифест, доки. gradlew :app:lintDebug + testDebugUnitTest.

## 9. Остаточные риски

- Самый большой новый контур — связка ContentProvider + CursorLoader + LoaderManager
  (шаг 5). Robolectric 4.12 на sdk 19 покрывает Loaders; фолбэк если хрупко: тесты
  провайдера + прямой adapter.changeCursor() в UI-тестах (продуктовый код не менять).
- Ренейм файла (не удаление) создаст дубликат строки — уже так сейчас, out of scope.
- Временная ошибка I/O при извлечении будет помечена meta_done=1 (книга «без
  метаданных» навсегда) — осознанный компромисс.
- Память: стадия 2 не декодирует битмапы (только байты → файл), LruCache-лимит и
  старые устройства не страдают.
