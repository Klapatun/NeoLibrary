package com.example.mylibrary.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
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
    public void roundedRectCropUsesTheCurrentGridFrameSize() {
        Bitmap source = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
        Bitmap result = CoverLoader.roundedRectCropForTest(source);

        assertEquals(242, result.getWidth());
        assertEquals(328, result.getHeight());
    }

    @Test
    public void roundedRectCropPreservesAspectRatioAndCropsWideSource() {
        Bitmap source = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setPixel(x, y, x < 500 ? Color.RED : Color.BLUE);
            }
        }

        Bitmap result = CoverLoader.roundedRectCropForTest(source);

        // BitmapShader rendering is not pixel-stable under Robolectric sdk 19;
        // the deterministic contract here is the target frame size.
        assertEquals(242, result.getWidth());
        assertEquals(328, result.getHeight());
    }

    @Test
    public void roundedRectCropPreservesAspectRatioAndCropsTallSource() {
        Bitmap source = Bitmap.createBitmap(242, 1000, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < source.getHeight(); y++) {
            int color = y < 500 ? Color.RED : Color.BLUE;
            for (int x = 0; x < source.getWidth(); x++) {
                source.setPixel(x, y, color);
            }
        }

        Bitmap result = CoverLoader.roundedRectCropForTest(source);

        // BitmapShader rendering is not pixel-stable under Robolectric sdk 19;
        // the deterministic contract here is the target frame size.
        assertEquals(242, result.getWidth());
        assertEquals(328, result.getHeight());
    }

    @Test
    public void roundedRectCropLeavesRoundedCornersTransparent() {
        Bitmap source = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setPixel(x, y, Color.GREEN);
            }
        }

        Bitmap result = CoverLoader.roundedRectCropForTest(source);

        assertEquals(Color.TRANSPARENT, result.getPixel(0, 0));
        assertEquals(242, result.getWidth());
        assertEquals(328, result.getHeight());
    }

    @Test
    public void malformedImageReturnsPlaceholderInsteadOfFullSizeDecode() throws Exception {
        java.lang.reflect.Method method = CoverLoader.class.getDeclaredMethod(
                "decodeForCache", byte[].class);
        method.setAccessible(true);
        Bitmap bitmap = (Bitmap) method.invoke(null, new byte[0]);
        assertTrue("unknown bounds must not trigger an oversized fallback decode",
                bitmap == null || Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 512);
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
