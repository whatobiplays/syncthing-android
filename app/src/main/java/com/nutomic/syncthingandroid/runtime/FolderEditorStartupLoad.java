package com.nutomic.syncthingandroid.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Opens one folder editor from the authoritative configuration, away from the thread that draws it.
 *
 * <p>An editor that shows a configured folder reads that folder from the authoritative
 * configuration before it may be edited: the read decides which folder the editor shows, and it
 * also produces the shared device list the editor renders. In Superuser Mode that read resolves
 * the configured folder path through the selected backend, which can wait for a bounded helper
 * activation, so the read must never run on the thread that draws the editor.</p>
 *
 * <p>The read is handed to a {@link FolderWorkQueue}, so the owner's thread returns immediately and
 * the snapshot arrives on that thread later. An answer the editor no longer wants, because the
 * editor was left or because a newer read replaced this one, is dropped before it touches any
 * view: the queue drops superseded requests, and the presence check drops answers whose editor is
 * finishing or destroyed.</p>
 *
 * <p>The request generation itself is deliberately not repeated in this class: whoever hands out
 * the answer already decides whether the request it belongs to is still wanted.</p>
 *
 * @param <S> snapshot type the reader produces and the editor applies
 */
public final class FolderEditorStartupLoad<S> {
    /** Reads the authoritative configuration; runs on the worker thread. */
    public interface ConfigurationRead<S> {
        S read();
    }

    /** Reports whether the editor that started the read still exists to receive its answer. */
    public interface EditorPresence {
        boolean isAlive();
    }

    /** Applies one loaded snapshot on the thread that draws the editor. */
    public interface Delivery<S> {
        void deliver(S snapshot);
    }

    /** Reads the identifier of one configuration entry. */
    public interface Identifier<T> {
        String id(T entry);
    }

    private final FolderWorkQueue queue;

    /**
     * Creates a startup read that runs on the worker of the given queue.
     *
     * @param queue queue that runs the read away from the owner's thread
     */
    public FolderEditorStartupLoad(FolderWorkQueue queue) {
        this.queue = Objects.requireNonNull(queue, "The folder work queue is required");
    }

    /**
     * Starts one authoritative configuration read for an editor that is opening.
     *
     * <p>The call returns as soon as the read is queued. The snapshot is delivered on the owner's
     * thread only while the editor is still there and no newer request replaced this one.</p>
     *
     * @param presence whether the editor still exists when the answer arrives
     * @param read     the read that runs on the worker
     * @param delivery applies the snapshot on the thread that draws the editor
     */
    public void start(
            EditorPresence presence,
            ConfigurationRead<S> read,
            Delivery<S> delivery
    ) {
        Objects.requireNonNull(presence, "The editor presence is required");
        Objects.requireNonNull(read, "The configuration read is required");
        Objects.requireNonNull(delivery, "The delivery is required");
        queue.submit(
                read::read,
                snapshot -> {
                    if (!presence.isAlive()) {
                        return;
                    }
                    delivery.deliver(snapshot);
                }
        );
    }

    /**
     * Finds the entry one editor was opened for.
     *
     * @param entries     entries of the loaded configuration
     * @param requestedId identifier the editor was opened for, or {@code null} for none
     * @param identifier  reads the identifier of one entry
     * @return the matching entry, or {@code null} when the configuration no longer holds it
     */
    public static <T> T select(List<T> entries, String requestedId, Identifier<T> identifier) {
        if (requestedId == null) {
            return null;
        }
        for (T entry : entries) {
            if (requestedId.equals(identifier.id(entry))) {
                return entry;
            }
        }
        return null;
    }
}
