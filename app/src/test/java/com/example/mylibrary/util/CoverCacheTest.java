package com.example.mylibrary.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * Robolectric tests for {@link CoverCache} — the durable file cache of cover
 * bytes, including the delete/clear API that keeps the "covers" directory free
 * of orphan files when books are removed from the catalog. Run at {@code sdk = 19}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class CoverCacheTest {

    private static final byte[] COVER = {1, 2, 3, 4, 5};

    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        CoverCache.clear(app); // start every test from an empty cache
    }

    @Test
    public void saveThenLoadRoundTrips() {
        CoverCache.save(app, "/x/a.epub", COVER);

        byte[] loaded = CoverCache.load(app, "/x/a.epub");
        assertNotNull(loaded);
        assertArrayEquals(COVER, loaded);
    }

    @Test
    public void saveReplacesThePreviousEntry() {
        CoverCache.save(app, "/x/a.epub", new byte[]{1});
        CoverCache.save(app, "/x/a.epub", COVER);

        assertArrayEquals(COVER, CoverCache.load(app, "/x/a.epub"));
    }

    @Test
    public void loadReturnsNullWhenNothingIsCached() {
        assertNull(CoverCache.load(app, "/x/unknown.epub"));
    }

    @Test
    public void saveIgnoresEmptyOrNullBytes() {
        CoverCache.save(app, "/x/a.epub", new byte[]{});
        CoverCache.save(app, "/x/b.epub", null);

        assertFalse(CoverCache.fileFor(app, "/x/a.epub").exists());
        assertFalse(CoverCache.fileFor(app, "/x/b.epub").exists());
    }

    @Test
    public void saveRangeStoresExactlyTheRangeFromALargerBuffer() {
        // The MOBI in-place trim hands out a record array plus an image length:
        // only the image's bytes may reach the cache file, no copy of the array.
        byte[] record = {9, 1, 2, 3, 4, 5, 7, 8};
        CoverCache.save(app, "/x/a.mobi", record, 1, 5);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, CoverCache.load(app, "/x/a.mobi"));
    }

    @Test
    public void saveRangeRejectsInvalidRanges() {
        byte[] record = {1, 2, 3};
        CoverCache.save(app, "/x/a.epub", record, 0, 0);
        CoverCache.save(app, "/x/b.epub", record, -1, 2);
        CoverCache.save(app, "/x/c.epub", record, 1, 5); // off + len > length
        CoverCache.save(app, "/x/d.epub", null, 0, 1);

        assertFalse(CoverCache.fileFor(app, "/x/a.epub").exists());
        assertFalse(CoverCache.fileFor(app, "/x/b.epub").exists());
        assertFalse(CoverCache.fileFor(app, "/x/c.epub").exists());
        assertFalse(CoverCache.fileFor(app, "/x/d.epub").exists());
    }

    @Test
    public void saveWholeArrayDelegatesToTheRangeOverload() {
        // The 3-arg overload must keep working exactly as before (whole array).
        CoverCache.save(app, "/x/a.epub", COVER, 0, COVER.length);
        CoverCache.save(app, "/x/b.epub", COVER);

        assertArrayEquals(COVER, CoverCache.load(app, "/x/a.epub"));
        assertArrayEquals(COVER, CoverCache.load(app, "/x/b.epub"));
    }

    @Test
    public void deleteRemovesTheCachedCoverFile() {
        CoverCache.save(app, "/x/a.epub", COVER);
        File f = CoverCache.fileFor(app, "/x/a.epub");
        assertTrue(f.exists());

        CoverCache.delete(app, "/x/a.epub");

        assertFalse("cache file must be gone", f.exists());
        assertNull(CoverCache.load(app, "/x/a.epub"));
    }

    @Test
    public void deleteRemovesAHalfWrittenTmpLeftByAnInterruptedSave() throws Exception {
        File dir = new File(app.getExternalFilesDir("covers"),
                CoverCache.fileFor(app, "/x/a.epub").getName() + ".tmp");
        if (!dir.getParentFile().exists()) dir.getParentFile().mkdirs();
        assertTrue(dir.createNewFile());

        CoverCache.delete(app, "/x/a.epub");

        assertFalse("the orphan .tmp must be removed too", dir.exists());
    }

    @Test
    public void deleteOfAnUnknownPathIsANoOp() {
        // Must neither throw nor touch other cached entries.
        CoverCache.save(app, "/x/keep.epub", COVER);
        CoverCache.delete(app, "/x/gone.epub");

        assertNotNull(CoverCache.load(app, "/x/keep.epub"));
    }

    @Test
    public void clearRemovesEveryCachedCover() {
        CoverCache.save(app, "/x/a.epub", new byte[]{1});
        CoverCache.save(app, "/x/b.epub", new byte[]{2});

        CoverCache.clear(app);

        assertNull(CoverCache.load(app, "/x/a.epub"));
        assertNull(CoverCache.load(app, "/x/b.epub"));
    }

    @Test
    public void saveWorksAgainAfterClear() {
        CoverCache.save(app, "/x/a.epub", COVER);
        CoverCache.clear(app);
        CoverCache.save(app, "/x/a.epub", COVER);

        assertArrayEquals(COVER, CoverCache.load(app, "/x/a.epub"));
    }
}
