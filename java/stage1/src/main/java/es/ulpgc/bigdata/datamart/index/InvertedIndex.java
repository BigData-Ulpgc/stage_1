package es.ulpgc.bigdata.datamart.index;

import java.util.List;
import java.util.Set;

/**
 * Índice invertido: término -> ids de los libros que lo contienen.
 *
 * El índice LÓGICO es siempre el mismo; cada implementación decide cómo
 * guardarlo (memoria, un JSON, un fichero por término, MongoDB). Quien busca
 * sólo usa addDocument y postings, y no sabe ni le importa dónde viven los datos.
 *
 * Qué significa flush en cada backend:
 *  - memory:       nada. No hay nada que persistir; los datos se pierden al cerrar.
 *  - monolithic:   reescribe el JSON COMPLETO (temporal + mover), con todos los términos.
 *  - hierarchical: escribe sólo los ficheros de los términos modificados desde el último flush.
 *  - mongo:        envía a la base las actualizaciones pendientes, si se acumularon en memoria.
 * En todos: después de flush, cerrar y reabrir el índice conserva todo lo añadido.
 *
 * Reglas que toda implementación cumple:
 *  - postings devuelve ids ordenados de menor a mayor, sin repetir; lista vacía si el término no existe.
 *  - postings refleja lo añadido aunque todavía no se haya hecho flush.
 *  - addDocument es idempotente: añadir otra vez el mismo libro no cambia nada.
 *  - clear deja el índice vacío, también en disco.
 */
public interface InvertedIndex extends AutoCloseable {

    /** Nombre de la estructura, como en el SPEC y el CSV del benchmark: "memory", "monolithic"... */
    String name();

    /** Añade el libro a la posting list de cada término (ya tokenizados). */
    void addDocument(int bookId, Set<String> terms);

    /** Ids de los libros que contienen el término: ordenados, sin repetir, inmodificables. */
    List<Integer> postings(String term);

    /** Hace persistente todo lo añadido hasta ahora. Ver tabla en el comentario de la interfaz. */
    void flush();

    /** Vacía el índice por completo, en memoria y en disco. */
    void clear();

    /** Bytes que ocupa el índice en disco ahora mismo (0 si no usa disco). Para el benchmark. */
    long diskUsageBytes();

    /** Libera recursos (conexiones, ficheros abiertos). No implica flush. */
    @Override
    void close();
}