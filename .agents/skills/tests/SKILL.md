---
name: tests
description: Тестирование Neo Library (Android API 19, JUnit 4 + Robolectric 4.12.2, sdk=19): запуск полного сьюта и одиночных тестов, конвенции написания тестов, TestFixtures, ожидание фоновой работы, известные флейки (голод фоновых тредов) и подводные камни Robolectric. Использовать при любом запуске/отладке/дописывании тестов этого проекта.
---

# Скил: тестирование Neo Library

Проект: `D:\Projects\myLibrary` — нативное Android-приложение (API 19, без
AndroidX и сторонних runtime-зависимостей). Все тесты — **юнит-тесты** в
`app/src/test/java/com/example/mylibrary/` (JUnit 4 + Robolectric);
**androidTest-каталога нет**, на устройство тесты не ставятся.

Состав сьюта (≈200 тестов, точное число — в HTML-отчёте):

| Группа | Классы | Запускаются через |
|---|---|---|
| scan | `FormatsTest`, `LibraryScannerTest` | чистый JUnit (без Robolectric) |
| model | `BookTest` | Robolectric |
| db | `BookDatabaseTest`, `BookProviderTest` | Robolectric (реальный SQLite in-process) |
| meta | `MetaExtractorTest`, `MetaWriterTest`, `CoverExtractorTest`, `MetaEnricherTest`, `MetaEnricherScaleTest` | Robolectric (MetaWriterTest — чистый JUnit) |
| util | `CoverCacheTest`, `CoverLoaderTest`, `OpenersTest`, `OpenersIntentTest`, `PageSwipeTrackerTest` | Robolectric |
| UI | `MainActivityTest`, `DetailActivityTest`, `EditMetaActivityTest`, `BookAdapterTest` | Robolectric (ActivityController) |
| фикстуры | `testutil/TestFixtures` (не тест) | — |

`FormatsTest` и `MetaWriterTest` не используют Robolectric, всё остальное —
`@RunWith(RobolectricTestRunner.class)` **строго с `@Config(sdk = 19)`**
(4.12.2 — последняя линейка Robolectric, поддерживающая API 19; 19 — нижняя
граница проекта, выше не подниматься).

## 1. Запуск

Эта среда — Windows; `java` **не** в PATH, JDK 17 живёт при Android Studio.
Перед любым Gradle-запуском (PowerShell):

```powershell
$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
```

Команды (из корня `D:\Projects\myLibrary`):

```powershell
gradlew.bat :app:testDebugUnitTest                                  # весь сьют
gradlew.bat :app:testDebugUnitTest --tests "com.example.mylibrary.scan.FormatsTest"          # один класс
gradlew.bat :app:testDebugUnitTest --tests "com.example.mylibrary.scan.FormatsTest.dottedFileNamesMatchOnTheLastExtension"  # один метод
gradlew.bat :app:lintDebug                                            # lint (его гоняет pre-commit)
gradlew.bat :app:assembleDebug                                        # APK, если тронули сборку
```

- В CI и в `git-hooks/pre-push` та же задача: `./gradlew --no-daemon testDebugUnitTest`
  (JDK 17 temurin, SDK platform 34). `pre-push` гоняет сьют **перед каждым push** и
  роняет push при падении; `pre-commit` гоняет только lint.
- Robolectric-тестам на этапе запуска нужен **интернет** (android-all jar тянется с
  Maven Central). Оффлайн остаётся только сборка приложения.
- Консольный вывод тестов подробный по конфигурации в `app/build.gradle`
  (`showStandardStreams`, `exceptionFormat = full`) — дампы тредов и стек-трейсы
  видны прямо в выводе Gradle.
- Отчёты: HTML — `app/build/reports/tests/testDebugUnitTest/index.html`
  (и `classes/*.html`), XML — `app/build/test-results/testDebugUnitTest/*.xml`.
- `MetaEnricherScaleTest` (400 книг, 4 формата) — тяжёлый: дедлайн в тесте 5 минут,
  на машине 15–30 с. Гнать в одиночку, если нужен быстрый сигнал.

## 2. ПЕРВОЕ правило эксплуатации: флейки класса 8.4

Документированный феномен проекта (ISSUES.md §8.4, §13.5–13.6): под нагрузкой
машины (Android Studio + Chrome + Steam) и даже на почти свободной (в 3 из 7
полных прогонов) **случайный** тест падает по 15–30-с таймауту — голод
однопоточного пула AsyncTask (фоновые треды просто не расписываются). Жертва
вращается (filterSpinner…, kebabDetails…, confirmTheOpenConfirmation…).

**Правило:** таймаут в полном сьюте — **не** регресс, пока не доказано
противное. Порядок:

1. Перепустить упавший тест **в изоляции** (`--tests "...Класс.метод"`):
   изолированно он проходит за секунды.
2. Прочитать stdout упавшего теста: await-хелперы `MainActivityTest`
   (`awaitCatalogSize`/`awaitAdapterCount`/`awaitCondition`) при 30-с таймауте
   дампят стеки **всех** тредов (`dumpThreads`) — видно, где застрял тред пула.
3. Только если изолированный прогон тоже падает — это баг, копать по коду.

Не поднимать `WAIT_MS` и не «чинить» код по первому же таймауту.

## 3. Как писать новый тест (конвенции сьюта)

Стиль проекта — pre-lambda: **анонимные классы, без лямбд и стримов** (Java 8
доступен, но стиль — инвариант).

Каркас Robolectric-теста (копировать с `BookDatabaseTest` / `MetaEnricherTest`):

```java
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class MyTest {

    private static final long WAIT_MS = 30000; // задокументированная верхняя граница голода фоновых тредов

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context app;
    private BookDatabase db;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("library.db");   // БД общая в пределах теста — чистим в начале
        db = new BookDatabase(app);
    }
    ...
}
```

Правила:

- **JUnit 4** (`org.junit.*`, static-импорты из `org.junit.Assert.*`), не JUnit 5.
- **Новых test-зависимостей не добавлять** — в `app/build.gradle` только
  `junit:junit:4.13.2` и `org.robolectric:robolectric:4.12.2`.
- Имя метода — предложение: `enrichOneNeverClobbersUserEditsButFillsEmptyFields`,
  `startWorksTheQueueFromTheCheapestFormatsUp`.
- В javadoc класса — что именно зафиксировано (какой инвариант/поведение
  «прибит» тестом); неочевидные ожидания — комментариями с причинами.
- Файлы — только в `@Rule TemporaryFolder`, фикстуры из `TestFixtures`;
  бинарных книг в git не хранить (фикстуры строятся в памяти / во временных).
- **Регрессионные тесты пишутся по практике проекта**: сначала RED на
  дефектном поведении, затем фикс, затем GREEN (примеры — ISSUES.md §9.3, §10.3, §10.4).

### Фикстуры: `com.example.mylibrary.testutil.TestFixtures`

Всё, что нужно для создания «книг», уже есть — не выдумывать свои генераторы:

- `writeText(f, s)`, `writeBytes(f, b)`, `readAll(f)` — обычные файлы;
- `writeZip(f, Map<String,byte[]>)`, `readZip(f)` — zip-архивы (порядок записей сохраняется);
- EPUB: `writeEpub(f, opf)` (container + OPF + глава + `images/cover.jpg`),
  `writeEpub3CoverCase(f, opf)`, `buildOpf(t,c,p,d,lang)` (свои метаданные, без обложки),
  константы `OPF_FULL`, `OPF_CREATOR_ONLY`, `OPF_WITH_COVER` (EPUB2 cover), `OPF3_WITH_COVER` (EPUB3 cover);
- FB2: `FB2_FULL`, `FB2_NO_TITLE`, `buildFb2(...)` (свои метаданные; автор = first + last,
  склеиваются пробелом), `buildFb2WithCover(base64)` (coverpage → `<binary>`);
- MOBI: `writeMobi(f, title, author, publisher, desc, lang, coverJpeg, firstImageIndex, coverOffset)` —
  структурно валидный PalmDB/MOBI (MOBI-хедер 232 байта, EXTH 100/101/103/524/201,
  cover = JPEG + 4 байта padding **после** EOI — правильный экстрактор обязан отрезать;
  `coverOffset = -1` — книга без обложки, 201-запись не пишется);
- `coverBytes()` — детерминированные псевдо-JPEG байты (256 Б).

MOBI-cover «JPEG» в тестах может быть минимальным: `{0xFF,0xD8,0xFF,0xD9}`
(SOI..EOI) — парсеру этого достаточно.

### Ожидание фоновой работы (стандартный паттерн)

Фоновые воркеры (AsyncTask/шареный пул) догоняются **idle'ом main-looper'а
с дедлайном**, а не sleep'ом:

```java
final AtomicBoolean workerDone = new AtomicBoolean(false);
// ... старт воркера с listener'ом, onFinished() → workerDone.set(true)
ShadowLooper looper = shadowOf(Looper.getMainLooper());
long deadline = System.currentTimeMillis() + WAIT_MS;
while (System.currentTimeMillis() < deadline && !workerDone.get()) {
    looper.idle();
    Thread.sleep(10);
}
looper.idle();
MetaEnricher.cancel();
```

- Ждать **финишный** признак (`onFinished`, опустевшая очередь `db.needMeta()`),
  а не промежуточный (очередь может стать меньше ещё до того, как воркер
  пометит таймаут-книгу).
- Листенер у bulk-воркера держать **сильной** локальной переменной на всё время
  теста: enricher держит его слабо, и на длинном прогоне (400 книг) он уйдёт
  по GC (прецедент — `MetaEnricherScaleTest`).
- `WAIT_MS = 30000` — граница задокументированного голода (класс 8.4);
  для 400-книжного scale-прогона — 300000.
- Для UI-тестов — готовые хелперы `MainActivityTest`: `awaitCatalogSize`,
  `awaitAdapterCount`, `awaitCondition` (с `dumpThreads` при таймауте).

### Тестовые хуки MetaEnricher

`MetaEnricher` оставляет package-private статические швы (сменить в тесте,
**обязательно вернуть в `finally`**, после — `MetaEnricher.cancel()`):

- `MetaEnricher.parseTask` — подменить `ParseTask` (имитация медленного/бьющего
  парсинга, запись фактического порядка обработки);
- `MetaEnricher.extractTimeoutMs` — сжать 2-минутный бюджет до сотен мс
  (тесты тайм-аута);
- `MetaEnricher.notifyMinIntervalMs` — тест троттлинга batch-notify
  (наблюдать через зарегистрированный `ContentObserver` на `BookProvider.CONTENT_URI`).

Такой же приём допустим и для новых швов (package-private static + restore
in finally) — это устоявшаяся практика проекта (ISSUES.md §8.2).

### UI-тесты (Activity)

- `Robolectric.buildActivity(MainActivity.class)` → `.setup().get()`;
  полный жизненный цикл — `c.setup().visible()...c.destroy()`.
- Корень сканера — внешнее хранилище: в `setUp`
  `ShadowEnvironment.setExternalStorageDirectory(folder.newFolder("external-storage").toPath())`
  — stage-1 скан становится детерминированным.
- SAF-импорт: вызывать `onActivityResult` напрямую + подсовывать
  `ContentResolver` фейк-курсор (display name) + input stream.
- Диалоги: `ShadowDialog.getLatestDialog()`, клики —
  `shadowOf(dialog).clickOn(android.R.id.button1)`; тосты — `ShadowToast`.
- Пустой каталог: `setEmptyView` прячет **сам** list/grid (`GONE`) —
  visibility не индикатор режима вида; ассерты по режиму делать ПОСЛЕ
  появления строк (adapter count > 0) или проверять `getAdapter() != null`.

## 4. Подводные камни Robolectric 4.12.2 @ sdk 19

Собранные в ISSUES.md (§8.4, §10.3, §11.4, §13.1) — проверять в первую очередь
при отладке падающего UI-теста:

1. `ShadowActivity.getNextStartedActivity()` **деструктивна** (списывает интент
   из очереди): читать один раз, дальше — сохранённый объект.
2. `ShadowDialog.clickOn(viewId)` принимает **id вью** (`android.R.id.button1`/
   `button2`), а НЕ `AlertDialog.BUTTON_*` (по ним — NPE).
3. В PAUSED-лоопере запуск интента откладывается: после клика по диалогу
   обязательно `shadowOf(Looper.getMainLooper()).idle()`, и только потом
   `getNextStartedActivity()`.
4. API 19: у `Dialog`/`AlertDialog` НЕТ публичных геттеров title/message.
   Title — `shadowOf(dialog).getTitle()`; message —
   `dialog.getWindow().getDecorView().findViewById(android.R.id.message)`.
5. `changeCursor` на НЕ-NULL курсор шлёт `onChanged` (`notifyDataSetChanged`),
   НЕ `onInvalidated` (onInvalidated — только при `changeCursor(null)`).
6. Сравнение `ConstantState` одного PNG, прошедшего через разные инфляционные
   пути (тема), в Robolectric не проходит — для иконок сравнивать доминантный
   непрозрачный цвет картинки (PNG почти монохромные).
7. Жесты: сырые `MotionEvent` DOWN→MOVE→UP через `dispatchTouchEvent`,
   **между событиями — `idle()` main-looper'а** (имитация кадров реального
   инпута), иначе posted-обработчики press-состояния не успеют до UP.
8. `OnTouchListener` на `AbsListView`/`GridView` **никогда** не срабатывает для
   жеста, начатого на строке (view group шлёт событие child'у, кликабельная
   строка поглощает). Если тест жмёт listener'ом по списку — проверять, не
   мёртвый ли это код.
9. Фейк-курсор SAF-импорта: у Robolectric-`BaseCursor` `register*Observer`/
   `getInt`/`getLong` могут бить `UnsupportedOperationException` — обход
   описан в `MainActivityTest` (обслуживать при апгрейде Robolectric).

## 5. Что закреплять тестами (инварианты проекта)

Каждая новая фича/фикс должна приносить тест на свои граничные случаи.
Ключевые инварианты (CONTEXT.md) — тесты на них уже есть и не должны ломаться:

- minSdk 19, Holo, без AndroidX и сторонних runtime-зависимостей
  (в тестах — `@Config(sdk = 19)`; в проде — lint `NewApi = error` в `app/lint.xml`);
- `BookDatabase.upsert`/`upsertBasic`/`updateMetadata` **не затирают `last_read`**
  (`BookDatabaseTest.upsertPreservesLastReadAcrossRescans`);
- enricher **не затирает** `user_edited`-значения, но заполняет пустые поля
  (`MetaEnricherTest.enrichOneNeverClobbersUserEditsButFillsEmptyFields`);
- `Formats.ALL` — single source of truth: каждый канонический id достижим по
  расширению, ни одно расширение не ведёт в чужеродный id; состав `Formats.ALL`
  менять только вместе с `FormatsTest`;
- один висящий/медленный файл не зацикливает очередь: 2-минутный per-file
  бюджет, overrun → `meta_failed=1` (остаётся в очереди, ретраится последней),
  очередь при этом дренируется (`MetaEnricherTest` — два теста про бюджет,
  один про throwing-parse containment: бьющий парс помечает done БЕЗ флага);
- порядок очереди stage 2: EPUB/FB2 → FB2ZIP → MOBI → остальное,
  `meta_failed` — самые последние (`startWorksTheQueueFromTheCheapestFormatsUp`);
- файл книги никогда не остаётся полудописанным (`.tmp`→swap→`.bak` —
  `MetaWriterTest`).

## 6. Чек-лист после изменений в тестах (или в коде, который они покрывают)

1. `gradlew.bat :app:testDebugUnitTest` — весь сьют зелёный (с `JAVA_HOME` из §1).
2. Если что-то упало по таймауту — правило §2 (изоляция → stdout → только потом код).
3. `gradlew.bat :app:lintDebug` — 0 errors (pre-commit и так его гоняет).
4. Рефакторинг: сьют — safety net, после КАЖДОГО шага прогон полный,
   публичный API не меняется (см. ISSUES.md [REFAC] MetaExtractor/CoverExtractor).
5. Push: `pre-push` сам гоняет сьют и не пустит push с красным; не обходить
   `--no-verify` ради флейка — повторить сьут на тихой машине/в изоляции.
