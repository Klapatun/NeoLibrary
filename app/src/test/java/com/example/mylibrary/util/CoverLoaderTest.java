package com.example.mylibrary.util;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.widget.ImageView;

import com.example.mylibrary.model.Book;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Robolectric tests for {@link CoverLoader}'s in-flight dedup: while a cover is
 * decoding, a second {@code load()} of the same key must join the in-flight task
 * (one decode shared by every tile) instead of spawning a second one. The decode
 * itself runs against a non-existent file, so it finishes with no cover — only the
 * task-creation counting is under test. Run at {@code sdk = 19}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class CoverLoaderTest {

    private static Book book(String path, String format) {
        Book b = new Book();
        b.path = path;
        b.format = format;
        b.title = "Book";
        return b;
    }

    @Test
    public void twoLoadsWithTheSameKeySpawnOnlyOneInFlightTask() {
        Context app = RuntimeEnvironment.getApplication();
        Book b = book("/x/same.epub", "EPUB");
        ImageView iv1 = new ImageView(app);
        ImageView iv2 = new ImageView(app);

        int before = CoverLoader.spawnedTasks;
        CoverLoader.load(b, iv1, null, CoverLoader.Shape.ROUNDED_RECT);
        CoverLoader.load(b, iv2, null, CoverLoader.Shape.ROUNDED_RECT);

        assertEquals("a second load of the same key must JOIN the in-flight task",
                before + 1, CoverLoader.spawnedTasks);
    }

    @Test
    public void loadsOfDifferentBooksSpawnSeparateTasks() {
        Context app = RuntimeEnvironment.getApplication();
        Book b1 = book("/x/one.epub", "EPUB");
        Book b2 = book("/x/two.epub", "EPUB");

        int before = CoverLoader.spawnedTasks;
        CoverLoader.load(b1, new ImageView(app), null, CoverLoader.Shape.ROUNDED_RECT);
        CoverLoader.load(b2, new ImageView(app), null, CoverLoader.Shape.ROUNDED_RECT);

        assertEquals("different keys need their own decodes",
                before + 2, CoverLoader.spawnedTasks);
    }

    @Test
    public void loadsOfDifferentShapesOfTheSameBookSpawnSeparateTasks() {
        // The in-flight key includes the shape: the round list badge and the grid
        // tile of the same book decode separately (different bitmaps).
        Context app = RuntimeEnvironment.getApplication();
        Book b = book("/x/both.mobi", "MOBI");

        int before = CoverLoader.spawnedTasks;
        CoverLoader.load(b, new ImageView(app), null, CoverLoader.Shape.CIRCLE);
        CoverLoader.load(b, new ImageView(app), null, CoverLoader.Shape.ROUNDED_RECT);

        assertEquals("CIRCLE and ROUNDED_RECT of one book are different keys",
                before + 2, CoverLoader.spawnedTasks);
    }
}
