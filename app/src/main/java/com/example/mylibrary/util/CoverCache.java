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
        save(context, bookPath, bytes, 0, bytes == null ? 0 : bytes.length);
    }

    /**
     * Stores the cover bytes for a book from a range of a larger buffer
     * ({@code bytes[off .. off+len)}) — the single-pass MOBI enricher trims the
     * cover in place at its JPEG EOI marker and writes exactly the image, so the
     * record's trailing padding never lands in the cache file and no
     * whole-array copy is made first. Silently does nothing when the range is
     * empty/out of bounds or the storage is unavailable — a missing cache entry
     * simply means "not pre-fetched yet".
     */
    public static void save(Context context, String bookPath, byte[] bytes, int off, int len) {
        if (bytes == null || len <= 0 || off < 0 || off + len > bytes.length) return;
        File dir = coversDir(context);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return;
        try {
            File target = fileFor(context, bookPath);
            File tmp = new File(dir, target.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes, off, len);
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
     * Deletes the cached cover file for a book (and any half-written ".tmp" left by
     * an interrupted {@link #save}). Silently does nothing when there is nothing to
     * delete — a missing cache entry is a normal state. Called from
     * {@code BookDatabase.deleteByPath} so a removed book leaves no orphan file.
     */
    public static void delete(Context context, String bookPath) {
        File dir = coversDir(context);
        if (dir == null) return;
        File target = fileFor(context, bookPath);
        File tmp = new File(dir, target.getName() + ".tmp");
        if (tmp.exists()) tmp.delete(); // best-effort: an orphan half-write
        target.delete();
    }

    /**
     * Deletes the entire cache (every cached cover, and the "covers" directory
     * itself). Called from {@code BookDatabase.clear} so a wiped catalog leaves no
     * orphan files behind.
     */
    public static void clear(Context context) {
        File dir = coversDir(context);
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
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
