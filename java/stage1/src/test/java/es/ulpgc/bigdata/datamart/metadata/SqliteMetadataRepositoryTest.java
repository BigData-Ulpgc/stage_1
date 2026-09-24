package es.ulpgc.bigdata.datamart.metadata;

import es.ulpgc.bigdata.model.BookMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMetadataRepositoryTest {

    @TempDir Path tmp;

    private Path dbFile;
    private MetadataRepository repo;      // se usa como interfaz, no como clase concreta

    @BeforeEach
    void open() {
        dbFile = tmp.resolve("datamarts/metadata.db");
        repo = new SqliteMetadataRepository(dbFile);
    }

    @AfterEach
    void close() {
        repo.close();
    }

    private static BookMetadata book(int id, String title, String author) {
        return new BookMetadata(id, title, author, "English", "June 1, 1998",
                Path.of("datalake/book/" + id + "/body.txt"),
                Path.of("datalake/book/" + id + "/header.txt"));
    }

    private static List<Integer> ids(List<BookMetadata> books) {
        return books.stream().map(BookMetadata::bookId).toList();
    }

    // --- findById -----------------------------------------------------------

    @Test
    void findByIdInexistenteDevuelveVacio() {
        assertTrue(repo.findById(999).isEmpty());
    }

    @Test
    void guardaYRecuperaTodosLosCampos() {
        BookMetadata original = book(1342, "Pride and Prejudice", "Jane Austen");
        repo.save(original);

        assertEquals(original, repo.findById(1342).orElseThrow());
    }

    @Test
    void camposNullSeConservanComoNull() {
        BookMetadata sinDatos = new BookMetadata(16328, "Beowulf", null, null, null, null, null);
        repo.save(sinDatos);

        assertEquals(sinDatos, repo.findById(16328).orElseThrow());
    }

    // --- save: idempotente --------------------------------------------------

    @Test
    void guardarOtraVezElMismoIdLoActualiza() {
        repo.save(book(1342, "Titulo provisional", "Anónimo"));
        repo.save(book(1342, "Pride and Prejudice", "Jane Austen"));

        assertEquals(1, repo.count());
        assertEquals("Pride and Prejudice", repo.findById(1342).orElseThrow().title());
    }

    @Test
    void persisteTrasCerrarYReabrir() {
        repo.save(book(84, "Frankenstein", "Mary Shelley"));
        repo.close();

        repo = new SqliteMetadataRepository(dbFile);

        assertEquals("Frankenstein", repo.findById(84).orElseThrow().title());
    }

    // --- Consultas por autor y título --------------------------------------

    @Test
    void findByAuthorDevuelveSusLibrosOrdenadosPorId() {
        repo.saveAll(List.of(
                book(1342, "Pride and Prejudice", "Jane Austen"),
                book(84, "Frankenstein", "Mary Shelley"),
                book(158, "Emma", "Jane Austen"),
                book(141, "Mansfield Park", "Jane Austen"),
                book(42324, "Frankenstein", "Mary Shelley")));

        assertEquals(List.of(141, 158, 1342), ids(repo.findByAuthor("Jane Austen")));
        assertEquals(List.of(84, 42324), ids(repo.findByAuthor("Mary Shelley")));
    }

    @Test
    void findByTitlePuedeDevolverVariosLibros() {
        repo.saveAll(List.of(
                book(42324, "Frankenstein", "Mary Shelley"),
                book(84, "Frankenstein", "Mary Shelley"),
                book(158, "Emma", "Jane Austen")));

        assertEquals(List.of(84, 42324), ids(repo.findByTitle("Frankenstein")));
        assertEquals(List.of(158), ids(repo.findByTitle("Emma")));
    }

    @Test
    void lasConsultasSonDeIgualdadExacta() {
        repo.save(book(158, "Emma", "Jane Austen"));

        assertEquals(List.of(), repo.findByAuthor("Austen"));        // no es "contiene"
        assertEquals(List.of(), repo.findByAuthor("jane austen"));   // distingue mayúsculas
        assertEquals(List.of(), repo.findByTitle("Em"));
    }

    @Test
    void consultasSinResultadosONullDevuelvenListaVacia() {
        repo.save(new BookMetadata(16328, "Beowulf", null, null, null, null, null));

        assertEquals(List.of(), repo.findByAuthor("Nadie"));
        assertEquals(List.of(), repo.findByAuthor(null));
        assertEquals(List.of(), repo.findByTitle(null));
    }

    // --- saveAll: una transacción ------------------------------------------

    @Test
    void saveAllGuardaTodoElLote() {
        repo.saveAll(List.of(book(1, "A", "X"), book(2, "B", "Y"), book(3, "C", "X")));

        assertEquals(3, repo.count());
    }

    @Test
    void saveAllEsTodoONadaSiFallaLaBaseDeDatos() throws SQLException {
        // Un trigger hace que SQLite rechace el libro 3 cuando las filas 1 y 2
        // ya se han enviado a la base. Sin transacción, el 1 y el 2 quedarían guardados.
        try (Connection c = DriverManager.getConnection(SqliteSchema.jdbcUrl(dbFile));
             Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TRIGGER falla_con_el_3 BEFORE INSERT ON books
                    WHEN NEW.book_id = 3
                    BEGIN SELECT RAISE(ABORT, 'fallo simulado'); END""");
        }

        List<BookMetadata> batch = List.of(book(1, "A", "X"), book(2, "B", "Y"), book(3, "C", "Z"));

        assertThrows(MetadataRepositoryException.class, () -> repo.saveAll(batch));
        assertEquals(0, repo.count());
    }

    @Test
    void saveAllEsTodoONadaSiFallaUnElementoDelLote() {
        // Aquí el fallo ocurre en Java, al preparar el tercer elemento.
        // Si saveAll llamara a save() fila a fila, el 1 y el 2 quedarían guardados.
        List<BookMetadata> batch = Arrays.asList(book(1, "A", "X"), book(2, "B", "Y"), null);

        assertThrows(NullPointerException.class, () -> repo.saveAll(batch));

        assertEquals(0, repo.count());
    }

    @Test
    void trasUnSaveAllFallidoSaveSigueConfirmandoAlMomento() {
        List<BookMetadata> batch = Arrays.asList(book(1, "A", "X"), null);
        assertThrows(NullPointerException.class, () -> repo.saveAll(batch));

        repo.save(book(84, "Frankenstein", "Mary Shelley"));
        repo.close();
        repo = new SqliteMetadataRepository(dbFile);        // ¿llegó al disco?

        assertEquals(List.of(84), ids(repo.findByAuthor("Mary Shelley")));
    }

    @Test
    void saveAllVacioNoHaceNada() {
        repo.saveAll(List.of());
        assertEquals(0, repo.count());
    }

    // --- count y clear ------------------------------------------------------

    @Test
    void clearVaciaLaTablaYElRepositorioSigueFuncionando() {
        repo.saveAll(List.of(book(1, "A", "X"), book(2, "B", "Y")));

        repo.clear();
        assertEquals(0, repo.count());
        assertTrue(repo.findById(1).isEmpty());

        repo.save(book(3, "C", "Z"));                       // tabla e índices siguen ahí
        assertEquals(1, repo.count());
    }
}