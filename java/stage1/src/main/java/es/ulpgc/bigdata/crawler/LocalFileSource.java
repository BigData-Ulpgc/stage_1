package es.ulpgc.bigdata.crawler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The offline counterpart of GutenbergClient: reads the raw Project Gutenberg files from a local
 * folder, named like the last part of the download URL, <dir>/pg<ID>.txt (sample_dataset/raw/).
 *
 * It returns the same text the URL would serve, untouched (the \r\n included), so everything
 * after the fetch (split, datalake, metadata, index, control) runs exactly as it does online.
 *
 * A missing file means the book is not available (empty, like a 404).
 * Any other read error is an exception, like a network failure.
 */
public class LocalFileSource implements BookSource {

    private final Path dir;

    public LocalFileSource(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    @Override
    public Optional<String> fetch(int bookId) {
        Path file = dir.resolve("pg" + bookId + ".txt");
        try {
            return Optional.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }
}
