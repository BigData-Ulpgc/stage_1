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
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datamart.index.Indexer;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.query.SearchService;

import java.util.Objects;

/**
 * El sistema completo montado a partir de un AppConfig: el ÚNICO sitio donde se conectan
 * las piezas. Main lo usa con Gutenberg de verdad; la prueba end-to-end, con una BookSource
 * falsa (sin red). Así la prueba ejercita exactamente el mismo montaje que el programa.
 *
 * Dónde queda cada parte del estado (todo bajo data.dir, ver AppConfig):
 *   datalake   <data>/datalake/<estructura>/...        header y body de cada libro
 *   metadatos  <data>/datamarts/metadata.db             SQLite, tabla books
 *   índice     <data>/datamarts/inverted_index.json | inverted_index/ | colección de Mongo
 *   control    <data>/control/downloaded_books.txt, indexed_books.txt
 *
 * close() libera conexiones; NO es lo que hace persistente el estado: Indexer ya hace
 * flush de cada libro y ControlFiles escribe cada marca al momento.
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

    /** Con Project Gutenberg de verdad (lo que usa Main). */
    public static SearchEngine open(AppConfig config) {
        return open(config, new GutenbergClient(config.connectTimeout(), config.requestTimeout()));
    }

    /** Con cualquier fuente de libros (los tests pasan una sin red). */
    public static SearchEngine open(AppConfig config, BookSource source) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(source, "source");
        Tokenizer tokenizer = Tokenizer.fromStopwordsFile(config.stopwordsFile());
        Datalake datalake = DatalakeFactory.create(config);
        MetadataRepository metadata = new SqliteMetadataRepository(config.metadataDb());
        InvertedIndex index;
        try {
            index = InvertedIndexFactory.create(config);
        } catch (RuntimeException e) {
            metadata.close();                                  // no dejar SQLite abierto si falla el índice
            throw e;
        }
        ControlFiles control = new ControlFiles(config.controlDir());
        BookDownloader downloader = new BookDownloader(source, new BookSplitter(), datalake);
        Indexer indexer = new Indexer(datalake, new MetadataParser(), metadata, tokenizer, index);
        PipelineController pipeline = new PipelineController(control, downloader, indexer,
                BookIdList.load(config.bookIdsFile()));
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

    /** Cierra índice y SQLite; si el primero falla, el segundo se cierra igualmente. */
    @Override
    public void close() {
        try {
            index.close();
        } finally {
            metadata.close();
        }
    }
}
