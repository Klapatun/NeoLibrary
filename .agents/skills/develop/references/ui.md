# UI: грид, пагинация, свайп

Детали раскладки и жестов. В `CONTEXT.md` остаётся только указатель на этот
файл. Читать, когда задача меняет каталог, режим вида, пагинацию или свайп.

## Режимы вида (список/плитки)

- `MainActivity` показывает один и тот же `BookAdapter` в `ListView` и
  `GridView` (`activity_main.xml`); переключатель (`@+id/toggle_view`) —
  `setViewMode()` (смена видимости + какой layout инфлятит адаптер).
- **Плитки — режим по умолчанию**: грид стартует видимым, адаптер прикреплён
  к гриду в `onCreate` (и **до `setAdapter` принудительно
  `adapter.setMode(initialMode)`** — регрессия 9.3: class-дефолт `MODE_LIST` +
  ранний выход в `setViewMode` = первый layout в чужом макете).
- Выбор режима персистентен: `SharedPreferences` (файл `library_prefs`, ключ
  `view_mode`) — переживает и ротацию, и закрытие приложения.
- Плитки: `GridView`, 3 колонки, тайл `item_book_grid.xml` — обложка сверху,
  бейдж формата (`bg_format_badge`) в левом нижнем углу обложки, заголовок
  под обложкой (2 строки); все тайлы одного размера.
- Грид разбит в **px** под ONYX Boox Volta 3 (6", 1024x758): тайл 242px
  (`stretchMode=none`, `columnWidth=242px`), зазоры 8px между колонками и
  строками, `numColumns="auto_fit"`: портрет 758px → 3 колонки
  (3×242 + 2×8 = 742px), ландшафт 1024px → 4; контейнер книг — 16px padding
  сверху, 8px по бокам; обложка 242x328px, красный круг 155px
  (`bg_circle_red`) с инициалом для книг без обложки; скроллбар `insideOverlay`.

## Пагинация (кебаб «Add/Remove pagination»)

- Checkable-пункт кебаба (`main_menu_pagination`); при включении — стрип
  `@+id/pagination_bar` (Prev / «X / Y» / Next). Кнопки — `ImageButton` 16dp
  (`page_prev`/`page_next`); disabled-состояние — селектор с потускневшим PNG
  (`#999999`): `android:alpha` на `<item>` селектора требует API 21 — на 19
  только отдельный PNG.
- Адаптер выдаёт **одну страницу за раз** — клиентское окно по ВСЕМУ курсору
  (`BookAdapter.setPagination`), поэтому любое изменение каталога (скан,
  enrich, импорт, удаление, фильтр) ребайдится как раньше, и прямые
  rebind-пути `MainActivity` (`changeCursor(currentCatalogCursor())` +
  `updateEmptyView()`) работают без изменений.
- Размер страницы: грид — фиксированные **6 тайлов** (`GRID_PAGE_SIZE`);
  список — «столько строк, сколько помещается на экране»
  (`computeListPageSize()`). Высота книжной области меняется под пальцами
  (stage-2 стрип, пейджер-стрип, первый layout) → `OnLayoutChangeListener` на
  `bookContainer`: новое значение ВЫСОТЫ → **posted**-рекомпьют (читает
  осевшую высоту, не уведомляет адаптер в середине layout-пасса).
- Выбор персистентен: `library_prefs`, ключ `pagination_enabled`;
  применяется в `onCreate` **ПОСЛЕ** режима вида (размер страницы от него
  зависит).
- `changeCursor` зажимает page до последнего существующего **ДО**
  `super.changeCursor` (dataset-колбэки видят зажатый page); свап на
  НЕ-NULL курсор шлёт `onChanged`, НЕ `onInvalidated` (тот — только для
  `changeCursor(null)`). Пустой каталог — 0 страниц, стрип скрыт.
- **Свайп** страниц (когда пагинация включена): горизонтальный свайп по
  list/grid (влево = +1, вправо = −1). `PagedListView`/`PagedGridView`
  (subclass'ы `ListView`/`GridView`): жест детектится в override
  `dispatchTouchEvent`, питающем `PageSwipeTracker` (свайп = на release
  |dx| ≥ 3×touchSlop и |dx| ≥ 2×|dy|; второй палец отменяет); смена страницы
  **постится** (выполняется после полного развёртывания touch-последовательности)
  и идёт через тот же `showPage()` (clamp + скролл наверх + индикатор).
  **Подводный камень API 19**: `OnTouchListener` на списке НИКОГДА не
  срабатывает для свайпа, начатого на строке (кликабельная строка поглощает
  жест до listener'а списка) — отсюда override'ы
  `dispatchTouchEvent`/`onInterceptTouchEvent`. Список перехватывает жест в
  тот момент, когда он пересёк порог свайпа (`onInterceptTouchEvent` → true,
  фреймворк шлёт строке `ACTION_CANCEL`) — иначе на API 19 свайп, не выходящий
  за границы строки, разрядился бы в клик по строке («Open this book?»).
  Тапы и дрожь ниже порога перехвату не подвергаются.
