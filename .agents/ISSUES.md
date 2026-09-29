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
