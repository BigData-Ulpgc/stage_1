package es.ulpgc.bigdata.model;

import java.nio.file.Path;

/**
 * Datos descriptivos de un libro, extraídos del header y de su
 * ubicación en el datalake. Los campos ausentes en el header son null,
 * según el contrato común (shared/SPEC.md).
 */

public record BookMetadata(
        int bookId,
        String title,
        String author,
        String language,
        String releaseDate,
        Path bodyPath,
        Path headerPath
) {
}