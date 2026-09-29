package es.ulpgc.bigdata.datamart.metadata;

import es.ulpgc.bigdata.model.BookMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.Objects;
import java.util.Optional;

/**
 * MetadataRepository sobre un fichero SQLite (shared/SPEC.md: tabla books).
 *
 * Mantiene UNA conexión abierta desde el constructor hasta close().
 * No es seguro usarlo desde varios hilos a la vez.
 */
public class SqliteMetadataRepository implements MetadataRepository {

    /**
     * Upsert: inserta la fila, y si ese book_id ya existe, actualiza sus columnas.
     * "excluded" es la fila que se intentaba insertar.
     */
    private static final String UPSERT = """
            INSERT INTO books (book_id, title, author, language, release_date, body_path, header_path)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(book_id) DO UPDATE SET
                title        = excluded.title,
                author       = excluded.author,
                language     = excluded.language,
                release_date = excluded.release_date,
                body_path    = excluded.body_path,
                header_path  = excluded.header_path""";

    private static final String SELECT_COLUMNS =
            "SELECT book_id, title, author, language, release_date, body_path, header_path FROM books ";

    private static final String FIND_BY_ID = SELECT_COLUMNS + "WHERE book_id = ?";
    private static final String FIND_BY_AUTHOR = SELECT_COLUMNS + "WHERE author = ? ORDER BY book_id";
    private static final String FIND_BY_TITLE = SELECT_COLUMNS + "WHERE title = ? ORDER BY book_id";

    private final Connection connection;

    public SqliteMetadataRepository(Path dbFile) {
        Objects.requireNonNull(dbFile, "dbFile");
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);        // p. ej. data/datamarts/
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la carpeta de " + dbFile, e);
        }
        try {
            this.connection = DriverManager.getConnection(SqliteSchema.jdbcUrl(dbFile));
            SqliteSchema.create(connection);
        } catch (SQLException e) {
            throw new MetadataRepositoryException("No se pudo abrir " + dbFile, e);
        }
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    @Override
    public void save(BookMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
            bind(ps, metadata);
            ps.executeUpdate();                          // autocommit: se confirma al momento
        } catch (SQLException e) {
            throw new MetadataRepositoryException("No se pudo guardar el libro " + metadata.bookId(), e);
        }
    }

    @Override
    public void saveAll(List<BookMetadata> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            return;
        }
        try {
            connection.setAutoCommit(false);             // abre UNA transacción
            try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
                for (BookMetadata metadata : batch) {
                    bind(ps, Objects.requireNonNull(metadata, "elemento null en el lote"));
                    ps.addBatch();                       // se acumula, aún no se envía
                }
                ps.executeBatch();                       // se envían todas las filas
                connection.commit();                     // UN solo commit para todo el lote
            } catch (SQLException | RuntimeException e) {
                connection.rollback();                   // todo o nada
                throw e;
            } finally {
                connection.setAutoCommit(true);          // save() vuelve a confirmar al momento
            }
        } catch (SQLException e) {
            throw new MetadataRepositoryException("No se pudo guardar el lote de " + batch.size() + " libros", e);
        }
    }

    /** Rellena los 7 huecos del UPSERT, en el orden de sus columnas. */
    private static void bind(PreparedStatement ps, BookMetadata m) throws SQLException {
        ps.setInt(1, m.bookId());
        setNullableString(ps, 2, m.title());
        setNullableString(ps, 3, m.author());
        setNullableString(ps, 4, m.language());
        setNullableString(ps, 5, m.releaseDate());
        setNullableString(ps, 6, m.bodyPath() == null ? null : m.bodyPath().toString());
        setNullableString(ps, 7, m.headerPath() == null ? null : m.headerPath().toString());
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    @Override
    public Optional<BookMetadata> findById(int bookId) {
        List<BookMetadata> found = query(FIND_BY_ID, ps -> ps.setInt(1, bookId));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<BookMetadata> findByAuthor(String author) {
        if (author == null) {
            return List.of();                            // "= NULL" nunca coincide en SQL
        }
        return query(FIND_BY_AUTHOR, ps -> ps.setString(1, author));
    }

    @Override
    public List<BookMetadata> findByTitle(String title) {
        if (title == null) {
            return List.of();
        }
        return query(FIND_BY_TITLE, ps -> ps.setString(1, title));
    }

    @Override
    public long count() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM books")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new MetadataRepositoryException("No se pudo contar los libros", e);
        }
    }

    @Override
    public void clear() {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM books");       // vacía la tabla; esquema e índices se quedan
        } catch (SQLException e) {
            throw new MetadataRepositoryException("No se pudo vaciar la tabla books", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new MetadataRepositoryException("No se pudo cerrar la base de datos", e);
        }
    }

    // ------------------------------------------------------------------
    // Auxiliares de lectura
    // ------------------------------------------------------------------

    /** Rellena los huecos de una consulta; puede lanzar SQLException. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** Ejecuta una SELECT y convierte cada fila en un BookMetadata, en orden. */
    private List<BookMetadata> query(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<BookMetadata> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readRow(rs));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new MetadataRepositoryException("Falló la consulta: " + sql, e);
        }
    }

    private static BookMetadata readRow(ResultSet rs) throws SQLException {
        String bodyPath = rs.getString("body_path");
        String headerPath = rs.getString("header_path");
        return new BookMetadata(
                rs.getInt("book_id"),
                rs.getString("title"),
                rs.getString("author"),
                rs.getString("language"),
                rs.getString("release_date"),
                bodyPath == null ? null : Path.of(bodyPath),
                headerPath == null ? null : Path.of(headerPath));
    }
}