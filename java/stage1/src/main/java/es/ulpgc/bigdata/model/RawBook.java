package es.ulpgc.bigdata.model;

/**
 * Representa un libro justo después de descargarlo y separar
 * header/body, ANTES de guardarlo en el datalake.
 */
public record RawBook(int id, String header, String body) {
}