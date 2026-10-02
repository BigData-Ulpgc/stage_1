package es.ulpgc.bigdata.control;

import java.util.Objects;

/**
 * What a pipeline step did.
 *
 * @param action  what happened
 * @param bookId  the affected book (-1 if there was nothing to do)
 * @param detail  error message, or "" if there was none
 */
public record StepResult(Action action, int bookId, String detail) {

    public enum Action {
        /** Indexed and marked in indexed_books.txt. */
        INDEXED,
        /** Downloaded, saved in the datalake and marked in downloaded_books.txt. */
        DOWNLOADED,
        /** Gutenberg does not have it or it has no markers: not marked, skipped in this run. */
        NOT_AVAILABLE,
        /** The control says "downloaded" but the book is not in the datalake. */
        MISSING_FROM_DATALAKE,
        /** The download threw an error (network, disk...): not marked. */
        DOWNLOAD_FAILED,
        /** The indexing threw an error: not marked. */
        INDEX_FAILED,
        /** There was nothing left to index or download. */
        IDLE
    }

    public StepResult {
        Objects.requireNonNull(action, "action");
        detail = detail == null ? "" : detail;
    }

    static StepResult of(Action action, int bookId) {
        return new StepResult(action, bookId, "");
    }

    static StepResult failed(Action action, int bookId, RuntimeException error) {
        return new StepResult(action, bookId, error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    static StepResult idle() {
        return new StepResult(Action.IDLE, -1, "");
    }

    /** true if the step finished successfully and marked something in the control. */
    public boolean marked() {
        return action == Action.INDEXED || action == Action.DOWNLOADED;
    }
}