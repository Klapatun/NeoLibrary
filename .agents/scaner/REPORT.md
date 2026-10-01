# Отчёт: трёхстадийная загрузка библиотеки (scan → display → enrich)

Фича: «сканер + метаданные фоном» — `.agents/scaner/PLAN.md`, журнал — `PLAN_CONTEXT.md`.
Ветка: `feature/separate_scan_and_finding_meta`. Дата завершения: 01.10.2026.

## Что сделано

Реализована трёхстадийная схема загрузки библиотеки по плану:

1. **Стадия 1 (быстрый скан)** — `LibraryScanner` строит только «скелет» книги
   (path/format/size/title из имени файла); `BookDatabase` v2: колонки `meta_done`/
   `user_edited` (идемпотентная ALTER-миграция, без DROP), `upsertBasic()` (на
   перескане обновляет только format+size), `updateMetadata()` (не затирает
   пользовательские правки, сохраняет `last_read`), `needMeta()`, `cursorAll()/
   cursorRecent()`, статический write-lock. Список виден сразу, пользователь уже
   взаимодействует с ним.
2. **Стадия 2 (фон)** — новый `MetaEnricher`: однопоточный воркер по `needMeta()`,
   `MetaExtractor` + `CoverExtractor` → `updateMetadata` + файловый кэш обложек
   (новый `CoverCache`, `getExternalFilesDir("covers")`, атомарный `.tmp`→rename);
   `notifyChange` каждой пачкой; `enrichOne` — одновариант для импорта и fast path;
   `cancel()` в `onDestroy`. `CoverLoader` теперь ищет обложку сначала в `CoverCache`.
3. **Стадия 3 (взаимодействие)** — UI курсорный: framework `LoaderManager` +
   `CursorLoader` (AndroidX не введён) через новый read-only `BookProvider`;
   `BookAdapter` переписан на `CursorAdapter`; спиннер → `restartLoader`; ручные
   `reload()` убраны. Статус-полоса стадии 2 (ProgressBar + «Fetching metadata… d/n»)
   видна, только пока воркер работает. `DetailActivity`: fast path — не-расшифрованная
   книга расшифровывается при открытии (бэграунд-поток, саморешфреш).
   `EditMetaActivity` ставит `userEdited = true` — enricher эти значения не затирает.

## Коммиты (по шагам плана)

| # | Коммит | Содержимое |
|---|---|---|
| 1 | `b239619` | DB v2: миграция, `upsertBasic`/`updateMetadata`/`needMeta`, курсоры, write-lock; `Book.metaDone/userEdited` |
| 2 | `1ea305c` | Fast scan: сканер без MetaExtractor; `extractText`: `_` → пробел (одинаковый title в обеих стадиях) |
| 3 | `3dcd27e` | `CoverCache` + интеграция в `CoverLoader` |
| 4 | `a130b8f` | `MetaEnricher` (bulk + `enrichOne` + OnProgress) + класс `BookProvider` (URI для notifyChange) |
| 5 | `6099dca` | Cursor-driven UI: `CursorLoader`/`BookProvider`/`BookAdapter(CursorAdapter)`, статус-полоса, манифест |
| 6 | `a4ce0cd` | Detail fast path + `userEdited` в редакторе |
| 7 | `43e9aa0` | Тесты: `MetaEnricherTest` (8) + `BookProviderTest` (5) |
| 8 | `docs: three-stage…` | Доки (CONTEXT.md/README.md под трёхстадийную схему) + журнал + этот отчёт |

## Исправления, найденные при проверке

- **Баг (критичный):** в `Book.writeToParcel` не писались новые два байта, а
  Parcel-конструктор их читал — падение при передаче модели. Исправлено, покрыто
  round-trip-тестом в `BookTest`.
- Убраны отладочные `System.err.println` из `MainActivityTest`.
- Подводные камни Robolectric/`CursorAdapter` при переписи `BookAdapterTest`
  (BaseCursor бросает UOE в register*/getInt/getLong) — задокументированы в журнале.

## Итог

- **Тесты:** 130/130 зелёные (`gradlew testDebugUnitTest`), было 114 до фичи.
- **Lint:** `gradlew :app:lintDebug` — 0 ошибок (NewApi=error, всё ≤ API 19),
  66 предупреждений — прежний набор, не этой фичи.
- **Инварианты соблюдены:** minSdk 19, Holo, без AndroidX, `.tmp`→swap→`.bak` не
  тронут, `last_read` сохраняется (покрыто тестами), `Formats.ALL` — единый источник.
- Зарисованные в план остаточные риски не менялись (ренейм → дубликат, временный I/O →
  `meta_done=1` навсегда, память: байты→файл без битмапов).
