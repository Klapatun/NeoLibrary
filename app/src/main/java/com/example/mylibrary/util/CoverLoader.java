package com.example.mylibrary.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.AsyncTask;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.example.mylibrary.R;
import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.model.Book;

import java.io.File;

/**
 * Loads a book's cover bitmap off the UI thread, caching results in memory.
 *
 * <p>To avoid stale images when a row is recycled, each {@link ImageView} is tagged
 * with its book path; the cover is only applied if the tag still matches when loading
 * finishes.</p>
 */
public class CoverLoader {

    private static final int MAX_MEM = (int) (Runtime.getRuntime().maxMemory() / 8);
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(MAX_MEM) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Applies the cached cover to {@code imageView} or kicks off an async load.
     *  When a cover is successfully shown, {@code badgeToHide} (if any) is hidden. */
    public static void load(Book book, ImageView imageView, final View badgeToHide) {
        load(book, imageView, badgeToHide, false);
    }

    /** Same as {@link #load(Book, ImageView, View)}, but {@code round} clips the bitmap
     *  into a circle (center-cropped) so it matches the round badge slot in list mode. */
    public static void load(Book book, ImageView imageView, final View badgeToHide, boolean round) {
        String key = coverKey(book, round);
        imageView.setTag(R.id.cover_tag, key);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (badgeToHide != null) badgeToHide.setVisibility(View.GONE);
            return;
        }
        imageView.setImageBitmap(null);
        new CoverTask(imageView, key, badgeToHide, round).execute(book);
    }

    private static String coverKey(Book b, boolean round) {
        // Format included so a cover never leaks across format reassignment; the shape is
        // part of the key so a round list badge and the square grid tile cache separately.
        return b.format + "|" + b.path + (round ? "|round" : "");
    }

    private static class CoverTask extends AsyncTask<Book, Void, Bitmap> {
        private final ImageView imageView;
        private final String key;
        private final View badgeToHide;
        private final boolean round;

        CoverTask(ImageView iv, String key, View badgeToHide, boolean round) {
            this.imageView = iv;
            this.key = key;
            this.badgeToHide = badgeToHide;
            this.round = round;
        }

        @Override protected Bitmap doInBackground(Book... params) {
            Book b = params[0];
            try {
                byte[] bytes = CoverExtractor.extract(new File(b.path));
                if (bytes == null || bytes.length == 0) return null;
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) return null;
                bmp = ensureReasonableSize(bmp);
                if (round) {
                    Bitmap cropped = circleCrop(bmp);
                    bmp.recycle();
                    return cropped;
                }
                return bmp;
            } catch (Exception e) {
                return null;
            }
        }

        @Override protected void onPostExecute(Bitmap result) {
            Object tag = imageView.getTag(R.id.cover_tag);
            if (!key.equals(tag)) return; // tile was recycled for another book meanwhile
            if (result == null) {
                // No extractable cover: restore the letter badge so the tile never
                // shows neither a cover nor its placeholder (bindGrid hid it in
                // advance while the load was in flight).
                if (badgeToHide != null) badgeToHide.setVisibility(View.VISIBLE);
                return;
            }
            CACHE.put(key, result);
            imageView.setImageBitmap(result);
            if (badgeToHide != null) badgeToHide.setVisibility(View.GONE);
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
}
