package com.example.mylibrary.meta;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only FB3 package reader shared by {@link MetaExtractor} and
 * {@link CoverExtractor}. One call on an already-open {@link ZipFile} returns
 * both the description metadata and the cover bytes.
 *
 * <p>The description part is the first non-external root {@code _rels/.rels}
 * relationship of type {@code .../relationships/Book}. Targets are resolved
 * from that part (the root relationships file is relative to the package
 * root). {@code [Content_Types].xml} is not read, {@code description.xml} is
 * not guessed by name, and {@code body.xml} is not opened. A later {@code Book}
 * relationship (a multi-book package) is ignored.</p>
 *
 * <p>The cover is one image: the first root thumbnail relationship, otherwise
 * the first {@code .../relationships/cover} in the description part's own
 * relationships file. An external thumbnail or cover is a missing image and
 * does not select a later relationship. Targets are percent-decoded URIs;
 * {@code \} in a target or a zip entry name is a separator. Bytes go through
 * {@link CoverExtractor#readEntryBytes}; an entry over the cover limit is null
 * and does not discard metadata.</p>
 */
final class Fb3Package {

    private static final String TAG = "Fb3Package";

    private static final String TYPE_BOOK =
            "http://www.fictionbook.org/FictionBook3/relationships/Book";
    private static final String TYPE_THUMB =
            "http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail";
    private static final String TYPE_COVER =
            "http://www.fictionbook.org/FictionBook3/relationships/cover";
    private static final String ROOT_RELS = "_rels/.rels";

    private Fb3Package() {}

    /** Metadata plus cover bytes. {@code cover} is null when the package has none. */
    static final class Result {
        final MetaData meta;
        final byte[] cover;

        Result(MetaData meta, byte[] cover) {
            this.meta = meta;
            this.cover = cover;
        }
    }

    /**
     * Reads the open package. Does not close {@code zip}. A missing or unreadable
     * description is {@code found == false} with {@code genres == null}. A finished
     * description leaves {@code genres} non-null (possibly empty) and sets
     * {@code found} only for a non-empty book title.
     */
    static Result read(ZipFile zip) throws Exception {
        ZipEntry relsEntry = findEntry(zip, ROOT_RELS);
        if (relsEntry == null) return notFound();
        byte[] relsBytes = CoverExtractor.readEntryBytes(zip, relsEntry);
        if (relsBytes == null) return notFound();
        String relsXml = XmlText.decode(relsBytes);
        if (relsXml == null) return notFound();
        Rels rels = parseRels(relsXml);
        if (!rels.hasBook) return notFound();

        // Root relationships resolve against the package root, not against _rels/.
        String bookPath = resolveTarget("", rels.bookTarget);
        if (bookPath == null) return notFound();
        ZipEntry descEntry = findEntry(zip, bookPath);
        if (descEntry == null) return notFound();
        byte[] descBytes = CoverExtractor.readEntryBytes(zip, descEntry);
        if (descBytes == null) return notFound();
        String descXml = XmlText.decode(descBytes);
        if (descXml == null) return notFound();

        MetaData md = parseDescription(descXml);
        byte[] cover = readCover(zip, rels, bookPath);
        return new Result(md, cover);
    }

    private static Result notFound() {
        MetaData md = new MetaData();
        md.found = false;
        return new Result(md, null);
    }

    // -------------------------------------------------------------------
    // relationships
    // -------------------------------------------------------------------

    private static final class Rels {
        boolean hasBook;
        String bookTarget;
        /** The first thumbnail relationship, external ones included. */
        boolean thumbSeen;
        boolean thumbExternal;
        String thumbTarget;
        /** The first cover relationship, external ones included. */
        boolean coverSeen;
        boolean coverExternal;
        String coverTarget;
    }

    /**
     * Namespace is not checked. The first non-external {@code Book} is the
     * description. The first thumbnail and the first cover are decisive:
     * an external one is not skipped in favour of a later internal target.
     */
    private static Rels parseRels(String xml) throws Exception {
        Rels rels = new Rels();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            if (!"Relationship".equals(localName(p.getName()))) continue;
            String type = attr(p, "Type");
            String target = attr(p, "Target");
            boolean external = "External".equals(attr(p, "TargetMode"));
            if (TYPE_BOOK.equals(type) && !rels.hasBook && !external) {
                rels.hasBook = true;
                rels.bookTarget = target;
            } else if (TYPE_THUMB.equals(type) && !rels.thumbSeen) {
                rels.thumbSeen = true;
                rels.thumbExternal = external;
                if (!external) rels.thumbTarget = target;
            } else if (TYPE_COVER.equals(type) && !rels.coverSeen) {
                rels.coverSeen = true;
                rels.coverExternal = external;
                if (!external) rels.coverTarget = target;
            }
        }
        return rels;
    }

    /**
     * Package-relative path of {@code target} as seen from {@code sourcePart}.
     * An empty {@code sourcePart} is the package root (the root relationships
     * file). A leading {@code /} is package-absolute. Percent-escapes are
     * decoded as UTF-8 before {@code .} and {@code ..} are collapsed; a path
     * that leaves the package, or a broken escape, is null (no such entry).
     */
    private static String resolveTarget(String sourcePart, String target) {
        if (target == null) return null;
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        target = target.replace('\\', '/');
        target = percentDecode(target);
        if (target == null) return null;
        target = target.replace('\\', '/');
        if (target.length() == 0) return null;
        String combined;
        if (target.charAt(0) == '/') {
            combined = target;
        } else if (sourcePart == null || sourcePart.length() == 0) {
            combined = target;
        } else {
            int slash = sourcePart.lastIndexOf('/');
            String dir = slash < 0 ? "" : sourcePart.substring(0, slash);
            combined = dir.length() == 0 ? target : dir + "/" + target;
        }
        return collapse(combined);
    }

    /** {@code %HH} sequences as UTF-8. {@code +} stays a plus. Null when an escape is broken. */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = hex(s.charAt(i + 1));
                int lo = hex(s.charAt(i + 2));
                if (hi < 0 || lo < 0) return null;
                pending.write((hi << 4) | lo);
                i += 3;
            } else {
                if (!flushUtf8(pending, out)) return null;
                out.append(c);
                i++;
            }
        }
        if (!flushUtf8(pending, out)) return null;
        return out.toString();
    }

    private static boolean flushUtf8(ByteArrayOutputStream pending, StringBuilder out) {
        if (pending.size() == 0) return true;
        try {
            CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            out.append(decoder.decode(ByteBuffer.wrap(pending.toByteArray())));
            pending.reset();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Null when {@code ..} would leave the package. */
    private static String collapse(String path) {
        String[] parts = path.split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() == 0 || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        if (out.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    /** {@code fb3/description.xml} → {@code fb3/_rels/description.xml.rels}. */
    private static String relsForPart(String partPath) {
        int slash = partPath.lastIndexOf('/');
        String name = slash < 0 ? partPath : partPath.substring(slash + 1);
        String dir = slash < 0 ? "_rels" : partPath.substring(0, slash) + "/_rels";
        return dir + "/" + name + ".rels";
    }

    /**
     * Case-insensitive entry lookup. {@code \} is a separator, one leading
     * {@code /} is ignored. Directory entries are not parts.
     */
    private static ZipEntry findEntry(ZipFile zip, String path) {
        if (path == null || path.length() == 0) return null;
        String want = zipName(path);
        if (want.length() == 0) return null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (name == null) continue;
            if (want.equalsIgnoreCase(zipName(name))) return entry;
        }
        return null;
    }

    private static String zipName(String name) {
        return stripOneSlash(name.replace('\\', '/'));
    }

    private static String stripOneSlash(String name) {
        if (name.startsWith("/")) return name.substring(1);
        return name;
    }

    /**
     * The first root thumbnail wins, including an external one (then the cover
     * is absent). Otherwise the description part's first cover relationship.
     * Any failure here is a missing cover, not a failed description.
     */
    private static byte[] readCover(ZipFile zip, Rels rels, String bookPath) {
        try {
            if (rels.thumbSeen) {
                if (rels.thumbExternal) return null;
                String path = resolveTarget("", rels.thumbTarget);
                if (path == null) return null;
                return CoverExtractor.readEntryBytes(zip, findEntry(zip, path));
            }
            ZipEntry partRels = findEntry(zip, relsForPart(bookPath));
            if (partRels == null) return null;
            byte[] raw = CoverExtractor.readEntryBytes(zip, partRels);
            if (raw == null) return null;
            String xml = XmlText.decode(raw);
            if (xml == null) return null;
            Rels part = parseRels(xml);
            if (!part.coverSeen || part.coverExternal) return null;
            String path = resolveTarget(bookPath, part.coverTarget);
            if (path == null) return null;
            return CoverExtractor.readEntryBytes(zip, findEntry(zip, path));
        } catch (Exception e) {
            Log.w(TAG, "Could not read FB3 cover", e);
            return null;
        }
    }

    // -------------------------------------------------------------------
    // description
    // -------------------------------------------------------------------

    /**
     * Genres are set only after an {@code fb3-description} element is read.
     * A document that parses without that root leaves {@code genres} null, so
     * a mis-aimed Book part cannot clear catalog genres. Inside a real
     * description, an empty field list is still a finished parse.
     * {@code found} follows the book title only.
     */
    private static MetaData parseDescription(String xml) throws Exception {
        MetaData md = new MetaData();
        ArrayList<String> genres = new ArrayList<String>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        boolean seen = false;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            if (!seen && "fb3-description".equals(localName(p.getName()))) {
                seen = true;
                readDescription(p, md, genres);
            }
        }
        if (!seen) {
            md.found = false;
            return md;
        }
        md.genres = MetaData.normalizeGenres(genres);
        md.found = md.title != null && md.title.length() > 0;
        return md;
    }

    /** Direct children of {@code fb3-description}. Nested titles are not the book. */
    private static void readDescription(XmlPullParser p, MetaData md, ArrayList<String> genres)
            throws Exception {
        boolean titleSeen = false;
        boolean seriesSeen = false;
        boolean langSeen = false;
        boolean annotationSeen = false;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG
                    && "fb3-description".equals(localName(p.getName()))) {
                return;
            }
            if (event != XmlPullParser.START_TAG) continue;
            String local = localName(p.getName());
            if ("title".equals(local) && !titleSeen) {
                titleSeen = true;
                String title = readTitle(p);
                if (title != null && title.length() > 0) md.title = title;
            } else if ("sequence".equals(local) && !seriesSeen) {
                seriesSeen = true;
                String series = readSequenceName(p);
                if (series != null && series.length() > 0) md.series = series;
            } else if ("fb3-relations".equals(local)) {
                readRelations(p, md);
            } else if ("fb3-classification".equals(local)) {
                readClassification(p, genres);
            } else if ("lang".equals(local) && !langSeen) {
                langSeen = true;
                String lang = elementText(p).trim();
                if (lang.length() > 0) md.language = lang;
            } else if ("annotation".equals(local) && !annotationSeen) {
                annotationSeen = true;
                String description = readAnnotation(p);
                if (description != null && description.length() > 0) md.description = description;
            } else {
                // periodical, object, written, history, and any other sibling:
                // their titles and lang are not the book's.
                skipElement(p);
            }
        }
    }

    /**
     * Title text: the first {@code main} (nested text included), or, when that
     * is missing or blank, the first direct {@code p}. {@code sub} and {@code alt}
     * are ignored.
     */
    private static String readTitle(XmlPullParser p) throws Exception {
        String main = null;
        String para = null;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && "title".equals(localName(p.getName()))) {
                break;
            }
            if (event != XmlPullParser.START_TAG) continue;
            String local = localName(p.getName());
            if ("main".equals(local) && main == null) {
                main = elementText(p).trim();
            } else if ("p".equals(local) && para == null) {
                para = elementText(p).trim();
            } else {
                skipElement(p);
            }
        }
        if (main != null && main.length() > 0) return main;
        if (para != null && para.length() > 0) return para;
        return null;
    }

    /** The sequence's own {@code title}. A nested {@code sequence} is not a series. */
    private static String readSequenceName(XmlPullParser p) throws Exception {
        String name = null;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && "sequence".equals(localName(p.getName()))) {
                break;
            }
            if (event != XmlPullParser.START_TAG) continue;
            if ("title".equals(localName(p.getName())) && name == null) {
                name = readTitle(p);
            } else {
                skipElement(p);
            }
        }
        return name;
    }

    private static void readRelations(XmlPullParser p, MetaData md) throws Exception {
        StringBuilder authors = new StringBuilder();
        boolean publisherSeen = md.publisher != null;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG
                    && "fb3-relations".equals(localName(p.getName()))) {
                break;
            }
            if (event != XmlPullParser.START_TAG) continue;
            if (!"subject".equals(localName(p.getName()))) {
                skipElement(p);
                continue;
            }
            String link = attr(p, "link");
            String name = readSubjectName(p);
            if ("author".equals(link)) {
                if (name != null && name.length() > 0) {
                    if (authors.length() > 0) authors.append(", ");
                    authors.append(name);
                }
            } else if ("publisher".equals(link) && !publisherSeen) {
                publisherSeen = true;
                if (name != null && name.length() > 0) md.publisher = name;
            }
        }
        if (authors.length() == 0) return;
        if (md.author != null && md.author.length() > 0) {
            md.author = md.author + ", " + authors.toString();
        } else {
            md.author = authors.toString();
        }
    }

    /**
     * Non-empty {@code title} ({@code main}, else {@code p}), otherwise the
     * non-empty {@code first-name} / {@code middle-name} / {@code last-name}
     * joined by a single space. Blank parts are skipped. Null when nothing remains.
     */
    private static String readSubjectName(XmlPullParser p) throws Exception {
        String titled = null;
        boolean titleSeen = false;
        String first = null;
        String middle = null;
        String last = null;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && "subject".equals(localName(p.getName()))) {
                break;
            }
            if (event != XmlPullParser.START_TAG) continue;
            String local = localName(p.getName());
            if ("title".equals(local) && !titleSeen) {
                titleSeen = true;
                titled = readTitle(p);
            } else if ("first-name".equals(local) && first == null) {
                first = elementText(p).trim();
            } else if ("middle-name".equals(local) && middle == null) {
                middle = elementText(p).trim();
            } else if ("last-name".equals(local) && last == null) {
                last = elementText(p).trim();
            } else {
                skipElement(p);
            }
        }
        if (titled != null && titled.length() > 0) return titled;
        StringBuilder sb = new StringBuilder();
        appendPart(sb, first);
        appendPart(sb, middle);
        appendPart(sb, last);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.length() == 0) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(part);
    }

    /** Direct {@code subject} text only. {@code class} and the other codes are not genres. */
    private static void readClassification(XmlPullParser p, ArrayList<String> genres)
            throws Exception {
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG
                    && "fb3-classification".equals(localName(p.getName()))) {
                return;
            }
            if (event != XmlPullParser.START_TAG) continue;
            if ("subject".equals(localName(p.getName()))) {
                genres.add(elementText(p));
            } else {
                skipElement(p);
            }
        }
    }

    /**
     * Direct {@code p} paragraphs, markup dropped, joined with {@code \n}.
     * When there is no {@code p}, the trimmed text of the whole annotation.
     */
    private static String readAnnotation(XmlPullParser p) throws Exception {
        boolean sawP = false;
        ArrayList<String> paras = new ArrayList<String>();
        StringBuilder all = new StringBuilder();
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && "annotation".equals(localName(p.getName()))) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                if ("p".equals(localName(p.getName()))) {
                    sawP = true;
                    String para = elementText(p).trim();
                    if (para.length() > 0) paras.add(para);
                } else {
                    all.append(elementText(p));
                }
            } else if (event == XmlPullParser.TEXT) {
                String text = p.getText();
                if (text != null) all.append(text);
            }
        }
        if (sawP) {
            if (paras.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < paras.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(paras.get(i));
            }
            return sb.toString();
        }
        String flat = all.toString().trim();
        return flat.length() == 0 ? null : flat;
    }

    /** All character data inside the current element, tags discarded. Parser is on START. */
    private static String elementText(XmlPullParser p) throws Exception {
        int depth = 1;
        StringBuilder sb = new StringBuilder();
        while (depth > 0) {
            int event = p.next();
            if (event == XmlPullParser.END_DOCUMENT) break;
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT && depth > 0) {
                String text = p.getText();
                if (text != null) sb.append(text);
            }
        }
        return sb.toString();
    }

    /** Consumes the current element, including its end tag. Parser is on START. */
    private static void skipElement(XmlPullParser p) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int event = p.next();
            if (event == XmlPullParser.END_DOCUMENT) return;
            if (event == XmlPullParser.START_TAG) depth++;
            else if (event == XmlPullParser.END_TAG) depth--;
        }
    }

    private static String attr(XmlPullParser p, String local) {
        int n = p.getAttributeCount();
        for (int i = 0; i < n; i++) {
            if (local.equals(localName(p.getAttributeName(i)))) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }

    /** Strips a namespace prefix ({@code dc:creator} → {@code creator}). */
    private static String localName(String name) {
        if (name == null) return "";
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }
}
