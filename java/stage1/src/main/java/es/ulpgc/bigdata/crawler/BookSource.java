package es.ulpgc.bigdata.crawler;

import java.util.Optional;

/**
 * Where the full text of a book comes from.
 *
 * GutenbergClient is the real implementation (HTTP). In the tests it is replaced
 * by a lambda, so BookDownloader is tested without network.
 */
@FunctionalInterface
public interface BookSource {

    /**
     * @return the text of the book, or empty if it is not available
     *         (for example, the server answered 404).
     *         A network failure is signalled with an exception, not with empty.
     */
    Optional<String> fetch(int bookId);
}