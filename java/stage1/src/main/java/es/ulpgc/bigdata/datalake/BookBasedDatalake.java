package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Datalake que organiza los libros con la estructura más simple:
 * un directorio por libro, nombrado con su id.
 *   <root>/<id>/header.txt
 *   <root>/<id>/body.txt
 */
public class BookBasedDatalake {

    private final Path root;

    public BookBasedDatalake(Path root) {
        this.root = root;
    }

    public BookLocation save(RawBook book) {
        Path bookDir = root.resolve(String.valueOf(book.id()));
        Path headerPath = bookDir.resolve("header.txt");
        Path bodyPath = bookDir.resolve("body.txt");

        try {
            Files.createDirectories(bookDir);
            Files.writeString(headerPath, book.header(), StandardCharsets.UTF_8);
            Files.writeString(bodyPath, book.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No se pudo guardar el libro " + book.id(), e);
        }

        return new BookLocation(book.id(), headerPath, bodyPath);
    }

    public Optional<BookLocation> locate(int bookId) {
        Path bookDir = root.resolve(String.valueOf(bookId));
        Path headerPath = bookDir.resolve("header.txt");
        Path bodyPath = bookDir.resolve("body.txt");

        if (Files.exists(headerPath) && Files.exists(bodyPath)) {
            return Optional.of(new BookLocation(bookId, headerPath, bodyPath));
        }
        return Optional.empty();
    }
}