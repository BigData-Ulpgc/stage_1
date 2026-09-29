package es.ulpgc.bigdata.model;

import java.nio.file.Path;

/**
 * Resultado de guardar un libro en el datalake: dónde quedaron
 * físicamente el header y el body. No contiene el texto, sólo las rutas.
 */
public record BookLocation(int id, Path headerPath, Path bodyPath) {
}
