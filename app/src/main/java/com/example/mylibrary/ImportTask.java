package com.example.mylibrary;

import android.app.ProgressDialog;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.Catalog;
import com.example.mylibrary.meta.MetaEnricher;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.LibraryScanner;
import com.example.mylibrary.util.CoverCache;

import java.io.File;
import java.lang.ref.WeakReference;

/** Copies a picked document into the library and re-enriches the book. A separate
 *  file with a {@link WeakReference} for the same reason as {@link ScanTask}: an
 *  anonymous inner class would carry a synthetic strong reference to the Activity,
 *  and copying a big file can outlive the screen. Uses the app context for the
 *  background work (the SAF read grant is process-wide — any ContentResolver in
 *  the process can open the picked document) and holds the Activity and the
 *  progress dialog weakly for the UI follow-up. */
final class ImportTask extends AsyncTask<Void, Void, Boolean> {
    private final Uri uri;
    private final String displayName;
    private final File dest;
    private final Context appContext;
    private final BookDatabase db;
    private final Catalog catalog;
    private final WeakReference<ProgressDialog> dialog;
    private final WeakReference<MainActivity> self;

    ImportTask(Context appContext, Catalog catalog, BookDatabase db,
            Uri uri, String displayName, File dest, ProgressDialog pd,
            MainActivity host) {
        this.uri = uri;
        this.displayName = displayName;
        this.dest = dest;
        this.appContext = appContext;
        this.db = db;
        this.catalog = catalog;
        this.dialog = new WeakReference<ProgressDialog>(pd);
        this.self = new WeakReference<MainActivity>(host);
    }

    @Override protected Boolean doInBackground(Void... v) {
        try {
            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            java.io.InputStream in = appContext.getContentResolver().openInputStream(uri);
            java.io.OutputStream out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            Book b = LibraryScanner.scanSingle(dest);
            if (b != null) {
                long existing = db.getIdForPath(dest.getAbsolutePath());
                catalog.importFile(b, existing >= 0, existing);
                if (existing >= 0) {
                    // The import overwrote an existing file: drop the stale cover
                    // cache — enrichOne below refreshes it.
                    CoverCache.delete(appContext, dest.getAbsolutePath());
                }
                // Stage 2 for this single book — already off the UI thread.
                // The bytes were just replaced, so a foreign in-flight parse
                // must not be taken.
                MetaEnricher.enrichOne(appContext, db, b, false);
            }
            return b != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override protected void onPostExecute(Boolean ok) {
        ProgressDialog d = dialog.get();
        if (d != null) d.dismiss();
        MainActivity a = self.get();
        if (a == null || a.isFinishing()) return;
        a.onImportPosted(ok, displayName);
    }
}
