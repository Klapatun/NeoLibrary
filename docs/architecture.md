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
(ANR). Долгие операции выносятся в **фоновые треady**.

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

В проекте фоновая работа происходит в четырёх местах:

1. `MainActivity.startScan()` — скан накопителя (этап 1);
2. `MetaEnricher.start()` — фоновый воркер чтения метаданных/обложек (этап 2);
3. `DetailActivity` — «быстрый путь» `enrichOne` для ещё не обогащённой книги;
4. `EditMetaActivity.save()` — запись метаданных (в файл + в БД).

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
  объекта `Book` как `Parcelable`, см. [2.6](#26-parcelable-передача-объектов));
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
чтобы передаваться из `DetailActivity` в `EditMetaActivity` одним `Intent`.

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
перезапрашивает → `CursorAdapter` (см. [2.11](#211-адаптеры-и-recycle-строк))
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

---

## 3. Слои приложения

Код разбит на три слоя по ответственности. Верхний слой — UI (экраны и адаптеры),
средний — логика и работа с форматами, нижний — хранилище.
Это классическая **многослойная архитектура** (Layered Architecture) —
[Многослойная архитектура (Wikipedia, рус.)](https://ru.wikipedia.org/wiki/Многослойная_архитектура).

```
┌────────────────────────── UI (Activity, Holo) ───────────────────────────┐
│  MainActivity        список · фильтр · «недавно прочитанные» · импорт     │
│  DetailActivity      книга: открыть · править · убрать                    │
│  EditMetaActivity    форма метаданных                                     │
│  BookAdapter         CursorAdapter: список + плитки                       │
│  (res/layout/*.xml)  макеты экранов и строк                               │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ Book (Parcelable) — пересылка между Activity
┌───────────────────────── ЛОГИКА / ФОРМАТЫ ────────────────────────────────┐
│  scan/Formats          единый список 15 форматов + карта расширений       │
│  scan/LibraryScanner   рекурсивный скан накопителя (этап 1)               │
│  meta/MetaExtractor    чтение метаданных: EPUB/FB2/FB2ZIP/MOBI/TXT/HTML   │
│  meta/MobiParser       бинарный reader MOBI/AZW (PalmDB + EXTH)           │
│  meta/MetaWriter       запись метаданных в EPUB/FB2 (.tmp → swap → .bak)  │
│  meta/CoverExtractor   байты обложки: EPUB/FB2/FB2ZIP/MOBI                │
│  meta/MetaEnricher     фоновый воркер (этап 2) + enrichOne (быстрый путь) │
│  meta/MetaData         POJO-контейнер «прочитанные метаданные»            │
│  util/Openers          MIME + ACTION_VIEW (открыть в Neo Reader)          │
│  util/CoverLoader      асинхронная обложка + LruCache + «обрезка»         │
│  util/CoverCache       файловый кэш обложек на внешнем накопителе         │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ persists via
┌────────────────────────────────── STORAGE ─────────────────────────────────┐
│  db/BookDatabase       SQLite library.db: схема, upsert, флаги, индекс    │
│  db/BookProvider       read-only ContentProvider (канал notifyChange)     │
└────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Кто за что отвечает (по классам)

| Класс | Слой | Роль |
|---|---|---|
| `MainActivity` | UI | главный экран; 3-ступенчатая загрузка; фильтр/«недавние»; SAF-импорт. Список — `CursorLoader`-управляемый. |
| `DetailActivity` | UI | карточка книги; «открыть в Neo Reader» (`ACTION_VIEW` + `markRead`); быстрый `enrichOne` для необогаъѐнной книги. |
| `EditMetaActivity` | UI | форма метаданных; запись в файл (только EPUB/FB2) + **всегда** в каталог с `userEdited=true`. |
| `BookAdapter` | UI | `CursorAdapter`; два режима (список/плитки); `getItem()` — курсор → `Book`. |
| `Book` | модель | `Parcelable`-модель книги; флаги `metaDone`/`userEdited`. |
| `db/BookDatabase` | хранилище | SQLite-каталог; `upsert`/`upsertBasic`/`updateMetadata` **не трогают** `last_read`/`user_edited`; статический write-лок; очередь `needMeta()`. |
| `db/BookProvider` | хранилище | read-only `ContentProvider` — канал `ContentObserver` для `CursorLoader` (URI: все / недавние / `format=?`). |
| `scan/Formats` | логика | канонические id 15 форматов + карта расширений (в т.ч. `.fb2.zip` как «сложное» расширение). **Единственный источник правды** для скана, фильтра и бейджа. |
| `scan/LibraryScanner` | логика | этап 1: рекурсивный обход накопителей (включая вторичные SD); `scanSingle()` для импорта. |
| `meta/MetaEnricher` | логика | этап 2: один фоновый поток по `needMeta()`; `enrichOne()` — для импорта и «быстрого пути»; `notifyChange` на партию. |
| `meta/MetaExtractor` | логика | чтение метаданных из EPUB/FB2/FB2ZIP (внутренний `.fb2`)/MOBI/TXT/HTML. |
| `meta/MobiParser` | логика | общий reader MOBI/AZW (package-private): таблица записей PalmDB + заголовок MOBI + EXTH. |
| `meta/MetaWriter` | логика | запись в EPUB/FB2, **недеструктивно** (`.tmp` → swap → `.bak`). |
| `meta/CoverExtractor` | логика | байты обложки: EPUB (OPF → manifest), FB2 (`coverpage` → `<binary>`), FB2ZIP, MOBI (EXTH 201, JPEG обрезается по EOI). |
| `meta/MetaData` | модель | простой контейнер прочитанных/записываемых метаданных. |
| `util/CoverCache` | логика | устойчивый файловый кэш обложек: `getExternalFilesDir("covers")/<hash>.img`, атомарно (`.tmp` → rename). |
| `util/CoverLoader` | логика | асинхронная обложка: сначала кэш, затем извлечение из файла, `LruCache`; скрывает «буквенный» бейдж после показа. |
| `util/Openers` | логика | карта MIME + `ACTION_VIEW`-intent (`Uri.fromFile` — допустимо в API 19). |

---

## 4. Ключевые ограничения (invariant) проекта

Это «нельзя сломать» — правила, за нарушение которых Lint и CI
скажут «нет», и которые при этом **объясняют** многие странные на первый
взгляд решения (Holo, `AsyncTask`, `Uri.fromFile`, без AndroidX):

1. **`minSdk 19` (Android 4.4.4)** — код обязан работать на нём. Никаких
   API-вызовов выше 19. Тема — Holo (Material — только с API 21+).
   Лint-правило `NewApi = error` в `app/lint.xml` — автоматическая проверка.
2. **Ни AndroidX, ни support-библиотек, ни сторонних рантайм-зависимостей.**
   Только Android Framework + классы JDK (`java.util.zip`,
   `XmlPullParser` через `android.util.Xml`, `java.io.RandomAccessFile`).
   Сборка работает **оффлайн**.
3. **Манифест**: без атрибута `package` (в AGP 8+ пакет — в `build.gradle`),
   у каждого `<activity>` — `android:exported`.
4. **Файл книги никогда не остаётся «наполовину записанным»** — всегда
   `.tmp` → swap → `.bak` (см. [раздел 6.4](#64-недеструктивная-запись-tmp--swap--bak)).
5. **`BookDatabase.upsert` сохраняет `last_read`** — повторный скан не должен
   «сбрасывать» «недавно прочитанные».
6. **`Formats.ALL` — единый источник правды** для сканера, фильтра и бейджа
   формата в UI: все три места не должны «расхождаться».

> Почему «без AndroidX и без библиотек»? Потому что цель — **оффлайн-сборка на
> старом (API 19) устройстве** без риска «подтянуть» зависимость, которая
> потребует более нового API или Material-темы. Ограничение намеренное, а не
> случайное (см. [раздел 2.2](#22-версии-android-api-level-minsdk-targetsdk-compileSdk)).

---

## 5. Экраны и навигация

Приложение — «трёхэкранное». Навигация линейная, без стека и без Fragment'ов:

```
[Launcher icon] → MainActivity  (список / плитки)
                    │  клик по строке/плитке
                    ▼
                  DetailActivity  (карточка книги)
                    │  «Редактировать»
                    ▼
                  EditMetaActivity  (форма метаданных)
```

- **MainActivity** — точка входа (в манифесте — `intent-filter`
  `MAIN`/`LAUNCHER`). Здесь же — меню «Пересканировать» / «Импортировать»,
  спиннер-фильтр по формату и переключатель «список ↔ плитки».
- **DetailActivity** — получает `id` книги в `Intent` (long), загружает её из
  БД, показывает обложку (если формат «умеет» обложки), кнопки «Открыть»,
  «Редактировать», «Убрать».
- **EditMetaActivity** — получает **объект** `Book` (Parcelable, см. [2.7](#27-передача-объектов-между-экранами-parcelable)),
  показывает форму; при «Сохранить» — в файл (EPUB/FB2) и **всегда** в каталог
  с флагом `userEdited=true` (см. [раздел 6.1](#61-двухслойная-модель-метаданных)).

После возврата из дочерних Activity родитель сам обновляет своё состояние
(`onActivityResult` / `notifyChange` → `CursorLoader`), то есть **никто нигде
не «перечитывает» список вручную** — это и есть cursor-driven UI.

---

## 6. Ключевые архитектурные идеи

Здесь — 5 приёмов, которые отличают этот проект от «простого» Android-демо.
Каждый — со своей диаграммой (SVG) в [`diagrams/`](diagrams/).

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
- **Этап 2 — фоновое «обогащение»** (`MetaEnricher`, отдельный поток): по очереди
  `db.needMeta()` (все книги с `meta_done=0`) для каждой книги —
  `MetaExtractor` + `CoverExtractor`, результат — `db.updateMetadata()` (не
  затирает `user_edited`, не трогает `last_read`) + запись обложки в
  `CoverCache`. Каждая партия (каждые 5 книг) — `notifyChange` →
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
[раздел 2.11](#211-адаптеры-и-recycle-строк).

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
  `CoverCache` (диск), затем in-file экстракция (если в кэше нет), затем —
  **`LruCache`** (в памяти). Плюс — «обрезка» `Bitmap` под форму (круг /
  скруглённый прямоугольник) и **проверка «на надет ли я на ту же строку»**
  (тег `cover_tag`), чтобы «переработанная» строка не нашла на себе чужую
  обложку (см. [2.11](#211-адаптеры-и-recycle-строк)).

Ключ кэша — `format + path + shape` (так обложки не «перетекают» между
форматами и формами).

---

## 7. Данные: модель и БД

### 7.1 Таблица `books` и её флаги

Каталог — **одна таблица** `books` в `library.db` (схема создаётся в
`BookDatabase.onCreate`, версия БД — `DB_VERSION = 3`, миграции — через
`ALTER TABLE ADD COLUMN` —
[ALTER TABLE (SQLite)](https://www.sqlite.org/lang_altertable.html)).

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
├── user_edited  INTEGER (0/1)          -- пользователь правил поля
└── last_read    INTEGER (epoch ms)     -- NULL = «не читали»
```

Индексы (для «горячих» запросов):
[`CREATE INDEX (SQLite)](https://www.sqlite.org/lang_createindex.html)`
— `idx_books_meta_done` (очередь этапа 2: `WHERE meta_done=0`) и
`idx_books_last_read` («недавно прочитанные»:
`WHERE last_read IS NOT NULL ORDER BY last_read DESC`).

**Три «защищённых» столбца** — `meta_done`, `user_edited`, `last_read` —
**никогда** не записываются в `upsert()` / `upsertBasic()`. Их меняют только
специальные методы: `markMetaPending`, `markUserEdited`, `markRead`.
Это гарантирует, что:

- повторный скан **не сбрасывает** «обогащено» / «недавно прочитанные»;
- фоновый воркер **не затирает** правки пользователя (см. [6.1](#61-двухслойная-модель-метаданных));
- `user_edited` — **монотонный** флаг: 1 → 0 никогда.

Диаграмма ER: [`db_er.svg`](diagrams/db_er.svg)
(исходник: [`db_er.mmd`](diagrams/db_er.mmd)).

### 7.2 Модель `Book`

`model/Book` — «плоский» `Parcelable`-класс с тем же набором полей + флаги
`metaDone` / `userEdited`. Методы-«помощники»: `initial()` (первая буква для
«буквенного» бейджа), `displayFormat()`. Пересылка между Activity — через
`Intent.putExtra` / `getParcelableExtra` (см. [2.7](#27-передача-объектов-между-экранами-parcelable)).

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
MainActivity.startScan()
  → AsyncTask → LibraryScanner.scan(context)
      → обход /storage/emulated/0 + вторичных SD
      → для каждого поддерживаемого файла: скелет Book
  → onPostExecute:
      → db.upsertBasic(b) для каждого
      → notifyChange(CONTENT_URI)
      → CursorLoader → CursorAdapter: список на экране
  → startEnrichment()  (если есть книги с meta_done=0)
```

### 8.2 Обогащение (этап 2)

```
MetaEnricher.start(app, db, listener)
  → AsyncTask (один воркер):
      → pending = db.needMeta()  (WHERE meta_done=0)
      → для каждой книги:
            MetaExtractor.extract(file)     (читает метаданные)
            CoverExtractor.extract(file)    (читает обложку, если формат «умеет»)
            db.updateMetadata(id, md, readable)
            CoverCache.save(path, coverBytes)
            (каждые 5 книг) notifyChange(CONTENT_URI)
      → финальный notifyChange + listener.onFinished()
```

### 8.3 Открытие книги

```
клик по строке → DetailActivity(id)
  → если !book.metaDone:  enrichOne() (fast path) в фоне, затем обновление карточки
  → кнопка «Открыть»:
      → Openers.openFile(path)  → Intent(ACTION_VIEW, mime, file://uri)
      → startActivity(intent)   (Android сам выбирает Neo Reader)
      → db.markRead(id)  → last_read = now
      → notifyChangeAll  → «недавно прочитанные» обновляется
```

### 8.4 Редактирование метаданных

```
кнопка «Редактировать» → EditMetaActivity(Book)
  → форма: title / author / publisher / description
  → «Сохранить» (в AsyncTask):
      → если EPUB/FB2 и файл существует:  MetaWriter.write(file, md)  (in-file)
      → ВСЕГДА:  db.upsert(book) + db.markUserEdited(id)   (in-catalog)
      → notifyChangeAll  → список обновится
  → setResult(RESULT_OK); finish()
```

### 8.5 Импорт (SAF)

```
меню «Импортировать» → ACTION_OPEN_DOCUMENT (системный файловый менеджер)
  → onActivityResult:
      → validate: Formats.isSupported(displayName)
      → копирование в getExternalFilesDir("books") (в AsyncTask)
      → LibraryScanner.scanSingle(dest)
      → db.upsertBasic(b)
      → если файл уже был в каталоге: markMetaPending + CoverCache.delete
      → MetaEnricher.enrichOne(app, db, b)  (в том же фоновом потоке)
      → notifyChange  → список «подхватывает» книгу (без ручного reload)
```

---

## 9. Форматы: как читать и писать метаданные

Ниже — «внутренности» каждого формата, которые проект понимает. Все
«внутренности» — на базе **только** `java.util.zip`, `XmlPullParser` (через
`android.util.Xml`) и `RandomAccessFile`; никаких сторонних библиотек.

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
4. `.tmp → swap → .bak` (см. [6.4](#64-недеструктивная-запись-tmp--swap--bak)).

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

### 9.3 FB2.ZIP

**FB2.ZIP** — «сложное» расширение: **ZIP**, внутри которого лежит `.fb2`
(и, возможно, отдельный `cover.jpg` рядом). В `Formats` обрабатывается **до**
проверки простого `.zip` (иголый `.zip` — **не** поддерживается).

**Чтение метаданных** — достать первый `.fb2` entry из ZIP и парсить как FB2.
**Чтение обложки** — сначала как FB2 (внутренний `<binary>`), затем — «свободный»
изображение в архиве (предпочтение — тому, в имени которого есть `cover`).

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
├── MainActivity.java        # экран 1: список, фильтр, импорт, 3 ступени
├── DetailActivity.java      # экран 2: одна книга (открыть/править/убрать)
├── EditMetaActivity.java    # экран 3: форма метаданных
├── BookAdapter.java         # CursorAdapter: список + плитки, recycle
├── model/
│   └── Book.java            # Parcelable-модель книги
├── db/
│   ├── BookDatabase.java    # SQLite: схема, upsert, флаги, needMeta()
│   └── BookProvider.java    # read-only ContentProvider (канал notifyChange)
├── scan/
│   ├── Formats.java         # 15 форматов, карта расширений, isSupported()
│   └── LibraryScanner.java  # рекурсивный скан накопителей (этап 1)
├── meta/
│   ├── MetaData.java        # POJO: прочитанные/записываемые метаданные
│   ├── MetaEnricher.java    # этап 2 (фоновый воркер) + enrichOne
│   ├── MetaExtractor.java   # чтение метаданных (EPUB/FB2/FB2ZIP/MOBI/TXT/HTML)
│   ├── MobiParser.java      # бинарный reader MOBI/AZW (PalmDB + EXTH)
│   ├── MetaWriter.java      # запись в EPUB/FB2 (.tmp → swap → .bak)
│   └── CoverExtractor.java  # байты обложки (EPUB/FB2/FB2ZIP/MOBI)
└── util/
    ├── CoverCache.java      # файловый кэш обложек (disk)
    ├── CoverLoader.java     # асинхронная обложка + LruCache + «обрезка»
    └── Openers.java         # MIME + ACTION_VIEW (открыть в Neo Reader)
```

Ресурсы:

```
app/src/main/res/
├── layout/
│   ├── activity_main.xml     # главный экран: toolbar + спиннер + ListView/GridView
│   ├── activity_detail.xml   # карточка книги
│   ├── activity_edit_meta.xml# форма
│   ├── item_book.xml         # строка списка
│   └── item_book_grid.xml    # плитка (242×387 px, скругление 8 px)
├── values/
│   ├── strings.xml           # строки
│   ├── colors.xml
│   ├── styles.xml            # AppTheme (Holo)
│   └── ids.xml               # R.id.cover_tag, R.id.view_mode
├── values-v21/               # Material-цвета — только для API 21+
├── drawable/                 # иконки (Holo, PNG)
└── mipmap/                   # иконка запуска
```

Тесты:

```
app/src/test/java/com/example/mylibrary/
├── MainActivityTest.java, DetailActivityTest.java, EditMetaActivityTest.java
├── BookAdapterTest.java
├── db/BookDatabaseTest.java, db/BookProviderTest.java
├── scan/FormatsTest.java, scan/LibraryScannerTest.java
├── meta/MetaExtractorTest.java, MetaWriterTest.java, MetaEnricherTest.java,
│      CoverExtractorTest.java
├── util/OpenersTest.java, OpenersIntentTest.java, CoverCacheTest.java
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

---

## 13. Глоссарий

| Термин | Что это (в контексте проекта) |
|---|---|
| **Activity** | Экран приложения (`MainActivity`, `DetailActivity`, `EditMetaActivity`). |
| **API level** | «Версия» Android: API 19 = 4.4.4, API 34 = 14. |
| **minSdk / targetSdk / compileSdk** | Минимальная / «обещанная» / «сборочная» версии Android (см. [2.2](#22-версии-android-api-level-minsdk-targetsdk-compileSdk)). |
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
2. **`scan/Formats.java`** — «что такое формат в этом проекте».
3. **`db/BookDatabase.java`** — схема, `upsert`, флаги. Поймите **почему**
   `last_read` / `meta_done` / `user_edited` не трогают `upsert`.
4. **`MainActivity.java`** — главный экран; 3 ступени; `CursorLoader`.
5. **`meta/MetaEnricher.java`** — фоновый воркер (этап 2).
6. **`meta/MetaExtractor.java` + `meta/MetaWriter.java`** — чтение/запись EPUB/FB2.
7. **`meta/MobiParser.java`** — бинарный формат (самый «нелегкий» для новичка).
8. **`util/CoverLoader.java` + `util/CoverCache.java`** — кэши и асинхронная
   загрузка обложек.
9. **`db/BookProvider.java`** — «почему» read-only провайдера.
10. **`app/src/test/`** — тесты как «документация поведения».

Если что-то непонятно — ищите в [разделе 2](#2-минимум-теории-android-что-нужно-знать-чтобы-читать-код)
ссылку на нужный термин, а в [разделе 14](#14-куда-читать-дальше-кураторенный-список) —
ссылку на «глубокое» чтение.

---

*Документ составлен по состоянию на текущую кодовую базу (AGP 8.5.2, Gradle 8.7,
compileSdk 34, minSdk 19, JDK 17+).*
