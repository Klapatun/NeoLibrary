package com.example.mylibrary;

import android.os.AsyncTask;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.Catalog;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.LibraryScanner;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The stage-1 scan task. A separate file with a {@link WeakReference} on purpose:
 *  an anonymous inner class would carry a synthetic strong reference to the
 *  Activity (javac emits it for every anonymous class inside an instance method,
 *  used or not), and a storage walk can run for minutes — the screen must be
 *  collectible while it is in flight. Holds the catalog for the writes, the
 *  database for the adopted-path read, and the Activity weakly for the UI
 *  follow-up: the catalog upserts complete even if the screen is gone by then. */
final class ScanTask extends AsyncTask<Void, Void, LibraryScanner.ScanResult> {
    private final Catalog catalog;
    private final BookDatabase db;
    private final File privateFilesDir;
    private final WeakReference<MainActivity> self;

    ScanTask(Catalog catalog, BookDatabase db, MainActivity host) {
        this.catalog = catalog;
        this.db = db;
        File dir = null;
        try {
            dir = host.getExternalFilesDir(null);
        } catch (Exception ignored) {}
        this.privateFilesDir = dir;
        this.self = new WeakReference<MainActivity>(host);
    }

    @Override protected LibraryScanner.ScanResult doInBackground(Void... v) {
        // Fast by design: file walk only, no in-file metadata extraction
        // (that is stage 2's job). Paths already recorded as adopted_from
        // are matched canonically, so the original file still sitting in
        // shared storage does not become a second book under another prefix.
        // The app files directory is not entered: private copies live there
        // and are already the catalog path.
        Set<String> skip = db.adoptedFromPaths();
        return LibraryScanner.scanDetailed(null, null, skip, privateFilesDir);
    }

    @Override protected void onPostExecute(LibraryScanner.ScanResult result) {
        List<Book> found = result == null ? null : result.books;
        MainActivity a = self.get();
        boolean screenAlive = a != null && !a.isFinishing();
        if (screenAlive) a.onScanProgressDone();
        // Do NOT clear the whole table here: upsertBasic() already keeps
        // metadata, enrichment state and last_read timestamps in sync. The
        // catalog work completes even if the screen is gone — the upserts are
        // the scan's actual result (the next launch only re-binds the UI).
        // The rows go in as batch commits (groups of
        // BookDatabase.BATCH_SIZE rows per transaction), not one autocommit
        // per book.
        if (found != null) {
            catalog.saveScanBatch(found);
            if (result != null) {
                for (LibraryScanner.RootScanResult root : result.roots) {
                    if (root.complete) {
                        Set<String> paths = new HashSet<String>();
                        for (Book book : root.books) paths.add(book.path);
                        catalog.removeMissing(root.rootPath, paths);
                    }
                }
            }
        }
        if (screenAlive) a.onScanPosted(found);
    }
}
