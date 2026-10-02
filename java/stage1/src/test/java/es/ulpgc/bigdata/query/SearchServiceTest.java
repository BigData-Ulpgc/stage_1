package es.ulpgc.bigdata.query;

import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    /** The three books of the paper exercise. */
    private static final List<RawBook> PAPER_BOOKS = List.of(
            new RawBook(10, "", "red boat sails"),
            new RawBook(20, "", "blue boat sails fast"),
            new RawBook(30, "", "red island"));

    private final Tokenizer tokenizer = new Tokenizer(Set.of("the", "and", "of"));
    private InvertedIndex index;
    private SearchService search;

    @BeforeEach
    void setUp() {
        index = new InMemoryInvertedIndex();
        for (RawBook book : PAPER_BOOKS) {
            index.addDocument(book.id(), tokenizer.uniqueTerms(book.body()));
        }
        search = new SearchService(tokenizer, index);
    }

    // --- Challenge criteria ----------------------------------------------------

    @Test
    void boatSailsDevuelveLosLibrosConAmbosTerminos() {
        assertEquals(List.of(10, 20), search.search("boat sails"));
    }

    @Test
    void lasTresConsultasDelApendiceA() {
        assertEquals(List.of(10, 20), search.search("boat sails"));
        assertEquals(List.of(30), search.search("red island"));
        assertEquals(List.of(10), search.search("red boat"));
    }

    @Test
    void unTerminoInexistenteDaResultadoVacio() {
        assertEquals(List.of(), search.search("whale"));
        assertEquals(List.of(), search.search("boat whale"));   // even though "boat" exists
        assertEquals(List.of(), search.search("whale boat"));
    }

    @Test
    void laInterseccionEsLinealYNoCuadratica() {
        // 200,000 elements per list. With contains inside a loop it would be
        // about 40 billion comparisons: minutes. With two pointers, milliseconds.
        List<Integer> evens = IntStream.range(0, 200_000).map(i -> i * 2).boxed().toList();
        List<Integer> multiplesOf3 = IntStream.range(0, 200_000).map(i -> i * 3).boxed().toList();

        List<Integer> result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> SearchService.intersect(evens, multiplesOf3));

        assertEquals(List.of(0, 6, 12), result.subList(0, 3));
        assertEquals(66_667, result.size());                    // multiples of 6 below 400,000
    }

    // --- The query goes through the same tokenizer --------------------------

    @Test
    void mayusculasYPuntuacionNoCambianElResultado() {
        assertEquals(List.of(10, 20), search.search("BOAT, Sails!"));
    }

    @Test
    void terminosRepetidosEnLaConsultaNoCambianElResultado() {
        assertEquals(List.of(10, 20), search.search("boat boat BOAT"));
        assertEquals(search.search("boat sails"), search.search("sails boat sails"));
    }

    @Test
    void lasStopwordsDeLaConsultaSeIgnoran() {
        assertEquals(List.of(10, 20), search.search("the boat and the sails"));
    }

    @Test
    void consultaSinTerminosValidosDaResultadoVacio() {
        assertEquals(List.of(), search.search(""));
        assertEquals(List.of(), search.search("the of"));        // only stopwords
        assertEquals(List.of(), search.search("a ! ?"));         // nothing with length >= 2
    }

    @Test
    void unSoloTerminoDevuelveSuPostingList() {
        assertEquals(List.of(10, 30), search.search("red"));
    }

    @Test
    void tresTerminos() {
        assertEquals(List.of(20), search.search("boat sails fast"));
        assertEquals(List.of(), search.search("red boat blue"));
    }

    @Test
    void elResultadoNoSePuedeModificar() {
        assertThrows(UnsupportedOperationException.class, () -> search.search("red").add(99));
        assertEquals(List.of(10, 30), search.search("red"));    // the index has not been touched
    }

    // --- Stopping early -------------------------------------------------------

    @Test
    void dejaDePedirPostingsEnCuantoUnaEstaVacia() {
        SpyIndex spy = new SpyIndex(index);
        SearchService spied = new SearchService(tokenizer, spy);

        assertEquals(List.of(), spied.search("boat whale sails"));

        assertEquals(List.of("boat", "whale"), spy.requested);   // "sails" is not even requested
    }

    /** Index that records which terms are requested and delegates the rest to another index. */
    private static final class SpyIndex implements InvertedIndex {
        private final InvertedIndex delegate;
        private final List<String> requested = new ArrayList<>();

        SpyIndex(InvertedIndex delegate) { this.delegate = delegate; }

        @Override public List<Integer> postings(String term) {
            requested.add(term);
            return delegate.postings(term);
        }

        @Override public String name() { return "spy"; }
        @Override public void addDocument(int bookId, Set<String> terms) { delegate.addDocument(bookId, terms); }
        @Override public void flush() { }
        @Override public void clear() { }
        @Override public long diskUsageBytes() { return 0; }
        @Override public void close() { }
    }

    // --- intersect, with two small lists --------------------------------------

    @Test
    void intersectCasosBasicos() {
        assertEquals(List.of(3, 7), SearchService.intersect(List.of(1, 3, 5, 7), List.of(2, 3, 4, 7, 9)));
        assertEquals(List.of(), SearchService.intersect(List.of(1, 3), List.of(2, 4)));      // no common elements
        assertEquals(List.of(), SearchService.intersect(List.of(), List.of(1, 2)));          // one of them empty
        assertEquals(List.of(1, 2), SearchService.intersect(List.of(1, 2), List.of(1, 2)));  // equal
        assertEquals(List.of(5), SearchService.intersect(List.of(5), List.of(1, 2, 3, 4, 5, 6, 7)));
    }

    @Test
    void intersectFuncionaConIdsGrandes() {
        // With Integer == Integer (instead of int) this would fail: Java only reuses
        // the Integer objects between -128 and 127.
        assertEquals(List.of(1342, 84000), SearchService.intersect(
                List.of(84, 1342, 84000), new ArrayList<>(List.of(1342, 2000, 84000))));
    }
}