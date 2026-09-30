package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.crawler.BookDownloader;
import es.ulpgc.bigdata.datamart.index.Indexer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Decide qué hacer a continuación y se lo pide a quien sabe hacerlo.
 * No descarga, no parsea, no indexa: sólo coordina ControlFiles, BookDownloader e Indexer.
 *
 * Cada step() hace COMO MÁXIMO una operación principal:
 *   1. Si hay libros descargados sin indexar -> indexa el primero y marca "indexed".
 *   2. Si no, si queda algún libro del dataset sin descargar -> lo descarga y marca "downloaded".
 *   3. Si no, no hace nada (IDLE).
 *
 * Las marcas se escriben SÓLO después de que la operación termine bien.
 * Todo lo necesario para continuar tras un reinicio está en los ficheros de control.
 */
public class PipelineController {

    private final ControlFiles control;
    private final BookDownloader downloader;
    private final Indexer indexer;
    private final List<Integer> dataset;

    /**
     * Libros que fallaron en ESTA ejecución: se saltan para no reintentar en bucle el mismo.
     * No se guarda en disco: al reiniciar el programa se vuelven a intentar.
     */
    private final Set<Integer> skippedThisRun = new HashSet<>();

    /**
     * @param dataset ids a descargar, en orden (normalmente BookIdList.load(shared/book_ids.txt)).
     *                No se usan ids aleatorios: el SPEC fija el dataset común.
     */
    public PipelineController(ControlFiles control, BookDownloader downloader, Indexer indexer,
                              List<Integer> dataset) {
        this.control = Objects.requireNonNull(control, "control");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.indexer = Objects.requireNonNull(indexer, "indexer");
        this.dataset = List.copyOf(Objects.requireNonNull(dataset, "dataset"));
    }

    /** Un paso del pipeline: como mucho una descarga o una indexación. */
    public StepResult step() {
        OptionalInt toIndex = nextToIndex();
        if (toIndex.isPresent()) {
            return index(toIndex.getAsInt());
        }
        OptionalInt toDownload = nextToDownload();
        if (toDownload.isPresent()) {
            return download(toDownload.getAsInt());
        }
        return StepResult.idle();
    }

    /** Repite step() hasta que no quede nada o se llegue a maxSteps. */
    public List<StepResult> runUntilIdle(int maxSteps) {
        List<StepResult> results = new ArrayList<>();
        for (int i = 0; i < maxSteps; i++) {
            StepResult result = step();
            if (result.action() == StepResult.Action.IDLE) {
                break;
            }
            results.add(result);
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Qué toca ahora
    // ------------------------------------------------------------------

    /** Primer libro de downloaded − indexed que no haya fallado en esta ejecución. */
    private OptionalInt nextToIndex() {
        for (int id : control.readyToIndex()) {
            if (!skippedThisRun.contains(id)) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }

    /** Primer libro del dataset, en su orden, aún no descargado ni fallido en esta ejecución. */
    private OptionalInt nextToDownload() {
        for (int id : dataset) {
            if (!control.isDownloaded(id) && !skippedThisRun.contains(id)) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }

    // ------------------------------------------------------------------
    // Las dos operaciones: llamar, y marcar SÓLO si terminó bien
    // ------------------------------------------------------------------

    private StepResult index(int bookId) {
        try {
            if (indexer.index(bookId).isEmpty()) {         // el control mentía: no está en el datalake
                skippedThisRun.add(bookId);
                return StepResult.of(StepResult.Action.MISSING_FROM_DATALAKE, bookId);
            }
        } catch (RuntimeException e) {
            skippedThisRun.add(bookId);                    // no se marca: se reintentará al reiniciar
            return StepResult.failed(StepResult.Action.INDEX_FAILED, bookId, e);
        }
        control.markIndexed(bookId);                       // sólo aquí: el Indexer ya hizo flush
        return StepResult.of(StepResult.Action.INDEXED, bookId);
    }

    private StepResult download(int bookId) {
        try {
            if (downloader.download(bookId).isEmpty()) {   // 404 o sin marcadores: nada guardado
                skippedThisRun.add(bookId);
                return StepResult.of(StepResult.Action.NOT_AVAILABLE, bookId);
            }
        } catch (RuntimeException e) {
            skippedThisRun.add(bookId);
            return StepResult.failed(StepResult.Action.DOWNLOAD_FAILED, bookId, e);
        }
        control.markDownloaded(bookId);                    // sólo aquí: el libro ya está en el datalake
        return StepResult.of(StepResult.Action.DOWNLOADED, bookId);
    }
}