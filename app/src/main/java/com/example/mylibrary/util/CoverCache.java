package com.example.mylibrary.util;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Durable, file-based cache of extracted cover <b>bytes</b> (not bitmaps — decoding
 * stays the UI's job, where the {@code LruCache} in {@link CoverLoader} manages
 * memory). The background enricher fills this cache so that switching to grid view
 * shows covers instantly instead of re-opening every book archive, and the cache
 * survives app restarts.
 *
 * <p>Covers are stored in the app's own external files dir (no storage permission
 * needed on any API level) under a name derived from the book's absolute path.
 * Writes are atomic (".tmp" then rename) so a cache file is never half-written.</p>
 */
public final class CoverCache {

    private static final String DIR = "covers";

    private CoverCache() {}

    /** The cache file that stores the cover bytes for {@code bookPath}. */
    public static File fileFor(Context context, String bookPath) {
        return new File(coversDir(context), Long.toHexString(bookPath.hashCode()) + ".img");
    }

    /**
     * Stores the cover bytes for a book (replacing any previous entry). Silently does
     * nothing when the storage is unavailable or the bytes are empty — a missing
     * cache entry simply means "not pre-fetched yet".
     */
    public static void save(Context context, String bookPath, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        File dir = coversDir(context);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return;
        try {
            File target = fileFor(context, bookPath);
            File tmp = new File(dir, target.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                // renameTo can fail across filesystems; retry after clearing the target
                target.delete();
                tmp.renameTo(target);
            }
        } catch (Exception ignored) {
            // Cache writes are best-effort; extraction failures fall back to CoverLoader.
        }
    }

    /**
     * Returns the cached cover bytes for a book, or {@code null} if the book has no
     * cached cover (yet).
     */
    public static byte[] load(Context context, String bookPath) {
        try {
            File f = fileFor(context, bookPath);
            if (!f.exists()) return null;
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static File coversDir(Context context) {
        // getExternalFilesDir may be null when external storage is unusable.
        return context.getExternalFilesDir(DIR);
    }
}
