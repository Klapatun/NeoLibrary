package com.example.mylibrary.meta;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Read-only, dependency-free parser for MOBI/AZW files (the PalmDB container plus the
 * MOBI header and the optional EXTH block). It is shared by {@link MetaExtractor}
 * (title/author/publisher/description/language) and {@link CoverExtractor} (the cover
 * image record).
 *
 * <p>Every multi-byte field in this format is <b>big-endian</b>. Absolute file offsets:
 * <ul>
 *   <li>0x000 – 32-byte database name (a shortened, often filename-style title).</li>
 *   <li>0x03C – 4-byte type ("BOOK").</li>
 *   <li>0x040 – 4-byte creator ("MOBI").</li>
 *   <li>0x04C – 2-byte record count.</li>
 *   <li>0x04E – the record table, 8 bytes per record: a 4-byte offset (big-endian),
 *       a 1-byte attribute flag, a 3-byte value. The data of record {@code N} spans
 *       {@code [offset(N), offset(N+1))} (or to end-of-file for the last record).</li>
 * </ul>
 *
 * <p>Record 0 is the header record. Its first 16 bytes are the PalmDOC header, then the
 * MOBI header begins with the literal "MOBI" at +0x10. Key fields within record 0
 * (relative offsets, big-endian): +0x14 MOBI header length, +0x1C text code page
 * (65001 = UTF-8, else CP1252), +0x54/+0x58 offset/length of the book's full name (the
 * title), +0x6C first image record index, +0x80 flags (bit 0x40 = an EXTH block
 * follows). The EXTH block, when present, sits at +16 + MOBI header length and holds
 * the structured metadata: record 100 = author, 101 = publisher, 103 = description,
 * 524 = language, 201 = cover offset (added to the first image index to locate the
 * cover image record).</p>
 */
final class MobiParser {

    // Populated by {@link #open(File)} once the header (and any EXTH) is parsed.
    String title;
    String author;
    String publisher;
    String description;
    String language;

    /** Absolute record number of the cover image, or -1 when none was found. */
    int coverRecord = -1;

    /** Sanity cap on how much of record 0 we buffer (it is always small in practice). */
    private static final int MAX_RECORD0 = 4 * 1024 * 1024;
    /** Sanity cap on a single record we are willing to copy out (the cover image). */
    private static final int MAX_RECORD = 64 * 1024 * 1024;

    private RandomAccessFile raf;
    private long fileLength;
    private int nrecs;
    private byte[] rec0;
    private int codepage = 1252;

    /**
     * Opens the file and parses the PalmDB/MOBI/EXTH headers. Returns {@code true} when
     * the file looks like a MOBI/AZW book with a readable header (and the metadata fields
     * above are populated, some possibly null); {@code false} otherwise. The underlying
     * file stays open for a later {@link #readRecord(int)} call; the caller must
     * {@link #close()}.
     */
    boolean open(File file) throws IOException {
        raf = new RandomAccessFile(file, "r");
        fileLength = raf.length();
        if (fileLength < 78) {
            close();
            return false;
        }

        // Identity at 60:68 must be "BOOKMOBI" (plain books) or "TEXTREAD" (personal docs).
        raf.seek(60);
        byte[] id = new byte[8];
        readFully(id, id.length);
        String ident = new String(id, "US-ASCII").toUpperCase();
        if (!ident.equals("BOOKMOBI") && !ident.equals("TEXTREAD")) {
            close();
            return false;
        }

        raf.seek(76);
        nrecs = readU16();
        if (nrecs < 1) {
            close();
            return false;
        }

        // Record 0 is the header record; it ends where record 1 begins (or at EOF).
        int off0 = recordOffset(0);
        int off1 = recordOffset(1);
        if (off0 < 0 || off0 >= fileLength) {
            close();
            return false;
        }
        int rec0Len = (off1 > off0 && off1 <= fileLength) ? (off1 - off0) : (int) (fileLength - off0);
        if (rec0Len <= 0) {
            close();
            return false;
        }
        rec0 = new byte[Math.min(rec0Len, MAX_RECORD0)];
        raf.seek(off0);
        readFully(rec0, rec0.length);

        parseHeader();
        return true;
    }

    /**
     * Returns the raw bytes of record {@code n} (from its offset to the next record or
     * end-of-file), or {@code null} if the record is out of range.
     */
    byte[] readRecord(int n) throws IOException {
        if (raf == null || n < 0 || n >= nrecs) return null;
        int off = recordOffset(n);
        if (off < 0 || off >= fileLength) return null;
        int end = (n + 1 < nrecs) ? recordOffset(n + 1) : (int) Math.min(fileLength, Integer.MAX_VALUE);
        if (end <= off) return null;
        long len = end - off;
        if (len > MAX_RECORD) return null;
        byte[] out = new byte[(int) len];
        raf.seek(off);
        readFully(out, out.length);
        return out;
    }

    void close() {
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
            raf = null;
        }
    }

    // ------------------------------------------------------------------
    // header / EXTH parsing
    // ------------------------------------------------------------------

    private void parseHeader() {
        codepage = be32(rec0, 0x1C);
        int mobiHdrLen = be32(rec0, 0x14);
        int titleOffset = be32(rec0, 0x54);
        int titleLength = be32(rec0, 0x58);
        int firstImageIndex = be32(rec0, 0x6C);
        int exthFlag = be32(rec0, 0x80);

        // Title: the book's full name stored in record 0.
        if (titleLength > 0 && titleOffset >= 0 && titleOffset + titleLength <= rec0.length) {
            title = clean(decode(rec0, titleOffset, titleLength));
        }
        // Fallback: the PDB database name (first 32 bytes of the file).
        if (title == null) title = clean(readPdbName());

        boolean hasExth = (exthFlag & 0x40) != 0;
        if (hasExth && mobiHdrLen > 0) {
            int exthStart = 16 + mobiHdrLen;
            if (exthStart + 12 <= rec0.length && matches(rec0, exthStart, "EXTH")) {
                int nitems = be32(rec0, exthStart + 8);
                int pos = exthStart + 12;
                int coverOffset = -1;
                for (int i = 0; i < nitems; i++) {
                    if (pos + 8 > rec0.length) break;
                    int id = be32(rec0, pos);
                    int size = be32(rec0, pos + 4);
                    if (size < 8 || pos + size > rec0.length) break;
                    if (id == 100) author = clean(decode(rec0, pos + 8, size - 8));
                    else if (id == 101) publisher = clean(decode(rec0, pos + 8, size - 8));
                    else if (id == 103) description = clean(decode(rec0, pos + 8, size - 8));
                    else if (id == 524) language = clean(decode(rec0, pos + 8, size - 8));
                    else if (id == 201) {
                        int co = be32(rec0, pos + 8);
                        if (co != 0xFFFFFFFF) coverOffset = co;
                    }
                    pos += size;
                }
                if (coverOffset >= 0) coverRecord = firstImageIndex + coverOffset;
            }
        }
    }

    private String readPdbName() {
        // The name lives at the very start of the file; re-read it independently of rec0.
        byte[] name = new byte[32];
        RandomAccessFile in = raf;
        long old = -1;
        try {
            old = in.getFilePointer();
            in.seek(0);
            readFully(name, name.length);
        } catch (IOException ignored) {
            return null;
        } finally {
            try { if (old >= 0) in.seek(old); } catch (IOException ignored) {}
        }
        int end = 0;
        while (end < name.length && name[end] != 0) end++;
        if (end == 0) return null;
        return decode(name, 0, end);
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private String decode(byte[] b, int off, int len) {
        String s;
        try {
            s = new String(b, off, len, charsetName());
        } catch (Exception e) {
            try { s = new String(b, off, len, "ISO-8859-1"); }
            catch (Exception e2) { s = new String(b, off, len); }
        }
        return s;
    }

    /** The book's declared text code page: 65001 = UTF-8, anything else = CP1252. */
    private String charsetName() {
        return (codepage == 65001) ? "UTF-8" : "Cp1252";
    }

    /** Drops NUL bytes and surrounding whitespace; unescapes a few common entities. */
    private static String clean(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\0') sb.append(c);
        }
        s = sb.toString().trim();
        if (s.length() == 0) return null;
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return s;
    }

    private static int be32(byte[] a, int off) {
        return ((a[off] & 0xFF) << 24) | ((a[off + 1] & 0xFF) << 16)
             | ((a[off + 2] & 0xFF) << 8) | (a[off + 3] & 0xFF);
    }

    private static boolean matches(byte[] a, int off, String s) {
        int len = s.length();
        if (off + len > a.length) return false;
        for (int i = 0; i < len; i++) {
            if ((a[off + i] & 0xFF) != (s.charAt(i) & 0xFF)) return false;
        }
        return true;
    }

    private int recordOffset(int i) throws IOException {
        if (i < 0 || i >= nrecs) return -1;
        raf.seek(78L + (long) i * 8L);
        return readU32();
    }

    private int readU16() throws IOException {
        int b1 = raf.read() & 0xFF;
        int b2 = raf.read() & 0xFF;
        return (b1 << 8) | b2;
    }

    private int readU32() throws IOException {
        int b1 = raf.read() & 0xFF;
        int b2 = raf.read() & 0xFF;
        int b3 = raf.read() & 0xFF;
        int b4 = raf.read() & 0xFF;
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private void readFully(byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = raf.read(buf, off, len - off);
            if (r < 0) break;
            off += r;
        }
    }
}
