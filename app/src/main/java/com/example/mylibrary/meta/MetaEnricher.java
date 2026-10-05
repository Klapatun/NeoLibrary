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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * <p><b>Time budget.</b> Each file is parsed on a throwaway thread with a deadline
 * of {@link #DEFAULT_EXTRACT_TIMEOUT_MS} (2 minutes) for the whole per-file parse
 * (metadata + cover). A parse that overruns it is abandoned and the book is marked
 * "un-enriched" via {@link BookDatabase#markMetaFailed}: it stays in the queue
 * ({@code meta_done} remains 0, so every rescan retries it) and is ordered last in
 * the queue ({@code needMeta()} sorts {@code meta_failed} last), so one pathological
 * file can never block the rest of the library. The abandoned thread is harmless —
 * it is read-only, its result is discarded, and it terminates on its own when the
 * file I/O completes (Java cannot kill a thread, so it is simply let go).</p>
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

    /** Per-file parse time budget: 2 minutes for the whole (metadata + cover) parse. */
    static final long DEFAULT_EXTRACT_TIMEOUT_MS = 2 * 60 * 1000L;

    /** The enforced deadline in milliseconds. Package-private so tests can shrink
     *  it (and restore it) without waiting real minutes. */
    static volatile long extractTimeoutMs = DEFAULT_EXTRACT_TIMEOUT_MS;

    /** The timed unit of work for one book: fills {@link Parsed} with the in-file
     *  metadata and (when the format can carry a cover) the cover bytes. Runs on a
     *  throwaway thread so the caller can enforce {@link #extractTimeoutMs}.
     *  Package-private so tests may substitute a controllable double for the real
     *  extractors (the default implementation is the production one). */
    interface ParseTask {
        void parse(File file, String format, Parsed out);
    }

    /** Result of {@link ParseTask#parse}: the extracted metadata (never null — a
     *  failed parse yields a {@code found = false} value) and the cover bytes
     *  (null when the format cannot carry a cover or none was found). */
    static final class Parsed {
        MetaData meta;
        byte[] cover;
    }

    /** The default {@link ParseTask}: the real extractors. */
    static volatile ParseTask parseTask = new ParseTask() {
        @Override
        public void parse(File file, String format, Parsed out) {
            out.meta = MetaExtractor.extract(file);
            if (CoverExtractor.canHaveCover(format)) {
                out.cover = CoverExtractor.extract(file);
            }
        }
    };

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
        if (!f.isFile()) {
            // File is gone: keep the file-name title and drain the queue slot.
            db.updateMetadata(book.id, new MetaData(), false);
            return;
        }

        // Parse on a throwaway thread with a time budget (extractTimeoutMs).
        // The worker thread waits at most that long; if the parse is still running
        // it is abandoned and the book is marked "un-enriched" so the pass moves on
        // to the next file instead of stalling on it.
        final Parsed parsed = new Parsed();
        final CountDownLatch parseDone = new CountDownLatch(1);
        Thread parseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    parseTask.parse(f, book.format, parsed);
                } finally {
                    parseDone.countDown();
                }
            }
        }, "meta-extract-" + book.id);
        parseThread.start();

        boolean finished;
        try {
            finished = parseDone.await(extractTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The worker was cancelled while waiting: leave the book pending
            // (nothing written) for the next start; the parse thread is let go.
            return;
        }
        if (!finished) {
            // Timed out. The book stays in the stage-2 queue (meta_done = 0) and is
            // flagged so every rescan takes it last. The abandoned thread may still
            // be chugging away, but it is read-only and its result is discarded —
            // it terminates on its own when the file I/O completes.
            db.markMetaFailed(book.id);
            return;
        }

        db.updateMetadata(book.id, parsed.meta, true);
        byte[] cover = parsed.cover;
        if (cover != null && cover.length > 0) {
            CoverCache.save(app, book.path, cover);
        }
    }
}
