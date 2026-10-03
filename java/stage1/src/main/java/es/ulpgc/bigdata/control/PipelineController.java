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
 * Decides what to do next and asks whoever knows how to do it.
 * It does not download, parse or index: it only coordinates ControlFiles, BookDownloader and Indexer.
 *
 * Each step() does AT MOST one main operation:
 *   1. If there are downloaded books not yet indexed -> indexes the first one and marks it "indexed".
 *   2. Otherwise, if some dataset book is still not downloaded -> downloads it and marks it "downloaded".
 *   3. Otherwise, it does nothing (IDLE).
 *
 * The marks are written ONLY after the operation finishes successfully.
 * Everything needed to continue after a restart is in the control files.
 */
public class PipelineController {

    private final ControlFiles control;
    private final BookDownloader downloader;
    private final Indexer indexer;
    private final List<Integer> dataset;

    /**
     * Books that failed in THIS run: they are skipped so the same one is not retried in a loop.
     * Not saved to disk: when the program restarts they are tried again.
     */
    private final Set<Integer> skippedThisRun = new HashSet<>();

    /**
     * @param dataset ids to download, in order (usually BookIdList.load(shared/book_ids.txt)).
     *                No random ids are used: the SPEC fixes the common dataset.
     */
    public PipelineController(ControlFiles control, BookDownloader downloader, Indexer indexer,
                              List<Integer> dataset) {
        this.control = Objects.requireNonNull(control, "control");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.indexer = Objects.requireNonNull(indexer, "indexer");
        this.dataset = List.copyOf(Objects.requireNonNull(dataset, "dataset"));
    }

    /** One pipeline step: at most one download or one indexing. */
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

    /** Repeats step() until nothing is left or maxSteps is reached. */
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
    // What comes next
    // ------------------------------------------------------------------

    /** First book of downloaded − indexed that has not failed in this run. */
    private OptionalInt nextToIndex() {
        for (int id : control.readyToIndex()) {
            if (!skippedThisRun.contains(id)) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }

    /** First dataset book, in its order, not yet downloaded nor failed in this run. */
    private OptionalInt nextToDownload() {
        for (int id : dataset) {
            if (!control.isDownloaded(id) && !skippedThisRun.contains(id)) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }

    // ------------------------------------------------------------------
    // The two operations: call, and mark ONLY if it finished successfully
    // ------------------------------------------------------------------

    private StepResult index(int bookId) {
        try {
            if (indexer.index(bookId).isEmpty()) {         // the control was lying: it is not in the datalake
                skippedThisRun.add(bookId);
                return StepResult.of(StepResult.Action.MISSING_FROM_DATALAKE, bookId);
            }
        } catch (RuntimeException e) {
            skippedThisRun.add(bookId);                    // not marked: it will be retried on restart
            return StepResult.failed(StepResult.Action.INDEX_FAILED, bookId, e);
        }
        control.markIndexed(bookId);                       // only here: the Indexer has already flushed
        return StepResult.of(StepResult.Action.INDEXED, bookId);
    }

    private StepResult download(int bookId) {
        try {
            if (downloader.download(bookId).isEmpty()) {   // 404 or no markers: nothing saved
                skippedThisRun.add(bookId);
                return StepResult.of(StepResult.Action.NOT_AVAILABLE, bookId);
            }
        } catch (RuntimeException e) {
            skippedThisRun.add(bookId);
            return StepResult.failed(StepResult.Action.DOWNLOAD_FAILED, bookId, e);
        }
        control.markDownloaded(bookId);                    // only here: the book is already in the datalake
        return StepResult.of(StepResult.Action.DOWNLOADED, bookId);
    }
}