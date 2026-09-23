package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Lo que comparten los datalakes basados en ficheros (book, range, time).
 * Cada subclase sólo decide DÓNDE va cada libro; CÓMO se escribe y QUÉ
 * cuenta como libro guardado se define aquí, una sola vez.
 *
 * Definición de "libro guardado": header y body existen como ficheros
 * regulares con su nombre definitivo. Un .tmp nunca cuenta.
 */
public abstract class AbstractFileDatalake implements Datalake {

    /** Sufijo de los ficheros a medio escribir. */
    static final String TMP_SUFFIX = ".tmp";

    /** Id que save() podría haber escrito: "0" o sin ceros delante, máx. 9 dígitos. */
    private static final Pattern CANONICAL_ID = Pattern.compile("0|[1-9][0-9]{0,8}");

    protected final Path root;

    protected AbstractFileDatalake(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    /**
     * Guarda un libro en las rutas indicadas por la subclase.
     * Header primero y body después: un libro sólo "existe" cuando ambos
     * están en su sitio, así que un corte a mitad deja un libro NO guardado.
     */
    protected BookLocation writeBook(RawBook book, Path header, Path body) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(book.header(), "header");
        Objects.requireNonNull(book.body(), "body");
        if (book.id() < 0) {
            throw new IllegalArgumentException("book_id negativo: " + book.id());
        }
        try {
            writeAtomically(header, book.header());
            writeAtomically(body, book.body());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el libro " + book.id(), e);
        }
        return new BookLocation(book.id(), header, body);
    }

    /**
     * Escribe content en target sin que nadie pueda ver nunca un target a medias:
     * crear directorio -> escribir target.tmp -> mover target.tmp a target.
     */
    static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // El sistema de ficheros no garantiza atomicidad: seguimos,
                // pero el hueco en el que target puede verse a medias ya no es cero.
                Files.move(tmp, target, REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);   // no dejar basura si algo falla
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    /** Única definición de "libro guardado", usada por locate y listBookIds. */
    protected static boolean isCompleteBook(Path header, Path body) {
        return Files.isRegularFile(header) && Files.isRegularFile(body);
    }

    protected static Optional<BookLocation> completeBook(int id, Path header, Path body) {
        return isCompleteBook(header, body)
                ? Optional.of(new BookLocation(id, header, body))
                : Optional.empty();
    }

    /** "1342" -> 1342 ; "0084", "abc", "-5", "99999999999" -> null. */
    protected static Integer parseCanonicalId(String text) {
        return CANONICAL_ID.matcher(text).matches() ? Integer.parseInt(text) : null;
    }

    /** ("1342.body.txt", ".body.txt") -> 1342 ; "1342.body.txt.tmp" -> null. */
    protected static Integer parseIdWithSuffix(String fileName, String suffix) {
        if (!fileName.endsWith(suffix)) {
            return null;
        }
        return parseCanonicalId(fileName.substring(0, fileName.length() - suffix.length()));
    }
}