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

/**
 * Loads a book's cover bitmap off the UI thread, caching results in memory.
 *
 * <p>To avoid stale images when a row is recycled, each {@link ImageView} is tagged
 * with its book path; the cover is only applied if the tag still matches when loading
 * finishes.</p>
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

    /** How a cover bitmap should be shaped before it is shown. */
    public enum Shape {
        /** As extracted (center-cropped by the ImageView). */
        SQUARE,
        /** Center-cropped into a circle (matches the round badge slot in list mode). */
        CIRCLE,
        /** Center-cropped into the grid tile's 8px-rounded cover frame. */
        ROUNDED_RECT
    }

    /** Applies the cached cover to {@code imageView} or kicks off an async load.
     *  When a cover is successfully shown, {@code badgeToHide} (if any) is hidden. */
    public static void load(Book book, ImageView imageView, final View badgeToHide, Shape shape) {
        String key = coverKey(book, shape);
        imageView.setTag(R.id.cover_tag, key);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (badgeToHide != null) badgeToHide.setVisibility(View.GONE);
            return;
        }
        imageView.setImageBitmap(null);
        new CoverTask(imageView, key, badgeToHide, shape).execute(book);
    }

    private static String coverKey(Book b, Shape shape) {
        // Format included so a cover never leaks across format reassignment; the shape is
        // part of the key so a round list badge and the grid tile's rounded cover cache
        // separately from each other.
        return b.format + "|" + b.path + "|" + shape;
    }

    /** One in-flight cover load. Runs on the shared pool, so it may outlive the screen
     *  that started it: it holds its views ONLY WEAKLY (see the class javadoc) and the
     *  application context — never the view's (Activity's) context. */
    private static class CoverTask extends AsyncTask<Book, Void, Bitmap> {
        private final android.content.Context context;
        private final WeakReference<ImageView> imageView;
        private final String key;
        private final WeakReference<View> badgeToHide;
        private final Shape shape;

        CoverTask(ImageView iv, String key, View badgeToHide, Shape shape) {
            this.context = iv.getContext().getApplicationContext();
            this.imageView = new WeakReference<ImageView>(iv);
            this.key = key;
            this.badgeToHide = new WeakReference<View>(badgeToHide);
            this.shape = shape;
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
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) return null;
                bmp = ensureReasonableSize(bmp);
                if (shape == Shape.CIRCLE) {
                    Bitmap cropped = circleCrop(bmp);
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
            // The decode is done — land it in the static cache regardless of what
            // happened to the views: even if the tile was recycled for another book
            // (or the screen went away), the next bind for THIS book is instant.
            if (result != null) CACHE.put(key, result);
            ImageView iv = imageView.get();
            if (iv == null) return; // the screen went away while the load was in flight
            Object tag = iv.getTag(R.id.cover_tag);
            if (!key.equals(tag)) return; // tile was recycled for another book meanwhile
            if (result == null) {
                // No extractable cover: restore the letter badge so the tile never
                // shows neither a cover nor its placeholder (bindGrid hid it in
                // advance while the load was in flight).
                View badge = badgeToHide.get();
                if (badge != null) badge.setVisibility(View.VISIBLE);
                return;
            }
            iv.setImageBitmap(result);
            View badge = badgeToHide.get();
            if (badge != null) badge.setVisibility(View.GONE);
        }
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

    /** Center-crops the bitmap to a square and clips it into a circle (transparent
     *  corners), so it fits the round badge slot in list mode. Always returns a new
     *  bitmap; the caller is responsible for recycling {@code src}. */
    private static Bitmap circleCrop(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
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
