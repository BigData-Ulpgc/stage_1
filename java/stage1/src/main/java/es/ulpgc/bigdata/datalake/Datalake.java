package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.util.List;
import java.util.Optional;

/**
 * Contrato común para cualquier organización física del datalake
 * (book, range, time). El resto del sistema depende de esta interfaz,
 * nunca de una implementación concreta.
 */
public interface Datalake {

    String name();

    BookLocation save(RawBook book);

    Optional<BookLocation> locate(int bookId);

    List<Integer> listBookIds();
}