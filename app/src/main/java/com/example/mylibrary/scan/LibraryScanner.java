package com.example.mylibrary.scan;

import android.content.Context;
import android.os.Environment;

import com.example.mylibrary.model.Book;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursively walks the readable storage roots looking for supported book files,
 * and resolves a display {@link Book} for each one. Runs on a worker thread; the UI
 * only receives the resulting list.
 */
public class LibraryScanner {

    /**
     * Scans the given roots (directories) recursively for supported books.
     *
     * @param roots directories to scan; if empty, common external/shared roots are used.
     * @param onProgress optional progress callback (path currently scanned).
     */
    public static List<Book> scan(List<File> roots, Progress onProgress) {
        if (roots == null || roots.isEmpty()) {
            roots = defaultRoots(null);
        }
        List<Book> books = new ArrayList<Book>();
        for (File root : roots) {
            walk(root, books, onProgress);
        }
        return books;
    }

    /** Convenience overload when a Context is available (external storage roots). */
    public static List<Book> scan(Context context, Progress onProgress) {
        return scan(defaultRoots(context), onProgress);
    }

    private static void walk(File dir, List<Book> out, Progress onProgress) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                walk(f, out, onProgress);
            } else if (f.isFile() && Formats.isSupported(f.getName())) {
                if (onProgress != null) onProgress.onScan(f.getAbsolutePath());
                Book b = buildBook(f);
                if (b != null) out.add(b);
            }
        }
    }

    private static Book buildBook(File f) {
        Book b = new Book();
        b.path = f.getAbsolutePath();
        b.format = Formats.formatOf(f.getName());
        b.sizeBytes = f.length();
        // Metadata extraction is cheap enough to do inline for supported formats;
        // for others we fall back to file name below.
        com.example.mylibrary.meta.MetaData md =
                com.example.mylibrary.meta.MetaExtractor.extract(f);
        if (md.found && md.title != null) {
            b.title = md.title;
            b.author = md.author;
            b.publisher = md.publisher;
            b.description = md.description;
            b.series = md.series;
        } else {
            b.title = titleFromName(f.getName());
        }
        return b;
    }

    /** Builds a book entry for a single file (used by the import flow). */
    public static Book scanSingle(File f) {
        if (f == null || !f.exists() || !f.isFile()) return null;
        if (!Formats.isSupported(f.getName())) return null;
        return buildBook(f);
    }

    private static String titleFromName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ');
    }

    /** Returns the default storage roots to scan. Starts with primary external
     *  storage (which on KitKat is the shared /storage/emulated/0 root) and then adds
     *  common secondary SD-card mount points if they are present and readable. */
    private static List<File> defaultRoots(Context context) {
        List<File> roots = new ArrayList<File>();
        try {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null && primary.exists()) roots.add(primary);
        } catch (Exception ignored) {}

        // Secondary SD card mount points seen on many KitKat devices.
        String[] candidates = {
                "/storage/extSdCard", "/storage/sdcard1", "/storage/external_SD",
                "/storage/UsbDriveA", "/mnt/extSdCard", "/sdcard1"
        };
        for (String c : candidates) {
            try {
                File f = new File(c);
                if (f.isDirectory() && !roots.contains(f)) roots.add(f);
            } catch (Exception ignored) {}
        }
        return roots;
    }

    public interface Progress {
        void onScan(String path);
    }
}
