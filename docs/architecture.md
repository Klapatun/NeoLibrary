# Архитектура приложения «Neo Library»

> Документ для студентов и людей, не знакомых с Android-разработкой.
> Все термины, концепции и технологии, используемые в проекте, снабжены ссылками
> на материалы для изучения (русские и английские источники в приоритете).
>
> Связанные материалы: [`README.md`](../README.md) — общее описание и сборка;
> [`CONTEXT.md`](../CONTEXT.md) — быстрый контекст; [`diagrams/`](diagrams/) —
> готовые диаграммы (SVG) и их исходники (Mermaid).

---

## 1. Что такое «Neo Library»

**Neo Library** — нативное Android-приложение (каталог-«библиотека») для ридера
**Neo Reader 3.0** на Android 4.4.4 (API 19), рассчитанное в первую очередь на
электронную книгу **ONYX Boox Volta 3** (6", 1024×758).

Приложение умеет:

- **находить** файлы книг на внешнем накопителе (рекурсивный скан);
- **показывать** их списком или плитками с обложками и метаданными
  (название, автор, издатель, описание);
- **открывать** книгу в Neo Reader (любом другом читателе) через стандартный
  механизм Android — intent;
- **редактировать** метаданные: для EPUB и FB2 — прямо в самом файле,
  для остальных форматов — в локальном каталоге приложения;
- **импортировать** файлы через системный файловый менеджер (SAF);
- вести список **«недавно прочитанных»**.

Поддерживаемые форматы (единый канонический список — класс `scan/Formats`):

| Формат | Расширение | Чтение метаданных из файла | Запись метаданных в файл |
|---|---|---|---|
| CHM | `.chm` | — | — |
| DOC | `.doc` | — | — |
| DOCX | `.docx` | — | — |
| DjVu | `.djvu` | — | — |
| EPUB | `.epub` | ✅ (OPF) | ✅ (пересборка OPF) |
| FB2 | `.fb2` | ✅ (XML) | ✅ (XML-патч) |
| FB2.ZIP | `.fb2.zip` | ✅ (внутренний FB2) | — |
| FB3 | `.fb3` | — | — |
| HTML/HTM | `.html/.htm` | ✅ (`<title>`) | — |
| MOBI/AZW | `.mobi/.azw` | ✅ (PalmDB/EXTH) | — |
| PDB | `.pdb` | — | — |
| PDF | `.pdf` | — | — |
| PRC | `.prc` | — | — |
| RTF | `.rtf` | — | — |
| TXT | `.txt` | ✅ (имя файла) | — |

**Ключевая идея проекта** — **двухслойная модель метаданных**: у каждой книги
метаданные хранятся *в файле* (только EPUB и FB2) и *в каталоге приложения*
(SQLite, все 15 форматов). Подробнее — в [разделе 6.1](#61-двухслойная-модель-метаданных).

---

## 2. Минимум теории Android: что нужно знать, чтобы читать код

Разделы 3–12 этого документа предполагают, что вы *не* знаете, что такое Activity,
Cursor или Gradle. Ниже — краткие пояснения со ссылками, куда копать.

> **Примечание о языках.** Официальная документация Android —
> [`developer.android.com`](https://developer.android.com/) — существует только
> на английском (русского перевода у Google нет). Русскоязычный «классический»
> учебник по Android — книга [«HeadAndroid»](https://www.mirrorbooks.ru/book/headandroid)
> (Сергей Коваленко, читается бесплатно в вебе). Русские статьи — на
> [Хабр](https://habr.com/ru/).

### 2.1 Из чего состоит Android-приложение

Приложение — это пакет **APK** (по сути ZIP-архив с кодом и ресурсами), который
операционная система устанавливает на устройство. Проект в нашем случае —
стандартная структура «одного модуля» Android:

```
myLibrary/
├── app/                      # «модуль» приложения — один модуль на APK
│   ├── build.gradle          # настройки сборки именно этого модуля
│   ├── lint.xml              # настройки статического анализатора (Lint)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # «паспорт» приложения: экраны, права, провайдер
│       │   ├── java/com/example/mylibrary/   # исходный код (Java)
│       │   └── res/                        # XML-ресурсы: макеты, строки, стили, иконки
│       └── test/             # unit-тесты (JUnit + Robolectric)
├── build.gradle              # корневой файл сборки (версия Android Gradle Plugin)
├── settings.gradle           # какие модули входят в проект (здесь: :app)
├── gradlew / gradlew.bat     # Gradle Wrapper — «сборка с фиксированной версией Gradle»
└── docs/                     # документация (этот файл, диаграммы)
```

Что важно понять новичку:

- **`AndroidManifest.xml`** — декларативный «паспорт» приложения: какие в нём
  экраны (Activity), какие разрешения, какой провайдер данных. О нём —
  [обзор AndroidManifest](https://developer.android.com/guide/topics/manifest/manifest-overview).
- **`res/`** — все не-Java-ресурсы: XML-макеты экранов (`res/layout/`), строки
  (`res/values/strings.xml`), стили и темы (`res/values/styles.xml`), иконки.
  О системе Views и макетов —
  [View system overview](https://developer.android.com/develop/ui/views).
- **Gradle** — язык описания сборки; проект собирается командой
  `gradlew assembleDebug`. Gradle — [`gradle.org`](https://gradle.org/),
  официальное руководство
  ([Gradle User Guide](https://docs.gradle.org/current/userguide/index.html) —
  на сайте есть выбор языка, включая русский). Android-специфичная часть —
  **Android Gradle Plugin (AGP)**, о нём —
  [Android build overview](https://developer.android.com/build).
  «Gradle Wrapper» (`gradlew`) — приём, фиксирующий версию Gradle в самом
  репозитории, чтобы все собирали проектом одинаково.

### 2.2 Версии Android: API level, minSdk, targetSdk, compileSdk

Android — операционная система с «уровнями API» (API level): Android 4.4 = API 19,
Android 14 = API 34 и т.д. Каждой функции в SDK свой минимальный уровень API.
О значимости версий — [About Android versions](https://developer.android.com/about/versions)
и о том, что такое API-уровни, —
[About SDK platform versions](https://developer.android.com/guide/topics/manifest/uses-sdk-tag).

В `app/build.gradle` записаны четыре версии, и это **главный источник ограничений
проекта**:

| Параметр | Значение | Что значит |
|---|---|---|
| `minSdk 19` | Android 4.4.4 | Приложение **обязано** работать на этом и более новых устройствах. |
| `targetSdk 34` | Android 14 | На уровне какого API приложение «обещано» тестировать (влияет на поведение ОС). |
| `compileSdk 34` | Android 14 | Против какого набора API компилируется код (требование современного AGP). |
| `namespace com.example.mylibrary` | — | Базовый пакет приложения (AGP 8+ требует его в Gradle, а не в манифесте). |

Наследие этой связки: **никаких API-вызовов выше 19** (иначе приложение упадёт на
ридере), **тема Holo** (материальные темы требуют API 21+, см.
[Holo design](https://developer.android.com/develop/ui/visual/holo-design)),
**без AndroidX и без сторонних библиотек**. Lint-правило `NewApi = error`
(см. `app/lint.xml`) — автоматическая «страховка» от нарушения этого правила:
[Android Lint](https://developer.android.com/studio/write/lint).

### 2.3 Экраны: Activity и её жизненный цикл

Основная единица UI в «классическом» Android — **Activity**: один экран = один
экземпляр класса, наследующего `android.app.Activity` (здесь — именно «родной»
класс, не AndroidX). У Activity есть **жизненный цикл** (`onCreate → onStart →
onResume → … → onPause → onStop → onDestroy`), который Android-система
управляет сама (поворот экрана, уход в фон и т.п.).
Подробно — [Activity lifecycle](https://developer.android.com/guide/topics/fundamentals/activity-lifecycle)
и [Activity overview](https://developer.android.com/guide/topics/fundamentals/activity).

В проекте три Activity:

- `MainActivity` — список библиотеки (главный экран, «точка входа»);
- `DetailActivity` — карточка одной книги (открыть / отредактировать / убрать);
- `EditMetaActivity` — форма редактирования метаданных.

Каждая Activity получает данные от «родителя» через **Intent**
(см. [2.5](#25-intent-как-сообщение-между-активностями)) — это и есть
[переход между экранами](https://developer.android.com/guide/topics/fundamentals/activity-creation).

### 2.4 Главный поток и фоновые потоки

Android держит **один главный поток (main thread)**: всё, что касается интерфейса,
выполняется только в нём. Если на нём делать долгую работу (чтение файлов,
парсинг, запись в БД) — интерфейс «зависнет» и система может убить приложение
(ANR). Долгие операции выносятся в **фоновые потоки**.

Исторически для этого использовался `AsyncTask` — так и делает этот проект
(запуск на фоновом потоке: `doInBackground()`, возврат на главный:
`onPostExecute()`). См.
[AsyncTask (API 19)](https://developer.android.com/reference/android/os/AsyncTask).
*Справедливое замечание:* с API 30 `AsyncTask` объявлен deprecated, и в новых
приложениях пишут `Executor`/`Coroutine`s (
[ExecutorService (JDK)](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html),
[Coroutines](https://developer.android.com/kotlin/coroutines-compatibilities)).
Здесь же `AsyncTask` — **сознательный** выбор: он гарантированно есть в API 19
и не требует ни AndroidX, ни сторонних библиотек (см.
[инварианты, раздел 4](#4-ключевые-ограничения-invariant-проекта)).

В проекте фоновая работа происходит в пяти местах:

1. `MainActivity` — скан накопителя (этап 1) и импорт файла (SAF);
2. `MetaEnricher.start()` — фоновый воркер чтения метаданных/обложек (этап 2);
   внутри него каждая книга парсится на одноразовом потоке с бюджетом
   2 минуты (см. [6.6](#66-время-бюджет-и-порядок-очереди-обогащения));
3. `DetailActivity` — «быстрый путь» `enrichOne` для ещё не обогащённой книги;
4. `EditMetaActivity.save()` — запись метаданных (в файл + в БД).

Все долгоживущие задачи (скан, импорт, обогащение, save, быстрый путь) —
**статические** вложенные классы `AsyncTask`: анонимный внутренний класс несёт
скрытую **сильную** ссылку на Activity (javac добавляет её автоматически,
используется она или нет), а эти задачи могут жить дольше экрана (минуты).
Поэтому каждая задача держит **app-контекст** (для самой работы) и Activity
только **слабо** — `WeakReference` (см.
[WeakReference (Javadoc)](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/WeakReference.html)):
мёртвый экран не удерживается в памяти, а работа в фоне завершается (например,
строки каталога пишутся в БД, даже если экран уже закрыт).

Правило: **чтение/запись файлов и БД — в фоне; изменение виджетов — только в
`onPostExecute` / через `Handler`**.

### 2.5 Intent как «сообщение» между активностями

**Intent** — «заявление о намерении»: «открой этот файл», «покажи экран X».
Intent — это же механизм, с помощью которого приложения «договариваются» о
переходах между собой. Полное описание —
[Intents and intent filters](https://developer.android.com/guide/topics/intents-and-intent-filters)
и [Intent (reference)](https://developer.android.com/reference/android/content/Intent).

В проекте Intent-ы используются так:

- `MainActivity → DetailActivity` — «покажи книгу с таким `id`»
  (`putExtra(EXTRA_BOOK_ID, id)`);
- `DetailActivity → EditMetaActivity` — «редактируй вот эту книгу» (пересылка
  объекта `Book` как `Parcelable`, см. [2.7](#27-передача-объектов-между-экранами-parcelable));
- `DetailActivity → Neo Reader` — **implicit** intent `ACTION_VIEW` с MIME-типом
  и `file://`-URI: Android сам решает, кем открыть; если читатель один — он
  открывается сразу (это «открыть в Neo Reader» без жёсткой привязки к приложению).

### 2.6 MIME-типы

**MIME-тип** (Media Type) — стандартное «имя» типа данных, вида
`application/epub+zip`. По нему ОС подбирает подходящее приложение-«просмотрщик».
О концепции — [Media type (Wikipedia, англ.)](https://en.wikipedia.org/wiki/Media_type)
/ [Типы данных Internet (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Типы_данных_Internet_(MIME)).
В коде таблица MIME-типов для всех 15 форматов — `util/Openers.mimeFor()`.

### 2.7 Передача объектов между экранами: Parcelable

**Parcelable** — интерфейс Android для «быстрой сериализации» объектов,
передаваемых в Intent (значительно быстрее стандартной Java-сериализации).
О `Parcelable` — [Parcelable (reference)](https://developer.android.com/reference/android/os/Parcelable).

Модель `Book` (см. [раздел 7](#7-данные-модель-и-бд)) реализует `Parcelable`,
чтобы передаваться из главного экрана (пункт «Edit metadata» в меню книги)
в `EditMetaActivity` одним `Intent`.

### 2.8 SQLite на Android: Cursor, SQLiteOpenHelper

**SQLite** — встраиваемая реляционная БД, ядро которой — в самом Android (и в
вашем устройстве). О SQLite — [SQLite (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/SQLite)
и [сайт проекта](https://www.sqlite.org/).

На Android к ней подключаются через два класса-«помощника»:

- **`SQLiteOpenHelper`** — обёртка, создающая и (при необходимости) обновляющая
  базу. В проекте: `db/BookDatabase`. О классе —
  [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper).
- **`Cursor`** — «курсор» по результату SELECT: итератор по строкам.
  [Cursor](https://developer.android.com/reference/android/database/Cursor).

Каталог приложения — одна таблица `books` в файле `library.db`
(схема — [раздел 7.1](#71-таблица-books-и-её-флаги)). Все записи (INSERT/UPDATE/DELETE)
идут через `BookDatabase` — и только через него.

### 2.9 ContentProvider, ContentResolver, ContentObserver

**ContentProvider** — стандартный способ для приложения (или части приложения)
«предоставлять данные» другим частям по URI вида
`content://com.example.mylibrary.books/books`. О них —
[ContentProvider overview](https://developer.android.com/guide/topics/fundamentals/providers).

В проекте `db/BookProvider` — **read-only** провайдер, который **не** хранит
данные (их хранит `BookDatabase`), а лишь даёт **канал уведомлений**:
после любой партии изменений каталога вызывается
`getContentResolver().notifyChange(uri, null)`, и все, кто **наблюдает** за этим
URI (`ContentObserver`, см.
[ContentObserver](https://developer.android.com/reference/android/database/ContentObserver)),
узнают, что данные изменились. Это классический **паттерн «наблюдатель» (Observer)** —
[Observer pattern (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Наблюдатель_(паттерн)).

Почему провайдер нужен, если данные можно читать напрямую из `BookDatabase`?
Потому что **`CursorLoader`** (см. [2.10](#210-cursorloader-подписка-ui-на-изменения-бд))
«подписывается» именно на URI, а не на произвольный объект.

### 2.10 CursorLoader: «подписка» UI на изменения БД

**`CursorLoader`** — «загрузчик», который:

1. выполняет `query()` у провайдера (в фоне);
2. возвращает `Cursor` на главный поток;
3. **подписан** на `notifyChange(uri)` и **перезапрашивает** данные, когда что-то
   изменилось.

См. [CursorLoader](https://developer.android.com/reference/android/content/CursorLoader).

Это «сердце» UI проекта: список книг в `MainActivity` — **cursor-driven**
(«курсорно-управляемый»): список не перечитывают вручную. Каждая партия работ
(скан, обогащение, импорт) вызывает `notifyChange` → `CursorLoader`
перезапрашивает → `CursorAdapter` (см. [2.11](#211-адаптеры-и-переработка-recycle-строк))
обновляет на экране только изменённые строки.
Полная картина — на диаграмме [«круговорот данных»](diagrams/data_flow.svg)
(исходник: [`data_flow.mmd`](diagrams/data_flow.mmd)).

### 2.11 Адаптеры и «переработка» (recycle) строк

**Адаптер (Adapter)** — объект, «переводящий» данные (строки БД, массив) в
виджеты списков/сеток. В проекте — `BookAdapter` на базе
**`CursorAdapter`** (адаптер, который сам читает `Cursor`). О `CursorAdapter` —
[CursorAdapter](https://developer.android.com/reference/android/widget/CursorAdapter),
о базовом механизме — [BaseAdapter](https://developer.android.com/reference/android/widget/BaseAdapter).
Концептуально это **паттерн «адаптер» (Adapter)** —
[Adapter pattern (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Адаптер_(паттерн)).

Ключевой механизм — **view recycling**: `ListView`/`GridView` не создают
виджет для каждой строки, а **переиспользуют** уже созданные (метод `getView`
получает `convertView` — «старый» виджет). Именно поэтому `BookAdapter`
аккуратно проверяет, что асинхронно загруженная обложка «надета» на ту же строку,
на которую её запрашивали (тег `cover_tag` на `ImageView`, см. `CoverLoader`).

Кроме того, `BookAdapter` умеет **опциональную client-side пагинацию**
(`setPagination`): держит *весь* курсор, но выдаёт одну страницу за раз
(окно по смещению). Благодаря этому все изменения каталога по-прежнему
перепривязываются «на месте», а экран получает и длинный скролл, и
страницы — см. [раздел 6.7](#67-пагинация-и-листание-страницы-свайпом).

### 2.12 Кэширование: LruCache (в памяти) и файловый кэш

Приложение хранит обложки в **два** кэша:

- **в памяти** — `LruCache<String, Bitmap>` (последние, вытесняемые при нехватке
  места). О `LruCache` —
  [LruCache](https://developer.android.com/reference/android/util/LruCache);
- **на диске** — каталог `getExternalFilesDir("covers")` с файлами `<hash>.img`
  (живёт пере перезапусков приложения, без разрешения на доступ к памяти).

Эта двухуровневая схема — в [разделе 6.5](#65-двухуровневый-кэш-обложек).

### 2.13 Графика: Bitmap, Canvas и обрезка изображений

Обложки — `Bitmap` (растровое изображение). Для того, чтобы круглый бейдж в
списке и «округлённая» плитка в сетке выглядели аккуратно, проект сам
«обрезает» `Bitmap` в `Canvas` (рисует его в круг/скруглённый прямоугольник с
прозрачностью). О `Bitmap` — [Bitmap](https://developer.android.com/reference/android/graphics/Bitmap),
о `Canvas` — [Canvas](https://developer.android.com/reference/android/graphics/Canvas).
Весь код — в `util/CoverLoader.java`.

### 2.14 Тестирование: JUnit, Robolectric, Lint, CI

- **JUnit** — фреймворк unit-тестов для Java (в проекте — JUnit 4).
  [JUnit 4](https://junit.org/junit4/).
- **Robolectric** — фреймворк, позволяющий тестировать Android-код (SQLite,
  Intent, `Parcel`) **на JVM без эмулятора**. [robolectric.org](https://robolectric.org/).
  Все тесты лежат в `app/src/test/`.
- **Android Lint** — статический анализатор, входящий в состав AGP (без
  сторонних зависимостей). Конфиг — `app/lint.xml`.
  [Android Lint](https://developer.android.com/studio/write/lint).
- **CI (GitHub Actions)** — автоматическая сборка/тесты/линты на каждый PR и
  push в `master`; плюс локальные git-хуки (`pre-commit` — Lint, `pre-push` —
  запрет прямого push в `master` + запуск тестов). Подробности —
  [раздел 11](#11-тестирование-ci-и-git-хуки) и в
  [`README.md`](../README.md#ci-and-branch-protection-github).

### 2.15 SharedPreferences: маленькие настройки, переживающие перезапуск

**SharedPreferences** — хранилище «ключ–значение» для настроек приложения
(«память телефона»): `getSharedPreferences(name, MODE_PRIVATE)` →
`edit().putInt/putBoolean(…).commit()` → `getInt/getBoolean` при следующем
старте. О нём —
[SharedPreferences (reference)](https://developer.android.com/reference/android/content/SharedPreferences).

Проект держит в файле `library_prefs` два UI-настроения:

- `view_mode` — режим отображения каталога: список или плитки
  (по умолчанию — **плитки**);
- `pagination_enabled` — включена ли разбивка на страницы
  (по умолчанию — выключена).

Оба применяются в `MainActivity` при старте (сначала режим, потом пагинация —
размер страницы зависит от режима), поэтому выбор переживает не только
поворот экрана, но и закрытие приложения.

---

## 3. Слои приложения

Код разбит на три слоя по ответственности. Верхний слой — UI (экраны и адаптеры),
средний — логика и работа с форматами, нижний — хранилище.
Это классическая **многослойная архитектура** (Layered Architecture) —
[Многослойная архитектура (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Многослойная_архитектура).

```
┌────────────────────────── UI (Activity, Holo) ───────────────────────────┐
│  MainActivity        список/плитки · header-kebab (импорт/рескан/         │
│                      пагинация/очистка) · фильтр · «недавние»             │
│  DetailActivity      книга: просмотр + открыть в Neo Reader               │
│  EditMetaActivity    форма метаданных (открывается из меню книги)         │
│  BookAdapter         CursorAdapter: список + плитки, меню книги,          │
│                      client-side пагинация (окно над курсором)            │
│  PagedListView/      ListView/GridView + горизонтальный свайп страницы    │
│  PagedGridView       (dispatchTouchEvent → PageSwipeTracker)              │
│  (res/layout/*.xml,  макеты экранов, строк, плиток; res/menu/*.xml —      │
│   res/menu/*.xml)   header- и per-book меню                                │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ Book (Parcelable) — пересылка между Activity
┌───────────────────────── ЛОГИКА / ФОРМАТЫ ────────────────────────────────┐
│  scan/Formats          15 форматов + карта расширений + tier стоимости    │
│                        обогащения (enrichmentPriority)                    │
│  scan/LibraryScanner   рекурсивный скан накопителя (этап 1)               │
│  meta/MetaExtractor    чтение метаданных: EPUB/FB2/FB2ZIP/MOBI/TXT/HTML   │
│  meta/MobiParser       бинарный reader MOBI/AZW (PalmDB + EXTH)           │
│  meta/MetaWriter       запись метаданных в EPUB/FB2 (.tmp → swap → .bak)  │
│  meta/CoverExtractor   байты обложки: EPUB/FB2/FB2ZIP/MOBI; FB2 —         │
│                        потоковая «шапка» (до </description>/<body>)       │
│  meta/MetaEnricher     фоновый воркер (этап 2): single-pass парсеры,      │
│                        бюджет 2 мин/файл, cheapest-first, batch-коммиты   │
│                        + enrichOne (быстрый путь/импорт)                  │
│  meta/MetaData         POJO-контейнер «прочитанные метаданные»            │
│  util/Openers          MIME + ACTION_VIEW (OpenOutcome-колбэки)           │
│  util/CoverLoader      асинхронная обложка: decode «в размер»,            │
│                        in-flight дедуп, LruCache + «обрезка»              │
│  util/CoverCache       файловый кэш обложек на внешнем накопителе         │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ persists via
┌────────────────────────────────── STORAGE ─────────────────────────────────┐
│  db/BookDatabase       SQLite library.db (v5): схема, batch-upsert,       │
│                        флаги, needMeta(), clear()                         │
│  db/BookProvider       read-only ContentProvider (канал notifyChange)     │
└────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Кто за что отвечает (по классам)

| Класс | Слой | Роль |
|---|---|---|
| `MainActivity` | UI | главный экран; 3-ступенчатая загрузка; header-kebab (**Импорт / Пересканировать / Добавить-убрать пагинацию / Очистить библиотеку**); фильтр/«недавние»; SAF-импорт; подтверждение «Открыть эту книгу?» на тапе. Список — `CursorLoader`-управляемый + **детерминированный rebind** (`changeCursor`) на запись с UI-потока. |
| `DetailActivity` | UI | карточка книги: просмотр + «открыть в Neo Reader» (`ACTION_VIEW` + `markRead`); быстрый `enrichOne` для необогащённой книги (статический таск, Activity по `WeakReference`). Правка/удаление переехали в меню книги главного экрана. |
| `EditMetaActivity` | UI | форма метаданных; открывается из меню книги; запись в файл (только EPUB/FB2) + **всегда** в каталог с `userEdited=true` (статический save-таск). |
| `BookAdapter` | UI | `CursorAdapter`; два режима (список/плитки); per-book kebab (Details / Edit metadata / Remove → интерфейс `BookMenuActions`); опциональная **client-side пагинация** (`setPagination` — окно над курсором, `changeCursor` откатывает страницу до последней существующей); `getItem()` — курсор → `Book`. |
| `PagedListView` / `PagedGridView` | UI | подклассы `ListView`/`GridView`: горизонтальный свайп листа страницу (влево — дальше, вправо — назад); детект в `dispatchTouchEvent`, перехват «свайпа в полёте» в `onInterceptTouchEvent` (API 19: иначе свайп внутри строки завершился бы кликом). |
| `PageSwipeTracker` | UI | учёт жеста свайпа (package-private): свайп = при отпускании |dx| ≥ 3×touchSlop и |dx| ≥ 2×|dy|; второй палец отменяет; сам события не потребляет (observe-only). |
| `Book` | модель | `Parcelable`-модель книги; флаги `metaDone`/`metaFailed`/`userEdited`. |
| `db/BookDatabase` | хранилище | SQLite-каталог (`DB_VERSION = 5`); `upsert`/`upsertBasic`/`updateMetadata` **не трогают** `last_read`/`user_edited`/`meta_failed`; **batch-коммиты** (`upsertBasicBatch`/`updateMetadataBatch`, транзакция на группу по `BATCH_SIZE = 5`); `needMeta()` — очередь этапа 2 (`meta_failed` последними); `markMetaPending`/`markMetaFailed`/`markUserEdited`/`markRead`; `clear()` (каталог + весь кэш обложек); статический `WRITE_LOCK`, одноразовые чтения — `readableDb()` под тем же локом. |
| `db/BookProvider` | хранилище | read-only `ContentProvider` — канал `ContentObserver` для `CursorLoader` (URI: все / недавние / `format=?`). |
| `scan/Formats` | логика | канонические id 15 форматов + карта расширений (в т.ч. `.fb2.zip` как «сложное» расширение) + **tier стоимости обогащения** `enrichmentPriority()` (0 = EPUB/FB2, 1 = FB2ZIP, 2 = MOBI, 3 = остальные). **Единственный источник правды** для скана, фильтра, бейджа и порядка очереди этапа 2. |
| `scan/LibraryScanner` | логика | этап 1: рекурсивный обход накопителей (включая вторичные SD); `scanSingle()` для импорта. |
| `meta/MetaEnricher` | логика | этап 2: один фоновый воркер по `needMeta()`, **самая дешёвая книга-формат первой** (`Formats.enrichmentPriority`); каждая книга — **single-pass** парсер (EPUB/FB2/FB2ZIP: один сеанс `ZipFile`; FB2: только «шапка»; MOBI: один `MobiParser` + in-place trim) на одноразовом потоке с **бюджетом 2 минуты** (перелёт → `markMetaFailed`); результаты группы — один `updateMetadataBatch`; `notifyChange` с троттлингом 300 мс; listener держится по `WeakReference`; `enrichOne()` — для импорта и «быстрого пути». |
| `meta/MetaExtractor` | логика | чтение метаданных из EPUB/FB2/FB2ZIP (внутренний `.fb2`)/MOBI/TXT/HTML; общие статические помощники (`parseOpf`, `parseFb2Xml`, `opfPathFromContainer`) переиспользуются single-pass парсерами воркера. |
| `meta/MobiParser` | логика | общий reader MOBI/AZW (package-private): таблица записей PalmDB + заголовок MOBI + EXTH. |
| `meta/MetaWriter` | логика | запись в EPUB/FB2, **недеструктивно** (`.tmp` → swap → `.bak`). |
| `meta/CoverExtractor` | логика | байты обложки: EPUB (OPF → manifest), FB2 (`coverpage` → `<binary>`, **потоковое** чтение «шапки»), FB2ZIP (внутренний `<binary>`, иначе свободный image-entry), MOBI (EXTH 201, JPEG обрезается по EOI); `canHaveCover(format)`. |
| `meta/MetaData` | модель | простой контейнер прочитанных/записываемых метаданных. |
| `util/CoverCache` | логика | устойчивый файловый кэш **байтов** обложек: `getExternalFilesDir("covers")/<hash>.img`, атомарно (`.tmp` → rename); `save(ctx, path, bytes, off, len)` пишет диапазон буфера (MOBI in-place trim); `delete()` (при удалении книги) и `clear()` (при «Очистить библиотеку»). |
| `util/CoverLoader` | логика | асинхронная обложка: `CoverCache` → in-file экстракция; **decode «в размер»** (`inJustDecodeBounds` + `inSampleSize`, max edge 512; CIRCLE кропится сразу в слот 48dp); **in-flight дедуп** декодов (реестр по ключу `format|path|shape`); `LruCache` (CIRCLE туда не кладётся); виджеты — только по `WeakReference`. |
| `util/Openers` | логика | карта MIME + `ACTION_VIEW`-intent (`Uri.fromFile` — допустимо в API 19); `openFile(file, activity, OpenOutcome)` — «файл на месте? есть viewer?» в одной точке (`onLaunched`/`onMissingFile`/`onNoViewer`). |

---

## 4. Ключевые ограничения (invariant) проекта

Это «нельзя сломать» — правила, за нарушение которых Lint и CI
скажут «нет», и которые при этом **объясняют** многие странные на первый
взгляд решения (Holo, `AsyncTask`, `Uri.fromFile`, без AndroidX):

1. **`minSdk 19` (Android 4.4.4)** — код обязан работать на нём. Никаких
   API-вызовов выше 19. Тема — Holo (Material — только с API 21+).
   Lint-правило `NewApi = error` в `app/lint.xml` — автоматическая проверка.
2. **Ни AndroidX, ни support-библиотек, ни сторонних рантайм-зависимостей.**
   Только Android Framework + классы JDK (`java.util.zip`,
   `XmlPullParser` через `android.util.Xml`, `java.io.RandomAccessFile`).
   Сборка работает **оффлайн**.
3. **Манифест**: без атрибута `package` (в AGP 8+ пакет — в `build.gradle`),
   у каждого `<activity>` — `android:exported`.
4. **Файл книги никогда не остаётся «наполовину записанным»** — всегда
   `.tmp` → swap → `.bak` (см. [раздел 6.4](#64-недеструктивная-запись-tmp-swap-bak)).
5. **`BookDatabase.upsert` сохраняет `last_read`** — повторный скан не должен
   «сбрасывать» «недавно прочитанные».
6. **`Formats.ALL` — единый источник правды** для сканера, фильтра и бейджа
   формата в UI: все три места не должны «расхождаться».
7. **Один медленный/зависший файл не должен блокировать очередь обогащения**:
   бюджет на парсинг одного файла — 2 минуты; перелёт бросается, книга
   помечается `meta_failed` («не обогащена») и на каждом пересканировании
   берётся **последней** (см. [раздел 6.6](#66-время-бюджет-и-порядок-очереди-обогащения)).
8. **Долгая фоновая задача не должна удерживать мёртвый Activity**: скан,
   импорт, обогащение, save и «быстрый путь» — статические классы
   `AsyncTask` с app-контекстом и Activity/диалогами/виджетами по
   `WeakReference` (см. [раздел 2.4](#24-главный-поток-и-фоновые-потоки)).

> Почему «без AndroidX и без библиотек»? Потому что цель — **оффлайн-сборка на
> старом (API 19) устройстве** без риска «подтянуть» зависимость, которая
> потребует более нового API или Material-темы. Ограничение намеренное, а не
> случайное (см. [раздел 2.2](#22-версии-android-api-level-minsdk-targetsdk-compilesdk)).

---

## 5. Экраны и навигация

Приложение — «трёхэкранное». Навигация линейная, без стека и без Fragment'ов:

```
[Launcher icon] → MainActivity  (список / плитки)
                    │  tap по строке/плитке
                    ▼
          «Открыть эту книгу?» (Open / Cancel)
                    │  Open
                    ▼
        Neo Reader (ACTION_VIEW) + markRead
                    │
                    │  kebab строки/плитки → «Details»
                    ▼
     DetailActivity  (карточка: просмотр + «Открыть»)

MainActivity → kebab строки/плитки → «Edit metadata»
                    ▼
     EditMetaActivity  (форма метаданных)
```

- **MainActivity** — точка входа (в манифесте — `intent-filter`
  `MAIN`/`LAUNCHER`, тема `AppTheme.NoActionBar` — у экрана своя индиго-шапка).
  В шапке: спиннер-фильтр (формат / «Recently read»), переключатель
  «список ↔ плитки» (`@+id/toggle_view`, иконка показывает *другой* режим)
  и справа в углу **kebab** (`@+id/btn_menu`, `ic_overflow`). Поп-ап kebab'а
  (`res/menu/main_menu.xml`): **Импорт**, **Пересканировать**, пункт с
  галочкой **Добавить/убрать пагинацию** (название и галочка зеркалят текущее
  состояние) и **Очистить библиотеку** (после диалога подтверждения: весь
  каталог — все строки + кэш обложек; файлы на диске не трогаются; бэкенд —
  `BookDatabase.clear()`). В каждой строке списка и плитке — **kebab книги**
  (`book_more`): **Details** / **Edit metadata** / **Remove**
  (`res/menu/book_menu.xml`). Простой **tap** по строке/плитке больше не
  ведёт на карточку — сначала вопрос «Открыть эту книгу?»
  (Open / Cancel); карточка достижима только через kebab книги.
- **DetailActivity** — получает `id` книги в `Intent` (long), загружает её из
  БД, показывает карточку (обложка, если формат «умеет» обложки;
  заголовок/автор/издатель/размер/описание) и кнопку «Открыть»
  (`Openers.openFile` + `markRead`). Кнопки «Редактировать»/«Убрать», которые
  раньше были здесь, переехали в kebab книги на главном экране — единая точка
  входа из списка и из плиток.
- **EditMetaActivity** — получает **объект** `Book` (Parcelable, см. [2.7](#27-передача-объектов-между-экранами-parcelable))
  из kebab книги на главном экране; показывает форму; при «Сохранить» — в
  файл (EPUB/FB2) и **всегда** в каталог с флагом `userEdited=true`
  (см. [раздел 6.1](#61-двухслойная-модель-метаданных)).

После возврата из дочерних Activity родитель сам обновляет своё состояние:
записи, сделанные с UI-потока, **детерминированно** перепривязывают адаптер
(`adapter.changeCursor(currentCatalogCursor())`), а фоновые изменения
доносятся через `notifyChange` → `CursorLoader`. То есть **никто нигде не
«перечитывает» список вслепую** — это и есть cursor-driven UI
(см. [раздел 6.3](#63-cursor-driven-ui-подписка-списка-на-бд)).

---

## 6. Ключевые архитектурные идеи

Здесь — 7 приёмов, которые отличают этот проект от «простого» Android-демо.
Первые четыре — со своими диаграммами (SVG) в [`diagrams/`](diagrams/).

### 6.1 Двухслойная модель метаданных

Каждая книга имеет метаданные в **двух** местах:

1. **В файле (in-file)** — только **EPUB** (OPF-документ `content.opf`) и
   **FB2** (XML-блок `<description>`). Для них `MetaWriter` **переписывает
   файл на месте** (`.tmp → swap → .bak`), поэтому Neo Reader и любое другое
   приложение увидят изменённые название/автора/издателя **внутри файла**.
2. **В каталоге (in-catalog)** — **для всех 15 форматов** редактируемые поля
   (название, автор, издатель, описание) **всегда** сохраняются в SQLite
   (`db/BookDatabase`). Для остальных 13 форматов это — единственное
   хранилище (их бинарная структура не «перезаписывается на месте» безопасно).

```
[В файле]  EPUB.content.opf  /  FB2.<description>   (только для этих двух)
    │  MetaWriter: in-place ( .tmp → swap → .bak )
    │
    │  MetaExtractor: читает из файла
    ▼
[В каталоге]  SQLite library.db (таблица books)   (все 15 форматов)
    │  + CoverCache (обложки на диске)
    │
    │  CursorLoader / BookProvider (read-only)
    ▼
[UI]  MainActivity (CursorAdapter: список / плитки)
```

Диаграмма: [`two_layer_model.svg`](diagrams/two_layer_model.svg)
(исходник: [`two_layer_model.mmd`](diagrams/two_layer_model.mmd)).

Почему так, а не «просто в БД» или «просто в файле»? Потому что:

- пользователь хочет, чтобы **изменённое название было видно и в Neo Reader**
  (т.е. «в самом файле») — для EPUB/FB2 это возможно;
- для остальных форматов (PDF, MOBI, DOCX, …) «перезаписать» метаданные —
  сложно и рискованно, зато **хранилище в каталоге работает одинаково** для
  всех форматов;
- «недавно прочитанные» (`last_read`) — это **только** каталог, и никогда
  не файл (файл не должен зависеть от того, когда пользователь его открывал).

### 6.2 Трёхступенчатая загрузка библиотеки

Загрузка — **не** «загрузи всё, покажи». Она разбита на 3 ступени, чтобы
пользователь **как можно быстрее** увидел рабочий список:

- **Этап 1 — быстрый скан** (`LibraryScanner`, в `AsyncTask`): рекурсивный обход
  накопителя, для каждого поддерживаемого файла создаётся «скелет» `Book`
  (путь, формат, размер, название — из имени файла). В БД — `upsertBasic()`.
  После `notifyChange` список **уже на экране** и им можно пользоваться.
- **Этап 2 — фоновое «обогащение»** (`MetaEnricher`, отдельный поток): по
  очереди `db.needMeta()` (все книги с `meta_done=0`), но **дешёвые форматы
  первыми** — EPUB/FB2 → FB2ZIP → MOBI → остальные, а «не обогащённые»
  (перелетевшие бюджет) — самыми последними
  (см. [6.6](#66-время-бюджет-и-порядок-очереди-обогащения)). Каждая книга
  парсится **single-pass** парсером (метаданные + обложка за один сеанс
  файла — см. [раздел 9](#9-форматы-как-читать-и-писать-метаданные)) на
  одноразовом потоке с **бюджетом 2 минуты**. Результаты группы (каждые
  5 книг) уходят **одним батч-коммитом** `db.updateMetadataBatch()` (не
  затирает `user_edited`, не трогает `last_read`) + запись обложки в
  `CoverCache`. Каждая партия — `notifyChange` (с троттлингом 300 мс) →
  `CursorLoader` → строки обновляются **на месте**. Пока идёт этап 2, в UI
  видна «полоса прогресса» `done/total`.
- **Этап 3 — взаимодействие**: список/плитки + `CursorLoader` + адаптер.
  Пользователь может в любой момент открыть книгу, отфильтровать, импортировать.

Диаграммы:
[`data_flow.svg`](diagrams/data_flow.svg) (последовательность) и
[`book_lifecycle.svg`](diagrams/book_lifecycle.svg) (состояния строки книги:
`Found → Skeleton → Enriched → Read / UserEdited`).

Почему 3 ступени, а не «загрузи всё сразу»? Потому что:

- скан накопителя — **быстрый** (только имена файлов), а парсинг метаданных —
  **медленный** (открытие ZIP, чтение XML, бинарный MOBI). Если ждать всё
  сразу — пользователь будет смотреть на «пустой» экран.
- «быстрый путь» (fast path): если пользователь **открыл книгу раньше, чем
  этап 2 до неё добрался**, `DetailActivity` сам вызывает
  `MetaEnricher.enrichOne()` для одной книги — и карточка сразу показывает
  настоящие название/автора, а не «имя файла».

### 6.3 Cursor-driven UI: «подписка» списка на БД

Список в `MainActivity` — **не** «список, который мы перечитываем вручную».
Это **`CursorLoader` → `CursorAdapter`**, и он **подписан** на `notifyChange`.
Каждое изменение каталога (скан, обогащение, импорт, правка, «открыть») — это
один вызов `getContentResolver().notifyChange(uri, null)` (через
`BookProvider.notifyChangeAll`), после чего:

1. `ContentObserver` (внутри `CursorLoader`) срабатывает;
2. `CursorLoader` **в фоне** делает `query()` у провайдера;
3. получает свежий `Cursor` и выдаёт его на главный поток;
4. `CursorAdapter` перепривязывает **только** изменённые строки.

Итог: **ни один экран в проекте не содержит строк вида
`adapter.notifyDataSetChanged()` «вручную»** — всё «обновится само».
См. [раздел 2.10](#210-cursorloader-подписка-ui-на-изменения-бд) и
[раздел 2.11](#211-адаптеры-и-переработка-recycle-строк).

Дополнение для API 19: записи, сделанные **с UI-потока** (рескан в
`onPostExecute`, «Убрать», «Очистить библиотеку»), не полагаются на
`ContentObserver` в одиночку — после цикла cancel/restart загрузчика на
API 19 наблюдатель может «потеряться», и удалённая/новая строка будет
стоять на экране «до какого-нибудь постороннего события загрузчика».
Поэтому экран **детерминированно** перепривязывает адаптер сам:
`adapter.changeCursor(currentCatalogCursor())` — заново запрашивает
каталог и передаёт новый курсор напрямую. `notifyChange` при этом
отправляется и как обычный канал (для «Recently read» и других
слушателей).

### 6.4 Недеструктивная запись: `.tmp` → swap → `.bak`

Когда `MetaWriter` правит EPUB/FB2, он **никогда** не пишет в «живой» файл
напрямую. Алгоритм:

1. **Собрать** новый файл целиком во временный `.tmp` (рядом с оригиналом).
2. **Обменять**: переименовать оригинал в `.bak`, переименовать `.tmp` в
   оригинальное имя.
3. **Удалить** `.bak` (успех) **или восстановить** из `.bak` (ошибка по
   полпути).

Так исходный файл **никогда не остаётся «наполовину записанным»** — при
сбое/обрыве питания/исключении мы либо в состоянии «до», либо в состоянии
«после». Это стандартный приём «атомарной замены файла» —
[Atomic operation (Wikipedia, англ.)](https://en.wikipedia.org/wiki/Atomic_operation).
Тот же приём — в `util/CoverCache` (атомарная запись кэша обложки).

### 6.5 Двухуровневый кэш обложек

Обложка — это **дорогая** операция (открыть ZIP, найти entry, декодировать
JPEG). Поэтому:

- **`CoverCache` (диск)** — «устойчивый» кэш **байтов** обложки в
  `getExternalFilesDir("covers")/<hash>.img`. Заполняется **фоном** на этапе 2
  (`MetaEnricher`), переживает перезапуск приложения и **не требует**
  разрешения на доступ к памяти (это «приватная» папка приложения).
- **`CoverLoader` (память)** — асинхронная загрузка `Bitmap` для UI: сначала
  `CoverCache` (диск), затем in-file экстракция (если в кэше нет, результат
  дописывается в `CoverCache`), затем — **`LruCache`** (в памяти).
  Декодка идёт **«в размер»**: сначала bounds-only проход
  (`inJustDecodeBounds`), затем decode с `inSampleSize` — степенью двойки так,
  чтобы максимальная грань не превышала 512 px (обложка 6000×9000 px
  декодируется в 1/8, а не во весь размер). Круглый бейдж списка
  (CIRCLE) кропится сразу в размер слота 48dp. Плюс — «обрезка» `Bitmap`
  под форму (круг / скруглённый прямоугольник) и **проверка «на надет ли я на
  ту же строку»** (тег `cover_tag`), чтобы «переработанная» строка не нашла
  на себе чужую обложку (см. [2.11](#211-адаптеры-и-переработка-recycle-строк)).

  Быстрый скролл списка перепривязывает одну и ту же книгу к нескольким
  «переработанным» плиткам, пока первая декодка ещё идёт — поэтому декоды
  **дедуплицируются in-flight**: реестр по полному ключу `format|path|shape`
  держит одну общую задачу на ключ, а все остальные плитки «присоединяются»
  к ней. CIRCLE-битмапы **намеренно не кладутся** в `LruCache` (они крошечные,
  а кэширование вытеснило бы дорогие битмапы плиток сетки). Задачи держат
  виджеты только по `WeakReference` (см. [2.4](#24-главный-поток-и-фоновые-потоки)).

Ключ кэша — `format + path + shape` (так обложки не «перетекают» между
форматами и формами).

### 6.6 Время-бюджет и порядок очереди обогащения

Два независимых правила гарантируют, что **один патологический файл никогда
не остановит остальную библиотеку**:

**Правило 1 — время-бюджет.** Парсинг каждого файла (метаданные + обложка)
выполняется на **одноразовом потоке** с дедлайном **2 минуты**
(`MetaEnricher.DEFAULT_EXTRACT_TIMEOUT_MS`). Воркер ждёт не дольше того
(`CountDownLatch`); если парсинг ещё не закончился — он **бросается**: книга
помечается `meta_failed = 1` (`db.markMetaFailed`), то есть получает статус
**«не обогащена»**; `meta_done` при этом остаётся 0, поэтому книга
**остаётся в очереди** и повторяется на каждом пересканировании.
Покинутый поток безобиден: он read-only, его результат отбрасывается, а сам он
закончится сам, когда завершится файловый I/O (Java не умеет убивать потоки —
его просто «отпускают»). Парсинг, который **бросает исключение** (битый файл,
I/O-ошибка), следует правилу проекта «не зацикливаться на неудачных
извлечениях»: заголовок из имени файла сохраняется, книга помечается
обогащённой.

**Правило 2 — порядок очереди.** Сразу после скана очередь сортируется по
**стоимости обогащения** (`Formats.enrichmentPriority`): сначала самые
дешёвые форматы — EPUB и FB2 (простой XML), затем FB2ZIP (FB2 внутри
архива), затем тяжёлый бинарный разбор MOBI, затем всё остальное; книги-
**«не обогащенные»** из правила 1 держат **абсолютно последнее место**
(`needMeta()` сортирует `meta_failed ASC, _id ASC`). Пользователь видит
«дешёвые победы» (названия, обложки) как можно раньше, а медленный файл
просто доберётся последним — и никогда ничего не блокирует.

Вместе два правила означают: очередь всегда работает, библиотека всегда
на экране, даже если в ней лежит «зависший» 50-мегабайтный MOBI.

### 6.7 Пагинация и листание страницы свайпом

Пункт kebab «Добавить/убрать пагинацию» переключает каталог между одним
длинным скроллом и фиксированными страницами. Реализация — **client-side
окно над всем курсором**:

- адаптер (`BookAdapter.setPagination`) **держит весь курсор** (поэтому все
  изменения каталога — скан, обогащение, импорт, удаление, фильтр, очистка —
  перепривязываются на месте, как и без пагинации), но **выдаёт одну
  страницу**: `getCount()` отчитывается строками страницы, каждая позиция
  мапится на строку курсора со смещением на начало страницы;
- размер страницы: **6 плиток** в плиточном режиме либо **столько строк
  списка, сколько влезает на экран** в списочном (измеряется, а не
  угадывается: высота книжной зоны ÷ высота одной измеренной строки).
  Высота зоны меняется под пальцами пользователя (первая раскладка,
  появление/исчезновение полосы обогащения и самой полосы страниц), поэтому
  `bookContainer` несёт `OnLayoutChangeListener`: при новой высоте
  размер списка-страницы **переизмеряется** (posted, чтобы читать
  устоявшуюся высоту и не уведомлять адаптер посреди layout-прохода);
- выбор on/off **персистится** в `SharedPreferences` (ключ
  `pagination_enabled`, как `view_mode`) и применяется **после** режима
  отображения, потому что размер страницы от него зависит;
- полоса-пейджер (Prev / «X / Y» / Next) видна, когда пагинация включена
  **и** каталог не пуст; при смене курсора (`changeCursor`) страница
  откатывается до последней существующей **до** самой замены — так
  `DataSetObserver` полосы видит уже ограниченный (clamped) номер;
- страницу можно перевернуть и **горизонтальным свайпом** по списку/сетке
  (влево — дальше, вправо — назад). Виджеты каталога — подклассы
  `PagedListView`/`PagedGridView` (от `ListView`/`GridView`): жест
  детектится в **`dispatchTouchEvent`** — `OnTouchListener` на списке не
  сработал бы: `ViewGroup` отдаёт жест строке/плитке под пальцем, а
  кликабельная строка его «съедает», и свой listener списка видел бы только
  касания по «голой» подкладке. Собранное идёт в `PageSwipeTracker`:
  свайп — это при отпускании |dx| ≥ 3×touchSlop **и** |dx| ≥ 2×|dy|
  (вертикальный скролл и диагональ страницу не листают); второй палец
  отменяет жест. Листание **откладывается** (post) — выполняется после полного
  «расмотания» touch-последовательности, тем же путём `showPage()`, что и
  кнопки. В момент, когда жест становится свайпом, виджет ещё и
  **перехватывает** его (`onInterceptTouchEvent`): на API 19 строка держит
  pre-pressed состояние, пока палец не выйдет за её *границы*, и список сам
  перехватывает только *вертикальное* движение — без перехвата свайп,
  оставшийся внутри строки, закончился бы кликом («Открыть эту книгу?»).
  Фреймворк шлёт строке `ACTION_CANCEL` (клик не случается), оставшиеся
  события потребляются в `onTouchEvent`. Тапы и мелкие «покачивания» ниже
  порога перехвачены не бывают.

---

## 7. Данные: модель и БД

### 7.1 Таблица `books` и её флаги

Каталог — **одна таблица** `books` в `library.db` (схема создаётся в
`BookDatabase.onCreate`, версия БД — `DB_VERSION = 5`, миграции — через
`ALTER TABLE ADD COLUMN` и idempotency-guard'ованные `CREATE INDEX` —
[ALTER TABLE (SQLite)](https://www.sqlite.org/lang_altertable.html):
v2 — `meta_done`/`user_edited`, v4 — `meta_failed`, v5 — индекс по title).

```
books
├── _id          INTEGER PRIMARY KEY AUTOINCREMENT
├── path         TEXT UNIQUE NOT NULL   -- логический ключ книги (путь к файлу)
├── format       TEXT                   -- канонический id из Formats.ALL
├── title        TEXT                   -- заголовок (сначала — из имени файла)
├── author       TEXT
├── publisher    TEXT
├── description  TEXT
├── series       TEXT
├── size_bytes   INTEGER
├── exported     INTEGER (0/1)          -- файл был перезаписан (EPUB/FB2)
├── meta_done    INTEGER (0/1)          -- этап 2 извлёк метаданные
├── meta_failed  INTEGER (0/1)          -- последний парсинг перелетел бюджет
│                                        -- 2 минут («не обогащена»: остаётся
│                                        -- в очереди, берётся последней)
├── user_edited  INTEGER (0/1)          -- пользователь правил поля
└── last_read    INTEGER (epoch ms)     -- NULL = «не читали»
```

Индексы (для «горячих» запросов) —
[`CREATE INDEX (SQLite)](https://www.sqlite.org/lang_createindex.html)`:

- `idx_books_meta_done` — очередь этапа 2 (`WHERE meta_done = 0`);
- `idx_books_last_read` — «недавно прочитанные»
  (`WHERE last_read IS NOT NULL ORDER BY last_read DESC`);
- `idx_books_title_nocase` — список каталога
  (`ORDER BY title COLLATE NOCASE` — порядковый по умолчанию на главном
  экране; добавлен в v5).

Все индексы не-уникальные (многие книги разделяют значения) и создаются
идемпотентно, поэтому повторный прогон «половинчатой» миграции безопасен.

**Четыре «защищённых» столбца** — `meta_done`, `meta_failed`, `user_edited`,
`last_read` — **никогда** не записываются в `upsert()` / `upsertBasic()`.
Их меняют только специальные методы: `markMetaPending` (сбрасывает
`meta_done` и `meta_failed`, когда файл перезаписан импортом под тем же
именем), `markMetaFailed` (парсинг перелетел бюджет), `markUserEdited`,
`markRead`; а `updateMetadata()` при успешном парсинге сам ставит
`meta_done = 1` и снимает `meta_failed`. Это гарантирует, что:

- повторный скан **не сбрасывает** «обогащено» / «не обогащена» /
  «недавно прочитанные»;
- фоновый воркер **не затирает** правки пользователя (см. [6.1](#61-двухслойная-модель-метаданных));
- `user_edited` — **монотонный** флаг: 1 → 0 никогда;
- «не обогащенная» книга не теряет статус при пересканировании, а
  `markMetaPending` снимает его только когда файл реально новый.

**Батч-коммиты (v5).** Два «массовых» пути записи — скан
(`upsertBasicBatch`) и этап 2 (`updateMetadataBatch`) — не идут
autocommit-ом по строке: строки применяются группами по `BATCH_SIZE = 5`
в одной SQLite-транзакции (один коммит на группу, а не на книгу). Группа
атомарна: упавшая строка откатывает всю группу (ничего не сохраняется,
причина логируется), неприменённые строки просто повторятся на следующем
скане — upsert'ы идемпотентны. Сам парсинг происходит **вне** базы (на
одноразовом потоке воркера под его бюджетом времени) — батч коммитит
только готовые результаты.

Все обращения к базе сериализуются **статическим** `WRITE_LOCK`
(см. [раздел 12](#12-почему-всё-устроено-именно-так-краткий-разбор-решений)),
а одноразовые чтения (`readableDb()`) выполняются **под тем же локом**:
свежая SQLite-соединение при открытии делает `PRAGMA user_version`, и этот
read не должен попасть в середину батч-записи другого соединения —
межсоединение такое столкновение не «ожидание», а твёрдый SQLITE_BUSY
(«database is locked»). Живые курсоры из `cursorAll`/`cursorRecent` —
исключение: они продолжают пользоваться своим handle и после
освобождения лока.

Диаграмма ER: [`db_er.svg`](diagrams/db_er.svg)
(исходник: [`db_er.mmd`](diagrams/db_er.mmd)).

### 7.2 Модель `Book`

`model/Book` — «плоский» `Parcelable`-класс с тем же набором полей + флаги
`metaDone` / `metaFailed` / `userEdited`. Методы-«помощники»: `initial()`
(первая буква для «буквенного» бейджа), `displayFormat()`. Пересылка между
Activity — через `Intent.putExtra` / `getParcelableExtra` (см. [2.7](#27-передача-объектов-между-экранами-parcelable)).

### 7.3 `MetaData` (внутренний)

`meta/MetaData` — POJO-контейнер «то, что прочитано из файла / что надо
записать в файл»: `title`, `author`, `publisher`, `description`, `series`,
`language`, `genre`, `documentId`, `firstName/middleName/lastName` (для FB2) и
флаг `found` («удалось ли прочитать что-то осмысленное»).

---

## 8. Сценарии работы приложения (data flow)

Краткое «как работает» по главным пользовательским сценариям. Полная
последовательность — на [`data_flow.svg`](diagrams/data_flow.svg).

### 8.1 Скан (этап 1)

```
cold start (savedInstanceState == null) → MainActivity.startScan()
(ре-создание после поворота — только startEnrichment(), скан не повторяют)
  → статический ScanTask (app-контекст в фоне, Activity — WeakReference):
      → LibraryScanner.scan(appContext)
          → обход /storage/emulated/0 + вторичных SD
          → для каждого поддерживаемого файла: скелет Book
  → onPostExecute:
      → db.upsertBasicBatch(found)  (группы по 5 в одной транзакции)
      → если экран жив: adapter.changeCursor(currentCatalogCursor())
        — детерминированный rebind (не только ContentObserver)
      → notifyChange(CONTENT_URI)  — обычный канал
      → Toast «Found N book(s)»
      → startEnrichment()  (если есть книги с meta_done=0)
```

### 8.2 Обогащение (этап 2)

```
MetaEnricher.start(app, db, listener)   — listener держат слабо (WeakReference)
  → статический воркер (AsyncTask, один поток):
      → pending = db.needMeta()  (WHERE meta_done=0; meta_failed — последние)
      → orderQueueByCost(pending)  (EPUB/FB2 → FB2ZIP → MOBI → остальные)
      → цикл группами по 5:
            для каждой книги:
              → парсинг на одноразовом потоке (single-pass: метаданные +
                обложка за один сеанс файла) с бюджетом 2 минуты:
                  overrun   → db.markMetaFailed(id) — «не обогащена»,
                              остаётся в очереди, повтор последним
                  исключение → заголовок из имени файла, книга — done
              → обложка найдена → CoverCache.save(path, bytes[, off, len])
            → db.updateMetadataBatch(группа)  (одна транзакция на группу)
            → notifyChange (троттлинг: не чаще раза в 300 мс)
      → при НОРМАЛЬНОМ завершении: финальный notifyChange (без троттлинга)
        + listener.onFinished()
      → после cancel (onDestroy / «Очистить библиотеку»): ни notify, ни
        onFinished — экран, начавший проход, уже мёртв
```

### 8.3 Открытие книги

```
tap по строке/плитке → «Открыть эту книгу?» (Open / Cancel)
  → Open: Openers.openFile(file, activity, outcome)
      → файла нет на диске?  → Toast «File not found»
      → нет ни одного viewer? → диалог «No viewer installed»
      → иначе: Intent(ACTION_VIEW, mime, file://uri) (Android сам выбирает
        Neo Reader; FLAG_GRANT_READ_URI_PERMISSION)
  → db.markRead(id)  → last_read = now
  → notifyChangeAll  → «недавно прочитанные» (и список) обновляются

та же логика — и на DetailActivity (kebab книги → «Details» → «Открыть»):
  → если !book.metaDone: статический EnrichTask → MetaEnricher.enrichOne()
    в фоне (fast path), затем карточка обновляет себя
```

### 8.4 Редактирование метаданных

```
kebab книги → «Edit metadata» → EditMetaActivity(Book, Parcelable)
  → форма: title / author / publisher / description
  → «Сохранить» (статический SaveTask в фоне):
      → если EPUB/FB2 и файл существует:  MetaWriter.write(file, md)  (in-file)
      → ВСЕГДА:  db.upsert(book) + db.markUserEdited(id)   (in-catalog)
      → notifyChangeAll  → список обновится
  → setResult(RESULT_OK); finish()
```

### 8.5 Импорт (SAF)

```
kebab → «Импорт» → ACTION_OPEN_DOCUMENT (системный файловый менеджер)
  → onActivityResult:
      → validate: Formats.isSupported(displayName)
  → статический ImportTask (в фоне):
      → копирование в getExternalFilesDir("books")
      → LibraryScanner.scanSingle(dest)
      → db.upsertBasic(b)
      → если файл уже был в каталоге: markMetaPending + CoverCache.delete
        (старые метаданные и обложка новому файлу не принадлежат)
      → MetaEnricher.enrichOne(appContext, db, b)  (в том же фоновом потоке)
      → notifyChange(CONTENT_URI)
  → onPostExecute: если экран жив — adapter.changeCursor(currentCatalogCursor())
    (детерминированный rebind) + Toast
```

### 8.6 «Очистить библиотеку»

```
kebab → «Очистить библиотеку» → диалог подтверждения
  (явно сказано: файлы на диске НЕ трогаются)
  → MetaEnricher.cancel()      (очередь и так исчезает)
  → db.clear()                 (все строки + CoverCache.clear() — весь кэш)
  → adapter.changeCursor(currentCatalogCursor())  (детерминированный rebind)
  → notifyChangeAll + Toast
```

### 8.7 Пагинация: вкл/выкл и листание

```
kebab → «Добавить пагинацию» (пункт с галочкой: название/галочка зеркалят состояние)
  → setPaginationEnabled(true):
      → SharedPreferences (pagination_enabled) — переживёт перезапуск
      → adapter.setPagination(true, pageSizeForCurrentMode(), 0)
          (плитки: 6; список: столько строк, сколько влезает на экран)
      → видимый виджет скроллится к первой строке, показывается пейджер
  → листание: кнопки Prev/Next ЛИБО горизонтальный свайп
    (PagedListView/PagedGridView → PageSwipeTracker → showPage())
  → любое изменение каталога (удаление/фильтр/очистка) может ужать курсор —
    changeCursor() откатывает страницу до последней существующей ДО замены
    (с пустым каталогом пейджер прячется)
```

---

## 9. Форматы: как читать и писать метаданные

Ниже — «внутренности» каждого формата, которые проект понимает. Все
«внутренности» — на базе **только** `java.util.zip`, `XmlPullParser` (через
`android.util.Xml`) и `RandomAccessFile`; никаких сторонних библиотек.

Важно про этапы: **публичные** экстракторы (`MetaExtractor.extract` /
`CoverExtractor.extract`) — это «по одному на каждое» (метаданные или обложка)
и они остаются fallback'ом для `CoverLoader` (обложка по требованию). А **этап 2**
использует отдельные **single-pass** парсеры в `MetaEnricher` (package-private):
каждый формат парсится **одним сеансом файла** — метаданные и обложка вместе
(см. пометки «В этапе 2» ниже).

### 9.1 EPUB

**EPUB** — по сути **ZIP-архив** (OEBPS) с XML-манифестом. О формате —
[EPUB (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/EPUB),
[официальная спецификация (IDPF)](https://idpf.org/epub/).

Структура:
- `META-INF/container.xml` — указывает путь к **OPF-документу**
  (`content.opf`);
- `content.opf` — XML с блоком `<metadata>` (Dublin Core: `<dc:title>`,
  `<dc:creator>`, `<dc:publisher>`, `<dc:description>`, `<dc:language>`),
  `<manifest>` (список всех файлов с `id` → `href`) и `<spine>`.

**Чтение метаданных** (`MetaExtractor.extractEpub`):
1. открыть ZIP, найти `META-INF/container.xml`, извлечь `full-path` к OPF;
2. открыть OPF, парсить `XmlPullParser` (см. [2.9](#29-contentprovider-contentresolver-contentobserver)),
   собирать `<dc:*>` (первый `<dc:creator>` — автор);
3. флаг `found` — если нашли `<dc:title>`.

**Чтение обложки** (`CoverExtractor.extractEpub`):
1. в OPF ищем `<meta name="cover" content="id"/>` (EPUB2) или
   `<meta property="cover-image" id="..."/>` (EPUB3);
2. по `id` находим `href` в `<manifest>`;
3. разрешаем относительный путь (учитывая каталог OPF) и читаем entry из ZIP.

**Запись** (`MetaWriter.writeEpub`):
1. прочитать OPF;
2. заменить содержимое `<dc:title>` / `<dc:creator>` / … (или вставить перед
   `</metadata>`, если элемента нет);
3. **пересобрать** ZIP во временный файл, заменив только OPF;
4. `.tmp → swap → .bak` (см. [6.4](#64-недеструктивная-запись-tmp-swap-bak)).

**В этапе 2** (`MetaEnricher.parseEpubSinglePass`): один сеанс `ZipFile`
и три чтения entry (container.xml, OPF, изображение обложки) вместо пяти
последовательных проходов по архиву. Неправильный архив деградирует до
«not found» (тот же контракт, что у публичных экстракторов).

### 9.2 FB2 (FictionBook)

**FB2** — **один** UTF-8 XML-файл (не архив). Спецификация — по-русски:
[Shishkin: FictionBook](https://shishkin.org/fictionbook/) (автор формата) и
[fb2_3.1 (gribun.ru)](https://www.gribun.ru/ebook/doc/fb2_3.1.html).

Ключевые блоки:
- `<description><title-info>` — `<title>`, `<author><first-name>/<middle-name>/
  <last-name>/<nickname>`;
- `<publisher>`, `<lang>`, `<genre>`;
- `<annotation>` — описание;
- `<coverpage><image l:href="#id"/></coverpage>` + `<binary id="id">base64</binary>`
  — обложка (Base64-закодированные байты).

**Чтение** (`MetaExtractor.parseFb2Xml`): `XmlPullParser`, собирать поля,
**объединять** `first/middle/last` в один `author`.
**Чтение обложки** (`CoverExtractor.coverFromFb2Xml`): regex на `href="#id"`,
найти `<binary id="...">`, декодировать Base64
([Base64 (Android)](https://developer.android.com/reference/android/util/Base64)).

**Запись** (`MetaWriter.writeFb2`): прочитать весь XML, заменить содержимое
соответствующих тегов regex'ом, записать (`.tmp → swap → .bak`). Для автора —
эвристика: последнее слово → `last-name`, остальное → `first-name`.

**В этапе 2** (`MetaEnricher.parseFb2SinglePass`): **потоковое чтение
«шапки»** (`CoverExtractor.readFb2Header`) — файл читается только до
`</description>` и до блока `<body>`: body (основная масса реальной книги)
вообще не читается и не парсится. Из этого одного прохода берутся и
метаданные (описание), и обложка (бинарные блоки); Base64 декодируется по
байтам, без промежуточного `String`.

### 9.3 FB2.ZIP

**FB2.ZIP** — «сложное» расширение: **ZIP**, внутри которого лежит `.fb2`
(и, возможно, отдельный `cover.jpg` рядом). В `Formats` обрабатывается **до**
проверки простого `.zip` (иголый `.zip` — **не** поддерживается).

**Чтение метаданных** — достать первый `.fb2` entry из ZIP и парсить как FB2.
**Чтение обложки** — сначала как FB2 (внутренний `<binary>`), затем — «свободный»
изображение в архиве (предпочтение — тому, в имени которого есть `cover`).

**В этапе 2** (`MetaEnricher.parseFb2ZipSinglePass`): один сеанс `ZipFile` —
центральный каталог просматривается в поисках внутреннего `.fb2` (ничего
другого не распаковывается); метаданные + обложка — из байтов внутреннего
документа; если во внутреннем документе нет обложки — читается первый
image-entry архива (имя с `cover` выигрывает).

### 9.4 MOBI / AZW (PalmDB)

**MOBI/AZW** — **бинарный** формат поверх контейнера **PalmDB**.
Документация (reverse-engineered, англ.):
[MOBI (MobileReader wiki)](https://wiki.mobilereader.com/wiki/MOBI) и
[PalmDB (MobileReader wiki)](https://wiki.mobilereader.com/wiki/PalmDB).
О **порядке байтов** (big-endian, который использует MOBI) —
[Эндианность (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Эндианность).

Ключевые факты (см. комментарии в `MobiParser`):
- байт 60–68 — идентификатор `BOOK` + `MOBI` (или `TEXTREAD`);
- байт 76 — количество записей (2 байта, BE);
- байт 78+ — **таблица записей**: 8 байт на запись (4 — смещение, 1 — флаг, 3 — значение);
- **запись 0** — заголовок: после 16-байтового PalmDOC-заголовка идёт MOBI-заголовок
  (с литералом `MOBI` на +0x10);
- в MOBI-заголовке: +0x54/+0x58 — смещение/длина **названия книги** (это и есть
  `title`); +0x6C — индекс первой image-записи; +0x80 — флаг «есть EXTH»;
- **EXTH-блок** (опционально) — структурированные метаданные: record 100 = автор,
  101 = издатель, 103 = описание, 524 = язык, **201 = смещение обложки**
  (добавляется к индексу первой image-записи → номер записи с обложкой);
- обложка — **JPEG** (или PNG) внутри одной PalmDB-записи; запись может иметь
  «хвост» после EOI — поэтому JPEG обрезается по маркеру **EOI** (`FF D9`).

Весь reader — в `meta/MobiParser.java` (package-private, общий для
`MetaExtractor` и `CoverExtractor`), на базе
[`RandomAccessFile`](https://docs.oracle.com/javase/8/docs/api/java/io/RandomAccessFile.html)
(прямой доступ к произвольному смещению в файле).

**В этапе 2** (`MetaEnricher.parseMobiSinglePass`): один `MobiParser`
(open → заголовки дают метаданные → запись обложки) и **in-place trim**
обложки: передаётся собственный массив записи с длиной изображения внутри
него (JPEG обрезается по EOI) — **ничего не копируется**. В `CoverCache`
эти байты пишет перегрузка `save(ctx, path, bytes, off, len)`, которая
записывает ровно диапазон `[off, off+len)` — «хвост» записи в кэш не
попадает.

### 9.5 TXT / HTML

- **TXT** — «метаданные» = имя файла без расширения (underscores → пробелы).
- **HTML** — regex на `<title>...</title>` (first match), иначе — как TXT.

### 9.6 Остальные форматы (CHM, DjVu, PDF, DOC, DOCX, FB3, PDB, PRC, RTF)

Для них **нет** встроенного парсера метаданных (их структуры сложнее и
нуждятся в отдельных библиотеках). Название — **из имени файла** (этап 1),
метаданные редактируются **только в каталоге** (SQLite). Обложки —
«буквенный» бейдж (первая буква названия).

> В `README.md` (раздел «Notes / possible extensions») — указание, что при
> желании глубокого парсинга CHM/DjVu/MOBI можно добавить
> [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)
> (выбрать версию, поддерживающую `minSdk 19`), и подключить его в
> `MetaExtractor`. Это **единственный** «разрыв» инварианта «без сторонних
> зависимостей», и он осознанный, опциональный, не включён по умолчанию.

---

## 10. Структура исходного кода (по каталогам)

```
app/src/main/java/com/example/mylibrary/
├── MainActivity.java        # экран 1: список/плитки, header-kebab, пагинация,
│                            #   импорт, 3 ступени, детерминированный rebind
├── DetailActivity.java      # экран 2: карточка книги (просмотр + «Открыть»;
│                            #   fast-path enrichOne)
├── EditMetaActivity.java    # экран 3: форма метаданных (из меню книги)
├── BookAdapter.java         # CursorAdapter: список + плитки, меню книги,
│                            #   client-side пагинация, recycle
├── PagedListView.java       # ListView + горизонтальный свайп страницы
├── PagedGridView.java       # GridView + горизонтальный свайп страницы
├── PageSwipeTracker.java    # детект свайпа (пороги, второй палец, in-flight)
├── model/
│   └── Book.java            # Parcelable-модель книги (metaDone/metaFailed/
│                            #   userEdited)
├── db/
│   ├── BookDatabase.java    # SQLite v5: схема, batch-upsert, флаги,
│   │                        #   needMeta(), clear(), статический WRITE_LOCK
│   └── BookProvider.java    # read-only ContentProvider (канал notifyChange)
├── scan/
│   ├── Formats.java         # 15 форматов, карта расширений, isSupported(),
│   │                        #   enrichmentPriority()
│   └── LibraryScanner.java  # рекурсивный скан накопителей (этап 1)
├── meta/
│   ├── MetaData.java        # POJO: прочитанные/записываемые метаданные
│   ├── MetaEnricher.java    # этап 2: single-pass парсеры, бюджет 2 мин/файл,
│   │                        #   cheapest-first, batch-коммиты, enrichOne
│   ├── MetaExtractor.java   # чтение метаданных (EPUB/FB2/FB2ZIP/MOBI/TXT/HTML)
│   ├── MobiParser.java      # бинарный reader MOBI/AZW (PalmDB + EXTH)
│   ├── MetaWriter.java      # запись в EPUB/FB2 (.tmp → swap → .bak)
│   └── CoverExtractor.java  # байты обложки (EPUB/FB2/FB2ZIP/MOBI),
│                            #   FB2-«шапка», canHaveCover()
└── util/
    ├── CoverCache.java      # файловый кэш обложек (disk; save с (off, len))
    ├── CoverLoader.java     # асинхронная обложка: decode «в размер»,
    │                        #   in-flight дедуп, LruCache, «обрезка»
    └── Openers.java         # MIME + ACTION_VIEW (OpenOutcome-колбэки)
```

Ресурсы:

```
app/src/main/res/
├── layout/
│   ├── activity_main.xml     # главный экран: индиго-шапка (спиннер-фильтр +
│   │                          # toggle + kebab), полоса обогащения,
│   │                          # PagedListView/PagedGridView, полоса страниц
│   ├── activity_detail.xml   # карточка книги
│   ├── activity_edit_meta.xml# форма
│   ├── item_book.xml         # строка списка (слот 48dp: круглая обложка/бейдж,
│   │                          # метка формата, kebab книги)
│   ├── item_book_grid.xml    # плитка (ширина 242 px, рамка обложки 242×328 px
│   │                          # со скруглением 8 px, заголовок 2 строки,
│   │                          # красный круг 155 px для «буквенного» бейджа)
│   └── spinner_item.xml      # элемент спиннера (белый текст на шапке)
├── menu/
│   ├── main_menu.xml         # header-kebab: Импорт / Пересканировать /
│   │                         # Добавить-убрать пагинацию (checkable) /
│   │                         # Очистить библиотеку
│   └── book_menu.xml         # kebab книги: Details / Edit metadata / Remove
├── values/
│   ├── strings.xml           # строки
│   ├── colors.xml
│   ├── styles.xml            # AppTheme (Holo) + AppTheme.NoActionBar (main)
│   └── ids.xml               # R.id.cover_tag, R.id.view_mode
├── values-v21/               # Material-цвета — только для API 21+
├── drawable/                 # иконки (Holo, PNG) + фоновые drawable (круги,
│                             # рамки, kebab, chevron'ы страниц)
└── mipmap/                   # иконка запуска
```

Тесты:

```
app/src/test/java/com/example/mylibrary/
├── MainActivityTest.java, DetailActivityTest.java, EditMetaActivityTest.java
├── BookAdapterTest.java
├── PageSwipeTrackerTest.java
├── db/BookDatabaseTest.java, db/BookProviderTest.java
├── scan/FormatsTest.java, scan/LibraryScannerTest.java
├── meta/MetaExtractorTest.java, MetaWriterTest.java, MetaEnricherTest.java,
│      MetaEnricherScaleTest.java (метаданные из 400 книг всех форматов),
│      CoverExtractorTest.java
├── util/OpenersTest.java, OpenersIntentTest.java, CoverCacheTest.java,
│      CoverLoaderTest.java
├── model/BookTest.java
└── testutil/TestFixtures.java
```

---

## 11. Тестирование, CI и git-хуки

### 11.1 Unit-тесты

Все тесты — **unit** (в `app/src/test/`), без эмулятора:

- **JUnit 4** ([junit.org](https://junit.org/junit4/)) — «каркас» тестов;
- **Robolectric** ([robolectric.org](https://robolectric.org/)) — позволяет
  тестировать Android-код (SQLite, `Intent`, `Parcelable`, `XmlPullParser`)
  **на JVM**. В `app/build.gradle` — `includeAndroidResources = true` и
  `returnDefaultValues = true`.
- Запуск: `./gradlew testDebugUnitTest` (в CI — на `ubuntu-latest`, JDK 17,
  SDK Platform 34).

### 11.2 Android Lint

[Android Lint](https://developer.android.com/studio/write/lint) — статический
анализатор, входящий в AGP (без сторонних зависимостей, сборка остаётся
оффлайн). Конфиг — `app/lint.xml`:

- **`NewApi = error`** — «страховка» инварианта «не выше API 19» (см. [4](#4-ключевые-ограничения-invariant-проекта));
- `ObsoleteApi`, `ObsoleteSdkInt`, `MissingPermission` — тоже `error`;
- `GoogleAppIndexing`, `IconDensities`, `IconMissingDensityFolder`,
  `IconLocation` — `ignore` (не относятся к этому стеку);
- `SetTextI18n`, `HardcodedText` — `warning` (UI-строки частично захардкожены);
- `PxUsage` — `ignore` в `item_book_grid.xml` / `bg_format_badge.xml` /
  `activity_main.xml` (плитка **сознательно** задана в px под ONYX Boox Volta 3).

Запуск: `./gradlew :app:lint` / `:app:lintDebug`.

### 11.3 CI (GitHub Actions)

Файл: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). Три **job'а**:

1. **Unit tests (JUnit + Robolectric)** — `./gradlew testDebugUnitTest`
   (обязательный status check на `master` — см. [11.5](#115-branch-protection-на-github));
2. **Android Lint** — `./gradlew :app:lintDebug` (параллельно с тестами);
3. **Assemble debug APK** — `./gradlew assembleDebug` (только если тесты
   прошли; APK — артефакт).

Триггеры: `pull_request` → `master` и `push` → `master`. Конкурентность:
одна CI на ветку, новый push «перекрывает» старый.
О GitHub Actions — [docs.github.com/en/actions](https://docs.github.com/en/actions)
(на сайте есть выбор языка, включая русский:
[docs.github.com/ru/actions](https://docs.github.com/ru/actions)).

### 11.4 Git-хуки (локальные)

Скрипты лежат в репозитории в `git-hooks/` (обновляются с кодом). Включаются
одной командой: `git config core.hooksPath git-hooks`.

- **`pre-commit`** — перед **каждым** `git commit` запускает тот же Lint, что и
  в CI (`./gradlew :app:lintDebug`); если Lint не прошёл — коммит отменяется.
  Использует **Gradle daemon** (тёплый daemon → быстрый запуск). Аварийный
  bypass: `SKIP_LINT=1 git commit ...`.
- **`pre-push`** — перед **каждым** `git push`:
  1. **отказывает** в прямом push в `master` (только feature-ветки + PR);
     bypass: `ALLOW_PUSH_MASTER=1 git push ...` (тесты всё равно запустятся);
  2. **запускает unit-тесты** (`./gradlew testDebugUnitTest`); если тест не
     прошёл — push отменяется.

Оба скрипта — **POSIX sh** (`.gitattributes` держит их с LF на всех
платформах). Если `java` не в `PATH`, скрипт сам ищет JDK 17 (включая `jbr`,
который идёт с Android Studio).

### 11.5 Branch protection на GitHub

CI **сам по себе** merge не блокирует — блокирует **branch protection rule**
на `master` (настраивается в GitHub → Settings → Branches):

1. **Require a pull request before merging**;
2. **Require status checks to pass before merging** → выбрать
   `Unit tests (JUnit + Robolectric)`;
3. (опционально) **Include administrators**.

После этого PR с «красным» тестом — merge-кнопка остаётся disabled.
О branch protection —
[About branch protection rules (GitHub docs, англ.)](https://docs.github.com/en/repositories/managing-your-repositories-settings-and-features/managing-branch-protection-rules/about-branch-protection-rules)
(есть и русский: [docs.github.com/ru](https://docs.github.com/ru)).

> Пошаговая инструкция — в
> [`README.md` → CI and branch protection (GitHub)](../README.md#ci-and-branch-protection-github).

---

## 12. Почему всё устроено именно так (краткий «разбор решений»)

| Решение | Почему именно так |
|---|---|
| Holo, а не Material | Material-темы — только с API 21+, цель — API 19 (ONYX Boox Volta 3 на Android 4.4.4). |
| Без AndroidX / без сторонних зависимостей | Оффлайн-сборка, гарантия «не подтянется» зависимость, требующая более нового API. |
| `AsyncTask` (deprecated в API 30) | Сознательный выбор: есть в API 19, не требует AndroidX/Coroutine'ов. |
| Cursor-driven UI (`CursorLoader` + `BookProvider`) | «Push»-обновления списка без ручных reload; `BookProvider` — read-only, только **канал** `notifyChange`. |
| Read-only `BookProvider` (не «полноценный» CRUD) | Все записи — через `BookDatabase` (там и write-лок, и защита `last_read`/`user_edited`). Провайдер здесь — **только** «адрес для подписки». |
| `upsert` не трогает `last_read` / `meta_done` / `user_edited` | Повторный скан не должен «сбрасывать» «недавно прочитанные» и не должен «затирать» правки пользователя. |
| `.tmp → swap → .bak` при записи в файл | Файл книги никогда не остаётся «наполовину записанным» (атомарная замена). |
| `Formats.ALL` — единственный источник правды | Сканер, фильтр и бейдж формата в UI не должны «расходиться». |
| Двухуровневый кэш обложек (диск + память) | Обложка — дорогая операция; кэш переживает перезапуски и не требует разрешений. |
| Плитка в **px** (не dp) | Целевое устройство — конкретное (ONYX Boox Volta 3, 1024×758); dp на нём не дают «ровных» 242 px. |
| Статический `WRITE_LOCK` (не `this`) | Разные Activity создают разные `BookDatabase`; общий статический лок — гарантия, что два write не «переплетутся». |
| `volatile` на `MetaEnricher.worker` | Одиночный воркер, ссылка на который видна из разных потоков; `volatile` — «атомарный» доступ к ссылке. |
| `markUserEdited` — монотонный (1 → 0 никогда) | Фоновый воркер не должен «отменять» правки пользователя. |
| Бюджет 2 минуты на файл + `meta_failed` | Один медленный/зависший файл не должен останавливать очередь: перелёт бросается, книга остаётся в очереди и берётся последней на каждом рескане ([6.6](#66-время-бюджет-и-порядок-очереди-обогащения)). |
| Cheapest-first (`Formats.enrichmentPriority`) | «Дешёвые победы» (названия/обложки EPUB/FB2) видны пользователю как можно раньше; MOBI-разбор не задерживает всё остальное. |
| Single-pass парсеры в enricher'е | Один сеанс файла вместо 2–5: EPUB — один `ZipFile` (3 чтения entry), FB2 — только «шапка» до `<body>`, MOBI — один `MobiParser` + in-place trim (без копирования записи). |
| Batch-коммиты (`BATCH_SIZE = 5`) + `readableDb()` под локом | Один коммит на группу вместо autocommit на строку; атомарный откат группы при сбое; свежая SQLite-соединение (`PRAGMA user_version` при открытии) не попадает в середину батч-записи чужого соединения (межсоединение SQLITE_BUSY — твёрдая ошибка, а не ожидание). |
| Детерминированный rebind на запись с UI-потока (`changeCursor` + `notifyChange`) | На API 19 `ContentObserver` может «потеряться» после цикла cancel/restart загрузчика; без прямого rebind удалённая/новая строка стоит «до постороннего события загрузчика». |
| Статические `AsyncTask` + `WeakReference` на Activity/диалог/виджеты | Скан/импорт/обогащение/save живут минуты и могут пережить экран; анонимный внутренний класс тащил бы за собой мёртвый Activity. |
| Client-side пагинация (окно над курсором) | Все изменения каталога продолжают перепривязываться «на месте» — пейджер и свайп не ломают cursor-driven архитектуру; `changeCursor` откатывает страницу до валидной до замены. |
| Свайп в `dispatchTouchEvent`, а не `OnTouchListener` | `ViewGroup` отдаёт жест строке/плитке под пальцем, кликабельная строка его «съедает» — listener самого списка видел бы только касания по «голой» подкладке; перехват «свайпа в полёте» спасает от клика «Открыть эту книгу?» на API 19. |
| Режим списка/плиток и пагинация — в `SharedPreferences` | Выбор переживает поворот экрана **и** закрытие приложения; размер страницы применяется после режима (от него зависит). |

---

## 13. Глоссарий

| Термин | Что это (в контексте проекта) |
|---|---|
| **Activity** | Экран приложения (`MainActivity`, `DetailActivity`, `EditMetaActivity`). |
| **API level** | «Версия» Android: API 19 = 4.4.4, API 34 = 14. |
| **minSdk / targetSdk / compileSdk** | Минимальная / «обещанная» / «сборочная» версии Android (см. [2.2](#22-версии-android-api-level-minsdk-targetsdk-compilesdk)). |
| **Intent** | «Сообщение» между Activity и между приложениями (`ACTION_VIEW` — «открой файл»). |
| **MIME type** | «Имя» типа данных (`application/epub+zip`) — по нему ОС подбирает «просмотрщик». |
| **Parcelable** | Быстрая сериализация объектов для передачи в Intent (здесь — `Book`). |
| **SQLite** | Встраиваемая реляционная БД; каталог — `library.db`. |
| **Cursor** | Итератор по результатам SELECT в SQLite. |
| **ContentProvider** | «Адрес» данных (`content://…`); здесь — read-only `BookProvider`. |
| **ContentObserver** | «Подписка» на изменения по URI; срабатывает на `notifyChange`. |
| **CursorLoader** | «Загрузчик» `Cursor` в фоне + «подписка» на `notifyChange`. |
| **CursorAdapter** | Адаптер, который сам читает `Cursor` и перепривязывает строки. |
| **Adapter / recycle** | Механизм, по которому `ListView`/`GridView` переиспользуют виджеты строк. |
| **LruCache** | In-memory кэш с вытеснением «самых старых» (для `Bitmap` обложек). |
| **SAF** | Storage Access Framework — системный файловый менеджер (`ACTION_OPEN_DOCUMENT`). |
| **Holo** | Тема Android 4.x (материальная тема — только с API 21+). |
| **OPF** | OPF-документ EPUB (`content.opf`) — XML с метаданными и манифестом. |
| **EXTH** | Блок расширенных метаданных в MOBI (author/publisher/description/language/cover). |
| **PalmDB** | Контейнер MOBI/AZW: заголовок + таблица записей + сами записи. |
| **EOI** | End-Of-Image — маркер `FF D9` в JPEG (по нему обрезается «хвост» записи MOBI). |
| **Big-endian** | Порядок байтов «старший сначала» (используется в MOBI). |
| **Upsert** | «UPDATE or INSERT» — здесь: вставка строки или обновление по `path`. |
| **Fast path** | «Быстрый путь»: если книга ещё не обогащена фоновым воркером — `DetailActivity` сам вызывает `enrichOne` для неё. |
| **Branch protection** | Правило GitHub: merge в `master` только по PR с «зелёным» CI. |
| **Lint** | Статический анализатор кода (входящий в AGP). |
| **Daemon (Gradle)** | Фоновый процесс Gradle, который «живёт» между запусками — ускоряет команды. |
| **`meta_failed` («не обогащена»)** | Книга, чей последний парсинг перелетел 2-минутный бюджет: остаётся в очереди (`meta_done = 0`) и берётся последней на каждом рескане. |
| **Batch-коммит** | Группа строк (`BATCH_SIZE = 5`), применяемая в одной SQLite-транзакции: один коммит на группу, атомарный откат группы при сбое. |
| **Single-pass парсинг** | Метаданные + обложка извлекаются в одном сеансе файла (один `ZipFile` / один проход «шапки» / один `MobiParser`) — приём этапа 2. |
| **Client-side пагинация** | Адаптер держит весь курсор, но выдаёт одну страницу (окно со смещением); все изменения каталога перепривязываются на месте. |
| **In-flight дедуп** | Пока декодка обложки идёт, все плитки, связанные с тем же ключом `format|path|shape`, «присоединяются» к одной общей задаче вместо запуска второй декодки. |
| **WeakReference (слабая ссылка)** | Ссылка, не мешающая GC: долгие фоновые задачи держат Activity/ProgressDialog/ImageView так, чтобы мёртвые экраны собирались. |
| **Touch slop** | Системный минимальный «дрож» пальца (`ViewConfiguration.getScaledTouchSlop`); пороги свайпа выражены его кратными. |

---

## 14. Куда читать дальше (кураторенный список)

**Основы Android (англ., официальная документация):**
- [developer.android.com](https://developer.android.com/) — главный портал.
- [Build your first app](https://developer.android.com/training/basics/firstapp) — «с нуля».
- [Activity lifecycle](https://developer.android.com/guide/topics/fundamentals/activity-lifecycle).
- [Intents and intent filters](https://developer.android.com/guide/topics/intents-and-intent-filters).
- [ContentProvider overview](https://developer.android.com/guide/topics/fundamentals/providers).
- [About SDK platform versions](https://developer.android.com/guide/topics/manifest/uses-sdk-tag).
- [Holo design](https://developer.android.com/develop/ui/visual/holo-design).
- [Android build overview](https://developer.android.com/build).
- [Android Lint](https://developer.android.com/studio/write/lint).
- [View system](https://developer.android.com/develop/ui/views).

**Основы Android (рус.):**
- [«HeadAndroid»](https://www.mirrorbooks.ru/book/headandroid) — классический русскоязычный учебник (бесплатно в вебе).
- [Хабр](https://habr.com/ru/) — русскоязычные статьи по Android (поиск: «Android», «Activity», «Cursor», «Gradle»).

**Книги / форматы:**
- [EPUB — Wikipedia (рус.)](https://ru.wikipedia.org/wiki/EPUB); [IDPF (официальная спецификация)](https://idpf.org/epub/).
- [FictionBook (FB2) — Shishkin (рус., автор формата)](https://shishkin.org/fictionbook/); [fb2_3.1 (gribun.ru)](https://www.gribun.ru/ebook/doc/fb2_3.1.html).
- [MOBI (MobileReader wiki, англ.)](https://wiki.mobilereader.com/wiki/MOBI); [PalmDB (MobileReader wiki, англ.)](https://wiki.mobilereader.com/wiki/PalmDB).
- [SQLite — Wikipedia (рус.)](https://ru.wikipedia.org/wiki/SQLite); [сайт SQLite](https://www.sqlite.org/).
- [XML — Wikipedia (рус.)](https://ru.wikipedia.org/wiki/XML).
- [Zip (file format) — Wikipedia (англ.)](https://en.wikipedia.org/wiki/Zip_(file_format)).
- [Эндианность — Wikipedia (рус.)](https://ru.wikipedia.org/wiki/Эндианность).

**Java (JDK):**
- [java.util.zip (Javadoc, Java 8)](https://docs.oracle.com/javase/8/docs/api/java/util/zip/package-summary.html)
  (в проекте — `ZipInputStream` / `ZipOutputStream`).
- [XmlPullParser (Javadoc, Java 8)](https://docs.oracle.com/javase/8/docs/api/org/xmlpull/v1/XmlPullParser.html)
  (в проекте — через `android.util.Xml`).
- [RandomAccessFile (Javadoc, Java 8)](https://docs.oracle.com/javase/8/docs/api/java/io/RandomAccessFile.html)
  (используется в `MobiParser`).
- [Base64 (Android reference)](https://developer.android.com/reference/android/util/Base64).

**Сборка / тесты / CI:**
- [Gradle](https://gradle.org/) + [Gradle User Guide](https://docs.gradle.org/current/userguide/index.html)
  (на сайте — выбор языка, включая русский).
- [JUnit 4](https://junit.org/junit4/).
- [Robolectric](https://robolectric.org/).
- [GitHub Actions](https://docs.github.com/en/actions) (рус. — [docs.github.com/ru](https://docs.github.com/ru)).

**Паттерны (для контекста):**
- [Observer pattern (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Наблюдатель_(паттерн)) — основа `ContentObserver`/`notifyChange`.
- [Adapter pattern (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Адаптер_(паттерн)) — `CursorAdapter`.
- [Многослойная архитектура (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Многослойная_архитектура) — 3 слоя проекта.
- [Atomic operation (Wikipedia, англ.)](https://en.wikipedia.org/wiki/Atomic_operation) — приём `.tmp → swap → .bak`.

**Язык описания диаграмм:**
- [Mermaid](https://mermaid.js.org/) — язык, на котором нарисованы диаграммы в
  [`diagrams/*.mmd`](diagrams/). [Mermaid (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Mermaid_(язык_маркировки)).

---

## 15. Как читать код, если вы новичок

Рекомендуемый порядок (от простого к сложному):

1. **`model/Book.java`** — модель, маленькая, без магии.
2. **`scan/Formats.java`** — «что такое формат в этом проекте» + tier стоимости
   обогащения (`enrichmentPriority`).
3. **`db/BookDatabase.java`** — схема (v5), `upsert`, флаги. Поймите **почему**
   `last_read` / `meta_done` / `meta_failed` / `user_edited` не трогают
   `upsert`, и как устроены batch-коммиты.
4. **`MainActivity.java`** — главный экран; 3 ступени; `CursorLoader`;
   header- и per-book kebab'ы; пагинация; детерминированный rebind.
5. **`meta/MetaEnricher.java`** — фоновый воркер (этап 2): single-pass парсеры,
   время-бюджет, порядок очереди.
6. **`PagedListView.java` + `PageSwipeTracker.java`** — почему свайп детектится
   в `dispatchTouchEvent`, а не `OnTouchListener` (и зачем перехват).
7. **`meta/MetaExtractor.java` + `meta/MetaWriter.java`** — чтение/запись EPUB/FB2.
8. **`meta/MobiParser.java`** — бинарный формат (самый «нелегкий» для новичка).
9. **`util/CoverLoader.java` + `util/CoverCache.java`** — кэши, decode «в размер»,
   in-flight дедуп, асинхронная загрузка обложек.
10. **`db/BookProvider.java`** — «почему» read-only провайдера.
11. **`app/src/test/`** — тесты как «документация поведения».

Если что-то непонятно — ищите в [разделе 2](#2-минимум-теории-android-что-нужно-знать-чтобы-читать-код)
ссылку на нужный термин, а в [разделе 14](#14-куда-читать-дальше-кураторенный-список) —
ссылку на «глубокое» чтение.

---

*Документ составлен по состоянию на текущую кодовую базу (AGP 8.5.2, Gradle 8.7,
compileSdk 34, minSdk 19, JDK 17+; схема каталога `library.db` v5;
single-pass обогащение с 2-минутным бюджетом на файл; пагинация и свайп
страницы).*
