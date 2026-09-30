package es.ulpgc.bigdata.datamart.metadata;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Esquema de la base de metadatos (shared/SPEC.md): tabla books + dos índices.
 * Todo usa IF NOT EXISTS, así que se puede ejecutar cada vez que se abre la base.
 */
public final class SqliteSchema {

    static final String CREATE_BOOKS_TABLE = """
            CREATE TABLE IF NOT EXISTS books (
                book_id      INTEGER PRIMARY KEY,
                title        TEXT,
                author       TEXT,
                language     TEXT,
                release_date TEXT,
                body_path    TEXT,
                header_path  TEXT
            )""";

    static final String CREATE_AUTHOR_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)";

    static final String CREATE_TITLE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)";

    private SqliteSchema() {
    }

    /** URL JDBC para un fichero de base de datos. */
    public static String jdbcUrl(Path dbFile) {
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    /** Crea tabla e índices si todavía no existen. */
    public static void create(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(CREATE_BOOKS_TABLE);
            st.execute(CREATE_AUTHOR_INDEX);
            st.execute(CREATE_TITLE_INDEX);
        }
    }
}