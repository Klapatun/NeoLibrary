package com.example.mylibrary.meta;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.Formats;
import com.example.mylibrary.util.CoverCache;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Comparator;
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
 * <p><b>Order of the pass.</b> Right after the stage-1 scan the queue is split by
 * format type and worked in order of enrichment cost
 * ({@link Formats#enrichmentPriority}): the cheapest formats first (EPUB and FB2 —
 * plain XML), then FB2ZIP (FB2 wrapped in an archive), then the heavy MOBI binary
 * parses, and the remaining formats last. The "un-enriched" books of the previous
 * paragraph keep the absolute last place, so the user sees the cheap wins (titles,
 * covers) as early as possible while one slow file can still never hold the queue
 * up.</p>
 *
 * <p><b>Error containment.</b> Every per-book failure is logged and contained: a parse
 * that throws (corrupt file, I/O error) keeps the file-name title and marks the book
 * done (the project's no-retry-loop rule for failed extractions), a cover-cache write
 * that fails is logged and skipped (the metadata is already persisted), and a database
 * error on one book can never kill the worker loop or the import/detail fast paths —
 * the pass always moves on to the next book.</p>
 *
 * <p>{@link #enrichOne} is the single-book variant used by the import flow and by
 * the detail screen's fast path; it is safe to call from any background thread,
 * idempotent (a book whose metadata was already extracted simply gets the same
 * values written again) and exception-safe (parse/persist errors are contained and
 * logged, never rethrown).</p>
 */
public final class MetaEnricher {

    private static final String TAG = "MetaEnricher";

    /** Progress callback; every method is invoked on the main thread.
     *  <b>Never implement this with a long-lived Activity</b>: the worker (and its
     *  posted callbacks) only hold the listener WEAKLY, so it must be an inner class
     *  of the screen that started the pass (or a dedicated, short-lived object) —
     *  see {@link #start}. */
    public interface OnProgress {
        /** Called after each (throttled) batch. {@code done} books out of {@code total}. */
        void onProgress(int done, int total);

        /** Called when the whole queue is exhausted. NOT called after a cancel:
         *  by then the screen that started the pass is gone and its callback dead. */
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
        // The listener is (in production) an inner class of the screen that started
        // the pass; cancel() comes from that screen's onDestroy, so the worker (which
        // can run on for minutes) and its posted callbacks must NOT hold the listener
        // strongly — a dead screen's Activity would be pinned. It is referenced
        // weakly and re-resolved at each use; a gone screen is simply skipped.
        final WeakReference<OnProgress> listenerRef =
                new WeakReference<OnProgress>(listener);
        worker = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                List<Book> pending = db.needMeta();
                // Work the queue cheapest-first (EPUB/FB2 -> FB2ZIP -> MOBI -> the
                // rest); the un-enriched (timed-out) books stay last of all.
                orderQueueByCost(pending);
                int total = pending.size();
                int done = 0;
                long lastProgress = 0;
                for (final Book book : pending) {
                    if (isCancelled()) break;
                    enrichOneBook(appContext, db, book);
                    done++;
                    long now = System.currentTimeMillis();
                    if (listenerRef.get() != null
                            && (done % BATCH_NOTIFY_EVERY == 0 || done == total || now - lastProgress > PROGRESS_MIN_INTERVAL_MS)) {
                        lastProgress = now;
                        final int d = done, t = total;
                        // The runnable re-resolves the (weak) listener at fire time,
                        // so even the main-queue never pins a dead screen's callback.
                        main.post(new Runnable() {
                            @Override public void run() {
                                OnProgress l = listenerRef.get();
                                if (l != null) l.onProgress(d, t);
                            }
                        });
                    }
                    if (done % BATCH_NOTIFY_EVERY == 0 || done == total) {
                        appContext.getContentResolver()
                                .notifyChange(BookProvider.CONTENT_URI, null);
                    }
                }
                // Final notify on a NORMAL finish (empty queue or last batch). After a
                // cancel the screen is gone (cancel() comes from onDestroy / a
                // "Clear"): the listener may be dead and no one is listening, so
                // neither the notify nor the onFinished post is worth sending.
                if (!isCancelled()) {
                    appContext.getContentResolver().notifyChange(BookProvider.CONTENT_URI, null);
                    if (listenerRef.get() != null) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                OnProgress l = listenerRef.get();
                                if (l != null) l.onFinished();
                            }
                        });
                    }
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

    /**
     * Splits the stage-2 queue by format type and orders it by enrichment cost
     * ({@link Formats#enrichmentPriority}): the simplest formats first (EPUB, FB2 —
     * plain XML), then FB2ZIP, then the heavy MOBI binary parses, then everything
     * else. Books flagged "un-enriched" by the per-file time budget
     * ({@link Book#metaFailed}) keep the absolute last place, so one pathological
     * file can never hold the queue up — it is retried after the whole normal
     * library. Within a tier the database order (row id) is preserved.
     * Package-private so tests can pin down the ordering directly.
     */
    static void orderQueueByCost(List<Book> queue) {
        if (queue == null || queue.size() < 2) return;
        Collections.sort(queue, new Comparator<Book>() {
            @Override
            public int compare(Book a, Book b) {
                // The rescan rule: a timed-out ("un-enriched") book is last of all,
                // whatever its format.
                if (a.metaFailed != b.metaFailed) return a.metaFailed ? 1 : -1;
                int tier = Formats.enrichmentPriority(a.format)
                        - Formats.enrichmentPriority(b.format);
                if (tier != 0) return tier;
                return (a.id < b.id) ? -1 : (a.id > b.id) ? 1 : 0;
            }
        });
    }

    private static void enrichOneBook(Context app, BookDatabase db, Book book) {
        File f = new File(book.path);
        if (!f.isFile()) {
            // File is gone: keep the file-name title and drain the queue slot.
            persistMetadata(db, book.id, new MetaData(), false);
            return;
        }

        // Parse on a throwaway thread with a time budget (extractTimeoutMs).
        // The worker thread waits at most that long; if the parse is still running
        // it is abandoned and the book is marked "un-enriched" so the pass moves on
        // to the next file instead of stalling on it.
        final Parsed parsed = new Parsed();
        // Set by the parse thread when the parse throws; inspected after the wait so
        // the worker (not the parse thread) decides the book's fate.
        final Exception[] parseError = new Exception[1];
        final CountDownLatch parseDone = new CountDownLatch(1);
        Thread parseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    parseTask.parse(f, book.format, parsed);
                } catch (Exception e) {
                    // Record instead of letting it kill the thread: the worker turns
                    // a failed parse into the book's final state (see below).
                    parseError[0] = e;
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
            Log.w(TAG, "Parse of " + f + " overran " + extractTimeoutMs
                    + " ms; marked un-enriched (id=" + book.id
                    + "), retried last on the next rescan");
            markFailed(db, book.id);
            return;
        }
        if (parseError[0] != null) {
            // The parse threw (corrupt file, I/O error, parser bug). Follow the
            // project rule for failed extractions: keep the file-name title, mark the
            // book done (no retry loop) and log the cause.
            Log.e(TAG, "Parse of " + f + " (id=" + book.id + ") failed; "
                    + "keeping the file-name title", parseError[0]);
            persistMetadata(db, book.id, new MetaData(), true);
            return;
        }

        persistMetadata(db, book.id, parsed.meta, true);
        byte[] cover = parsed.cover;
        if (cover != null && cover.length > 0) {
            try {
                CoverCache.save(app, book.path, cover);
            } catch (Exception e) {
                // The metadata is already persisted; a failed cover-cache write (e.g.
                // full disk) must not kill the worker — CoverLoader re-extracts on
                // demand when the cover is next shown.
                Log.e(TAG, "Could not cache cover of " + f + " (id=" + book.id + ")", e);
            }
        }
    }

    /** {@link BookDatabase#updateMetadata} isolated: a database error on one book
     *  must not kill the worker loop or the import/detail fast paths. */
    private static void persistMetadata(BookDatabase db, long id, MetaData md, boolean readable) {
        try {
            db.updateMetadata(id, md, readable);
        } catch (Exception e) {
            Log.e(TAG, "Could not persist metadata (id=" + id + ")", e);
        }
    }

    /** {@link BookDatabase#markMetaFailed} isolated (see {@link #persistMetadata}). */
    private static void markFailed(BookDatabase db, long id) {
        try {
            db.markMetaFailed(id);
        } catch (Exception e) {
            Log.e(TAG, "Could not flag the timed-out book (id=" + id + ")", e);
        }
    }
}
