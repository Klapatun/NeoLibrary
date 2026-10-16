package com.example.mylibrary.meta;

import android.util.Log;
import android.util.Xml;

import com.example.mylibrary.scan.Formats;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads embedded metadata (title, author, publisher, description...) out of the
 * supported formats.
 *
 * <p>EPUB and FB2 have well-defined, easily editable metadata structures and are
 * fully parsed (FB2ZIP — a ZIP container around an FB2 document — is parsed
 * through its inner {@code .fb2} entry). MOBI/AZW is parsed read-only
 * (PalmDB + MOBI header + EXTH) for
 * title/author/publisher/description/language. Plain-text and other simple formats
 * are parsed best-effort for title/author. For the remaining container formats (CHM,
 * DjVu, PDB, PDF, DOC/DOCX, RTF, FB3...) we do not rewrite files, so catalog metadata
 * is stored in SQLite and the display title falls back to the file name.</p>
 *
 * <p>No third-party libraries are used: ZIP reading uses {@link java.util.zip}, XML
 * parsing uses the platform XmlPullParser, and MOBI is read straight off the bytes.</p>
 */
public final class MetaExtractor {

    private static final String TAG = "MetaExtractor";

    private MetaExtractor() {}

    /**
     * Attempt to read metadata from the given file. Returns a {@link MetaData} whose
     * {@link MetaData#found} flag tells whether anything usable was parsed; fields may
     * still be null/unset, in which case the caller should fall back to the file name.
     *
     * <p>Never throws: a broken file (corrupt archive, malformed XML, I/O error) is
     * logged with its cause and degrades to {@code found = false}, so one bad file can
     * never take the enricher down.</p>
     */
    public static MetaData extract(File file) {
        String format = Formats.formatOf(file.getName());
        if (format == null) format = "";
        try {
            if (format.equals("EPUB")) return extractEpub(file);
            if (format.equals("FB2")) return extractFb2(file);
            if (format.equals("FB2ZIP")) return extractFb2Zip(file);
            if (format.equals("MOBI")) return extractMobi(file);
            if (format.equals("TXT")) return extractText(file);
            if (format.equals("HTML")) return extractHtml(file);
        } catch (Exception e) {
            // A broken file must not take the enricher down: log the cause, fall
            // through -> not found (the file-name title is kept).
            Log.w(TAG, "Could not parse metadata of " + file, e);
        }
        return notFound(file);
    }

    // -------------------------------------------------------------------
    // EPUB
    // -------------------------------------------------------------------

    /**
     * EPUB is a ZIP whose root file (the OPF package document) is located via
     * {@code META-INF/container.xml}. The OPF is a Dublin-Core flavoured XML file.
     * One {@link ZipFile} session: the container entry is read, then the OPF entry.
     */
    private static MetaData extractEpub(File file) throws Exception {
        MetaData md = new MetaData();
        ZipFile zip = new ZipFile(file);
        try {
            String opfPath = opfPathFromContainer(zip);
            if (opfPath == null) return notFound(file);
            InputStream in = zip.getInputStream(zip.getEntry(opfPath));
            try {
                parseOpf(in, md);
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
        md.found = md.title != null && md.title.length() > 0;
        return md;
    }

    /**
     * The OPF package document's path from {@code META-INF/container.xml} on an
     * already-open archive — one entry read, no sequential scan (the rootfile
     * full-path attribute is namespace-agnostic in practice).
     *
     * <p>Package-private so the single-pass enricher parse shares it with the public
     * {@link #extract} path (the duplicated container lookup of the two extractors
     * lives here, once).</p>
     */
    static String opfPathFromContainer(ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            if (!e.getName().equalsIgnoreCase("META-INF/container.xml")) continue;
            InputStream in = zip.getInputStream(e);
            try {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                String xml = sb.toString();
                int idx = xml.indexOf("full-path");
                if (idx < 0) return null;
                int s = xml.indexOf('\"', idx);
                if (s < 0) return null;
                int en = xml.indexOf('\"', s + 1);
                if (en <= s + 1) return null;
                return xml.substring(s + 1, en);
            } finally {
                in.close();
            }
        }
        return null;
    }

    /**
     * Parses an OPF stream into a {@link MetaData} (DC title/creator/publisher/
     * description/language). Package-private so the single-pass enricher parse
     * shares it with the public {@link #extract} path.
     */
    static void parseOpf(InputStream in, MetaData md) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        int event;
        String tag = null;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (local.equals("creator") && md.author == null) {
                    md.author = firstText(p);
                    continue; // parser now positioned after </dc:creator>
                }
                tag = local;
            } else if (event == XmlPullParser.TEXT) {
                if (tag != null) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (text.length() == 0) continue;
                    if (tag.equals("title") && md.title == null) md.title = text;
                    else if (tag.equals("publisher") && md.publisher == null) md.publisher = text;
                    else if (tag.equals("description") && md.description == null) md.description = text;
                    else if (tag.equals("language") && md.language == null) md.language = text;
                }
            } else if (event == XmlPullParser.END_TAG) {
                tag = null;
            }
        }
    }

    // Reads the first text child of the current element (used for <dc:creator>).
    private static String firstText(XmlPullParser p) throws Exception {
        int event = p.next();
        StringBuilder sb = new StringBuilder();
        while (event != XmlPullParser.END_TAG && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) {
                sb.append(p.getText());
            } else if (event == XmlPullParser.START_TAG) {
                // recurse into nested (e.g. file-as) but only text matters
            }
            event = p.next();
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------
    // FB2
    // -------------------------------------------------------------------

    /** FB2 is UTF-8 XML with a &lt;description&gt; block containing title-info. */
    private static MetaData extractFb2(File file) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            return parseFb2Xml(in);
        } finally {
            in.close();
        }
    }

    /**
     * FB2ZIP (".fb2.zip") is a ZIP container whose payload is a plain FB2 document
     * (often alongside a loose cover image). The metadata lives in the inner FB2's
     * &lt;description&gt; block, so we pull the first {@code .fb2} entry out of the
     * archive and parse it exactly like a standalone FB2 file. One ZipFile session
     * (the central directory is scanned; only the .fb2 entry is decompressed).
     */
    private static MetaData extractFb2Zip(File file) throws Exception {
        ZipFile zip = new ZipFile(file);
        try {
            byte[] xml = CoverExtractor.readFb2EntryBytes(zip);
            if (xml == null) return notFound(file);
            return parseFb2Xml(new ByteArrayInputStream(xml));
        } finally {
            zip.close();
        }
    }

    /**
     * Parses a FB2 XML stream into a {@link MetaData}: title, author name parts
     * (first/middle/last/nickname, combined afterwards), publisher, annotation,
     * language and genre. Stops at the closing {@code description} tag (the body —
     * the bulk of a real file — is never parsed).
     *
     * <p>Package-private so the single-pass enricher parse (which feeds it the
     * batch-1 header region) shares it with the public {@link #extract} path.</p>
     */
    static MetaData parseFb2Xml(InputStream in) throws Exception {
        MetaData md = new MetaData();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        int event;
        String pending = null; // current leaf element we care about
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (local.equals("title")) pending = "title";
                else if (local.equals("first-name")) pending = "first";
                else if (local.equals("last-name")) pending = "last";
                else if (local.equals("middle-name")) pending = "middle";
                else if (local.equals("nickname")) pending = "nick";
                else if (local.equals("publisher")) pending = "publisher";
                else if (local.equals("annotation")) pending = "annotation";
                else if (local.equals("lang")) pending = "lang";
                else if (local.equals("genre")) pending = "genre";
            } else if (event == XmlPullParser.TEXT && pending != null) {
                String text = p.getText();
                if (text == null) text = "";
                text = text.trim();
                if (text.length() == 0) continue;
                if (pending.equals("title") && md.title == null) md.title = text;
                else if (pending.equals("first")) md.firstName = text;
                else if (pending.equals("middle")) md.middleName = text;
                else if (pending.equals("last")) md.lastName = text;
                else if (pending.equals("nick") && md.lastName == null) md.lastName = text;
                else if (pending.equals("publisher") && md.publisher == null) md.publisher = text;
                else if (pending.equals("annotation") && md.description == null) md.description = text;
                else if (pending.equals("lang") && md.language == null) md.language = text;
                else if (pending.equals("genre") && md.genre == null) md.genre = text;
            } else if (event == XmlPullParser.END_TAG) {
                // Every metadata element (and the cover's binary id) lives inside
                // <description>, which the FB2 XSD places before <body>: stop here —
                // the body (the bulk of a real file) is never parsed. A non-conformant
                // file without the closing tag simply parses to the end, as before.
                if (localName(p.getName()).equals("description")) break;
                pending = null;
            }
        }

        StringBuilder author = new StringBuilder();
        if (md.firstName != null) author.append(md.firstName).append(' ');
        if (md.middleName != null) author.append(md.middleName).append(' ');
        if (md.lastName != null) author.append(md.lastName);
        md.author = author.toString().trim();
        md.found = md.title != null && md.title.length() > 0;
        return md;
    }

    // -------------------------------------------------------------------
    // MOBI / AZW
    // -------------------------------------------------------------------

    /**
     * MOBI/AZW is a PalmDB container. The title is the book's full name stored in
     * record 0; the author, publisher, description and language live in the EXTH block.
     * {@link MobiParser} handles the binary layout; we just copy the results.
     */
    private static MetaData extractMobi(File file) throws Exception {
        MobiParser p = new MobiParser();
        try {
            if (!p.open(file)) return notFound(file);
            MetaData md = new MetaData();
            md.title = p.title;
            md.author = p.author;
            md.publisher = p.publisher;
            md.description = p.description;
            md.language = p.language;
            md.found = p.title != null && p.title.length() > 0;
            return md;
        } finally {
            p.close();
        }
    }

    // -------------------------------------------------------------------
    // TXT / HTML (best effort)
    // -------------------------------------------------------------------

    private static MetaData extractText(File file) throws Exception {
        MetaData md = new MetaData();
        // Use the file name minus extension as title. Underscores are rendered as
        // spaces so the background stage shows the same title the fast stage
        // (LibraryScanner.titleFromName) already put on screen.
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        md.title = name.replace('_', ' ');
        md.found = true;
        return md;
    }

    private static MetaData extractHtml(File file) throws Exception {
        MetaData md = new MetaData();
        // Grab the <title> element, if present and non-empty.
        java.io.BufferedReader r =
                new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("(?i)<title[^>]*>(.*?)</title>").matcher(line);
                if (m.find()) {
                    String t = m.group(1).trim();
                    if (t.length() > 0) {
                        md.title = t;
                        md.found = true;
                    }
                    break;
                }
            }
        } finally {
            r.close();
        }
        if (!md.found) md = extractText(file);
        return md;
    }

    // -------------------------------------------------------------------

    private static MetaData notFound(File file) {
        MetaData md = new MetaData();
        md.found = false;
        return md;
    }

    /** Strips a namespace prefix (e.g. "dc:creator" -> "creator"). */
    private static String localName(String name) {
        if (name == null) return "";
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }
}
