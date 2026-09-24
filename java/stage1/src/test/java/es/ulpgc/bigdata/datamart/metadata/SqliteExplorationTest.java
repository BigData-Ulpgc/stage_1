package es.ulpgc.bigdata.datamart.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reto 14: SQLite "a mano" con JDBC, sin repositorio todavía.
 * Cada test abre la base, hace algo y la cierra, como haría un programa real.
 */
class SqliteExplorationTest {

    private static final String INSERT_BOOK =
            "INSERT INTO books (book_id, title, author, language, release_date, body_path, header_path) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    @TempDir Path tmp;

    private Connection open() throws SQLException {
        return DriverManager.getConnection(SqliteSchema.jdbcUrl(tmp.resolve("metadata.db")));
    }

    /** Conexión con el esquema ya creado. */
    private Connection openWithSchema() throws SQLException {
        Connection c = open();
        SqliteSchema.create(c);
        return c;
    }

    private static void insert(PreparedStatement ps, int id, String title, String author) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, title);
        if (author == null) {
            ps.setNull(3, Types.VARCHAR);
        } else {
            ps.setString(3, author);
        }
        ps.setString(4, "English");
        ps.setString(5, "June 1, 1998");
        ps.setString(6, "datalake/book/" + id + "/body.txt");
        ps.setString(7, "datalake/book/" + id + "/header.txt");
        ps.executeUpdate();
    }

    private static int count(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM books")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------
    // Criterio: cerrar, reabrir y recuperar la fila
    // ------------------------------------------------------------------

    @Test
    void laFilaSobreviveAlCerrarYReabrir() throws SQLException {
        // 1ª "ejecución del programa": crea, inserta y cierra.
        try (Connection c = openWithSchema();
             PreparedStatement ps = c.prepareStatement(INSERT_BOOK)) {
            insert(ps, 1342, "Pride and Prejudice", "Jane Austen");
        }
        assertTrue(Files.exists(tmp.resolve("metadata.db")), "la base es un fichero en disco");

        // 2ª "ejecución": conexión nueva, mismo fichero.
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT title, author, language, release_date, body_path, header_path "
                   + "FROM books WHERE book_id = ?")) {
            ps.setInt(1, 1342);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "la fila debería existir");
                assertEquals("Pride and Prejudice", rs.getString("title"));
                assertEquals("Jane Austen", rs.getString("author"));
                assertEquals("English", rs.getString("language"));
                assertEquals("June 1, 1998", rs.getString("release_date"));
                assertEquals("datalake/book/1342/body.txt", rs.getString("body_path"));
                assertEquals("datalake/book/1342/header.txt", rs.getString("header_path"));
                assertFalse(rs.next(), "sólo debería haber una fila");
            }
        }
    }

    @Test
    void crearElEsquemaAlReabrirNoFallaNiBorraDatos() throws SQLException {
        try (Connection c = openWithSchema();
             PreparedStatement ps = c.prepareStatement(INSERT_BOOK)) {
            insert(ps, 1342, "Pride and Prejudice", "Jane Austen");
        }
        try (Connection c = openWithSchema()) {        // CREATE ... IF NOT EXISTS otra vez
            assertEquals(1, count(c));
        }
    }

    // ------------------------------------------------------------------
    // Criterio: qué protege PreparedStatement y por qué es reutilizable
    // ------------------------------------------------------------------

    @Test
    void concatenarSqlSeRompeConUnApostrofe() throws SQLException {
        String title = "Tom's Adventures";
        try (Connection c = openWithSchema(); Statement st = c.createStatement()) {
            // MAL: el ' del título cierra la cadena SQL antes de tiempo.
            String sql = "INSERT INTO books (book_id, title) VALUES (1, '" + title + "')";
            assertThrows(SQLException.class, () -> st.executeUpdate(sql));
        }
    }

    @Test
    void preparedStatementGuardaElTextoTalCualAunqueParezcaSql() throws SQLException {
        String malicious = "Robert'); DROP TABLE books;--";
        try (Connection c = openWithSchema()) {
            try (PreparedStatement ps = c.prepareStatement(INSERT_BOOK)) {
                insert(ps, 1, malicious, "Tom's Mother");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT title, author FROM books WHERE book_id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(malicious, rs.getString("title"));      // guardado literalmente
                    assertEquals("Tom's Mother", rs.getString("author"));
                }
            }
            assertEquals(1, count(c));                                   // la tabla sigue ahí
        }
    }

    @Test
    void unMismoPreparedStatementSirveParaMuchasFilas() throws SQLException {
        try (Connection c = openWithSchema();
             PreparedStatement ps = c.prepareStatement(INSERT_BOOK)) {  // se compila UNA vez
            insert(ps, 84, "Frankenstein", "Mary Shelley");
            insert(ps, 158, "Emma", "Jane Austen");
            insert(ps, 1342, "Pride and Prejudice", "Jane Austen");     // sólo cambian los valores
            assertEquals(3, count(c));
        }
    }

    @Test
    void nullSeGuardaComoNullYNoComoTexto() throws SQLException {
        try (Connection c = openWithSchema()) {
            try (PreparedStatement ps = c.prepareStatement(INSERT_BOOK)) {
                insert(ps, 16328, "Beowulf", null);
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT author FROM books WHERE book_id = 16328")) {
                assertTrue(rs.next());
                assertNull(rs.getString("author"));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM books WHERE author IS NULL")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    // ------------------------------------------------------------------
    // Pregunta: ¿qué aporta una PRIMARY KEY?
    // ------------------------------------------------------------------

    @Test
    void primaryKeyImpideDosFilasConElMismoId() throws SQLException {
        try (Connection c = openWithSchema();
             PreparedStatement ps = c.prepareStatement(INSERT_BOOK)) {
            insert(ps, 1342, "Pride and Prejudice", "Jane Austen");

            SQLException e = assertThrows(SQLException.class,
                    () -> insert(ps, 1342, "Otra cosa", "Otro autor"));
            assertTrue(e.getMessage().contains("UNIQUE constraint failed"), e.getMessage());
            assertEquals(1, count(c));
        }
    }

    // ------------------------------------------------------------------
    // Índices: existen y SQLite los usa
    // ------------------------------------------------------------------

    @Test
    void existenLosIndicesDelContrato() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Connection c = openWithSchema();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'books' ORDER BY name")) {
            while (rs.next()) {
                indexes.add(rs.getString("name"));
            }
        }
        assertEquals(List.of("idx_books_author", "idx_books_title"), indexes);
    }

    /** Pregunta a SQLite cómo ejecutaría una consulta, sin ejecutarla. */
    private String queryPlan(Connection c, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString("detail")).append('\n');
            }
        }
        return plan.toString();
    }

    @Test
    void sqliteUsaLosIndicesSoloCuandoPuede() throws SQLException {
        try (Connection c = openWithSchema()) {
            // Buscar por id: va directo por la clave primaria.
            assertTrue(queryPlan(c, "SELECT * FROM books WHERE book_id = 1342")
                    .contains("USING INTEGER PRIMARY KEY"));

            // Igualdad por autor: usa idx_books_author.
            assertTrue(queryPlan(c, "SELECT * FROM books WHERE author = 'Jane Austen'")
                    .contains("USING INDEX idx_books_author"));

            // LIKE con % al principio: no puede usar el índice y recorre toda la tabla.
            String likePlan = queryPlan(c, "SELECT * FROM books WHERE author LIKE '%austen%'");
            assertTrue(likePlan.contains("SCAN books"), likePlan);
            assertFalse(likePlan.contains("idx_books_author"), likePlan);
        }
    }
}