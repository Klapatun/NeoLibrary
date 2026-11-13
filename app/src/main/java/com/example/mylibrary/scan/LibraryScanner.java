package com.example.mylibrary.scan;

import android.content.Context;
import android.os.Environment;

import com.example.mylibrary.model.Book;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 1 of library loading: recursively walks the readable storage roots looking
 * for supported book files and builds a FAST skeleton {@link Book} for each one
 * (path, format, size and a file-name title only). In-file metadata (title/author/...)
 * is deliberately NOT extracted here — that is the job of the background stage
 * ({@code MetaEnricher}), so the scan finishes quickly and the catalog is on screen
 * while the user can already interact with it. Runs on a worker thread; the UI only
 * receives the resulting list.
 */
public class LibraryScanner {

    /** Secondary SD-card mount points seen on many KitKat devices. */
    private static final String[] SD_CANDIDATES = {
            "/storage/extSdCard", "/storage/sdcard1", "/storage/external_SD",
            "/storage/UsbDriveA", "/mnt/extSdCard", "/sdcard1"
    };

    /**
     * Scans the given roots (directories) recursively for supported books.
     *
     * @param roots directories to scan; if empty, common external/shared roots are used.
     * @param onProgress optional progress callback (path currently scanned).
     */
    public static List<Book> scan(List<File> roots, Progress onProgress) {
        return scanDetailed(roots, onProgress).books;
    }

    public static ScanResult scanDetailed(List<File> roots, Progress onProgress) {
        return scanDetailed(roots, onProgress, null);
    }

    public static ScanResult scanDetailed(List<File> roots, Progress onProgress,
            Set<String> skipAbsolutePaths) {
        return scanDetailed(roots, onProgress, skipAbsolutePaths, null);
    }

    /**
     * @param skipTree a directory the walk must not enter (the app external
     *        files directory, which holds private book copies). Null skips nothing.
     *        Matching uses the canonical path, so another mount of the same
     *        directory is skipped too. Refusing to enter it does not mark the
     *        root incomplete.
     */
    public static ScanResult scanDetailed(List<File> roots, Progress onProgress,
            Set<String> skipAbsolutePaths, File skipTree) {
        if (roots == null || roots.isEmpty()) roots = defaultRoots(null);
        Set<String> canonicalSkip = canonicalSkipSet(skipAbsolutePaths);
        String treeAbsolute = skipTree == null ? null : skipTree.getAbsolutePath();
        String treeCanonical = skipTree == null ? null : identity(skipTree);
        String treeName = skipTree == null ? null : skipTree.getName();
        List<RootScanResult> rootResults = new ArrayList<RootScanResult>();
        List<Book> books = new ArrayList<Book>();
        List<String> normalizedRoots = new ArrayList<String>();
        for (File root : roots) {
            if (root == null) continue;
            String rootPath = root.getAbsolutePath();
            if (containsRoot(normalizedRoots, rootPath)) continue;
            normalizedRoots.add(rootPath);
            RootScanResult result = scanRoot(root, onProgress, canonicalSkip,
                    treeAbsolute, treeCanonical, treeName);
            rootResults.add(result);
            addUniqueBooks(books, result.books);
        }
        return new ScanResult(rootResults, books);
    }

    private static RootScanResult scanRoot(File root, Progress onProgress,
            Set<String> canonicalSkip, String treeAbsolute, String treeCanonical,
            String treeName) {
        List<Book> found = new ArrayList<Book>();
        boolean complete = walk(root, found, onProgress, canonicalSkip,
                treeAbsolute, treeCanonical, treeName);
        return new RootScanResult(root.getAbsolutePath(), found, complete);
    }

    private static boolean containsRoot(List<String> roots, String candidate) {
        for (String root : roots) {
            if (candidate.equals(root) || candidate.startsWith(root + File.separator)
                    || root.startsWith(candidate + File.separator)) return true;
        }
        return false;
    }

    private static void addUniqueBooks(List<Book> target, List<Book> source) {
        Set<String> paths = new HashSet<String>();
        for (Book book : target) paths.add(book.path);
        for (Book book : source) {
            if (paths.add(book.path)) target.add(book);
        }
    }

    /** Convenience overload when a Context is available (external storage roots). */
    public static List<Book> scan(Context context, Progress onProgress) {
        return scan(defaultRoots(context), onProgress);
    }

    private static boolean walk(File dir, List<Book> out, Progress onProgress,
            Set<String> canonicalSkip, String treeAbsolute, String treeCanonical,
            String treeName) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        // Chosen exclusion, not a failed listing: the parent root stays complete.
        if (isExcludedDirectory(dir, treeAbsolute, treeCanonical, treeName)) return true;
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Exception e) {
            return false;
        }
        if (children == null) return false;
        boolean complete = true;
        for (File f : children) {
            if (f.isDirectory()) {
                if (isExcludedDirectory(f, treeAbsolute, treeCanonical, treeName)) continue;
                if (!walk(f, out, onProgress, canonicalSkip,
                        treeAbsolute, treeCanonical, treeName)) {
                    complete = false;
                }
            } else if (f.isFile() && Formats.isSupported(f.getName())) {
                String id = identity(f);
                if (canonicalSkip.contains(id)) continue;
                if (isInside(id, treeCanonical) || isInside(f.getAbsolutePath(), treeAbsolute)) {
                    continue;
                }
                if (onProgress != null) onProgress.onScan(f.getAbsolutePath());
                Book b = buildBook(f);
                if (b != null) out.add(b);
            }
        }
        return complete;
    }

    /** Canonical path, or the absolute path when the filesystem refuses. */
    private static String identity(File f) {
        if (f == null) return null;
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    private static Set<String> canonicalSkipSet(Set<String> skipAbsolutePaths) {
        HashSet<String> out = new HashSet<String>();
        if (skipAbsolutePaths == null) return out;
        for (String p : skipAbsolutePaths) {
            if (p == null || p.length() == 0) continue;
            String id = identity(new File(p));
            if (id != null) out.add(id);
        }
        return out;
    }

    /**
     * True when {@code dir} is the excluded tree or lies inside it.
     * The absolute prefix is enough when both paths share a mount. A directory
     * whose name matches the excluded directory is also compared canonically,
     * so the same folder reached through another prefix is not entered.
     */
    private static boolean isExcludedDirectory(File dir, String treeAbsolute,
            String treeCanonical, String treeName) {
        if (dir == null || (treeAbsolute == null && treeCanonical == null)) return false;
        String abs = dir.getAbsolutePath();
        if (isInside(abs, treeAbsolute) || isInside(abs, treeCanonical)) return true;
        if (treeCanonical == null || treeName == null || !treeName.equals(dir.getName())) {
            return false;
        }
        return isInside(identity(dir), treeCanonical);
    }

    /** {@code path} equals {@code root} or is a file inside it. A shared prefix
     *  without a separator ({@code files} vs {@code files-backup}) does not count. */
    private static boolean isInside(String path, String root) {
        if (path == null || root == null || root.length() == 0) return false;
        if (path.equals(root)) return true;
        String prefix = root.endsWith(File.separator) ? root : root + File.separator;
        return path.startsWith(prefix);
    }

    public static final class ScanResult {
        public final List<RootScanResult> roots;
        public final List<Book> books;

        ScanResult(List<RootScanResult> roots, List<Book> books) {
            this.roots = roots;
            this.books = books;
        }
    }

    public static final class RootScanResult {
        public final String rootPath;
        public final List<Book> books;
        public final boolean complete;

        RootScanResult(String rootPath, List<Book> books, boolean complete) {
            this.rootPath = rootPath;
            this.books = books;
            this.complete = complete;
        }
    }

    private static Book buildBook(File f) {
        Book b = new Book();
        b.path = f.getAbsolutePath();
        b.format = Formats.formatOf(f.getName());
        b.sizeBytes = f.length();
        // Fast stage: file-name title only. In-file metadata is extracted later by
        // the background enricher (MetaEnricher), which also updates the row in place.
        b.title = titleFromName(f.getName());
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

        for (String c : SD_CANDIDATES) {
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
