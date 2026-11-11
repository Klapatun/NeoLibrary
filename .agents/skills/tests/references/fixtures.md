# TestFixtures

Каталог `com.example.mylibrary.testutil.TestFixtures`. Читать, когда тест
собирает книгу, zip или обложку. Свои генераторы не заводить.

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
