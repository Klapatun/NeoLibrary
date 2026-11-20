# ISSUES — заметки для будущих сессий

Рабочие тикеты/планы проекта Neo Library. Формат: каждая запись — задача,
контекст, план и состояние. Подробности о проекте — в `CONTEXT.md` и `README.md`.
Закрытые пункты выносятся из этого файла (полный текст — в истории git).
Чистка 2026-10-21: вынесены закрытый тикет [REFAC] «Разрастание
`MetaExtractor` и `CoverExtractor`» (полностью), 8.1, 9.1, 9.3, 10.1, 10.3,
10.4, 10.5, 11.1, 11.4, раздел [FEATURE] 12, 13.1, 13.2, 13.6 и раздел
[FEATURE] 14; перекрёстные ссылки на вынесенные пункты исправлены.
2026-10-21 (дополнительно): вынесен 7.1 — закрыт (фикс `cf94ae8`, 2026-10-19;
валидация и тест `c3937ba`, 2026-10-21; полный текст — в истории git).
2026-11-12: вынесен 16.1 — закрыт (схема v7, merge `cb3c426`).
2026-11-14: вынесены 16.2 (фасад `Catalog`, PR #24 `1060bfa`), 16.3
(локатор `FileLocator`, PR #26), раздел [FIX] «остаток ревью 6.5/6.6»
(только история, открытых пунктов не было), тикет [FIX] class map
`BookProvider` в `CONTEXT.md` (строка уже с `format IN` / жанрами; полный
текст — в истории git).
2026-11-14 (дополнительно): вынесен 1.4 — закрыт (план
`feature/catalog-orphan-detail-mark`, `8d98204` / `ee58ccf`: пометка
`detail_missing` через `FileLocator`, ограничение reconcile в `CONTEXT`;
глобальный `File.exists()` по таблице не делали) — см. шапку раздела 1.
2026-11-18: вынесен раздел [FIX] «Новые проблемы из ревью фикс-коммитов
2026-10-02» — открытых пунктов не было (7.1–7.6 закрыты; полный текст — в
истории git).
2026-11-21: вынесен 3.2 — закрыт (ветка `improve/skip-inflight-enrich`,
тесты `9d71a01`, claim по id `52c8fdd`: fast path ждёт чужой разбор и сам
пишет результат, bulk пропускает занятый id и не ставит `meta_failed`,
импорт при занятом id парсит сам; полный текст — в истории git).
Остались: открытые тикеты, подводные камни и наблюдения.

---

## [FEATURE] Каталог: очередь и удалённая синхронизация (2026-11-12)

**Создано:** 2026-11-12. **Приоритет:** высокий. **Сверка 2026-11-14:**
шаги 16.1 (схема v7), 16.2 (`Catalog`), 16.3 (`FileLocator`) в `master`;
далее — только 16.4 → 16.5. Закрытые 16.1–16.3 — в истории git.

### 16.4 Очередь работ и скачивание издания — [ ] (высокий)
- **План:** `.agents/plans/feature_download-queue/PLAN.md` (ветка `feature/download-queue`).
- **Суть:** таблица `jobs` и один фоновый поток; первая сетевая работа — скачать издание с `remote_key`. Скан и обогащение переезжают в ту же очередь.
- **Согласование:** не согласован. Согласование заблокировано, пока в репозитории нет рабочей загрузки байтов файла и в плане не заполнена проверка сети.

### 16.5 Индекс удалённой библиотеки и выбор формата — [ ] (высокий)
- **План:** `.agents/plans/feature_remote-sync/PLAN.md` (ветка `feature/remote-sync`).
- **Суть:** работа `sync` принимает индекс и отправляет локальные правки. Жанры произведения в индексе — упорядоченный список, тот же, что `work_genres`. Несколько форматов одной книги приходят только с сервера; локальный скан по имени не склеивает. Диалог формата при открытии. Ждёт merge 16.4.
- **Согласование:** не согласован. Согласование заблокировано своей проверкой сети (приём индекса и отправка правки). Проверка 16.4 её не заменяет.

---

## [IMPROVE] Доработки и известные ограничения после фичи «сканер → метаданные фоном»

**Создано:** 2026-10-01. **Статус:** закрытые пункты (1.1, 1.2, 2.1 — сделаны
2026-10-02; 3.1 — реализовано в фиче cursor UI; 3.2 — 2026-11-21, см. шапку
файла) вынесены из этого файла
(полный текст — в истории git). Осталось: 1.3 (частично), 2.2.
Источник: журнал `.agents/scaner/PLAN_CONTEXT.md` (8 коммитов, 130/130 тестов) +
обзор кода. Приоритет: **высокий** — до масштабирования библиотеки (тысячи+
книг) или до релиза; **средний** — заметное улучшение; **низкий** — nice-to-have.
Статус пункта: `[ ]` не начато, `[~]` в работе, `[x]` готово.

### 1. База данных (`db/BookDatabase`)

#### 1.3 Одиночный `upsertBasic()` без транзакции — [~] (низкий, частично закрыт)
- **Где:** открытый остаток — `upsertBasic()` / `upsertBasicOn()`; закрытые
  пути — `upsert()` и `importSkeleton()` (транзакции), scan
  (`upsertBasicBatch`), enricher (`updateMetadataBatch`), импорт файла
  (`Catalog.importFile`).
- **Сделано (PR #14, `17a8c65`, 2026-10-17):** scan и stage-2 — батчи в
  транзакциях, BATCH_SIZE=5. **Сверка 2026-11-18:** схема v9 (`DB_VERSION = 9`,
  `BookDatabase.java:32`, `f0ec059`), prod-импорт — через `Catalog`, не голый
  `upsertBasic` из UI.
- **Осталось:** публичный `upsertBasic()` без обёртки (в основном тесты и
  редкие прямые вызовы) — при падении посередине теоретически половинчатое
  `works`/`files`.
- **Доработка:** `beginTransaction` вокруг одиночного `upsertBasic()` либо
  пометить API тест-only. `INSERT … ON CONFLICT` недоступен (SQLite 3.7), см. §4.

### 2. Кэш обложек (`util/CoverCache`)

#### 2.2 Ключ кэша — `path.hashCode()` — [x] (низкий)
- **План:** `.agents/plans/fix_2.2_cover-cache-key/PLAN.md` (ветка `fix/2.2_cover-cache-key`).
- **Сделано:** тесты `330f444`, код `852c47f`. Имя файла — SHA-1 UTF-8 пути. Старые файлы не мигрировались.
- **Где:** `CoverCache.fileFor()`.
- **Проблема:** `String.hashCode()` — 32 бита, коллизии двух разных путей возможны;
  при коллизии одна книга увидит чужую обложку. Риск вырос после 314831a: кэш стал
  перманентным (delete/clear связаны с каталогом), а коллизия — навсегда.
- **Доработка:** имя файла — SHA-1 UTF-8 пути, 40 hex. Не FNV/base36: base36 упирается
  в API 26, а 16 hex FNV пересекаются со старыми именами отрицательного `hashCode`.
  Старые файлы не мигрировать (обратного пути нет); `load` их не читает.
- **Согласование:** согласован 2026-11-21. Готово: сьют 441/441, lint 0 errors.

### 4. Платформенные ограничения (иметь в виду, не задача)

- **API 19 → SQLite 3.7:** нет `INSERT ... ON CONFLICT DO UPDATE` (3.24+), нет JSON1,
  нет оконных функций. Любое будущее улучшение схемы БД сверять с этой версией.
- **Holo-тема / API ≤19:** любые новые UI-компоненты только из фреймворка API 19.
- **Без AndroidX/зависимостей:** новые классы — только фреймворк + JDK
  (`java.util.zip`, `XmlPullParser`); билд офлайн.

### 5. Качество (вплетено в общий бэклог)

- **65 предсуществующих Lint-предупреждений** (`:app:lintDebug`, 0 errors;
  DefaultLocale, UnusedResources, …; один из них — `StaticFieldLeak`,
  закрыт фиксом `185d95b`, см. 7.4 в истории git) —
  отдельная чистовая задача по категориям [ ] (низкий).
  К ним добавились hardcoded-строки в `MainActivity`/`DetailActivity`/`EditMetaActivity`
  («Found N book(s)», «No books read yet.», «Format:», «Saving metadata…» и т.п.) и `#FFF3CD`
  в `activity_main.xml`. «File not found» уже ресурс `R.string.file_not_found`.
- **`UnknownIssueId`-шум из `app/lint.xml`** (2 шт. на каждый вариант;
  контекст — в вынесенном разделе 14, полный текст — в истории git) [ ]
  (низкий, найдено 2026-10-08): `ObsoleteApi` (строка 21,
  `severity="error"`) и `GoogleAppIndexing` (строка 28, `ignore`) — оба id
  **не существуют** в Lint, которым билдится проект (31.5.2, AGP 8.5.2;
  проверено по `lint-checks-31.5.2.jar` из Gradle-кэша: таких детекторов нет,
  валидный аналог `ObsoleteSdkInt` на строке 22 работает и не шипит).
  `ObsoleteSdkInt` (строка 22) — валидный. Последствие: строка 21 — мёртвая
  (инвариант minSdk 19 при этом не ослаблен: `NewApi` и `ObsoleteSdkInt`
  оба резолвятся), строка 28 — no-op ignore. Фикс: удалить строки 21 и 28
  (вместе с ревизией Phase-1-бейслайна, см. комментарий в `build.gradle`).
- **Robolectric-обходные пути в тестах** [~] (обслуживание): фейк-курсор (обход
  `UnsupportedOperationException` в `BaseCursor` — `register*Observer`/`getInt`/`getLong`)
  и миграционный тест через `PRAGMA user_version` — при апгрейде Robolectric (сейчас
  4.12.2) перепроверить; `SQLiteFactory`/`SQLiteConnection` — hidden API, в тестах
  не используются намеренно.

---

## [REFAC] Kebab-рефакторинг

**Создано:** 2026-10-03. Локальный журнал — `tmp/REFACTORING.md` (gitignored).
Коммиты ветки: `3772d74` (шаг 1, kebab) → `980e406` (шаг 2, подтверждение открытия)
→ `91be6e0` (фикс Remove-ребайнда) → тесты шага 3. Сьют 160/160,
lint 0 errors.

### 8.2 Тестовый хук `BookAdapter.getLastPopupMenu()` — [ ] (низкий)
- **Где:** `BookAdapter` (prod-код): package-private getter на pop-up, собранный
  `showBookMenu` — юнит-тестам нужно выбирать пункты меню без окна popup.
- **Доработка:** вынести шов в DI (конструктор/сеттер) или перейти на реальное
  нажатие / `ShadowPopupMenu`, когда Robolectric позволит.

### 8.3 Дублирование диалога «No reader found» — [ ] (низкий)
- **Где:** `showNoViewer()` в `MainActivity` и `DetailActivity`.
- **Суть:** логика «файл есть? читалка есть?» теперь общая (`Openers.openFile` +
  `OpenOutcome`, строки `no_viewer_title`/`no_viewer_message`/`ok` общие), но сам
  AlertDialog написан в двух местах.
- **Доработка:** вынести `showNoViewer` в `Openers` (нужен только `Context`)
  либо в маленький общий хелпер.

### 8.4 Подводные камни Robolectric в этом сьите (4.12.2, sdk 19) — [x] (знание)
- `ShadowActivity.getNextStartedActivity()` **деструктивна** (списывает интент из
  очереди): второй вызов подряд получит null — в тесте читать один раз и
  использовать сохранённый объект.
- `ShadowDialog.clickOn(viewId)` принимает **id вью** (`android.R.id.button1` /
  `button2`), а НЕ константы `AlertDialog.BUTTON_*` (по ним — NPE на
  `Dialog.findViewById`).
- В режиме PAUSED-лоопера запись запущенного интента откладывается до
  `shadowOf(Looper.getMainLooper()).idle()` — после клика по кнопке диалога
  обязательно idle, и только потом `getNextStartedActivity()`.
- API 19: у `Dialog`/`AlertDialog` НЕТ публичных геттеров title/message.
  Title — `shadowOf(dialog).getTitle()` (window title); message —
  `dialog.getWindow().getDecorView().findViewById(android.R.id.message)`.
- Загруженная машина (Android Studio + Chrome + Steam) может ронять случайный
  тест по 15-30-с таймауту из-за голода фоновых тредов (жертва менялась:
  `filterSpinner...`, `kebabDetails...`, `backgroundEnricher...`; 2026-10-21 —
  `startThrottles...` ×2 и `filterSpinnerRestricts...`: структурный фактор
  (GC слабого listener'а в 4 тестах `MetaEnricherTest`) устранён коммитом
  `1d4ab2a`, план `fix_flaky_worker_listener`, класс 8.4 принят как есть);
  изолированный прогон — секунды. Сначала перепроверять изолированно, потом чинить.

---

## [FEATURE] 2026-10-06: плитка по умолчанию + кебаб в хедере + Clear (ветка feature/new_view)

**Создано:** 2026-10-06. **Статус:** сделано (коммиты `784622a` → `6b694cb`,
каждый шаг — отдельный коммит); полный прогон 174/174 зелёные, lint 0 errors.

### 9.2 Подводный камень: AbsListView + empty view (не баг, но ловушка для тестов)
- При **пустом** каталоге `setEmptyView(emptyView)` + пустой адаптер заставляет
  фреймворк (`AbsListView.checkForDisabledView`) **скрывать сам грид/список** и
  показывать empty view. Т.е. сразу после запуска с пустым каталогом
  `grid.getVisibility() == GONE` — НЕ индикатор режима отображения. Данные
  прилетели (`AdapterDataSetObserver.onChanged` → `checkForDisabledView`) —
  видимость вернулась. Отсюда правило тестов: assertions по виду/режиму делать
  ПОСЛЕ `awaitAdapterCount(..., N > 0)`, либо проверять прикреплённость
  адаптера (`getAdapter() != null` на нужном виде), а не голую visibility.

### 9.4 Наблюдения и улучшения (не сделаны в этой задаче)
- **Роняемый Clear под working stage-2 воркером:** `MetaEnricher.cancel()` +
  `db.clear()` не останавливают воркер, уже вошедший в `enrichOneBook` для книги:
  после очистки он выполнит `updateMetadata(id)` (no-op — строки нет) и,
  возможно, `CoverCache.save(path, cover)` — сиротский `<hash>.img` для книги,
  которой больше нет в каталоге (уберётся следующим Clear'ом). Окно узкое (только
  текущая парс-книга). Простое закрытие: в `enrichOneBook` перед persist'ом
  добавить проверку «книга ещё в каталоге / воркер не отменён» (например,
  `db.getById(id) != null` перед `CoverCache.save`).
- **30-с флейки `MainActivityTest` под нагрузкой машины** (см. 8.4): во время
  этой работы жертвами были `filterSpinnerRestrictsTheVisibleBooks` и
  `kebabEditMetadataOpensTheEditorWithTheBookParcel` — оба проходили изолированно.
  Задокументированное явление, не регресс; если флейки зачастят — поднять
  `WAIT_MS` или гонять сьют на тихой машине / в CI.
- Row-иконки `ic_kebab`/`ic_kebab_gray` (96px) НЕ имеют SVG-источников в
  `app/icons/` (в отличие от хедерных): при перерисовке придётся либо создать
  SVG и прогнать генератор, либо править PNG вручную.
- `menu_settings` в `strings.xml` — мёртвая строка (меню настроек нет).

---

## [FEATURE] 2026-10-06: пагинация (6 плиток / экран списка) + Add/Remove в кебабе

**Создано:** 2026-10-06. **Статус:** сделано (один коммит на `feature/new_view`
сверх `d21554b`); полный прогон 184/184 зелёные, lint 0 errors.

### 10.2 Инцидент: параллельные сессии написали дубль

Две сессии OpenCode работали над одной задачей параллельно: одна довела до
конца клиентскую реализацию (последние записи в файлы 19:58), другая (эта)
записала серверный слой LIMIT/OFFSET в `BookDatabase`/`BookProvider`. Итог:
двойная пагинация, где вторая — мёртвый код. Серверный слой откатили
(`git checkout` двух db-файлов), клиентский реализацию оставили (решение
пользователя). **Правило:** в начале сессии, до правок — `git status`
(грязное дерево = кто-то уже работает) и mtime файлов, которые собираешься
менять (запись свежая, минут на 30–60 — вероятно, активна параллельная
сессия; свериться, а не переписывать).

### 10.6 Открытые заметки (улучшения)

- **Текущая страница сбрасывается в 1 при ротации** — page index не
  сохраняется в `onSaveInstanceState` (режим вида и on/off пагинации
  персистентны, номер страницы — нет). Тот же класс, что 6.6.
- **Размер страницы списка — измеренное приближение:** если список ещё не
  laid out (сразу после смены режима), высота берётся от грида (тот же
  контейнер), а до первого layout — от всего экрана (слушатель поправит).
  Если когда-нибудь высота строки перестанет быть постоянной — пересчёт
  произойдёт при следующем relayout контейнера, а не сразу.
- **«Recently read» ограничен 200** (`cursorRecent(200)`) — пагинация
  листает окно, а не весь каталог.
- Индикатор «X / Y» захардкожен в Java (`updatePageIndicator`) — согласовано
  с существующими hardcoded-строками (см. §5).
- При одной странице стрип показывает «1 / 1» с отключёнными кнопками —
  оставлено как есть.
- Размер страницы грида — фиксированные 6, не зависят от экрана (по ТЗ).
- Фича добавила свои Lint-предупреждения того же класса, что существующие
  (см. §5): `SetTextI18n` — индикатор «X / Y» (`updatePageIndicator`),
  `ApplySharedPref` — `commit()` в `setPaginationEnabled`. Чистовая задача по
  категориям уже в бэклоге §5.

---

## [FEATURE] 2026-10-06: свайп страниц по каталогу (grid/list), когда включена пагинация

**Создано:** 2026-10-06. **Статус:** сделано (один коммит на `feature/new_view`);
полный прогон зелёный, lint 0 errors.

### 11.2 Подводный камень, найден пробами: OnTouchListener на списке НЕ вызывается

Первая версия вешала observe-only `OnTouchListener` на list/grid
(`setOnTouchListener`) — и регрессионный тест упал: listener не вызывался.
Пробы (Robolectric sdk 19): на голом `View` listener срабатывал, на
`FrameLayout` — тоже, на `GridView` — никогда (при том что событие «обрабатывалось»).
Причина (AOSP 4.4 `ViewGroup.dispatchTouchEvent`): `OnTouchListener` вызывается
только из `View.dispatchTouchEvent`, а `ViewGroup` ПЕРЕОПРЕДЕЛЯЕТ этот метод и
сначала шлёт событие в child под пальцем; кликабельная строка/плитка
ПОГЛАЩАЕТ весь жест (`handled = true`) — до `super.dispatchTouchEvent` (и до
listener'а списка) дело не доходит. Т.е. на реальном устройстве свайп, начатый
на книге, до listener'а списка тоже не дошёл бы — баг был не в тесте, а в
дизайне. Отсюда `dispatchTouchEvent`-override в subclass'ах (он выполняется ДО
dispatch'а в children). **Урок:** OnTouchListener на AbsListView/ScrollView =
мёртвый код для жестиов, начинающихся на child'ах; проверять пробой на голых
View → ViewGroup → целевой вью.

### 11.3 Подводный камень №2: на API 19 свайп ВНУТРИ строки = клик по строке

Вторая итерация (после 11.2) уже перелистывала страницы, но тест «свайп не
открывает диалог» упал: диалог «Open this book?» открывался. Проверено по
исходникам AOSP 4.4 (`View.onTouchEvent`, `AbsListView.onInterceptTouchEvent`):
- в вью внутри скроллящего контейнера DOWN ставит только `PFLAG_PREPRESSED`
  (зажигание press с задержкой), а MOVE снимает его, только когда палец
  ВЫШОЛ за ГРАНИЦЫ вью (`pointInView(x, y, slop)`) — не по расстоянию от
  точки DOWN. Свайп на 150px из x=150 в плитке 242px ширины границ не
  пересекает → на UP `prepressed` ещё стоит → клик постится;
- `AbsListView` перехватывает жест у строки, только когда ВЕРТИКАЛЬНОЕ
  смещение > slop (`startScrollIfNeeded(y)`, x не учитывается) — горизонтальный
  свайп строка удерживает до конца.

Т.е. на реальном 4.4-устройстве горизонтальный свайп по строке, не выходящий
за её границы, тоже открывал бы книгу — баг был бы в проде, а не только в
тесте. **Фикс** (стандартный паттерн swipeable-list'ов): в тот момент, когда
жест пересёк порог свайпа, `PagedListView`/`PagedGridView` перехватывают его
сами (`onInterceptTouchEvent` → true) — фреймворк шлёт строке `ACTION_CANCEL`
(press сброшен, клика не будет) — и глотают оставшиеся события в
`onTouchEvent` (список сам не скроллится/не кликается по горизонтальному
свайпу). Тап и короткая дрожь ниже порога (3×touchSlop) перехвату не
подвергаются — обычные клики по строкам работают как раньше.

### 11.5 Ограничения (улучшения)

- Без слайд-анимации (реалистичный слайд требовал бы второй страничный view /
  своего пейджера — за рамками framework-only API 19); листание мгновенное,
  как у кнопок Prev/Next.
- Шлёпок-флик признаётся только если смещение ≥ 3×touchSlop (без
  velocity-tracking — намеренно: детерминированно и тестируемо).
- Свайп работает по области книг (list/grid); сам пейджер-стрип не свайпается.

---

## [REVIEW] 2026-10-07: ревью последних коммитов (свайп, иконки < / >, размеры плитки)

**Создано:** 2026-10-07. Источник: ревью коммитов `88c36cd` (пагинация) →
`6d25f57` (свайп) → `876a5eb` (иконки < / >) → `c1cd4e4` (размеры плитки) +
полный прогон тестов (194/194). Закрытые пункты (13.1, 13.2, 13.6) вынесены
2026-10-21 (полный текст — в истории git).

### 13.3 `PagedListView`/`PagedGridView`: три одинаковых override'а в каждом классе — [x] (низкий)

- **План:** `.agents/plans/fix_13.3_page-swipe-control/PLAN.md` (ветка `fix/13.3_page-swipe-control`).
- **Сделано:** код `b71e5f4` (`PageSwipeControl`), `4f1e644` (кэш высоты строки списка). Новых тестов не было.
- **Согласование:** согласован 2026-11-21. Готово: сьют 441/441 после каждого шага, lint 0 errors.
- **Где:** `PagedListView.java` / `PagedGridView.java` (коммит 6d25f57).
- **Проблема:** `dispatchTouchEvent` / `onInterceptTouchEvent` /
  `onTouchEvent` скопипастаны между классами (~50 строк на класс; различие —
  только слова «row»/«tile» в комментах). Bookkeeping уже вынесен в общий
  `PageSwipeTracker`, а view-слой продублирован: любой будущий багфикс жеста
  придётся вносить дважды.
- **Доработка:** package-private `PageSwipeControl`; три override делегируют,
  один `ownsGesture()` по `swipeInFlight()`. Общий базовый класс не подходит:
  `ListView` и `GridView` сходятся только на `AbsListView`. Поведение жеста
  то же; safety net — `PageSwipeTrackerTest` (9 тестов) + e2e свайп-тест.

### 13.4 Перехват свайпа активен и при ВЫКЛЮЧЕННОЙ пагинации — [x] (знание, не баг)

- **Где:** `PagedListView`/`PagedGridView` (трекер заводится в `onCreate`
  безусловно); само перелистывание гвардится в `MainActivity.pageSwipe` по
  `paginationEnabled`.
- **Суть:** при пагинации OFF горизонтальный жест выше порога тоже забирается
  у строки и глотается (страница просто не меняется). Наблюдаемый эффект:
  горизонтальный drag по строке/плитке, закончившийся на ней, больше НЕ даёт
  row click («Open this book?») — и это полезный побочный эффект фикса 11.3
  (pre-pressed release-into-click): случайное открытие книги боковым
  сдвигом исключено. Вертикальный скролл и тапы не затронуты (порог требует
  |dx| ≥ 3×slop И |dx| ≥ 2|dy|).
- **Если понадобится отключать перехват вместе с пагинацией:** нужен
  `disablePageSwipe()`/повторный `enablePageSwipe` при тоггле (сейчас метода
  нет, и в этом нет необходимости).

### 13.5 Мелочи (отмечено, не чинилось)

- `computeListPageSize()` инфлейтил новую строку на каждый вызов — [x]
  в `4f1e644`: кэшируется пиксельная высота непривязанной строки, доступная
  высота по-прежнему читается каждый раз.
- Свайп постится через `view.post`: если activity к моменту выполнения
  завершён, `showPage()` работает по мёртвому экрану (безвредно: адаптер
  жив, crash'а нет).
- Флейк (8.4) подтверждён в этом прогоне: в полном сьюте пал
  `confirmingTheOpenConfirmationLaunchesTheReaderAndMarksTheBookRead`
  («timed out waiting for the catalog to hold 2 book(s)») и прошёл изолированно
  за 3с; повторный полный прогон — 194/194.
- **План 13.3:** кэш высоты строки вошёл в `4f1e644`. Гард `showPage` после `view.post` и флейк 8.4 оставлены как есть.

---

## [REVIEW] 2026-10-17: ветка feature/batch_metadata (PR #14) не была отревьюена — [ ] (средний)

**Создано:** 2026-10-17 (сверка ISSUES.md с состоянием репозитория).
- **Что влилось:** PR #14 `feature/batch_metadata` — 13 кодовых коммитов
  `b2437f0`…`0dc457a` (+ 3 `dock:`-коммита по агентской документации,
  merge `de26820`): single-pass парсер stage 2 (`ed76d8f`), SQLite-батчи —
  одна транзакция на группу BATCH_SIZE=5, DB_VERSION 5,
  `idx_books_title_nocase` (`17a8c65`), доработки `CoverLoader`/`CoverCache`/
  MOBI (`f4264f9`, `edec9c8`, `a38a515`, `2b08946`), 5 фиксов утечек
  (`bbe1437`, `185d95b`, `808051b`, `a190dd1`, `4dd6a91`), масштабный тест на
  400 книг (`0dc457a`).
- **Проблема:** каждая предыдущая ветка имела в этом файле свой [REVIEW]
  (6.x — `feature/separate_scan_and_finding_meta`, 13.x — свайп/пагинация);
  PR #14 влился без него. Разделы 15.1–15.3 ниже — находки сверки, а не
  полновесного ревью.
- **Доработка:** прогнать ревью по образцу 13.x (коммиты + полный сьют +
  lint), зафиксировать находки, закрыть/открыть тикеты.

---

## [REFAC] 2026-10-17: декомпозиция `CoverExtractor` (~800 строк) — [ ] (средний)

**Создано:** 2026-10-17. **Актуальность сверена:** 2026-11-18 (862 строки в редакторе, непустых 798; файл не менялся с `c1aef32`, 2026-11-13).
Естественный наследник закрытого [REFAC] «Разрастание `MetaExtractor` и
`CoverExtractor`» (вынесен из файла 2026-10-21, полный текст — в истории
git). Тикет согласован к работе после сверки с текущим кодом; изменений в
production-код не внесено.
- **Где:** `meta/CoverExtractor` — 862 строки в редакторе, непустых 798 (на 2026-09-30 было 448;
  на 2026-10-17 — 651; рост связан с потоковым чтением FB2-шапки, base64 по
  байтам и ограничителями памяти, `f4264f9`, `edec9c8`, `7486c32`, `55d19f8`).
- **Проблема:** в классе сосредоточены все byte-level FB2-логики и общие
  ZIP/byte helpers:
  `readFb2Header` (`CoverExtractor.java:308`, поток `:317`),
  `coverFromFb2Bytes` (`:540`), base64-декодер по байтам,
  `readFb2EntryBytes` (`:700`),
  `readEntryBytes` / `readEntryAsString` / `readToEndBytes`
  (`CoverExtractor.java:822-847`). Одновременно здесь остаётся логика EPUB,
  FB2, FB2ZIP и MOBI.
- **Доработка:** вынести byte-level FB2-логику в отдельный package-private
  класс (например `meta/Fb2ZipReader`) по прецеденту `MobiParser`;
  публичный API (`CoverExtractor.extract(File)`, `canHaveCover`) и шов
  `readFb2EntryBytes` для `MetaExtractor`/`MetaEnricher` — без изменений.
  Рефакторинг без изменения поведения, шаг = коммит, safety net —
  `CoverExtractorTest` + полный сьют.
- **Уточнение поведения:** описание длиннее `effectiveCoverLimit()` —
  `readFb2Header` возвращает `null`, частичный буфер не отдаётся
  (`CoverExtractor.java:328-330`). Элемент обложки, который не влезает в лимит,
  из результата выкидывается, байты description возвращаются
  (`finishFb2Header` / `joinCoverElement`, `c1aef32`). Оба исхода сохраняются
  при рефакторинге. Класса `Fb2ZipReader` в дереве нет. Публичные `extract(File)`
  (`:71`) и `canHaveCover` (`:87`) и шов `readFb2EntryBytes` для `MetaExtractor`
  (`:214`) / `MetaEnricher` (`:249`) на месте.
- **Ограничения:** анти-рекомендации (no единый `BookReader`, no
  interface-стратегия, no субпакеджи, pre-lambda, no новые зависимости) —
  раздел 3 вынесенного закрытого тикета (полный текст — в истории git);
  minSdk 19 / Holo / framework-only — CONTEXT.md, раздел «Invariants».

---

## [IMPROVE] 2026-10-17: новые наблюдения (сверка ISSUES.md с репозиторием)

**Создано:** 2026-10-17. Источник: read-only сверка файла с кодом и
git-историей; полновесного ревью PR #14 ещё не было (см. [REVIEW] выше) —
при нём находки могут дополниться. **Сверка 15.1–15.3:** 2026-11-14 (`master`).

### 15.1 `CoverLoader`: CIRCLE-обложки намеренно не кэшируются — [ ] (низкий)
- **Где:** `util/CoverLoader` (PR #14, `2b08946`; поведение без изменений в
  `master`).
- **Суть:** CIRCLE-битмапы (бейдж 48dp в list-режиме) **намеренно** не
  попадают в `LruCache` (класс-Javadoc + `CoverLoader.java:227`: «option (v) of
  the optimization plan»): in-flight дедуп по ключу `format|path|shape`;
  кэширование CIRCLE вытеснило бы ROUNDED_RECT/SQUARE из общего LruCache.
- **Осталось:** в `CONTEXT.md` class map — одна строка про `CoverLoader`,
  **без** явного про skip CIRCLE (в отличие от Javadoc в коде); отдельного
  CIRCLE-кэша нет.
- **Доработка:** docs-only — коротко зафиксировать trade-off в `CONTEXT.md`
  (или сослаться на Javadoc `CoverLoader`); опционально — замеры передекода
  на большой библиотеке; при желании — маленький отдельный CIRCLE-кэш.

### 15.2 «Зомби» parse-треды `MetaEnricher` — [ ] (низкий)
- **Где:** `meta/MetaEnricher.enrichOneBook` (parse-тред + таймаут, PR #14).
- **Сделано (`7486c32`):** `maxLiveParseThreads` (по умолчанию **2**) и
  `liveParseThreads` — при превышении cap новый парс **не стартует**
  (`skipped_due_to_live_thread_cap`, `markFailed`); регрессия в
  `MetaEnricherTest`.
- **Проблема (остаётся):** по таймауту тред «отпускается» (результат
  отбрасывается), но **дорабатывает** read-only парсинг до конца и держит
  слот счётчика до `finally`; отмены парсинга нет. Cap ограничивает **старт**
  новых тредов, а не суммарное CPU/IO от «зомби» после таймаута; политика
  skip при cap ≠ политика timeout (`meta_failed` / очередь).
- **Доработка:** задокументировать ожидаемый потолок (cap × длительность
  тяжёлого файла); при необходимости — пересмотреть skip vs wait,
  кооперативная отмена или вынос cap/таймаута в настройки.

### 15.3 Доки: `docs/architecture.md` vs `CONTEXT.md` — [ ] (низкий)
- **Где:** `docs/architecture.md` (1481 строка в редакторе, непустых ~1233; шапка ссылается на
  `CONTEXT.md` как «быстрый контекст»), `CONTEXT.md`, `README.md`.
- **Проблема:** формального разведения ролей нет: `CONTEXT.md` шлёт за
  архитектурой в **`README.md`**, а не в `docs/architecture.md`; `README` не
  ссылается на `architecture.md`. Два длинных текста дублируют знания;
  `architecture.md` местами отстаёт от кода (7.6 закрыт, в истории git; в строке
  ~428 всё ещё `DB_VERSION = 7`, в коде v9).
- **Доработка:** явно прописать иерархию (напр. CONTEXT — сессия / class map,
  README — сборка и workflows, `architecture.md` — учебное развёрнутое
  описание) + перекрёстные ссылки; сверить устаревшие абзацы с v9/`Catalog`.

---

## [REFAC] 2026-11-21: декомпозиция `MainActivity` — вынос `FilterTab`, `ScanTask`, `ImportTask` — [ ] (средний)

**Создано:** 2026-11-21. **Статус:** план согласован (2026-11-21, человек).
Секции ниже — утверждённый план. К коду приступает `neo-refactor`.
Отдельный `.agents/plans/**/PLAN.md` не заводить. Открытый [REFAC]
`CoverExtractor` не смешивать: другой файл и другой коммитный ряд.

Номера строк — снимок `MainActivity.java` до шага 1 (1547 строк,
последняя — закрывающая скобка класса, 2026-11-21). После шага 1
искать методы по имени.

### 1. Задача / диагностика

- **Где:** `app/src/main/java/com/example/mylibrary/MainActivity.java`,
  1547 строк. Один экран каталога: `onCreate` / `onDestroy` /
  `onSaveInstanceState`, список/плитка, пагинация, kebab, диалог
  фильтра, скан, обогащение, импорт, открытие и удаление книги.
- **Проблема:** в одном классе шесть работ. Три уже статические
  вложенные классы и не держат активити сильно — их можно вынести
  файлами без смены поведения.
- **Согласованный разрез** (сверен с файлом, не двигать):
  1. `FilterTab` — javadoc и класс, строки 1267–1546 (ровно 280).
     Объявление класса — 1281: `private static final class FilterTab`.
     → `final class FilterTab` в новом
     `app/src/main/java/com/example/mylibrary/FilterTab.java`
     (package-private, без `public`). Тесты тип не называют: ищут
     `TabHost`, `R.id.formats_tab` / `R.id.genres_tab`, подписи
     вкладок.
  2. Сборка диалога (`showFilterDialog` 618–766, включая javadoc;
     `ensureFilterTabLabel` 768–789) уходит в тот же `FilterTab.java`.
     Обработчик OK остаётся в активити: пишет `filters`, ставит
     `recentView = false`, зовёт существующий приватный
     `applyCurrentView()` (restartLoader + иконка воронки). Поле
     `openFilterGenresTab` (154–158) и вызовы `setLoading` / `refresh`
     из `startEnrichment` (1125–1127) и `onEnrichmentStopped`
     (191–194) остаются на активити.
  3. `ScanTask` — javadoc 1000–1006, класс 1007–1098 →
     `ScanTask.java`, тот же пакет, `final class`, без `public`.
     После скана зовёт активити: спрятать прогресс, rebind курсора,
     `initLoader` / `restartLoader`, тост «Found N book(s)»,
     `startEnrichment`. Приватный `startScan()` (995–998) остаётся
     и только делегирует: прогресс-бар VISIBLE и `execute()`.
     Сигнатура `private void startScan()` без аргументов не меняется
     (`MainActivityTest` берёт её через `getDeclaredMethod("startScan")`).
  4. `ImportTask` — javadoc 1183–1189, класс 1190–1265 →
     `ImportTask.java`, тот же пакет, `final class`, без `public`.
     Шов с активити: тост и rebind курсора. Закрытие `ProgressDialog`
     остаётся в задаче (см. шаг 4: диалог гасится и когда экран уже
     мёртв). `launchImport` (1134), `onActivityResult` (1146),
     `queryDisplayName` (1162), `importToLibrary` (1177) остаются
     в активити: кусок задачи без них смыкается.
- **Остаётся в `MainActivity`:** жизненный цикл и `onCreate`,
  режим списка/плитки, пагинация и свайп (`pageSwipe`,
  `setPaginationEnabled`, `showPage`, `computeListPageSize`),
  kebab (`showHeaderMenu`, `getLastHeaderMenu`, `confirmClear`,
  `setPaginationEnabled`), колбэки лоадера, действия с книгой
  (open / edit / remove и их диалоги), полоска обогащения
  (`startEnrichment` / `onEnrichmentStopped` / `enrichListener`).
  Пагинацию, меню и диалоги книг не выносить.
- **Уже существующий package-private API, который нельзя менять:**
  `PopupMenu showHeaderMenu(View)`, `PopupMenu getLastHeaderMenu()`,
  `FilterState getFilterState()`. Новые package-private методы —
  только шов ниже; они аддитивные. Тесты ищут `startScan` по имени
  и не перебирают все методы класса.
- **Почему у задач был `static`:** javadoc `ScanTask` / `ImportTask` /
  `FilterTab` — анонимный или нестатический внутренний класс держал
  бы активити сильной ссылкой, а скан и копирование живут дольше
  экрана. В своём файле класса это то же правило: не хранить
  `MainActivity` сильным полем. `ScanTask` и `ImportTask` держат экран
  через `WeakReference`, как сейчас. `FilterTab` активити полем не
  держит: страницу по-прежнему строит из `Context` активити (тема
  Holo), не из application context.
- **Ограничения:** поведение не меняется; тесты не правятся, safety
  net — `MainActivityTest` и весь сьют; один шаг = один коммит;
  pre-lambda (анонимные классы, явные аргументы типов, без diamond
  и без method reference); без субпакетов, без интерфейса-стратегии,
  без новых зависимостей; minSdk 19, только фреймворк.

Единственная реализация шва (другой вариант не выбирать):

| Кто зовёт | Новый метод на `MainActivity` | Зачем он есть |
|---|---|---|
| `FilterTab.show`, кнопка OK | `void commitFilterDraft(FilterTab formatsTab, FilterTab genreTab)` | Запись приватных `filters` и вызов приватного `applyCurrentView`. Тело — нынешний `onClick` строк 742–756, дословно. |
| `FilterTab.show`, после `setLoading` и в `OnDismissListener` | `void setOpenFilterGenresTab(FilterTab tab)` | Поле остаётся private. `null` на dismiss. `startEnrichment` / `onEnrichmentStopped` это поле по-прежнему читают сами. |
| `ScanTask.onPostExecute`, экран жив, до записи каталога | `void onScanProgressDone()` | `progressBar.setVisibility(GONE)`. Порядок как сейчас: спрятать прогресс раньше `saveScanBatch`. |
| `ScanTask.onPostExecute`, экран жив, после записи каталога | `void onScanPosted(List<Book> found)` | Если `found != null`: `adapter.changeCursor(currentCatalogCursor())`, `updateEmptyView()`, затем `getLoader(LOADER_BOOKS)` → `initLoader`, если лоадера нет, иначе `restartLoader`. Потом тост `Found N` (`N = found == null ? 0 : found.size()`) и `startEnrichment()`. |
| `ImportTask.onPostExecute`, экран жив, после `dismiss` | `void onImportPosted(Boolean ok, String displayName)` | `if (ok)` — тост `Imported …`, rebind, `updateEmptyView()`; иначе тост `Import failed`. Unboxing как сейчас: `Boolean`, не `boolean`. |

Поля `adapter`, `progressBar`, `filters`, `db`, `catalog`, `openFilterGenresTab` не делать package-private. `LOADER_BOOKS` остаётся private в активити; `initLoader` / `restartLoader` живут внутри `onScanPosted`, колбэк лоадера — `this` активити.

### 2. Сложность и субагент

**Уровень:** — (neo-refactor)
**Субагент (Cursor):** `neo-refactor`

**Обоснование:** это рефакторинг без смены поведения и без новых тестов.
Поток — `PIPELINE.md` §3: исполняет R, не D. `neo-developer` /
`neo-developer-low` сюда не ставить. Чеклист §2.1 скилла analytics
для D здесь нарочно не выбирает модель: шагов больше трёх, задеты UI,
сканер и class map, рядом риски 8.4 / 9.2 / 10.2 — как у «сложной»
задачи разработчика, но контракт другой. Существующие тесты — safety
net, их не пишут заранее и не правят. Публичный и уже существующий
package-private API `MainActivity` не меняется; новые методы — только
шов из таблицы §1.

### 3. План по шагам

Каждый шаг = один коммит. Сообщение: `Шаг N: …`. Критерий «готово» —
полный сьют зелёный и число тестов то же, что до шага 1 (ориентир
исходников: 449 аннотаций `@Test`, из них `MainActivityTest` — 46;
истина — атрибут `tests` в XML-отчёте Gradle). Lint: 0 errors.
Перед правкой шага — `git status` и mtime `MainActivity.java` (правило
10.2). Чужой незакоммиченный prod-код не откатывать и не править.
`app/src/test/**` не трогать. `meta/CoverExtractor*` не трогать.

Импорты: убрать из `MainActivity` только те, которые этот шаг сделал
неиспользуемыми. Импорты, которые не использовались и до тикета
(`Color`, `Button`, `CompoundButton`, `Arrays`, `MotionEvent`,
`SuppressLint`), не чистить.

**Шаг 1. `FilterTab` → свой файл.**
Перенести javadoc и тело класса (1267–1546) дословно, включая
анонимные `TextWatcher` / `OnClickListener` / `OnItemClickListener`.
Класс — `final`, package-private. Модификаторы членов не менять:
`page`, `all`, `checked`, `rebuildVisible`, `setLoading`, `refresh`,
`bindAppliedMarker` остаются package-private (их читает активити и
на шаге 2 прочитает `commitFilterDraft`); `applyLoading` остаётся
private. В javadoc заменить «STATIC on purpose / non-static inner
class» на ту же причину для отдельного файла: у класса нет ссылки
на внешний экземпляр, страницы диалога не должны её держать.
`MainActivity` по-прежнему делает `new FilterTab(...)`.
Сборку диалога на этом шаге не трогать.
Документы тем же коммитом (факт «вкладка живёт внутри активити»
перестаёт быть верным):
- `CONTEXT.md`, class map: в ячейке `MainActivity` к словам
  `two FilterTab pages` добавить, что это package-private класс
  `FilterTab.java`, не вложенный тип. Смысл ячейки (TabHost, Formats
  из `Formats.ALL`, жанры из `distinctGenres()`, замок на время
  stage-2, черновик, точка на подписи, воронка = `isActive()`)
  не переписывать. Новая строка таблицы `FilterTab`: одна страница
  диалога (Formats или Genres) — поиск, select-all/clear, список;
  точка черновика через `bindAppliedMarker`; замок жанров —
  `setLoading`; пересборка словаря — `refresh` (выбор хранится по
  имени). Своей Activity нет.
- `README.md`, дерево файлов (строка `MainActivity.java`): следующей
  строкой добавить `FilterTab.java` — одна вкладка диалога фильтра.
  Строку про `MainActivity.startScan()` не менять: метод на месте.

**Шаг 2. Сборка диалога → `FilterTab.show`.**
Удалить из активити `showFilterDialog` и `ensureFilterTabLabel`.
Клик воронки (`onCreate`, сейчас строка 283, `showFilterDialog()`)
заменить на:

```java
FilterTab.show(MainActivity.this, filters.copy(),
        db.distinctGenres().toArray(new String[0]), enriching);
```

`FilterTab.show(MainActivity host, FilterState draft, String[] genreNames, boolean genresLocked)` —
package-private static. Тело — нынешний `showFilterDialog` (633–765)
со следующими заменами и только ими:
- `Context` / `this` активити → `host`;
- черновик, имена жанров и флаг замка — аргументы (не читать приватные
  поля активити);
- `openFilterGenresTab = genreTab` в том же месте (после
  `genreTab.setLoading(genresLocked)`, до сборки `TabHost`) →
  `host.setOpenFilterGenresTab(genreTab)`;
- тело `onClick` OK → `host.commitFilterDraft(formatsTab, genreTab)`;
- dismiss → `host.setOpenFilterGenresTab(null)`.

Сохранить дословно, это поведение, которое тесты и комментарии уже
фиксируют: конструктор `TabHost(host, null)` (одноаргументный роняет
диалог на resource 0x0); `addTab` formats затем genres; маркеры через
`ensureFilterTabLabel`; `setCurrentTab(draft.selectedGenres.isEmpty() ? 0 : 1)`;
скрытие нетекущей страницы (`genreTab.page` GONE, если текущая 0,
иначе `formatsTab.page` GONE); заполнение `checked` из черновика и
повторный `rebuildVisible()` после конструктора (конструктор
пересобирает список раньше заполнения). `ensureFilterTabLabel`
становится `private static` на `FilterTab` и принимает `Context`
вместо неявного `this`. Имя метода сохранить.

`commitFilterDraft` и `setOpenFilterGenresTab` — методы из таблицы §1,
package-private, рядом с `getFilterState`. `applyCurrentView` и
`updateFilterIcon` остаются private.

Тем же коммитом дописать в строку class map `FilterTab`: диалог
собирает `FilterTab.show`; OK — `MainActivity.commitFilterDraft`.
`README.md` и `docs/architecture.md` на этом шаге не менять
(`FilterTab` в architecture.md не назван).

Импорты, которые после переноса в `MainActivity` больше не встречаются:
`Editable`, `TextWatcher`, `SparseBooleanArray`, `TypedValue`,
`Gravity`, `AdapterView`, `ArrayAdapter`, `CheckBox`, `EditText`,
`ListView`, `TabHost`, `TabWidget`. `FrameLayout` и `LinearLayout`
в активити остаются.

**Шаг 3. `ScanTask` → свой файл.**
Перенести класс дословно. Конструктор больше не читает приватные
`host.catalog` / `host.db`. Сигнатура:

```java
ScanTask(Catalog catalog, BookDatabase db, MainActivity host)
```

`getExternalFilesDir(null)` по-прежнему вызывается внутри конструктора
задачи (публичный метод `Activity`, в try/catch как сейчас). Так
строка class map `LibraryScanner` остаётся правдой: набор
`adoptedFromPaths` передаёт `ScanTask`, каталог внешних файлов в
`scanDetailed` тоже передаёт `ScanTask`. Эту строку `CONTEXT.md` не
править. `startScan` остаётся:

```java
private void startScan() {
    progressBar.setVisibility(View.VISIBLE);
    new ScanTask(catalog, db, this).execute();
}
```

`onPostExecute` — те же ветки, в том же порядке:
1. `found = result == null ? null : result.books`;
   `screenAlive = a != null && !a.isFinishing()` (без `isDestroyed` —
   на этом пути его сейчас нет).
2. Если экран жив — `a.onScanProgressDone()`.
3. Если `found != null` — `catalog.saveScanBatch` и `removeMissing`
   по `root.complete` дословно, даже если экран мёртв.
4. Если экран жив — `a.onScanPosted(found)`.

Запись каталога не переносить в активити. Проверку `isFinishing` не
дублировать внутри `onScanPosted`. Тела `onScanProgressDone` и
`onScanPosted` — таблица §1. Javadoc задачи сохранить и поправить
«STATIC nested class» на отдельный файл с `WeakReference` (причина
та же). Сильное поле `MainActivity` не добавлять.

Документы тем же коммитом:
- `CONTEXT.md`: новая строка `ScanTask` — package-private `AsyncTask`
  в своём файле; `Catalog` / `BookDatabase` и экран через
  `WeakReference`; фон — `adoptedFromPaths` и `getExternalFilesDir(null)`
  в `LibraryScanner.scanDetailed`; UI-хвост — `onScanProgressDone` /
  `onScanPosted`. Workflow «Scan (stage 1)» не переписывать: там по-прежнему
  `startScan()` → `AsyncTask`, это не стало ложью.
- `README.md`: в дерево файлов добавить `ScanTask.java`. Строку
  `MainActivity.startScan()` не менять.
- `docs/architecture.md` строка 185 — одна фраза. Сейчас: все
  долгоживущие задачи, включая скан, — статические вложенные
  `AsyncTask`, и пункт 1 списка выше — это `MainActivity`. Убрать
  «скан» из перечисления вложенных и в той же фразе назвать
  package-private `ScanTask` (свой файл, Activity через
  `WeakReference`). Абзац под фразой, строки 885 и 956 и §10 не
  трогать (тикет 15.3; 885/956 не говорят «вложенный»).

Импорты активити: убрать `HashSet` и `java.util.Set`, если их больше
нет. `LibraryScanner`, `AsyncTask`, `WeakReference` ещё нужны
`ImportTask` — оставить до шага 4.

**Шаг 4. `ImportTask` → свой файл.**
Перенести класс дословно. `launchImport`, `onActivityResult`,
`queryDisplayName`, `importToLibrary` не переносить. `importToLibrary`
по-прежнему создаёт `dest`, показывает `ProgressDialog` и запускает
задачу. Конструктор принимает уже собранные зависимости вместо чтения
приватных полей:

```java
ImportTask(Context appContext, Catalog catalog, BookDatabase db,
        Uri uri, String displayName, File dest, ProgressDialog pd,
        MainActivity host)
```

Вызов из `importToLibrary`:
`new ImportTask(getApplicationContext(), catalog, db, uri, displayName, dest, pd, this).execute()`.
Тело `doInBackground` (копирование, `scanSingle`, `catalog.importFile`,
`CoverCache.delete` при перезаписи, `MetaEnricher.enrichOne(..., false)`)
не менять: тот же буфер 8192, та же запись прямо в `dest`, без
`.copying`. `onPostExecute`: сначала `dismiss` у диалога из
`WeakReference` (и когда активити уже нет), затем
`if (a == null || a.isFinishing()) return;`, затем
`a.onImportPosted(ok, displayName)`. `dismiss` не переносить в
`onImportPosted`: иначе мёртвый экран оставит диалог. Javadoc —
отдельный файл и `WeakReference`, причина та же.

Документы тем же коммитом:
- `CONTEXT.md`: строка `ImportTask` — package-private `AsyncTask`;
  app context на время копирования; Activity и `ProgressDialog` слабо;
  `dismiss` даже если экран мёртв; дальше `onImportPosted`.
  `launchImport` / `onActivityResult` / `queryDisplayName` /
  `importToLibrary` остаются на активити. Workflow «Import» не
  переписывать.
- `README.md`: строка `ImportTask.java` в дереве.
- `docs/architecture.md` строка 185: так же одной фразой вынуть
  «импорт» из вложенных и назвать `ImportTask`. Больше в этом файле
  ничего.

Импорты активити, которые после этого не встречаются: `AsyncTask`,
`WeakReference`, `LibraryScanner`, `CoverCache`. `ProgressDialog`
остаётся: его показывает `importToLibrary`.

### 4. Тесты (задание тестировщику)

Новые тесты не писать. Набор не менять: ни добавить, ни удалить, ни
править assertions. Красный тест — сломанный контракт рефакторинга,
не повод чинить тест. Проверка T — после всех четырёх шагов R
(`PIPELINE.md` §3): полный сьют, lint, то же число тестов. R гоняет
сьют сам после каждого шага; T это не заменяет.

Что уже прибивает каждый шаг (`MainActivityTest`, Robolectric sdk 19).
Тесты ходят в экран через `R.id.btn_filter`, kebab, `getFilterState()`,
`getLastHeaderMenu()`, reflection `startScan` и `onActivityResult`.
Тип `FilterTab` / `ScanTask` / `ImportTask` они не называют — вынос
файла сам по себе их не ломает, ломает другое поведение.

- **Шаг 1 (вкладка).** Поиск, select-all, крестик, чекбоксы, точка
  на подписи: `theFilterDialogSearchBarFiltersTheFormatList`,
  `theFilterDialogSelectAllCheckboxTogglesAllFormats`,
  `selectAllWithActiveSearchResetsSearchAndSelectsAllFormats`,
  `filterTabDotsFollowTheDraftAndTheTabClear`,
  `filterTabDotSurvivesSearchAndSelectAll`,
  `filterTabDotMatchesCommittedStateOnReopen`,
  `genreTabDotTracksOnlyTheGenreDraft`. Список форматов и id страниц:
  `theFunnelIconOpensTheFilterDialogWithOneItemPerFormat`,
  `filterDialogHidesTheTabThatIsNotCurrent` (`R.id.formats_tab` /
  `R.id.genres_tab`).
- **Шаг 2 (сборка диалога и OK).** `TabHost` и подписи:
  `filterDialogHidesTheTabThatIsNotCurrent`,
  `filterTabDotMatchesCommittedStateOnReopen` (текст
  `filter_tab_formats` / `filter_tab_genres` и compound drawable).
  OK пишет фильтр и сбрасывает recent view:
  `checkingAFormatInTheDialogRestrictsTheVisibleBooks`,
  `applyingTheFilterDialogOverridesTheRecentViewWithoutLosingTheFilter`,
  `anEmptySelectionInTheDialogMeansAllFormats`,
  `theFilterStateIsActiveExactlyWhenAFormatIsSelected`.
  Cancel не трогает состояние:
  `cancellingTheFilterDialogLeavesTheCatalogAndTheStateUntouched`.
  Черновик после поворота:
  `theFilterChoiceSurvivesARotation`,
  `theFilterStateSurvivesRotationButDialogStateIsReset`.
  Ветку «открыть диалог сразу на Genres, если в черновике есть жанры»
  (`setCurrentTab`) ни один тест не утверждает — её всё равно сохранить
  (шаг 2). Хелпер теста `ensureFilterTabIndicators` дорисовывает
  индикаторы, только если у `TabWidget` меньше двух детей; пустой
  strip из-за сломанного `addTab` этот хелпер замаскирует не весь
  assert (текст подписи и drawable проверяются отдельно).
- **Жанры во время enrichment.** Прямого теста нет: никто не открывает
  диалог, пока `R.id.enrich_bar` ещё VISIBLE, и не проверяет
  `setEnabled(false)` / подсказку `filter_genres_loading`.
  Ближайшее — `genreTabDotTracksOnlyTheGenreDraft`: ждёт
  `needMeta()` пустым и полоску GONE, затем видит жанр «Prose»
  в списке вкладки. `backgroundEnricherCompletesForAllBooks` прибивает
  саму полоску. Замок и `refresh` остаются на активити
  (`startEnrichment` / `onEnrichmentStopped`) и обязаны звать те же
  `setLoading` / `refresh` на том же экземпляре, который
  `setOpenFilterGenresTab` запомнил. Выкинуть эти два вызова нельзя,
  даже хотя сьют их не ловит.
- **Шаг 3 (rescan через `startScan`).**
  `rescanAfterEmptyFirstScanDrawsTheNewBooks` — reflection
  `startScan()`, список рисует новые книги, прогресс уходит.
  `headerKebabRescanPickRunsTheScanAndDrawsTheNewBooks` — пункт меню.
  `scanOnLaunchFindsBooksInExternalStorage` — холодный старт.
  `recreationSkipsTheRescanButResumesEnrichment` — после поворота
  `startScan` не вызывается, прогресс не показывается, enrichment
  продолжается. Rebind после скана не завязан на живой
  `ContentObserver` — это комментарий теста и код `onPostExecute`;
  `initLoader` на холодном старте и `restartLoader` на повторном
  скане оба обязаны остаться.
- **Шаг 4 (импорт).**
  `headerKebabImportPickLaunchesTheDocumentPicker` — `launchImport`.
  `importFromDocumentPickerCopiesFileIntoLibraryAndCatalog` и
  `reimportingOverAnExistingFileReEnrichesTheBook` — `onActivityResult`,
  копирование, каталог, повторное обогащение, rebind.
  `importRejectsWhenThePickerYieldsNoUsableFile` — отказ по типу ещё
  в активити, до задачи; поэтому `queryDisplayName` /
  `onActivityResult` не переезжают.

Пагинация, свайп и empty-view этим тикетом не двигаются. Если упал
тест вида/страниц — сначала 9.2 (пустой каталог прячет `AbsListView`,
visibility грида не означает режим) и 13.3–13.5 (жест в
`PagedListView` / `PagedGridView`, перехват свайпа включён и при
выключенной пагинации, `showPage` с поста). Чинить это «заодно» нельзя.

**Флейки 8.4.** Таймаут 15–30 с в полном сьюте (часто
`MainActivityTest` или `MetaEnricherTest`) — не регресс, пока
изолированный прогон того же метода падает. Сначала
`--tests "com.example.mylibrary.MainActivityTest.<метод>"`, потом вывод.
`ShadowActivity.getNextStartedActivity()` по-прежнему одноразовый;
`ShadowDialog.clickOn` — id вью `android.R.id.button1` / `button2`.

### 5. Анти-рекомендации

- **Отдельная Activity** для фильтра, скана или импорта. Тесты
  поднимают один `MainActivity` и жмут `btn_filter`, kebab и
  `onActivityResult`. Второй экран — другой манифест и другое
  поведение.
- **Presenter и любой интерфейс хоста** (`ScanHost`, `OnFilterCommit`,
  стратегия «как читать книгу»). Прецедент закрытого [REFAC]
  MetaExtractor/CoverExtractor: интерфейс-стратегии нет, единого
  `BookReader` нет. Хост `ScanTask` / `ImportTask` / `FilterTab.show` —
  конкретные методы `MainActivity` из таблицы §1.
- **Общий базовый класс** для `ScanTask` и `ImportTask`. Разные
  результаты (`ScanResult` / `Boolean`) и разные хвосты; общая база
  стала бы тем же интерфейсом без ключевого слова.
- **Субпакет `ui` или перенос `ScanTask` в `scan/`.** Новые классы
  лежат рядом с `FilterState` и `BookAdapter`, в
  `com.example.mylibrary`. В `scan/` остаются `LibraryScanner` и
  `Formats`.
- **Вынос пагинации, kebab и диалогов книг.** Общие `adapter`, `list`,
  `grid`, `filters`, prefs. Жест и пустой список — 9.2 и 13.3–13.5,
  не этот тикет. `showHeaderMenu` / `getLastHeaderMenu` /
  `confirmClear` / `setPaginationEnabled` остаются как есть, включая
  приватность `confirmClear` и `setPaginationEnabled`.
- **Лямбды, method reference, diamond.** Исходники Java 8 из-за AGP,
  стиль — анонимные классы и явные аргументы типов (`new WeakReference<MainActivity>(host)`,
  `new HashSet<String>()`). При переносе не осовременивать.
- **Package-private поля** `filters` / `db` / `adapter` / `progressBar`
  вместо методов хвоста. Шире, чем шов, и даёт тесту писать состояние
  в обход `getFilterState()`.
- **Перенос `launchImport` / `onActivityResult` / `queryDisplayName` /
  `importToLibrary` в файл задачи.** Смыкание есть без них: задача
  получает uri, dest, catalog, db и app context аргументами.
  Отказ «That file type is not supported» остаётся в активити — его
  прибивает `importRejectsWhenThePickerYieldsNoUsableFile` до
  `execute()`. Оставлять в активити пустую оболочку этих четырёх
  методов и уносить тела незачем.
- **`dismiss` внутри `onImportPosted`.** Метод зовётся только для живого
  экрана; диалог сейчас гасится раньше этой проверки.
- **Новый тест на замок жанров** в этом тикете. [REFAC] тесты не
  добавляет. Поведение `setLoading` / `refresh` сохранять дословно.
- **Правка `docs/architecture.md` целиком** и правка строк 885 / 956.
  Там «статический ScanTask / ImportTask» без слова «вложенный», а
  data flow рядом устарел (`upsertBasicBatch`, `LibraryScanner.scan`).
  Это тикет 15.3. В этом тикете — только фраза на строке 185, шаги 3 и 4.
- **Попутный фикс копирования** (`.copying` → rename, другой буфер) и
  смена write-path скана (`clear`, прямой `upsert` мимо `Catalog`).
  Инварианты ниже.

### 6. Invariants, которые нельзя сломать

Из `CONTEXT.md` «Invariants (don't break)» — только задетое:

- **minSdk 19, никаких API выше 19, тема Holo.** Диалог — framework
  `TabHost` (не ViewPager), `AlertDialog`, `AsyncTask`, `ProgressDialog`.
  Support-библиотек и AndroidX не добавлять.
- **Никаких новых зависимостей.**
- **`Formats.ALL` — единственный список форматов** вкладки Formats.
  Жанры — только `db.distinctGenres()` на открытии и в `refresh`,
  без зашитого списка.
- **Фильтры сессионные.** `FilterState` по-прежнему в instance state.
  OK подменяет `filters` новым состоянием с `recentView = false` и
  зовёт `applyCurrentView`. Cancel `filters` не пишет. Точка воронки —
  `isActive()` уже записанного состояния, не черновика вкладки.
  `recentView` по-прежнему побеждает в запросе, пока OK его не сбросит.
- **Запись скана не меняет контракт `last_read`.** `ScanTask` по-прежнему
  пишет через `catalog.saveScanBatch` / `removeMissing`, не через
  `BookDatabase` в обход `Catalog` и не через `clear`.
- **Файл импорта не «улучшать».** Инвариант про полузаписанный файл
  (`.copying` → rename у приватной копии, `.tmp` → swap у MetaWriter)
  этот тикет не реализует заново: нынешнее тело `ImportTask` пишет
  в `dest` напрямую. Перенести байт-в-байт. Менять протокол — отдельная
  задача.

Не задеты и не трогаются: манифест (`package`, `exported`), бюджет
2 минут на файл (`MetaEnricher` и `startEnrichment` остаются на
активити).

### 7. Как проверять

PowerShell, корень `D:\Projects\myLibrary`. `java` в PATH нет:

```powershell
$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lintDebug
```

`assembleDebug` этим тикетом не требуется: ресурсы, манифест и gradle
не меняются. T по `PIPELINE.md` §3 может прогнать его отдельно.

Быстрый сигнал по экрану (не заменяет полный сьют):

```powershell
gradlew.bat :app:testDebugUnitTest --tests "com.example.mylibrary.MainActivityTest"
```

Число тестов — `app/build/test-results/testDebugUnitTest/*.xml`
(атрибут `tests`) и HTML
`app/build/reports/tests/testDebugUnitTest/index.html`.
После каждого шага failures = 0 и `tests` равно числу до шага 1.
Таймаут в полном сьюте — правило 8.4 из §4: сначала изолированный
`--tests "com.example.mylibrary.<Класс>.<метод>"`.

### Текущее состояние

Ветка `refactor/main-activity-extract` (от `master` / `8fc49d1`).
Перед шагом 1: `git status` — изменён только `.agents/ISSUES.md`;
`MainActivity.java` совпадал с HEAD, mtime 2026-11-21 04:15:31;
чужого незакоммиченного prod-кода нет. База сьюта до шага 1 — XML
`app/build/test-results/testDebugUnitTest` от 2026-11-21 04:14
(prod-код совпадает с HEAD): tests=449, failures=0, errors=0.

Шаг 1 — `309d864`: `FilterTab` — package-private `final class` в
`FilterTab.java`. Сьют после шага 1: tests=449, failures=0, errors=0.
Lint: 0 errors.

Шаг 2 — `aeb25e9`: диалог собирает `FilterTab.show`; OK —
`MainActivity.commitFilterDraft`; вкладка жанров —
`setOpenFilterGenresTab`. Сьют после шага 2: tests=449, failures=0,
errors=0. Lint: 0 errors.

Шаг 3 выполнен в этом коммите: `ScanTask` — package-private
`final class` в своём файле. Конструктор принимает `Catalog`,
`BookDatabase` и экран; `getExternalFilesDir(null)` остаётся внутри
задачи. Хвост — `onScanProgressDone` / `onScanPosted`. Приватный
`startScan()` без аргументов на месте. Шаг 4 не начат. Сьют и lint
шага 3 прогоняются до шага 4.
