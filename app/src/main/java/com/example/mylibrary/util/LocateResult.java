package com.example.mylibrary.util;

import java.io.File;

/**
 * What {@link FileLocator} decided about an edition's bytes. Status is an int,
 * not an enum, so callers on the old toolchain can switch on it without extra
 * classes. {@link #file} is set only for {@link #READY}.
 */
public final class LocateResult {

    /** Bytes are on disk and the edition is not mid-download or failed. */
    public static final int READY = 0;
    /** No path, or the path does not point at a file that is still there. */
    public static final int ABSENT = 1;
    /** {@code files.availability} is {@code downloading}. Wins over a file on disk. */
    public static final int DOWNLOADING = 2;
    /** {@code files.availability} is {@code failed}. */
    public static final int FAILED = 3;

    public final int status;
    public final File file;

    public LocateResult(int status, File file) {
        this.status = status;
        this.file = file;
    }
}
