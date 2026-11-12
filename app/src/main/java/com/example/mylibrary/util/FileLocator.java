package com.example.mylibrary.util;

import com.example.mylibrary.db.BookDatabase;

import java.io.File;
import java.util.List;

/**
 * Answers whether an edition's bytes are on disk before {@link Openers} is
 * asked to launch a viewer. Reads {@code files} through {@link BookDatabase}
 * and {@link File#exists()}. Does not write the catalog and does not start a
 * download.
 */
public final class FileLocator {

    private static final String AVAILABILITY_LOCAL = "local";
    private static final String AVAILABILITY_DOWNLOADING = "downloading";
    private static final String AVAILABILITY_FAILED = "failed";

    private FileLocator() {}

    /** One {@code files} row by its {@code _id}. A missing row is {@link LocateResult#ABSENT}. */
    public static LocateResult locateFile(BookDatabase db, long fileId) {
        return decide(db.getFileEdition(fileId));
    }

    /**
     * The edition a screen should open for this work. One edition is
     * {@link #locateFile}. Several: the first (by {@code files._id}) whose
     * availability is {@code local} and whose file is still on disk. If none
     * is ready, a {@code downloading} edition wins over {@code failed}, and
     * {@code failed} wins over {@code absent}, so a later download is not
     * reported as a missing file. When every edition is absent, the result is
     * the smallest {@code files._id}.
     */
    public static LocateResult locateWork(BookDatabase db, long workId) {
        List<BookDatabase.FileEdition> editions = db.listFileEditions(workId);
        if (editions.isEmpty()) return absent();
        for (int i = 0; i < editions.size(); i++) {
            BookDatabase.FileEdition edition = editions.get(i);
            File onDisk = localFileOnDisk(edition);
            if (onDisk != null) {
                return new LocateResult(LocateResult.READY, onDisk);
            }
        }
        LocateResult failed = null;
        for (int i = 0; i < editions.size(); i++) {
            LocateResult decided = decide(editions.get(i));
            if (decided.status == LocateResult.DOWNLOADING) return decided;
            if (failed == null && decided.status == LocateResult.FAILED) {
                failed = decided;
            }
        }
        if (failed != null) return failed;
        return decide(editions.get(0));
    }

    private static LocateResult decide(BookDatabase.FileEdition edition) {
        if (edition == null) return absent();
        if (AVAILABILITY_DOWNLOADING.equals(edition.availability)) {
            return new LocateResult(LocateResult.DOWNLOADING, null);
        }
        if (AVAILABILITY_FAILED.equals(edition.availability)) {
            return new LocateResult(LocateResult.FAILED, null);
        }
        File onDisk = fileOnDisk(edition.path);
        if (onDisk == null) return absent();
        return new LocateResult(LocateResult.READY, onDisk);
    }

    /** A {@code local} edition whose path still names a file, or null. */
    private static File localFileOnDisk(BookDatabase.FileEdition edition) {
        if (edition == null || !AVAILABILITY_LOCAL.equals(edition.availability)) return null;
        return fileOnDisk(edition.path);
    }

    private static File fileOnDisk(String path) {
        if (path == null || path.length() == 0) return null;
        File file = new File(path);
        if (!file.exists()) return null;
        return file;
    }

    private static LocateResult absent() {
        return new LocateResult(LocateResult.ABSENT, null);
    }
}
