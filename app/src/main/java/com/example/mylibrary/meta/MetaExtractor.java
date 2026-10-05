package com.example.mylibrary.meta;

import android.util.Log;
import android.util.Xml;

import com.example.mylibrary.scan.Formats;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
     */
    private static MetaData extractEpub(File file) throws Exception {
        MetaData md = new MetaData();
        String opfPath = findOpfPath(file);
        if (opfPath == null) return notFound(file);

        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(opfPath)) {
                    parseOpf(zip, md);
                    break;
                }
            }
        } finally {
            zip.close();
        }
        md.found = md.title != null && md.title.length() > 0;
        return md;
    }

    private static String findOpfPath(File file) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equalsIgnoreCase("META-INF/container.xml")) {
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zip.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    String xml = sb.toString();
                    // rootfile full-path attribute is namespace-agnostic in practice
                    int idx = xml.indexOf("full-path");
                    if (idx < 0) return null;
                    int s = xml.indexOf('\"', idx);
                    int en = xml.indexOf('\"', s + 1);
                    return xml.substring(s + 1, en);
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    private static void parseOpf(InputStream in, MetaData md) throws Exception {
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
     * archive and parse it exactly like a standalone FB2 file.
     */
    private static MetaData extractFb2Zip(File file) throws Exception {
        byte[] xml = readFb2Entry(file);
        if (xml == null) return notFound(file);
        return parseFb2Xml(new ByteArrayInputStream(xml));
    }

    /**
     * Parses a FB2 XML stream into a {@link MetaData}: title, author name parts
     * (first/middle/last/nickname, combined afterwards), publisher, annotation,
     * language and genre.
     */
    private static MetaData parseFb2Xml(InputStream in) throws Exception {
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

    /**
     * Returns the bytes of the first {@code .fb2} file entry in the given ZIP archive
     * (entry names matched case-insensitively, so a book tucked into a subfolder is
     * found too), or {@code null} if the archive contains no FB2 document.
     */
    private static byte[] readFb2Entry(File file) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".fb2")) {
                    return readEntryBytes(zip);
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    /** Reads the current (already-opened) zip entry into a byte array. */
    private static byte[] readEntryBytes(ZipInputStream zip) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zip.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
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
