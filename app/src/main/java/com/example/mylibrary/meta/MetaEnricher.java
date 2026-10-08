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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.zip.ZipFile;
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
 * {@code meta_done = 0} it runs the single-pass parsers and stores the cover bytes
 * in {@link CoverCache}, then commits the group's results through
 * {@link BookDatabase#updateMetadataBatch} — every {@code BATCH_NOTIFY_EVERY} books
 * in one SQLite transaction (same contract as the single-book path: it preserves
 * {@code last_read} and never clobbers user edits). After each batch it calls
 * {@code notifyChange} (throttled — no more often than once per
 * {@code notifyMinIntervalMs} — plus one final, unthrottled notify at the end of
 * the pass) so the UI's {@code CursorLoader} picks the new values up automatically
 * — the user has been able to see and interact with the whole library since stage 1
 * finished.</p>
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

    /** Default minimum gap between two of the worker's in-loop {@code notifyChange}
     *  posts (the resolver callback is expensive, and a big pass would otherwise
     *  fire it every few books): 300 ms, the middle of the 250-500 ms band. The
     *  FINAL notify of a finished pass is never throttled. */
    private static final long DEFAULT_NOTIFY_MIN_INTERVAL_MS = 300L;

    /** The enforced minimum gap in milliseconds (see the default above).
     *  Package-private so tests can widen it (and restore it) without waiting real
     *  time, like {@link #extractTimeoutMs}. */
    static volatile long notifyMinIntervalMs = DEFAULT_NOTIFY_MIN_INTERVAL_MS;

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
        /** Range of image bytes inside {@link #cover}: {@code [coverOff, coverOff + coverLen)}.
         *  {@code coverLen <= 0} means "the whole array" — the cover is a self-contained
         *  array (EPUB/FB2 extract, or a test double), so the default 0/-1 is correct
         *  for every task that does not set it. */
        int coverOff;
        int coverLen = -1;
    }

    /**
     * The default {@link ParseTask}: the single-pass parsers. EPUB, FB2, FB2ZIP and
     * MOBI are each parsed in ONE file session (metadata + cover together — see the
     * {@code parse*SinglePass} methods below) instead of the public extractors'
     * separate passes; the remaining formats keep the public extractors' best-effort
     * path (metadata only, no cover).
     */
    static volatile ParseTask parseTask = new ParseTask() {
        @Override
        public void parse(File file, String format, Parsed out) {
            if (format != null) {
                if (format.equals("EPUB")) { parseEpubSinglePass(file, out); return; }
                if (format.equals("FB2")) { parseFb2SinglePass(file, out); return; }
                if (format.equals("FB2ZIP")) { parseFb2ZipSinglePass(file, out); return; }
                if (format.equals("MOBI")) { parseMobiSinglePass(file, out); return; }
            }
            // Everything else (TXT, HTML, PDF, ...): best-effort metadata, no cover.
            out.meta = MetaExtractor.extract(file);
        }
    };

    // ------------------------------------------------------------------
    // single-pass parse: one file session per book (metadata + cover together)
    // ------------------------------------------------------------------

    /**
     * EPUB, one session: a single {@link ZipFile} open and three entry reads
     * (container.xml, the OPF, the cover image) — instead of the public fallback's
     * five sequential archive passes. A malformed archive degrades to "not found"
     * (the same contract as the public extractors). Package-private so the tests
     * share it with the default parse task.
     */
    static void parseEpubSinglePass(File file, Parsed out) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            String opfPath = MetaExtractor.opfPathFromContainer(zip);
            if (opfPath == null) {
                out.meta = notFound();
                return;
            }
            byte[] opfBytes = CoverExtractor.readEntryBytes(zip, opfPath);
            if (opfBytes == null) {
                out.meta = notFound();
                return;
            }
            MetaData md = new MetaData();
            MetaExtractor.parseOpf(new ByteArrayInputStream(opfBytes), md);
            md.found = md.title != null && md.title.length() > 0;
            out.meta = md;
            // The cover is best-effort on top: a missing/unreadable image keeps the
            // metadata (same as the two-public-call path).
            try {
                out.cover = CoverExtractor.coverFromOpf(new String(opfBytes, "UTF-8"),
                        opfPath, zip);
            } catch (Exception e) {
                Log.w(TAG, "EPUB cover of " + file + " could not be read", e);
            }
        } catch (Exception e) {
            Log.w(TAG, "Single-pass EPUB parse of " + file, e);
            out.meta = notFound();
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * FB2, one pass: the batch-1 streaming header read ({@link
     * CoverExtractor#readFb2Header}) yields BOTH the metadata (the description block)
     * and the cover (the binary blocks) from a single pass over the file — the body
     * (the bulk of a real book) is never read or parsed.
     */
    static void parseFb2SinglePass(File file, Parsed out) {
        try {
            byte[] header = CoverExtractor.readFb2Header(file);
            out.meta = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(header));
            out.cover = CoverExtractor.coverFromFb2Bytes(header);
        } catch (Exception e) {
            Log.w(TAG, "Single-pass FB2 parse of " + file, e);
            out.meta = notFound();
        }
    }

    /**
     * FB2ZIP, one session: a single {@link ZipFile} open — the central directory is
     * scanned for the inner {@code .fb2} (nothing else is decompressed); the
     * metadata + cover come from the inner document's bytes, and when the inner
     * document carries no cover, the first image entry (a name containing "cover"
     * wins outright) is read instead.
     */
    static void parseFb2ZipSinglePass(File file, Parsed out) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            byte[] fb2 = CoverExtractor.readFb2EntryBytes(zip);
            if (fb2 == null) {
                out.meta = notFound();
                return;
            }
            out.meta = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(fb2));
            out.cover = CoverExtractor.coverFromFb2Bytes(fb2);
            if (out.cover == null) {
                try {
                    out.cover = CoverExtractor.looseImageFromZip(zip);
                } catch (Exception e) {
                    Log.w(TAG, "FB2ZIP loose cover of " + file + " could not be read", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Single-pass FB2ZIP parse of " + file, e);
            out.meta = notFound();
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * MOBI, one session: a single {@link MobiParser} — open (the PalmDB/MOBI/EXTH
     * headers give the metadata), then the cover record with the batch-3 in-place
     * trim: the record's own array is handed out with the image length inside it
     * (JPEG cut at its EOI), so nothing is copied.
     */
    static void parseMobiSinglePass(File file, Parsed out) {
        MobiParser p = new MobiParser();
        try {
            if (!p.open(file)) {
                out.meta = notFound();
                return;
            }
            MetaData md = new MetaData();
            md.title = p.title;
            md.author = p.author;
            md.publisher = p.publisher;
            md.description = p.description;
            md.language = p.language;
            md.found = md.title != null && md.title.length() > 0;
            out.meta = md;
            if (p.coverRecord >= 0) {
                byte[] raw = p.readRecord(p.coverRecord);
                if (raw != null && raw.length > 0) {
                    int len = CoverExtractor.imageLen(raw);
                    if (len > 0) {
                        out.cover = raw;
                        out.coverOff = 0;
                        out.coverLen = len;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Single-pass MOBI parse of " + file, e);
            out.meta = notFound();
        } finally {
            p.close();
        }
    }

    private static MetaData notFound() {
        MetaData md = new MetaData();
        md.found = false;
        return md;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

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
                long lastNotify = 0;
                for (int i = 0; i < total; i += BATCH_NOTIFY_EVERY) {
                    if (isCancelled()) break;
                    final int to = Math.min(i + BATCH_NOTIFY_EVERY, total);
                    // 1) Parse the group (each book on its own throwaway thread,
                    //    under the per-file time budget) and collect the stage-2
                    //    results: the database work of the whole group goes into ONE
                    //    batch commit (below), not one autocommit per book.
                    List<BookDatabase.MetaUpdate> batch =
                            new ArrayList<BookDatabase.MetaUpdate>(to - i);
                    for (int j = i; j < to; j++) {
                        if (isCancelled()) break;
                        Book book = pending.get(j);
                        BookDatabase.MetaUpdate update = enrichOneBook(appContext, db, book);
                        if (update != null) batch.add(update);
                        done++;
                        long now = System.currentTimeMillis();
                        if (listenerRef.get() != null
                                && (done % BATCH_NOTIFY_EVERY == 0 || done == total
                                        || now - lastProgress > PROGRESS_MIN_INTERVAL_MS)) {
                            lastProgress = now;
                            final int d = done, t = total;
                            // The runnable re-resolves the (weak) listener at fire
                            // time, so even the main-queue never pins a dead
                            // screen's callback.
                            main.post(new Runnable() {
                                @Override public void run() {
                                    OnProgress l = listenerRef.get();
                                    if (l != null) l.onProgress(d, t);
                                }
                            });
                        }
                    }
                    if (!batch.isEmpty()) {
                        try {
                            db.updateMetadataBatch(batch);
                        } catch (Exception e) {
                            // A database error can never kill the worker: the failed
                            // group was rolled back as a whole (nothing of it was
                            // persisted) and its books keep meta_done = 0 — the next
                            // rescan retries them.
                            Log.e(TAG, "Could not persist the metadata batch (rolled back)", e);
                        }
                    }
                    // 2) Announce the batch, THROTTLED: the resolver callback is
                    //    expensive, and the final notify of a finished pass (below)
                    //    is never throttled.
                    if (done % BATCH_NOTIFY_EVERY == 0 && done < total) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotify >= notifyMinIntervalMs) {
                            lastNotify = now;
                            appContext.getContentResolver()
                                    .notifyChange(BookProvider.CONTENT_URI, null);
                        }
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
     * book and persists the result — a single-book commit (the import/detail fast
     * path; the bulk worker uses the batch commit). Call from a background thread;
     * idempotent.
     */
    public static void enrichOne(Context app, BookDatabase db, Book book) {
        BookDatabase.MetaUpdate update =
                enrichOneBook(app.getApplicationContext(), db, book);
        if (update != null) {
            persistMetadata(db, update.id, update.meta, update.fileReadable);
        }
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

    /**
     * Parses one book (on a throwaway thread, under the per-file time budget) and
     * returns the stage-2 result for the caller's batch commit — or {@code null}
     * when the book overran the budget (it is flagged "un-enriched" right here and
     * left in the queue for the next rescan, with nothing to commit). The cover,
     * when found, is cached right away: it is a file write, independent of the
     * database batch.
     */
    private static BookDatabase.MetaUpdate enrichOneBook(Context app, BookDatabase db, Book book) {
        File f = new File(book.path);
        if (!f.isFile()) {
            // File is gone: keep the file-name title and drain the queue slot.
            return new BookDatabase.MetaUpdate(book.id, new MetaData(), false);
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
            return null;
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
            return null;
        }
        if (parseError[0] != null) {
            // The parse threw (corrupt file, I/O error, parser bug). Follow the
            // project rule for failed extractions: keep the file-name title, mark the
            // book done (no retry loop) and log the cause.
            Log.e(TAG, "Parse of " + f + " (id=" + book.id + ") failed; "
                    + "keeping the file-name title", parseError[0]);
            return new BookDatabase.MetaUpdate(book.id, new MetaData(), true);
        }

        byte[] cover = parsed.cover;
        if (cover != null && cover.length > 0) {
            // The cover may be a range of a larger array (the MOBI in-place trim):
            // write exactly the image bytes. coverLen <= 0 means "the whole array"
            // (the self-contained covers of the other formats and test doubles).
            int off = parsed.coverOff;
            int len = (parsed.coverLen > 0) ? parsed.coverLen : cover.length - off;
            if (off >= 0 && len > 0 && off + len <= cover.length) {
                try {
                    CoverCache.save(app, book.path, cover, off, len);
                } catch (Exception e) {
                    // The metadata is persisted with the group's batch commit; a
                    // failed cover-cache write (e.g. full disk) must not kill the
                    // worker — CoverLoader re-extracts on demand when the cover is
                    // next shown.
                    Log.e(TAG, "Could not cache cover of " + f + " (id=" + book.id + ")", e);
                }
            }
        }
        return new BookDatabase.MetaUpdate(book.id, parsed.meta, true);
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
