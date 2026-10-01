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

## [IMPROVE] Доработки и известные ограничения после фичи «сканер → метаданные фоном»

**Создано:** 2026-10-01. **Статус:** план, не начато. Источник: журнал
`.agents/scaner/PLAN_CONTEXT.md` (8 коммитов, 130/130 тестов) + обзор кода.
Приоритет: **высокий** — до масштабирования библиотеки (тысячи+ книг) или до релиза;
**средний** — заметное улучшение; **низкий** — nice-to-have. Статус пункта:
`[ ]` не начато, `[~]` в работе, `[x]` готово.

### 1. База данных (`db/BookDatabase`)

#### 1.1 Нет индексов, кроме `UNIQUE(path)` — [x] (высокий) — сделано 2026-10-02
- **Где:** `CREATE` / `onUpgrade`.
- **Проблема:** `needMeta()` (`WHERE meta_done=0` — очередь enrich) и `cursorRecent()`
  (`WHERE last_read IS NOT NULL ORDER BY last_read DESC`) делают полный перебор таблицы.
  На библиотеке в десятки тысяч книг каждое открытие вкладки «Недавно прочитанные» и
  старт обогащения сканируют всю БД.
- **Доработка:** `DB_VERSION = 3` + `CREATE INDEX` на `meta_done` и `last_read`
  (в `onCreate` для новых установок и в `onUpgrade` — по образцу миграции v1→v2:
  идемпотентно, без DROP).
- **Сделано:** `DB_VERSION = 3`; `createIndexes()` создаёт `idx_books_meta_done` и
  `idx_books_last_read` в `onCreate` и в `onUpgrade` (ветка `oldVersion < 3`,
  идемпотентно try/catch). Тесты: `openingAV2DatabaseUpgradesInPlaceAndAddsIndexes`
  (v2→v3, данные + `PRAGMA index_list`) и `freshDatabaseHasIndexesOnMetaDoneAndLastRead`.

#### 1.2 `updateMetadata`: 5 лишних SELECT на книгу — [ ] (высокий)
- **Где:** `updateMetadata()` + `isBlankValue()`.
- **Проблема:** первый запрос уже выборит `title/author/publisher/description/series`
  (используется только `user_edited`), а затем `isBlankValue()` делает отдельный `SELECT`
  **на каждое** поле. Итог: до 6 запросов на одну книгу — самый горячий путь фазы 2.
- **Доработка:** читать значения 5 полей из первого курсора (они уже выбораны) и
  определять «пустое» в Java. 6 запросов → 1.

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

#### 2.1 Кэш не чистится — [ ] (высокий)
- **Где:** `CoverCache` (нет delete-API), `BookDatabase.deleteByPath/clear`,
  `DetailActivity` (удаление книги).
- **Проблема:** `deleteByPath()` и `clear()` не касаются файлов `covers/<hash>.img` →
  после удаления/переноса книги обложка-орфаним остаётся на диске вечно; кэш растёт
  без ограничения.
- **Доработка:** `CoverCache.delete(path)`; вызовы в `deleteByPath`/`clear`; опционально —
  лимит размера кэша с вытеснением по mtime файлов.

#### 2.2 Ключ кэша — `path.hashCode()` — [ ] (низкий)
- **Где:** `CoverCache.fileFor()`.
- **Проблема:** `String.hashCode()` — 32 бита, коллизии двух разных путей возможны;
  при коллизии одна книга увидит чужую обложку.
- **Доработка:** коллизионно-устойчивый ключ (FNV-1a 64 → base36, либо SHA-1 hex).

### 3. UI / потоки

#### 3.1 Прогресс обогащения — [ ] (низкий)
- **Где:** `MetaEnricher.OnProgress`, `enrich_bar` в `activity_main.xml`.
- **Проблема:** статус-полоса показывает факт работы воркера, но не «12 из 450» и не
  оценку остатка — при большой библиотеке пользователю неясно, когда можно
  переключать вкладки.
- **Доработка:** `OnProgress(done, total)` → текст прогресса; `total` считать из
  `needMeta().size()` до старта.

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
- **Robolectric-обходные пути в тестах** [~] (обслуживание): фейк-курсор (обход
  `UnsupportedOperationException` в `BaseCursor` — `register*Observer`/`getInt`/`getLong`)
  и миграционный тест через `PRAGMA user_version` — при апгрейде Robolectric (сейчас
  4.12.2) перепроверить; `SQLiteFactory`/`SQLiteConnection` — hidden API, в тестах
  не используются намеренно.
