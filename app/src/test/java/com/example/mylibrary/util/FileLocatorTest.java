package com.example.mylibrary.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.testutil.TestFixtures;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;

/**
 * Unit tests for {@link FileLocator}: whether an edition's bytes are on disk and
 * readable, versus absent, still downloading, or failed — without writing the DB
 * or starting a download. Run at {@code sdk = 19}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class FileLocatorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context context;
    private BookDatabase db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("library.db");
        db = new BookDatabase(context);
    }

    private static Book book(String path, String format, String title) {
        Book b = new Book();
        b.path = path;
        b.format = format;
        b.title = title;
        b.author = "Author";
        b.sizeBytes = 100;
        b.genres = new ArrayList<String>();
        return b;
    }

    private long soleFileId(long workId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id FROM files WHERE work_id=? ORDER BY _id ASC",
                new String[]{String.valueOf(workId)});
        try {
            assertTrue("work must have at least one file row", c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private void setAvailability(long fileId, String availability) {
        db.getWritableDatabase().execSQL(
                "UPDATE files SET availability='" + availability + "' WHERE _id=" + fileId);
    }

    private void clearPath(long fileId) {
        db.getWritableDatabase().execSQL(
                "UPDATE files SET path=NULL WHERE _id=" + fileId);
    }

    @Test
    public void existingLocalFileIsReady() throws Exception {
        File onDisk = new File(folder.getRoot(), "ready.txt");
        TestFixtures.writeText(onDisk, "body\n");
        long workId = db.upsertBasic(book(onDisk.getAbsolutePath(), "TXT", "Ready"));
        long fileId = soleFileId(workId);

        LocateResult r = FileLocator.locateFile(db, fileId);

        assertEquals(LocateResult.READY, r.status);
        assertNotNull(r.file);
        assertEquals(onDisk.getAbsolutePath(), r.file.getAbsolutePath());
        assertTrue(r.file.exists());
    }

    @Test
    public void nullPathIsAbsent() {
        long workId = db.upsertBasic(book("/x/placeholder.txt", "TXT", "No path"));
        long fileId = soleFileId(workId);
        clearPath(fileId);

        LocateResult r = FileLocator.locateFile(db, fileId);

        assertEquals(LocateResult.ABSENT, r.status);
    }

    @Test
    public void missingPathOnDiskIsAbsent() throws Exception {
        File onDisk = new File(folder.getRoot(), "vanish.txt");
        TestFixtures.writeText(onDisk, "gone soon\n");
        long workId = db.upsertBasic(book(onDisk.getAbsolutePath(), "TXT", "Vanish"));
        long fileId = soleFileId(workId);
        assertTrue(onDisk.delete());

        LocateResult r = FileLocator.locateFile(db, fileId);

        assertEquals(LocateResult.ABSENT, r.status);
    }

    @Test
    public void downloadingWinsOverExistingFile() throws Exception {
        File onDisk = new File(folder.getRoot(), "busy.txt");
        TestFixtures.writeText(onDisk, "still on disk\n");
        long workId = db.upsertBasic(book(onDisk.getAbsolutePath(), "TXT", "Busy"));
        long fileId = soleFileId(workId);
        setAvailability(fileId, "downloading");

        LocateResult r = FileLocator.locateFile(db, fileId);

        assertEquals(LocateResult.DOWNLOADING, r.status);
        assertNull(r.file);
    }

    @Test
    public void failedStatus() {
        long workId = db.upsertBasic(book("/x/never.arrived.epub", "EPUB", "Failed"));
        long fileId = soleFileId(workId);
        clearPath(fileId);
        setAvailability(fileId, "failed");

        LocateResult r = FileLocator.locateFile(db, fileId);

        assertEquals(LocateResult.FAILED, r.status);
        assertNull(r.file);
    }

    @Test
    public void locateWorkPrefersExistingLocalEdition() throws Exception {
        File local = new File(folder.getRoot(), "dual-local.txt");
        TestFixtures.writeText(local, "local bytes\n");
        long workId = db.upsertBasic(book(local.getAbsolutePath(), "TXT", "Dual"));
        SQLiteDatabase raw = db.getWritableDatabase();
        raw.execSQL("INSERT INTO files (work_id, format, path, availability) VALUES ("
                + workId + ", 'PDF', NULL, 'remote_only')");

        LocateResult r = FileLocator.locateWork(db, workId);

        assertEquals(LocateResult.READY, r.status);
        assertNotNull(r.file);
        assertEquals(local.getAbsolutePath(), r.file.getAbsolutePath());
    }

    @Test
    public void locateWorkWithNoReadyEditionReturnsFirstById() {
        long workId = db.upsertBasic(book("/x/first.epub", "EPUB", "Remote"));
        long firstId = soleFileId(workId);
        clearPath(firstId);
        setAvailability(firstId, "remote_only");

        SQLiteDatabase raw = db.getWritableDatabase();
        raw.execSQL("INSERT INTO files (work_id, format, path, availability) VALUES ("
                + workId + ", 'PDF', NULL, 'remote_only')");

        long secondId;
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id FROM files WHERE work_id=? ORDER BY _id ASC",
                new String[]{String.valueOf(workId)});
        try {
            assertTrue(c.moveToFirst());
            firstId = c.getLong(0);
            assertTrue(c.moveToNext());
            secondId = c.getLong(0);
        } finally {
            c.close();
        }

        LocateResult expected = FileLocator.locateFile(db, firstId);
        LocateResult byWork = FileLocator.locateWork(db, workId);

        assertEquals(LocateResult.ABSENT, expected.status);
        assertEquals(expected.status, byWork.status);
        assertNull(byWork.file);
        assertTrue("smaller files._id must be used", secondId > firstId);
    }

    @Test
    public void locateWorkSurfacesDownloadingWhenEarlierEditionIsAbsent() {
        long workId = db.upsertBasic(book("/x/first-absent.epub", "EPUB", "Split"));
        long firstId = soleFileId(workId);
        clearPath(firstId);
        setAvailability(firstId, "remote_only");

        db.getWritableDatabase().execSQL(
                "INSERT INTO files (work_id, format, path, availability) VALUES ("
                        + workId + ", 'TXT', NULL, 'downloading')");

        LocateResult r = FileLocator.locateWork(db, workId);

        assertEquals(LocateResult.DOWNLOADING, r.status);
        assertNull(r.file);
    }

    @Test
    public void locateWorkSurfacesFailedWhenEarlierEditionIsAbsent() {
        long workId = db.upsertBasic(book("/x/first-absent.epub", "EPUB", "Split failed"));
        long firstId = soleFileId(workId);
        clearPath(firstId);
        setAvailability(firstId, "remote_only");

        db.getWritableDatabase().execSQL(
                "INSERT INTO files (work_id, format, path, availability) VALUES ("
                        + workId + ", 'PDF', NULL, 'failed')");

        LocateResult r = FileLocator.locateWork(db, workId);

        assertEquals(LocateResult.FAILED, r.status);
        assertNull(r.file);
    }
}
