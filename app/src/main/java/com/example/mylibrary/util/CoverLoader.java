package com.example.mylibrary.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.AsyncTask;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.example.mylibrary.R;
import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.model.Book;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a book's cover bitmap off the UI thread, caching results in memory.
 *
 * <p>To avoid stale images when a row is recycled, each {@link ImageView} is tagged
 * with its book path; the cover is only applied if the tag still matches when loading
 * finishes.</p>
 *
 * <p>Loads are deduplicated per key: while a cover is decoding, every tile that gets
 * bound to the same book joins the in-flight task instead of spawning a second decode
 * (see {@link #load}), and the result is applied to all of them when the decode lands.
 * CIRCLE bitmaps (the small list-mode badge) are deliberately NOT put into the memory
 * cache — the other shapes are.</p>
 *
 * <p>The in-flight tasks hold their views by WEAK reference only (and the application
 * context, never the Activity's): the user may leave the screen while a cover is still
 * loading, and the pool task must not keep the dead screen's view hierarchy (and with
 * it the Activity) alive — the task simply drops its drawing work, and the decoded
 * bitmap still lands in the static cache, so the next bind for that book is instant.</p>
 */
public class CoverLoader {

    private static final int MAX_MEM = (int) (Runtime.getRuntime().maxMemory() / 8);
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(MAX_MEM) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** The grid tile's cover frame (item_book_grid.xml): 242x387px (5:8) with
     *  8px rounded corners; ROUNDED_RECT bitmaps are pre-cropped to exactly this. */
    private static final int GRID_COVER_W = 242;
    private static final int GRID_COVER_H = 387;
    private static final int GRID_COVER_RADIUS = 8;

    /** The round badge slot in list mode (item_book.xml): 48dp, converted to pixels
     *  with the screen's density; CIRCLE bitmaps are pre-cropped to exactly this, so
     *  a 3000x4000 source costs a 48x48 (or 96x96 on xxhdpi) bitmap, not 512x512. */
    private static final int LIST_BADGE_DP = 48;

    /** How a cover bitmap should be shaped before it is shown. */
    public enum Shape {
        /** As extracted (center-cropped by the ImageView). */
        SQUARE,
        /** Center-cropped into a circle (matches the round badge slot in list mode). */
        CIRCLE,
        /** Center-cropped into the grid tile's 8px-rounded cover frame. */
        ROUNDED_RECT
    }

    /** The in-flight registry: one shared decode per {@link #coverKey}. Keyed by the
     *  FULL key (format|path|shape) — never just format|path — because the decoded
     *  bitmap depends on the shape (a CIRCLE tile and a grid tile of the same book
     *  would otherwise share one decode and get the wrong bitmap). Touched from the
     *  main thread only ({@link #load} and the tasks' {@code onPostExecute} both run
     *  there), so it needs no locking. */
    private static final Map<String, InFlight> IN_FLIGHT = new HashMap<String, InFlight>();

    /** How many {@link CoverTask}s have been spawned. Package-private so a test can
     *  verify the in-flight dedup (two loads of the same key must spawn one task). */
    static int spawnedTasks = 0;

    /** Applies the cached cover to {@code imageView} or joins/kicks off the shared
     *  async load for the key. When a cover is successfully shown, {@code badgeToHide}
     *  (if any) is hidden.
     *
     *  <p><b>In-flight dedup.</b> When a decode for the key is already running, this
     *  call does NOT spawn a second task: the tile is simply added to the in-flight
     *  task's target list, and when the decode finishes the bitmap is applied to every
     *  tile still bound to the key (each after its own tag check). A fast list scroll
     *  re-binds the same book to several recycled tiles while the first decode is still
     *  running — without the registry each rebind would re-decode the same cover.</p>
     *
     *  <p><b>No flicker on rebind.</b> {@code setImageBitmap(null)} is skipped when the
     *  tile was already bound to this key before the call (a re-layout pass, not a
     *  recycle to another book): such a tile already shows this book's placeholder
     *  state, and clearing it again would only blink it.</p> */
    public static void load(Book book, ImageView imageView, final View badgeToHide, Shape shape) {
        String key = coverKey(book, shape);
        // What the tile was bound to before this call: if it was already bound to THIS
        // key, it is being re-bound (a re-layout), not recycled from another book.
        Object prevTag = imageView.getTag(R.id.cover_tag);
        imageView.setTag(R.id.cover_tag, key);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (badgeToHide != null) badgeToHide.setVisibility(View.GONE);
            return;
        }

        InFlight inFlight = IN_FLIGHT.get(key);
        if (inFlight == null) {
            inFlight = new InFlight();
            IN_FLIGHT.put(key, inFlight);
        }
        inFlight.targets.add(new Target(imageView, badgeToHide));
        if (inFlight.task == null) {
            // The first load for this key spawns the shared task; later loads just join.
            inFlight.task = new CoverTask(
                    imageView.getContext().getApplicationContext(), key, shape, inFlight);
            inFlight.task.execute(book);
        }
        if (!key.equals(prevTag)) {
            // The tile was showing another book's content: clear it so the old cover
            // does not linger until the shared decode lands. (A tile already bound to
            // this key is left alone — see the javadoc.)
            imageView.setImageBitmap(null);
        }
    }

    private static String coverKey(Book b, Shape shape) {
        // Format included so a cover never leaks across format reassignment; the shape is
        // part of the key so a round list badge and the grid tile's rounded cover cache
        // separately from each other.
        return b.format + "|" + b.path + "|" + shape;
    }

    /** One in-flight decode and the tiles waiting on it. The task runs on the shared
     *  pool and may outlive the screen that started it, so every target is held ONLY
     *  WEAKLY (see the class javadoc); a tile recycled for another book (or a dead
     *  screen) simply drops out at apply time — the decode itself still lands in the
     *  static cache, so the next bind for that book is instant. */
    private static final class InFlight {
        CoverTask task;
        final List<Target> targets = new ArrayList<Target>(4);
    }

    /** One tile (and its letter badge, if it has one) waiting for a shared decode. */
    private static final class Target {
        final WeakReference<ImageView> imageView;
        final WeakReference<View> badge;
        Target(ImageView iv, View badgeToHide) {
            this.imageView = new WeakReference<ImageView>(iv);
            this.badge = (badgeToHide == null) ? null : new WeakReference<View>(badgeToHide);
        }
    }

    /** One in-flight cover load. Runs on the shared pool, so it may outlive the screen
     *  that started it: it holds its targets (in {@link InFlight}) ONLY WEAKLY (see
     *  the class javadoc) and the application context — never the view's (Activity's)
     *  context. One task serves EVERY tile bound to its key (the in-flight registry in
     *  {@link #load} deduplicates the loads). */
    private static class CoverTask extends AsyncTask<Book, Void, Bitmap> {
        private final android.content.Context context;
        private final String key;
        private final Shape shape;
        private final InFlight inFlight;

        CoverTask(android.content.Context context, String key, Shape shape, InFlight inFlight) {
            this.context = context;
            this.key = key;
            this.shape = shape;
            this.inFlight = inFlight;
            spawnedTasks++;
        }

        @Override protected Bitmap doInBackground(Book... params) {
            Book b = params[0];
            try {
                // Prefer the durable file cache (warmed by the background enricher);
                // fall back to extracting from the book and cache the result. The
                // app context is all CoverCache needs (it only resolves the external
                // files dir).
                byte[] bytes = CoverCache.load(context, b.path);
                if (bytes == null) {
                    bytes = CoverExtractor.extract(new File(b.path));
                    if (bytes != null && bytes.length > 0) CoverCache.save(context, b.path, bytes);
                }
                if (bytes == null || bytes.length == 0) return null;
                Bitmap bmp = decodeForCache(bytes);
                if (bmp == null) return null;
                bmp = ensureReasonableSize(bmp);
                if (shape == Shape.CIRCLE) {
                    // The badge slot is 48dp: crop to it directly so the cached bitmap
                    // is as small as the slot, not as large as the source image.
                    int target = Math.max(1, (int) (LIST_BADGE_DP
                            * context.getResources().getDisplayMetrics().density));
                    Bitmap cropped = circleCrop(bmp, target);
                    bmp.recycle();
                    return cropped;
                }
                if (shape == Shape.ROUNDED_RECT) {
                    Bitmap cropped = roundedRectCrop(bmp);
                    bmp.recycle();
                    return cropped;
                }
                return bmp;
            } catch (Exception e) {
                return null;
            }
        }

        @Override protected void onPostExecute(Bitmap result) {
            // The decode is done: take the key out of the in-flight registry (a later
            // load for the same key spawns a fresh task) and apply the result to every
            // tile that is STILL bound to this key — each after its own tag check,
            // because a tile may have been recycled for another book meanwhile (or the
            // screen may have gone away).
            IN_FLIGHT.remove(key);
            // CIRCLE bitmaps are NOT cached (option (v) of the optimization plan):
            // the 48dp badge is tiny and — thanks to the in-flight dedup — a list
            // scroll re-decodes it at most once per book, while caching it would push
            // the expensive ROUNDED_RECT/SQUARE bitmaps out of the LruCache.
            if (result != null && shape != Shape.CIRCLE) CACHE.put(key, result);
            for (Target t : inFlight.targets) {
                ImageView iv = t.imageView.get();
                if (iv == null) continue; // the screen went away while the load was in flight
                if (!key.equals(iv.getTag(R.id.cover_tag))) continue; // recycled for another book
                if (result == null) {
                    // No extractable cover: restore the letter badge so the tile never
                    // shows neither a cover nor its placeholder (the bind hid it in
                    // advance while the load was in flight).
                    View badge = (t.badge == null) ? null : t.badge.get();
                    if (badge != null) badge.setVisibility(View.VISIBLE);
                    continue;
                }
                iv.setImageBitmap(result);
                View badge = (t.badge == null) ? null : t.badge.get();
                if (badge != null) badge.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Decodes the image bytes sized for the in-memory cache: first a
     * bounds-only pass ({@code inJustDecodeBounds}) reads the image's dimensions
     * without allocating a pixel buffer, then the real decode runs with the
     * smallest power-of-two {@code inSampleSize} whose result keeps the max edge at
     * or under 512 px (the largest shape the cache stores: the 242x387 grid tile
     * fits comfortably under it). A 10 MB book cover that is 6000x9000 px is
     * decoded once, at 1/8, into ~2.2 MB of pixels — instead of the ~216 MB the
     * full-size decode would allocate on the device's 64 MB heap.
     *
     * <p>When the bounds pass yields no dimensions (undecodable header) the plain
     * decode is attempted as-is and simply returns {@code null} for bad data.</p>
     */
    private static Bitmap decodeForCache(byte[] bytes) {
        final int MAX_EDGE = 512;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        int sample = 1;
        while (Math.max(w, h) / sample > MAX_EDGE) sample *= 2;
        if (sample == 1) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    /** Downsamples very large covers so the memory cache stays small. */
    private static Bitmap ensureReasonableSize(Bitmap src) {
        final int MAX_EDGE = 512;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_EDGE && h <= MAX_EDGE) return src;
        float scale = Math.min(MAX_EDGE / (float) w, MAX_EDGE / (float) h);
        Bitmap scaled = Bitmap.createScaledBitmap(src,
                Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    /** Center-crops the bitmap to a {@code targetPx} square and clips it into a
     *  circle (transparent corners), so it fits the round badge slot in list mode.
     *  The target is the SLOT's size (48dp), not the source's shorter edge: the
     *  cached bitmap is then as small as the slot it will be drawn at. Always
     *  returns a new bitmap; the caller is responsible for recycling {@code src}. */
    private static Bitmap circleCrop(Bitmap src, int targetPx) {
        int size = Math.max(1, targetPx);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Center-crop: scale the shorter edge up to the target size, drop the overflow.
        float scale = size / (float) Math.min(src.getWidth(), src.getHeight());
        float dx = (src.getWidth() * scale - size) / 2f;
        float dy = (src.getHeight() * scale - size) / 2f;
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(-dx, -dy);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }

    /** Center-crops the bitmap to the grid tile's cover frame (242x387px, 5:8) and
     *  clips it into a rounded rectangle (8px corners, transparent outside), so it
     *  matches the rounded cover frame in grid mode. The pre-cropped bitmap is exactly
     *  the size of the ImageView, so the ImageView's centerCrop scales it 1:1.
     *  Always returns a new bitmap; the caller is responsible for recycling {@code src}. */
    private static Bitmap roundedRectCrop(Bitmap src) {
        int w = GRID_COVER_W;
        int h = GRID_COVER_H;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Center-crop: scale the image up until it fully covers w x h, drop the overflow.
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dx = (src.getWidth() * scale - w) / 2f;
        float dy = (src.getHeight() * scale - h) / 2f;
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(-dx, -dy);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        c.drawRoundRect(new RectF(0, 0, w, h), GRID_COVER_RADIUS, GRID_COVER_RADIUS, paint);
        return out;
    }
}
