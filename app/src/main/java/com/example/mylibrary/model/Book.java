package com.example.mylibrary.model;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A single book entry in the library.
 *
 * The metadata shown/edited is a blend of two sources:
 *  - the <b>on-disk metadata</b> actually written into the file (for formats we can
 *    safely edit, currently EPUB and FB2);
 *  - the <b>catalog metadata</b> stored in our local SQLite database (title, author,
 *    publisher, description, tags, rating...), which is the fallback/cache for every
 *    other format.
 */
public class Book implements Parcelable {
    public long id;             // SQLite row id (-1 if not yet persisted)
    public String path;         // absolute filesystem path
    public String format;       // canonical format id, e.g. EPUB, FB2, PDF, TXT...
    public String title;        // display title
    public String author;       // display author
    public String publisher;    // publisher
    public String description;  // description / notes
    public String series;       // optional series name
    public long sizeBytes;
    public boolean exported;    // true if the on-disk file metadata was edited
    public boolean metaDone;    // true once the background stage extracted in-file metadata
    public boolean metaFailed;  // true if the last parse overran the enricher's time budget
                                // ("un-enriched": stays pending, taken last on the next rescan)
    public boolean userEdited;  // true once the user edited fields in the catalog

    public String displayFormat() {
        if (format == null) return "";
        return format.toUpperCase();
    }

    public String initial() {
        if (title == null || title.length() == 0) return "?";
        return String.valueOf(title.charAt(0)).toUpperCase();
    }

    // ---------------------------------------------------------------
    // Parcelable
    // ---------------------------------------------------------------

    public Book() {}

    protected Book(Parcel in) {
        id = in.readLong();
        path = in.readString();
        format = in.readString();
        title = in.readString();
        author = in.readString();
        publisher = in.readString();
        description = in.readString();
        series = in.readString();
        sizeBytes = in.readLong();
        exported = in.readByte() != 0;
        metaDone = in.readByte() != 0;
        metaFailed = in.readByte() != 0;
        userEdited = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(path);
        dest.writeString(format);
        dest.writeString(title);
        dest.writeString(author);
        dest.writeString(publisher);
        dest.writeString(description);
        dest.writeString(series);
        dest.writeLong(sizeBytes);
        dest.writeByte((byte) (exported ? 1 : 0));
        dest.writeByte((byte) (metaDone ? 1 : 0));
        dest.writeByte((byte) (metaFailed ? 1 : 0));
        dest.writeByte((byte) (userEdited ? 1 : 0));
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<Book> CREATOR = new Creator<Book>() {
        @Override public Book createFromParcel(Parcel in) { return new Book(in); }
        @Override public Book[] newArray(int size) { return new Book[size]; }
    };
}
