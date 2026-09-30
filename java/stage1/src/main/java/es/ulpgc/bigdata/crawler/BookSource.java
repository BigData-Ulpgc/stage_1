package es.ulpgc.bigdata.crawler;

import java.util.Optional;

/**
 * De dónde sale el texto completo de un libro.
 *
 * GutenbergClient es la implementación real (HTTP). En los tests se sustituye
 * por una lambda, así BookDownloader se prueba sin red.
 */
@FunctionalInterface
public interface BookSource {

    /**
     * @return el texto del libro, o vacío si no está disponible
     *         (por ejemplo, el servidor respondió 404).
     *         Un fallo de red se señala con una excepción, no con vacío.
     */
    Optional<String> fetch(int bookId);
}