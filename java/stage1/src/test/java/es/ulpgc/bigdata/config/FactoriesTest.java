package es.ulpgc.bigdata.config;

import es.ulpgc.bigdata.crawler.BookDownloader;
import es.ulpgc.bigdata.crawler.BookSource;
import es.ulpgc.bigdata.crawler.BookSplitter;
import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datalake.RangeBasedDatalake;
import es.ulpgc.bigdata.datalake.TimeBasedDatalake;
import es.ulpgc.bigdata.datamart.index.HierarchicalFolderIndex;
import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.datamart.index.Indexer;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.MonolithicJsonIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.query.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FactoriesTest {

    @TempDir Path tmp;

    private AppConfig config(String datalake, String index) {
        Properties p = new Properties();
        p.setProperty(AppConfig.DATA_DIR, tmp.resolve("data").toString());
        p.setProperty(AppConfig.DATALAKE_STRUCTURE, datalake);
        p.setProperty(AppConfig.INDEX_STRUCTURE, index);
        return AppConfig.fromProperties(p);
    }

    // --- DatalakeFactory ------------------------------------------------------------------

    @Test
    void cadaNombreDaSuDatalakeEnSuCarpeta() {
        assertInstanceOf(BookBasedDatalake.class, DatalakeFactory.create(config("book", "memory")));
        assertInstanceOf(RangeBasedDatalake.class, DatalakeFactory.create(config("range", "memory")));
        assertInstanceOf(TimeBasedDatalake.class, DatalakeFactory.create(config("time", "memory")));
        for (String name : DatalakeFactory.NAMES) {
            assertEquals(name, DatalakeFactory.create(name, tmp, Clock.systemUTC()).name());   // nombre = el del SPEC
        }
    }

    @Test
    void unNombreDeDatalakeDesconocidoEsUnError() {
        assertThrows(IllegalArgumentException.class, () -> DatalakeFactory.create("folder", tmp, Clock.systemUTC()));
    }

    // --- InvertedIndexFactory -------------------------------------------------------------

    @Test
    void cadaNombreDaSuIndiceConLasRutasDeAppConfig() {
        AppConfig c = config("book", "monolithic");
        try (InvertedIndex mono = InvertedIndexFactory.create(c);
             InvertedIndex hier = InvertedIndexFactory.create("hierarchical", c);
             InvertedIndex mem = InvertedIndexFactory.create("memory", c)) {
            assertInstanceOf(MonolithicJsonIndex.class, mono);
            assertInstanceOf(HierarchicalFolderIndex.class, hier);
            assertInstanceOf(InMemoryInvertedIndex.class, mem);

            mono.addDocument(1, Set.of("whale"));
            mono.flush();
            hier.addDocument(1, Set.of("whale"));
            hier.flush();
            assertTrue(Files.exists(c.monolithicIndexFile()));
            assertTrue(Files.exists(c.hierarchicalIndexDir().resolve("W/whale.txt")));
        }
    }

    @Test
    void unNombreDeIndiceDesconocidoEsUnError() {
        assertThrows(IllegalArgumentException.class, () -> InvertedIndexFactory.create("sqlite", AppConfig.defaults()));
    }

    // --- Criterio: cambiar time->book o monolithic->hierarchical sin tocar la lógica -----------

    private static String gutenberg(String title, String body) {
        return "Title: " + title + "\nAuthor: Someone\nLanguage: English\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK X ***\n" + body
                + "\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\nfooter";
    }

    private static final Map<Integer, String> BOOKS = Map.of(
            10, gutenberg("Moby", "The whale and the ship"),
            20, gutenberg("Island", "A ship reached the island"),
            30, gutenberg("Love", "A love letter"));

    /** EXACTAMENTE el mismo código para cualquier configuración: sólo cambia el AppConfig. */
    private List<Integer> downloadIndexAndSearch(AppConfig c, String query) {
        BookSource fakeGutenberg = id -> Optional.ofNullable(BOOKS.get(id));
        Tokenizer tokenizer = new Tokenizer(Set.of("the", "and", "a"));
        Datalake datalake = DatalakeFactory.create(c);
        try (MetadataRepository metadata = new SqliteMetadataRepository(c.metadataDb());
             InvertedIndex index = InvertedIndexFactory.create(c)) {
            BookDownloader downloader = new BookDownloader(fakeGutenberg, new BookSplitter(), datalake);
            Indexer indexer = new Indexer(datalake, new MetadataParser(), metadata, tokenizer, index);
            for (int id : BOOKS.keySet()) {
                downloader.download(id);
                indexer.index(id);
            }
            return new SearchService(tokenizer, index).search(query);
        }
    }

    @Test
    void laMismaLogicaFuncionaConCualquierCombinacionDeEstructuras() {
        AppConfig timeMono = config("time", "monolithic");
        AppConfig bookHier = config("book", "hierarchical");

        assertEquals(List.of(10, 20), downloadIndexAndSearch(timeMono, "ship"));
        assertEquals(List.of(10, 20), downloadIndexAndSearch(bookHier, "ship"));

        assertTrue(Files.isDirectory(timeMono.datalakeDir()));                   // cada estructura en su carpeta
        assertTrue(Files.exists(bookHier.datalakeDir().resolve("10/body.txt")));
        assertTrue(Files.exists(timeMono.monolithicIndexFile()));
        assertTrue(Files.isDirectory(bookHier.hierarchicalIndexDir()));
    }
}
