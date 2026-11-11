package com.example.mylibrary.meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Plain container of metadata read from (or to be written to) a book file.
 */
public class MetaData {
    public String title;
    public String author;
    public String publisher;
    public String description;
    public String series;
    public String language;
    /** Null means the caller did not pass genres (file write leaves subjects alone). */
    public ArrayList<String> genres;
    public String documentId;
    // Split FB2 author name parts, plus combined convenience fields.
    public String firstName;
    public String middleName;
    public String lastName;
    public boolean found; // true if we successfully read any metadata from the file

    /**
     * Normalizes a genre list for catalog storage: trim, drop blanks,
     * {@link Locale#ROOT} lower case, de-duplicate preserving first occurrence.
     * A null input yields an empty list (never null).
     */
    public static ArrayList<String> normalizeGenres(List<String> raw) {
        ArrayList<String> out = new ArrayList<String>();
        if (raw == null) return out;
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i < raw.size(); i++) {
            String g = raw.get(i);
            if (g == null) continue;
            g = g.trim();
            if (g.length() == 0) continue;
            g = g.toLowerCase(Locale.ROOT);
            if (seen.contains(g)) continue;
            seen.add(g);
            out.add(g);
        }
        return out;
    }
}
