package es.ulpgc.bigdata.crawler;

import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.model.BookLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * Primer componente compuesto: descarga un libro, lo separa y lo guarda.
 *
 *   fetch  -> si hay texto,    split
 *   split  -> si es válido,    save
 *   save   -> BookLocation
 *
 * No hace nada por sí mismo: coordina tres piezas que recibe por constructor.
 * Tampoco toca ficheros de control; eso es responsabilidad del reto 24.
 */
public class BookDownloader {

    private final BookSource source;
    private final BookSplitter splitter;
    private final Datalake datalake;

    public BookDownloader(BookSource source, BookSplitter splitter, Datalake datalake) {
        this.source = Objects.requireNonNull(source, "source");
        this.splitter = Objects.requireNonNull(splitter, "splitter");
        this.datalake = Objects.requireNonNull(datalake, "datalake");
    }

    /**
     * @return dónde quedó guardado el libro, o vacío si no estaba disponible
     *         o su texto no tenía los marcadores de Gutenberg. En ambos casos
     *         no se escribe nada en el datalake.
     * @throws RuntimeException si falla la red o el disco: el libro no queda
     *         guardado y quien llama decide si reintentar.
     */
    public Optional<BookLocation> download(int bookId) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        return source.fetch(bookId)                        // Optional<String>
                .flatMap(text -> splitter.split(bookId, text))  // Optional<RawBook>
                .map(datalake::save);                      // Optional<BookLocation>
    }
}