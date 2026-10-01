package com.example.mylibrary.meta;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverCache;

import java.io.File;
import java.util.List;

/**
 * Stage 2 of library loading: the background process that fills in what stage 1
 * (the fast file scan) deliberately left empty — the in-file metadata
 * (title/author/publisher/...) and the cover image.
 *
 * <p>Works book by book, fully off the UI thread: for every catalog row with
 * {@code meta_done = 0} it runs {@link MetaExtractor} and (for formats that can
 * carry a cover) {@link CoverExtractor}, persists the result through
 * {@link BookDatabase#updateMetadata} (which preserves {@code last_read} and never
 * clobbers user edits) and stores the cover bytes in {@link CoverCache}. After each
 * batch it calls {@code notifyChange} so the UI's {@code CursorLoader} picks the
 * new values up automatically — the user has been able to see and interact with
 * the whole library since stage 1 finished.</p>
 *
 * <p>{@link #enrichOne} is the single-book variant used by the import flow and by
 * the detail screen's fast path; it is safe to call from any background thread and
 * is idempotent (a book whose metadata was already extracted simply gets the same
 * values written again).</p>
 */
public final class MetaEnricher {

    /** Progress callback; every method is invoked on the main thread. */
    public interface OnProgress {
        /** Called after each (throttled) batch. {@code done} books out of {@code total}. */
        void onProgress(int done, int total);

        /** Called when the whole queue is exhausted (or the worker was cancelled). */
        void onFinished();
    }

    private static final int BATCH_NOTIFY_EVERY = 5;
    private static final long PROGRESS_MIN_INTERVAL_MS = 150L;

    private static volatile AsyncTask<Void, Void, Void> worker;

    private MetaEnricher() {}

    /**
     * Starts (or restarts) the background worker over the full "not yet enriched"
     * queue. Any previously running worker is cancelled first, so a rescan simply
     * restarts the stage.
     */
    public static void start(Context app, BookDatabase db, OnProgress listener) {
        cancel();
        final Context appContext = app.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        worker = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                List<Book> pending = db.needMeta();
                int total = pending.size();
                int done = 0;
                long lastProgress = 0;
                for (final Book book : pending) {
                    if (isCancelled()) break;
                    enrichOneBook(appContext, db, book);
                    done++;
                    long now = System.currentTimeMillis();
                    if (listener != null
                            && (done % BATCH_NOTIFY_EVERY == 0 || done == total || now - lastProgress > PROGRESS_MIN_INTERVAL_MS)) {
                        lastProgress = now;
                        final int d = done, t = total;
                        main.post(new Runnable() {
                            @Override public void run() {
                                listener.onProgress(d, t);
                            }
                        });
                    }
                    if (done % BATCH_NOTIFY_EVERY == 0 || done == total) {
                        appContext.getContentResolver()
                                .notifyChange(BookProvider.CONTENT_URI, null);
                    }
                }
                // Final notify even when the queue was empty or we broke early.
                appContext.getContentResolver().notifyChange(BookProvider.CONTENT_URI, null);
                if (listener != null) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            listener.onFinished();
                        }
                    });
                }
                return null;
            }
        }.execute();
    }

    /**
     * Extracts metadata (and the cover, if the format can carry one) for a single
     * book and persists the result. Call from a background thread; idempotent.
     */
    public static void enrichOne(Context app, BookDatabase db, Book book) {
        enrichOneBook(app.getApplicationContext(), db, book);
    }

    /** Stops the running worker (idempotent). Call from the UI lifecycle (onDestroy). */
    public static void cancel() {
        AsyncTask<Void, Void, Void> w = worker;
        if (w != null) w.cancel(false);
        worker = null;
    }

    // ------------------------------------------------------------------

    private static void enrichOneBook(Context app, BookDatabase db, Book book) {
        File f = new File(book.path);
        boolean readable = f.isFile();
        MetaData md = readable ? MetaExtractor.extract(f) : new MetaData();
        db.updateMetadata(book.id, md, readable);
        if (readable && CoverExtractor.canHaveCover(book.format)) {
            byte[] cover = CoverExtractor.extract(f);
            if (cover != null && cover.length > 0) {
                CoverCache.save(app, book.path, cover);
            }
        }
    }
}
