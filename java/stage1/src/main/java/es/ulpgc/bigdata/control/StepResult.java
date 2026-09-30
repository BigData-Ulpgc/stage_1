package es.ulpgc.bigdata.control;

import java.util.Objects;

/**
 * Qué hizo un paso del pipeline.
 *
 * @param action  lo que ocurrió
 * @param bookId  el libro afectado (-1 si no había nada que hacer)
 * @param detail  mensaje del error, o "" si no lo hubo
 */
public record StepResult(Action action, int bookId, String detail) {

    public enum Action {
        /** Indexado y marcado en indexed_books.txt. */
        INDEXED,
        /** Descargado, guardado en el datalake y marcado en downloaded_books.txt. */
        DOWNLOADED,
        /** Gutenberg no lo tiene o no tiene marcadores: no se marca, se salta en esta ejecución. */
        NOT_AVAILABLE,
        /** El control dice "descargado" pero el libro no está en el datalake. */
        MISSING_FROM_DATALAKE,
        /** La descarga lanzó un error (red, disco...): no se marca. */
        DOWNLOAD_FAILED,
        /** La indexación lanzó un error: no se marca. */
        INDEX_FAILED,
        /** No quedaba nada por indexar ni por descargar. */
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

    /** true si el paso terminó bien y marcó algo en el control. */
    public boolean marked() {
        return action == Action.INDEXED || action == Action.DOWNLOADED;
    }
}