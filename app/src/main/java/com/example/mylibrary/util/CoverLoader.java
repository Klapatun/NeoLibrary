package com.example.mylibrary.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
        String key = coverKey(book);
        imageView.setTag(R.id.cover_tag, key);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (badgeToHide != null) badgeToHide.setVisibility(View.GONE);
            return;
        }
        imageView.setImageBitmap(null);
        new CoverTask(imageView, key, badgeToHide).execute(book);
    }

    private static String coverKey(Book b) {
        // Format included so a cover never leaks across format reassignment.
        return b.format + "|" + b.path;
    }

    private static class CoverTask extends AsyncTask<Book, Void, Bitmap> {
        private final ImageView imageView;
        private final String key;
        private final View badgeToHide;

        CoverTask(ImageView iv, String key, View badgeToHide) {
            this.imageView = iv;
            this.key = key;
            this.badgeToHide = badgeToHide;
        }

        @Override protected Bitmap doInBackground(Book... params) {
            Book b = params[0];
            try {
                byte[] bytes = CoverExtractor.extract(new File(b.path));
                if (bytes == null || bytes.length == 0) return null;
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                return bmp == null ? null : ensureReasonableSize(bmp);
            } catch (Exception e) {
                return null;
            }
        }

        @Override protected void onPostExecute(Bitmap result) {
            if (result == null) return;
            CACHE.put(key, result);
            Object tag = imageView.getTag(R.id.cover_tag);
            if (key.equals(tag)) {
                imageView.setImageBitmap(result);
                if (badgeToHide != null) badgeToHide.setVisibility(View.GONE);
            }
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
}
