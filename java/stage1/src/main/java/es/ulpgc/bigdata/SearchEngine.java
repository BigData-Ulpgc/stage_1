package es.ulpgc.bigdata;

import es.ulpgc.bigdata.config.AppConfig;
import es.ulpgc.bigdata.config.DatalakeFactory;
import es.ulpgc.bigdata.config.InvertedIndexFactory;
import es.ulpgc.bigdata.control.BookIdList;
import es.ulpgc.bigdata.control.ControlFiles;
import es.ulpgc.bigdata.control.PipelineController;
import es.ulpgc.bigdata.crawler.BookDownloader;
import es.ulpgc.bigdata.crawler.BookSource;
import es.ulpgc.bigdata.crawler.BookSplitter;
import es.ulpgc.bigdata.crawler.GutenbergClient;
import es.ulpgc.bigdata.crawler.LocalFileSource;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datamart.index.Indexer;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.query.SearchService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The complete system assembled from an AppConfig: the ONLY place where the pieces
 * are connected. Main uses it with the real Gutenberg, or with sample_dataset/ in offline mode;
 * the end-to-end test, with a fake BookSource (no network). This way the test exercises exactly the same assembly as the program.
 *
 * Where each part of the state ends up (all under data.dir, see AppConfig):
 *   datalake   <data>/datalake/<structure>/...         header and body of each book
 *   metadata   <data>/datamarts/metadata.db             SQLite, books table
 *   index      <data>/datamarts/inverted_index.json | inverted_index/ | Mongo collection
 *   control    <data>/control/downloaded_books.txt, indexed_books.txt
 *
 * close() releases connections; it is NOT what makes the state persistent: Indexer already
 * flushes each book and ControlFiles writes each mark immediately.
 */
public final class SearchEngine implements AutoCloseable {

    private final Datalake datalake;
    private final MetadataRepository metadata;
    private final InvertedIndex index;
    private final ControlFiles control;
    private final PipelineController pipeline;
    private final SearchService search;

    private SearchEngine(Datalake datalake, MetadataRepository metadata, InvertedIndex index,
                         ControlFiles control, PipelineController pipeline, SearchService search) {
        this.datalake = datalake;
        this.metadata = metadata;
        this.index = index;
        this.control = control;
        this.pipeline = pipeline;
        this.search = search;
    }

    /** With the real Project Gutenberg (what Main uses). */
    public static SearchEngine open(AppConfig config) {
        return open(config, new GutenbergClient(config.connectTimeout(), config.requestTimeout()));
    }

    /**
     * Without network, with the sample dataset: the 15 books of <sample>/book_ids.txt, read from
     * <sample>/raw/. The data goes to the same data.dir as online: the raw files are byte-identical
     * to what Gutenberg serves, so a later online run simply finds those books already done.
     */
    public static SearchEngine openOffline(AppConfig config) {
        return open(config, new LocalFileSource(config.sampleRawDir()), config.sampleBookIdsFile());
    }

    /** With any book source (the tests pass one without network). */
    public static SearchEngine open(AppConfig config, BookSource source) {
        return open(config, source, config.bookIdsFile());
    }

    /** With any book source and the list of ids the pipeline downloads. */
    public static SearchEngine open(AppConfig config, BookSource source, Path bookIdsFile) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(source, "source");
        List<Integer> dataset = BookIdList.load(bookIdsFile);   // before opening anything: a bad file leaks nothing
        Tokenizer tokenizer = Tokenizer.fromStopwordsFile(config.stopwordsFile());
        Datalake datalake = DatalakeFactory.create(config);
        MetadataRepository metadata = new SqliteMetadataRepository(config.metadataDb());
        InvertedIndex index;
        try {
            index = InvertedIndexFactory.create(config);
        } catch (RuntimeException e) {
            metadata.close();                                  // do not leave SQLite open if the index fails
            throw e;
        }
        ControlFiles control = new ControlFiles(config.controlDir());
        BookDownloader downloader = new BookDownloader(source, new BookSplitter(), datalake);
        Indexer indexer = new Indexer(datalake, new MetadataParser(), metadata, tokenizer, index);
        PipelineController pipeline = new PipelineController(control, downloader, indexer, dataset);
        return new SearchEngine(datalake, metadata, index, control, pipeline,
                new SearchService(tokenizer, index));
    }

    public PipelineController pipeline() {
        return pipeline;
    }

    public SearchService search() {
        return search;
    }

    public MetadataRepository metadata() {
        return metadata;
    }

    public Datalake datalake() {
        return datalake;
    }

    public InvertedIndex index() {
        return index;
    }

    public ControlFiles control() {
        return control;
    }

    /** Closes index and SQLite; if the first one fails, the second is closed anyway. */
    @Override
    public void close() {
        try {
            index.close();
        } finally {
            metadata.close();
        }
    }
}
