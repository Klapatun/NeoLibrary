package com.example.mylibrary.model;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Robolectric tests for the {@link Book} Parcelable contract — the model is passed
 * between activities, so a field-order slip in {@code writeToParcel}/the
 * constructor would corrupt the catalog UI.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class BookTest {

    @Test
    public void parcelRoundTripPreservesEveryField() {
        Book b = new Book();
        b.id = 42;
        b.path = "/storage/emulated/0/Books/Master and Margarita.epub";
        b.format = "EPUB";
        b.title = "Мастер и Маргарита";
        b.author = "М. А. Булгаков";
        b.publisher = "Издательство";
        b.description = "Roman in verse";
        b.series = "Классика";
        b.sizeBytes = 123456789L;
        b.exported = true;
        b.metaDone = true;
        b.userEdited = true;

        Book copy = roundTrip(b);

        assertEquals(42, copy.id);
        assertEquals(b.path, copy.path);
        assertEquals("EPUB", copy.format);
        assertEquals("Мастер и Маргарита", copy.title);
        assertEquals("М. А. Булгаков", copy.author);
        assertEquals("Издательство", copy.publisher);
        assertEquals("Roman in verse", copy.description);
        assertEquals("Классика", copy.series);
        assertEquals(123456789L, copy.sizeBytes);
        assertEquals(true, copy.exported);
        assertEquals("metaDone flag must round-trip", true, copy.metaDone);
        assertEquals("userEdited flag must round-trip", true, copy.userEdited);
    }

    @Test
    public void parcelRoundTripWithNullsAndDefaults() {
        Book b = new Book();
        // everything null / default except id and path

        Book copy = roundTrip(b);

        assertEquals(0, copy.id);
        assertEquals(null, copy.path);
        assertEquals(null, copy.format);
        assertEquals(null, copy.title);
        assertEquals(null, copy.author);
        assertEquals(null, copy.publisher);
        assertEquals(null, copy.description);
        assertEquals(null, copy.series);
        assertEquals(0, copy.sizeBytes);
        assertEquals(false, copy.exported);
    }

    private static Book roundTrip(Book b) {
        Parcel p = Parcel.obtain();
        b.writeToParcel(p, 0);
        p.setDataPosition(0);
        Book copy = Book.CREATOR.createFromParcel(p);
        p.recycle();
        return copy;
    }

    @Test
    public void initialAndDisplayFormatHelpers() {
        Book b = new Book();

        b.title = "привет";
        assertEquals("П", b.initial());

        b.title = "";
        assertEquals("?", b.initial());

        b.title = null;
        assertEquals("?", b.initial());

        b.title = "a";
        assertEquals("A", b.initial());

        b.format = "epub";
        assertEquals("EPUB", b.displayFormat());

        b.format = null;
        assertEquals("", b.displayFormat());
    }
}
