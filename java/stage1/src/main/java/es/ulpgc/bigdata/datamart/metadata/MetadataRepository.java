package es.ulpgc.bigdata.datamart.metadata;

import es.ulpgc.bigdata.model.BookMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Almacén de metadatos de libros. El resto del sistema sólo conoce esta interfaz:
 * no sabe si detrás hay SQLite, otra base de datos o memoria.
 *
 * Reglas que toda implementación debe cumplir (y que el benchmark da por hechas):
 *  - save/saveAll son idempotentes: guardar otra vez un book_id lo actualiza.
 *  - saveAll es todo o nada: si falla una fila, no se guarda ninguna.
 *  - findByAuthor/findByTitle buscan por igualdad exacta y devuelven
 *    los resultados ordenados por book_id.
 */
public interface MetadataRepository extends AutoCloseable {

    void save(BookMetadata metadata);

    void saveAll(List<BookMetadata> batch);

    Optional<BookMetadata> findById(int bookId);

    List<BookMetadata> findByAuthor(String author);

    List<BookMetadata> findByTitle(String title);

    long count();

    void clear();

    /** Sin "throws Exception": cerrar el repositorio no obliga a capturar nada. */
    @Override
    void close();
}