package com.example.mylibrary.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Builds the intent used to open a book in Neo Reader 3.0.
 *
 * <p>Neo Reader registers as a viewer for the supported document types; the safest,
 * most forward-compatible way to hand it a file is a plain {@code ACTION_VIEW} intent
 * carrying the file's MIME type and a {@code file://} URI. KitKat (API 19) allows
 * passing file URIs directly. If Neo Reader is the only viewer for that type the
 * chooser is short-circuited automatically.</p>
 */
public final class Openers {

    private Openers() {}

    /** Returns an ACTION_VIEW intent to open {@code file} in any capable reader. */
    public static Intent openFile(File file) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri uri = Uri.fromFile(file);
        String mime = mimeFor(file.getName());
        if (mime != null) {
            i.setDataAndType(uri, mime);
        } else {
            i.setData(uri);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /**
     * True if Neo Reader 3.0 is installed and can open the file (best-effort check by
     * package name). Returns false when ambiguous; callers should just fire the intent
     * and let Android resolve.
     */
    public static boolean isNeoReaderInstalled(Context context) {
        Intent probe = openFile(new File("probe.pdf"));
        List<ResolveInfo> list = context.getPackageManager().queryIntentActivities(probe, 0);
        if (list == null) return false;
        for (ResolveInfo ri : list) {
            if (ri.activityInfo != null && isNeoReaderPackage(ri.activityInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNeoReaderPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.contains("neoreader") || p.contains("neo.reader")
                || p.contains("reader3") || p.contains("jin.intelligy");
    }

    /** Resolves a MIME type from the file extension (covers the supported set). */
    public static String mimeFor(String fileName) {
        String f = fileName.toLowerCase(Locale.US);
        if (f.endsWith(".pdf")) return "application/pdf";
        if (f.endsWith(".epub")) return "application/epub+zip";
        if (f.endsWith(".mobi") || f.endsWith(".prc") || f.endsWith(".azw")) return "application/x-mobipocket-ebook";
        if (f.endsWith(".fb2")) return "application/x-fictionbook+xml";
        if (f.endsWith(".fb2.zip")) return "application/x-zip-compressed-fb2";
        if (f.endsWith(".fb3")) return "application/x-fictionbook3";
        if (f.endsWith(".chm")) return "application/x-chm";
        if (f.endsWith(".doc")) return "application/msword";
        if (f.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (f.endsWith(".djvu")) return "image/vnd.djvu";
        if (f.endsWith(".pdb")) return "application/x-palm-database";
        if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html";
        if (f.endsWith(".rtf")) return "application/rtf";
        if (f.endsWith(".txt")) return "text/plain";
        return null;
    }
}
