package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Datalake que organiza los libros con la estructura más simple:
 * un directorio por libro, nombrado con su id.
 *   <root>/<id>/header.txt
 *   <root>/<id>/body.txt
 */
public class BookBasedDatalake extends AbstractFileDatalake {

    private static final String HEADER_FILE = "header.txt";
    private static final String BODY_FILE = "body.txt";

    public BookBasedDatalake(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "book";
    }

    @Override
    public BookLocation save(RawBook book) {
        return writeBook(book, headerPath(book.id()), bodyPath(book.id()));
    }

    @Override
    public Optional<BookLocation> locate(int bookId) {
        if (bookId < 0) {
            return Optional.empty();
        }
        return completeBook(bookId, headerPath(bookId), bodyPath(bookId));
    }

    @Override
    public List<Integer> listBookIds() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .map(entry -> parseCanonicalId(entry.getFileName().toString()))
                    .filter(Objects::nonNull)
                    .filter(id -> locate(id).isPresent())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar " + root, e);
        }
    }

    private Path headerPath(int bookId) {
        return root.resolve(String.valueOf(bookId)).resolve(HEADER_FILE);
    }

    private Path bodyPath(int bookId) {
        return root.resolve(String.valueOf(bookId)).resolve(BODY_FILE);
    }
}