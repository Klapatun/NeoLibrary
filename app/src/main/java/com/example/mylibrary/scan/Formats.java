package com.example.mylibrary.scan;

import java.util.Locale;

/**
 * Canonical list of supported document formats for Neo Reader 3.0 and helpers to
 * map a file to its canonical format id.
 *
 * The canonical ids are chosen to match Neo Reader 3.0's recognised extensions so
 * that a single, uniform list drives scanning, the filter spinner and the format
 * badge shown in the UI.
 */
public final class Formats {

    private Formats() {}

    /** Canonical format ids, ordered the same way Neo Reader lists them. */
    public static final String[] ALL = {
            "CHM", "DOC", "DOCX", "DJVU", "EPUB",
            "FB2", "FB2ZIP", "FB3", "HTML", "MOBI",
            "PDB", "PDF", "PRC", "RTF", "TXT"
    };

    /** Maps every supported extension to its canonical id. */
    private static final java.util.Map<String, String> BY_EXT = new java.util.HashMap<String, String>();

    static {
        BY_EXT.put("chm", "CHM");
        BY_EXT.put("doc", "DOC");
        BY_EXT.put("docx", "DOCX");
        BY_EXT.put("djvu", "DJVU");
        BY_EXT.put("epub", "EPUB");
        BY_EXT.put("fb2", "FB2");
        BY_EXT.put("fb2.zip", "FB2ZIP");
        BY_EXT.put("fb3", "FB3");
        BY_EXT.put("html", "HTML");
        BY_EXT.put("htm", "HTML");
        BY_EXT.put("mobi", "MOBI");
        BY_EXT.put("azw", "MOBI"); // AZW is the MOBI container
        BY_EXT.put("pdb", "PDB");
        BY_EXT.put("pdf", "PDF");
        BY_EXT.put("prc", "PRC");
        BY_EXT.put("rtf", "RTF");
        BY_EXT.put("txt", "TXT");
    }

    /**
     * Returns true if the given file name looks like a supported book, based on its
     * file extension (case-insensitive). {@code .fb2.zip} is handled as a compound
     * extension before the plain {@code .zip} check (and plainly, we do not treat a
     * bare {@code .zip} as a supported book).
     */
    public static boolean isSupported(String fileName) {
        String f = fileName.toLowerCase(Locale.US);
        if (f.endsWith(".fb2.zip")) return true; // compound FB2.ZIP container
        if (BY_EXT.containsKey(f)) return true;
        int dot = f.lastIndexOf('.');
        return dot >= 0 && BY_EXT.containsKey(f.substring(dot + 1));
    }

    /**
     * Returns the canonical format id for a file name, or {@code null} if the file
     * is not a recognised supported format. The compound {@code .fb2.zip} is matched
     * before the single-extension path.
     */
    public static String formatOf(String fileName) {
        String f = fileName.toLowerCase(Locale.US);
        if (f.endsWith(".fb2.zip")) return "FB2ZIP";
        // The full compound name takes priority: fb2.zip
        if (BY_EXT.containsKey(f)) return BY_EXT.get(f);
        int dot = f.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = f.substring(dot + 1);
        return BY_EXT.get(ext);
    }

    /**
     * The stage-2 enrichment cost tier of a format: the lower the number, the
     * cheaper extracting its in-file metadata and cover, and the earlier the
     * enricher should get to it. EPUB and FB2 are plain XML (fastest), FB2ZIP is
     * the same FB2 content wrapped in an archive, MOBI needs the heaviest parse
     * (PalmDB + MOBI + EXTH binary), and every remaining format comes last.
     *
     * @param formatId a canonical id from {@link #ALL} ({@code null} / unknown -> last)
     * @return the tier: 0 = EPUB/FB2, 1 = FB2ZIP, 2 = MOBI, 3 = everything else
     */
    public static int enrichmentPriority(String formatId) {
        if ("EPUB".equals(formatId) || "FB2".equals(formatId)) return 0;
        if ("FB2ZIP".equals(formatId)) return 1;
        if ("MOBI".equals(formatId)) return 2;
        return 3;
    }
}
