package com.example.mylibrary.db;

import android.content.Context;

import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.model.Book;

import java.util.List;
import java.util.Set;

/**
 * The single write facade for the library catalog. Screens and {@link
 * com.example.mylibrary.meta.MetaEnricher} persist changes only through this class;
 * each successful write announces on both {@link BookProvider#CONTENT_URI} and
 * {@link BookProvider#RECENT_URI}. Reads stay on {@link BookDatabase}.
 */
public final class Catalog {

    private final Context context;
    private final BookDatabase db;

    public Catalog(Context context, BookDatabase db) {
        this.context = context.getApplicationContext();
        this.db = db;
    }

    public void saveScanBatch(List<Book> books) {
        db.upsertBasicBatch(books);
        BookProvider.notifyChangeAll(context);
    }

    public void removeMissing(String root, Set<String> foundPaths) {
        db.removeMissingUnderRoot(root, foundPaths);
        BookProvider.notifyChangeAll(context);
    }

    public void importFile(Book skeleton, boolean alreadyInCatalog, long existingWorkId) {
        if (existingWorkId >= 0) {
            skeleton.id = existingWorkId;
        }
        db.importSkeleton(skeleton, alreadyInCatalog);
        BookProvider.notifyChangeAll(context);
    }

    public void saveUserEdit(Book book) {
        db.saveUserEdit(book);
        BookProvider.notifyChangeAll(context);
    }

    public void markRead(long workId) {
        db.markRead(workId);
        BookProvider.notifyChangeAll(context);
    }

    public void deleteByPath(String path) {
        db.deleteByPath(path);
        BookProvider.notifyChangeAll(context);
    }

    public void clear() {
        db.clear();
        BookProvider.notifyChangeAll(context);
    }

    public void applyEnrichmentBatch(List<BookDatabase.MetaUpdate> updates) {
        db.updateMetadataBatch(updates);
        BookProvider.notifyChangeAll(context);
    }

    /**
     * Persists a metadata batch without notifying. The enricher worker pairs this with
     * throttled {@link #announceChangeAll()} calls so in-loop announces stay bounded.
     */
    public void persistEnrichmentBatch(List<BookDatabase.MetaUpdate> updates) {
        db.updateMetadataBatch(updates);
    }

    public void applyEnrichmentOne(long workId, MetaData md, boolean fileReadable) {
        db.updateMetadata(workId, md, fileReadable);
        BookProvider.notifyChangeAll(context);
    }

    public void markMetaFailed(long workId) {
        db.markMetaFailed(workId);
        BookProvider.notifyChangeAll(context);
    }

    /** Flags a timed-out parse without notifying (the enricher batch loop announces). */
    public void persistMetaFailed(long workId) {
        db.markMetaFailed(workId);
    }

    /** Announces catalog changes on both URIs (used by the throttled enricher loop). */
    public void announceChangeAll() {
        BookProvider.notifyChangeAll(context);
    }
}
