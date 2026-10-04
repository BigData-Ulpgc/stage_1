package es.ulpgc.bigdata;

import es.ulpgc.bigdata.config.AppConfig;
import es.ulpgc.bigdata.control.BookIdList;
import es.ulpgc.bigdata.control.StepResult;
import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The offline pipeline on the real sample_dataset/ (15 books, no network), assembled by
 * SearchEngine.openOffline exactly as `Main pipeline --offline` does. It checks what
 * sample_dataset/README.md promises:
 *   - the split of every raw/pg<ID>.txt is byte-identical to book/<ID>/header.txt and body.txt
 *   - the index has exactly 30,396 distinct terms and 89,727 postings
 */
class SampleDatasetTest {

    private static final int EXPECTED_TERMS = 30_396;
    private static final int EXPECTED_POSTINGS = 89_727;

    @TempDir Path tmp;

    private AppConfig config() {
        Properties p = new Properties();
        p.setProperty(AppConfig.DATA_DIR, tmp.resolve("data").toString());
        p.setProperty(AppConfig.DATALAKE_STRUCTURE, "book");               // same layout as sample_dataset/book
        p.setProperty(AppConfig.INDEX_STRUCTURE, "memory");                // to count terms and postings
        return AppConfig.fromProperties(p);
    }

    @Test
    void elPipelineOfflineReproduceLaMuestraYSusValoresDeReferencia() throws IOException {
        AppConfig config = config();
        List<Integer> ids = BookIdList.load(config.sampleBookIdsFile());
        assertEquals(15, ids.size());

        try (SearchEngine engine = SearchEngine.openOffline(config)) {
            List<StepResult> steps = engine.pipeline().runUntilIdle(100);

            assertEquals(30, steps.size());                                  // 15 downloads + 15 indexings
            assertTrue(steps.stream().allMatch(r -> r.action() == StepResult.Action.DOWNLOADED
                    || r.action() == StepResult.Action.INDEXED), steps.toString());
            assertEquals(ids.size(), engine.control().indexed().size());

            // The split: byte for byte the expected output of the sample
            Path expected = config.sampleDir().resolve("book");
            for (int id : ids) {
                for (String file : List.of("header.txt", "body.txt")) {
                    Path got = config.datalakeDir().resolve(String.valueOf(id)).resolve(file);
                    assertEquals(-1L, Files.mismatch(got, expected.resolve(String.valueOf(id)).resolve(file)),
                            "book " + id + "/" + file + " differs from sample_dataset/book");
                }
            }

            // The tokenizer and the index: the reference values
            InMemoryInvertedIndex index = (InMemoryInvertedIndex) engine.index();
            long postings = index.terms().stream().mapToLong(t -> index.postings(t).size()).sum();
            assertEquals(EXPECTED_TERMS, index.termCount());
            assertEquals(EXPECTED_POSTINGS, postings);

            // A search and the metadata, the same answer as the C++ module
            assertEquals(List.of(76, 84, 2701), engine.search().search("whale island"));
            assertEquals("Moby Dick; Or, The Whale",
                    engine.metadata().findById(2701).map(BookMetadata::title).orElseThrow());
        }
    }
}
