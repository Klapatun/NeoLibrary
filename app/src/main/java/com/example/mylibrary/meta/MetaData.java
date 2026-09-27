package com.example.mylibrary.meta;

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
    public String genre;
    public String documentId;
    // Split FB2 author name parts, plus combined convenience fields.
    public String firstName;
    public String middleName;
    public String lastName;
    public boolean found; // true if we successfully read any metadata from the file
}
