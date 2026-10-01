# ISSUES — заметки для будущих сессий

Рабочие тикеты/планы проекта Neo Library. Формат: каждая запись — задача,
контекст, план и состояние. Подробности о проекте — в `CONTEXT.md` и `README.md`.

---

## [REFAC] Разрастание `MetaExtractor` и `CoverExtractor` — декомпозиция

**Создано:** 2026-09-30. **Статус:** план утверждён, рефакторинг не начат.

### 1. Диагностика

Оба класса — «толстые фасад-классы», в которых смешаны три слоя:
(1) парсинг форматов, (2) обвязка ZIP/IO, (3) утилиты над байтами изображений.

Текущий объём: `MetaExtractor` 343 стр., `CoverExtractor` 448 стр.,
`MobiParser` 277 стр.

Конкретное дублирование:

| Дублируется | Где |
|---|---|
| `findOpfPath` (EPUB, ~25 строк) | `MetaExtractor` **и** `CoverExtractor` |
| `readFb2Entry` (FB2ZIP) | оба класса |
| `readToEndBytes` / `readZipEntry*` / `readTextFile` | оба класса |
| перечисление форматов | 4 места: `Formats`, dispatch в `MetaExtractor`, dispatch в `CoverExtractor`, `canHaveCover` |

### 2. Целевая архитектура — 4 шага

Каждый шаг — **отдельный коммит-рефакторинг без изменения поведения**;
публичный API (`MetaExtractor.extract(File)`, `CoverExtractor.extract(File)`,
`canHaveCover`) и все тесты остаются без изменений.

**Шаг 0 (бонус) — собрать знание о форматах:** перенести `canHaveCover` из
`CoverExtractor` в `Formats.canHaveCover(String)` (Formats — объявленный
single source of truth).

**Шаг 1 — `meta/ZipUtil` (package-private):** весь ZIP/IO-плаггинг:

```java
final class ZipUtil {
    static String  readEntry(File zip, String name) throws Exception;
    static byte[]  readEntryBytes(File zip, String name) throws Exception;
    static byte[]  firstEntryEndingWith(File zip, String suffix) throws Exception; // readFb2Entry
    static byte[]  readAll(File f);
}
```
Снимает ~60 строк дублирования из каждого класса.

**Шаг 2 — один ридер на формат (package-private), по прецеденту `MobiParser`:**
- `meta/EpubPackage` — `findOpfPath`, `parseMeta(InputStream opf)`, `cover(...)`.
  Убивает дублированный `findOpfPath`; manifest- и OPF-логика в одном месте.
- `meta/Fb2Reader` — `parseMeta(InputStream)` (текущий `parseFb2Xml`),
  `coverFromXml(String)`, вся FB2ZIP-логика (`readFb2Entry` + loose-картинка).
  Простой FB2 и FB2ZIP описываются в одном файле.
- MOBI-утилиты над байтами `trimToImage`/`lastEoi` → `MobiParser`
  (это семантика MOBI-записей); `looksLikeImage` оставить в `CoverExtractor`.
- TXT/HTML (~20 строк) — не выносить.

**Шаг 3 — тонкие фасады:** `MetaExtractor` ~40 строк (dispatch + `notFound`),
`CoverExtractor` ~30 строк (dispatch). XML-помощники `localName`/`firstText` —
в общий маленький хелпер или в соответствующий ридер.

### 3. Анти-рекомендации (рассмотрено и отклонено)

- **Единый `BookReader` с `meta()` + `cover()`** — соблазнительно, но сканеру
  нужна только мета (обложка на каждый файл при скане — лишний расход), а
  `CoverLoader` — только обложка. Два фасада = два сценария использования.
  Если понадобится парсить контейнер один раз — кэш делать в `CoverLoader`,
  не в парсерах.
- **Интерфейс-стратегия** (`interface MetaSource` + реализации) — полиморфизма
  нет, dispatch только по расширению; абстракция ради абстракции.
- **Субпакеджи** `meta/epub`, `meta/fb2` — преждевременно; делить при 10+ классах.
- **Лямбды/streams** — кодбейс намеренно pre-lambda (анонимные классы);
  стиль не менять (Java 8 доступен, но стиль — invariant).
- **Новые зависимости** — запрещено инвариантом проекта (только framework + JDK).

### 4. Invariants, которые нельзя сломать (см. CONTEXT.md)

- minSdk 19 / Holo / без AndroidX и сторонних runtime-зависимостей.
- Публичный API фасадов не меняется → `MetaExtractorTest` (18 тестов),
  `CoverExtractorTest` (18 тестов) проходят без правки — это safety net.
- Рефакторинг не меняет поведение: после каждого шага весь сьют зелёный.

### 5. Как проверять (сборка в этой среде)

- `JAVA_HOME = D:\Program Files\Android\Android Studio\jbr` (в PATH java нет;
  в pre-push хуке это учтено).
- `gradlew.bat :app:testDebugUnitTest` — полный сьют (Robolectric, sdk 19).
- `gradlew.bat :app:assembleDebug` — сборка APK.
- Хук `git-hooks/pre-push` гоняет весь сьют перед каждым push.

### 6. Текущее состояние (на 2026-09-30)

- Поддержка FB2ZIP добавлена (мета через внутренний `.fb2`; обложка — внутренний
  `<binary>` либо loose-картинка в архиве); все тесты зелёные, APK собирается.
- Рефакторинг по плану выше **не начат**; начинать с шага 0 или шага 1
  (оба наиболее безопасны, шаг 1 сразу убирает самое густое дублирование).

---

## [FIX] Новые проблемы из ревью последних 14 коммитов (2026-10-02)

**Создано:** 2026-10-02. Источник: ревью коммитов `b239619…314831a`
(ветка `feature/separate_scan_and_finding_meta`) + сверка с AOSP: локальный
`SQLiteCursor` (API 19) **не имеет** автоматического уведомления об изменении
данных — `CursorLoader` переспрашивает только по явному
`ContentResolver.notifyChange`. Тикеты 6.1–6.4 закрываются серияю фикс-коммитов
2026-10-02 (по одному коммиту на пункт; тесты 144/144 на старте).

### 6.1 Список не обновлялся после удаления / правки / markRead — [x] (высокий) — сделано 2026-10-02
- **Где:** `DetailActivity` (delete, `openBook`→`markRead`), `EditMetaActivity` (save).
- **Проблема:** с cursor-driven UI (коммит `6099dca`) список живёт от явного
  `notifyChange`. Удаление книги и сохранение метаданных его не вызывали →
  удалённая книга оставалась в списке, новый title/author не видны (пока
  enricher не доделает партию или не сменится фильтр). `markRead` — та же
  история для вкладки «Недавно прочитанные»: её CursorLoader слушает
  `RECENT_URI`, а уведомления уходили только на `CONTENT_URI`.
- **Сделано:** `BookProvider.notifyChangeAll(context)` (статика, шлёт оба URI);
  вызовы — после `deleteByPath` (confirmDelete), после `markRead` (openBook),
  после `upsert` (EditMetaActivity.save). Тесты: observer-ассерты в
  `DetailActivityTest` (`deleteNotifiesTheCatalogObservers`,
  `openingABookNotifiesTheCatalogObservers`) и `EditMetaActivityTest`
  (`saveNotifiesTheCatalogObservers`).

### 6.2 `upsert` затирает `meta_done`/`user_edited` из устаревшей модели — [ ] (высокий)
- **Где:** `BookDatabase.upsert`, `EditMetaActivity.save`.
- **Проблема:** `upsert` писал `meta_done`/`user_edited` из модели в памяти.
  (a) Гонка: enricher между перечитом и записью ставит `meta_done=1` → upsert
  возвращает 0 → лишний повторный парсинг файла; (b) если строку удалили во
  время редактирования, `updated = book` + `upsert` **вставали удалённую книгу
  обратно** (resurrect).
- **План:** `upsert` больше не пишет эти два столбца (как `last_read` —
  сохраняются; при INSERT — дефолт 0). Новый `markUserEdited(id)` (монотонный:
  0→1). `EditMetaActivity`: запись в каталог только если строка ещё существует
  (resurrect устранён). Тесты: `upsertDoesNotTouchMetaFlagsOnExistingRow`,
  `markUserEditedSetsTheFlag`, `saveDoesNotResurrectADeletedBook` +
  приведение 3 старых тестов к новому API.

### 6.3 Полный рескан и рестарт воркера при каждом `onCreate` — [ ] (средний)
- **Где:** `MainActivity.onCreate` (`startScan()` вызывался безусловно).
- **Проблема:** любое пересоздание activity (ротация, low-memory) = полный
  обход дисков + `MetaEnricher.start()` (cancel + рестарт воркера с начала
  очереди) — минуты лишней работы на большой библиотеке, прогресс сбрасывался.
- **План:** `startScan()` только при `savedInstanceState == null` (холодный
  старт — как раньше подхватывает новые файлы с диска); при пересоздании —
  `startEnrichment()` (воркер уже остановлен в `onDestroy`, `needMeta()` даёт
  остаток очереди). Тест: `recreationSkipsTheRescanButResumesEnrichment`.

### 6.4 Импорт под тем же именем: протухшие метаданные и обложка — [ ] (средний)
- **Где:** `MainActivity.importToLibrary`, `BookDatabase`.
- **Проблема:** импорт файла с уже существующим именем молча перезаписывал
  содержимое, но строка держала старые in-file метаданные (`meta_done=1` →
  enricher пропускал) и в `CoverCache` оставалась старая обложка (если новой
  книги обложки нет — старая висит вечно).
- **План:** при перезаписи (строка существует) — `db.markMetaPending(id)`
  (`meta_done=0`, новый метод) + `CoverCache.delete(path)`; немедленный
  `enrichOne` перепарсит, а сброшенный флаг — страховка для фонового воркера.
  Тесты: `markMetaPendingClearsTheDoneFlag`,
  `reimportingOverAnExistingFileReEnrichesTheBook`.

### 6.5 Документация разошлась с кодом — [~] (низкий)
- **Сделано** (коммит с разделом 6): CONTEXT.md — фактические отступы грида
  (8px/8px, а не 32/16; верхний padding контейнера 16px, по бокам 8px; пересчёт
  auto_fit: портрет 758px → 3 колонки, ландшафт 1024px → 4); тот же комментарий
  исправлен в `activity_main.xml`.
- **Осталось:** мёртвый ресурс `empty_view_grid` в `activity_main.xml`
  (в коде не используется — один `empty_view` служит обоим видам); удалить.

### 6.6 Spinner фильтра теряет выбор при ротации — [ ] (низкий)
- **Где:** `MainActivity` (`currentFilter`, `filterSpinner`).
- **Проблема:** при пересоздании `currentFilter` обнуляется в `""`, спиннер
  сбрасывается на «All formats» (`onSaveInstanceState` не реализован) —
  пользователь на «Recently read» или фильтре формата после поворота экрана
  оказывается на «All formats».
- **Доработка:** сохранять позицию спиннера (или `currentFilter`) в
  `onSaveInstanceState` и восстанавливать в `onCreate`.

---

## [IMPROVE] Доработки и известные ограничения после фичи «сканер → метаданные фоном»

**Создано:** 2026-10-01. **Статус:** приоритет «высокий» закрыт (1.1, 1.2, 2.1 —
сделаны 2026-10-02), 3.1 закрыт 2026-10-02 (реализовано в фиче cursor UI);
остаток «среднего»/«низкого» — не начато (см. также раздел [FIX] 6.x).
Источник: журнал `.agents/scaner/PLAN_CONTEXT.md` (8 коммитов, 130/130 тестов) +
обзор кода. Приоритет: **высокий** — до масштабирования библиотеки (тысячи+
книг) или до релиза; **средний** — заметное улучшение; **низкий** — nice-to-have.
Статус пункта: `[ ]` не начато, `[~]` в работе, `[x]` готово.

### 1. База данных (`db/BookDatabase`)

#### 1.1 Нет индексов, кроме `UNIQUE(path)` — [x] (высокий) — сделано 2026-10-02
`DB_VERSION = 3`; `idx_books_meta_done` / `idx_books_last_read` создаются в
`onCreate` и в `onUpgrade` (ветка `oldVersion < 3`, идемпотентно try/catch, без
DROP). Тесты: `openingAV2DatabaseUpgradesInPlaceAndAddsIndexes` (v2→v3, данные +
`PRAGMA index_list`) и `freshDatabaseHasIndexesOnMetaDoneAndLastRead`.

#### 1.2 `updateMetadata`: 5 лишних SELECT на книгу — [x] (высокий) — сделано 2026-10-02
Значения 5 полей читаются из первого (единственного) курсора; «пустое» решается
в Java (`isBlank`: NULL/пустое/только пробелы — семантика сохранена). 6 запросов → 1.
Тест: `updateMetadataTreatsWhitespaceOnlyFieldsAsBlank`.

#### 1.3 `upsert`/`upsertBasic` не атомарны — [ ] (средний)
- **Где:** `upsert()`, `upsertBasic()`.
- **Проблема:** `SELECT _id` → `UPDATE`/`INSERT` без транзакции (статический лок
  защищает от interleaving, но не от падения в середине — может остаться
  половинчатое состояние, напр. вставленная строка без корректного `_id` в модели).
- **Доработка:** обёртка в `db.beginTransaction()`/`setTransactionSuccessful()`/`end()`.
  Примечание: синтаксис `INSERT ... ON CONFLICT DO UPDATE` (SQLite 3.24+) **недоступен** —
  на API 19 стоит SQLite 3.7, см. §4.

#### 1.4 Сироты каталога: файл удалён/перемещён на диске — [ ] (средний)
- **Где:** `DetailActivity` (remove — только ручное `deleteByPath`), скан.
- **Проблема:** если книгу удалить/переименовать мимо приложения, строка в `books`
  остаётся навсегда: скан её «не видит», а гонять `File.exists()` по всей таблице на
  каждый скан дорого.
- **Доработка:** по завершении скана — проход по строкам, чей `path` не существует:
  либо тихое удаление, либо пометка/предупреждение в `DetailActivity` («файл не найден»).

### 2. Кэш обложек (`util/CoverCache`)

#### 2.1 Кэш не чистится — [x] (высокий) — сделано 2026-10-02
`CoverCache.delete(ctx, path)` (удаляет `covers/<hash>.img` и возможный `.tmp`
орфаним от прерванного save; no-op, если записи нет) и `CoverCache.clear(ctx)`
(весь кэш + каталог). Вызовы — в `BookDatabase.deleteByPath` (контекст хранится
в поле, т.к. `SQLiteOpenHelper` не даёт `getContext()`) и `BookDatabase.clear`.
Опциональный лимит с вытеснением по mtime — не сделан (отдельная задача при
росте кэша). Тесты: `CoverCacheTest` (9 тестов, incl. half-written tmp) +
`deleteByPathAlsoDropsTheCachedCover`, `clearDropsTheWholeCoverCache`.

#### 2.2 Ключ кэша — `path.hashCode()` — [ ] (низкий)
- **Где:** `CoverCache.fileFor()`.
- **Проблема:** `String.hashCode()` — 32 бита, коллизии двух разных путей возможны;
  при коллизии одна книга увидит чужую обложку. Риск вырос после 314831a: кэш стал
  перманентным (delete/clear связаны с каталогом), а коллизия — навсегда.
- **Доработка:** коллизионно-устойчивый ключ (FNV-1a 64 → base36, либо SHA-1 hex).

### 3. UI / потоки

#### 3.1 Прогресс обогащения — [x] (низкий) — закрыто 2026-10-02 (реализовано в фиче cursor UI)
`OnProgress(done, total)` → полоса `enrich_bar` показывает «Fetching metadata…
done/total» (ресурс `enriching_progress`); `total` = `needMeta().size()` на старте
воркера. Оценка остатка по времени не добавлена (не было в скопе фичи).

#### 3.2 Двойной парсинг при fast path — [ ] (низкий)
- **Где:** `DetailActivity` (fast path) vs bulk-воркер `MetaEnricher`.
- **Проблема:** если книга не дообогащена, её параллельно парсят два воркера
  (безопасно: запись идемпотентна под `WRITE_LOCK`), но файл и обложка читаются дважды.
- **Доработка:** перед `enrichOne` проверять, не обрабатывает ли уже этот `id`
  bulk-воркер (флаг/сета в `MetaEnricher`), и пропустить.

### 4. Платформенные ограничения (иметь в виду, не задача)

- **API 19 → SQLite 3.7:** нет `INSERT ... ON CONFLICT DO UPDATE` (3.24+), нет JSON1,
  нет оконных функций. Любое будущее улучшение схемы БД сверять с этой версией.
- **Holo-тема / API ≤19:** любые новые UI-компоненты только из фреймворка API 19.
- **Без AndroidX/зависимостей:** новые классы — только фреймворк + JDK
  (`java.util.zip`, `XmlPullParser`); билд офлайн.

### 5. Качество (вплетено в общий бэклог)

- **66 предсуществующих Lint-предупреждений** (`:app:lintDebug`, 0 errors;
  DefaultLocale, UnusedResources, …) — отдельная чистовая задача по категориям [ ] (низкий).
  К ним добавились hardcoded-строки в `MainActivity`/`DetailActivity`/`EditMetaActivity`
  («Found N book(s)», «File not found», «No books read yet.» и т.п.) и `#FFF3CD`
  в `activity_main.xml`.
- **Robolectric-обходные пути в тестах** [~] (обслуживание): фейк-курсор (обход
  `UnsupportedOperationException` в `BaseCursor` — `register*Observer`/`getInt`/`getLong`)
  и миграционный тест через `PRAGMA user_version` — при апгрейде Robolectric (сейчас
  4.12.2) перепроверить; `SQLiteFactory`/`SQLiteConnection` — hidden API, в тестах
  не используются намеренно.
