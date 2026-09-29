package es.ulpgc.bigdata.datamart.metadata;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.BookMetadata;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae los metadatos de un header de Project Gutenberg (shared/SPEC.md).
 *
 * Reglas del contrato común (iguales en Java, Python y C):
 *  - se busca la PRIMERA línea que empieza por "Title:", "Author:", ...;
 *  - sólo cuenta la primera línea del valor;
 *  - se recortan los espacios; un campo ausente o vacío es null;
 *  - en release_date se descarta lo que va entre corchetes: "[eBook #1342]".
 *
 * No lee ficheros ni guarda nada: recibe texto y devuelve un BookMetadata.
 */
public class MetadataParser {

    // Las regex del SPEC, compiladas una sola vez al cargar la clase.
    // MULTILINE: ^ y $ significan principio y fin de CADA LÍNEA, no del texto entero.
    private static final Pattern TITLE =
            Pattern.compile("^Title:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern AUTHOR =
            Pattern.compile("^Author:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern RELEASE_DATE =
            Pattern.compile("^Release date:\\s*(.+?)(\\s*\\[.*)?$", Pattern.MULTILINE);
    private static final Pattern LANGUAGE =
            Pattern.compile("^Language:\\s*(.+)$", Pattern.MULTILINE);

    /**
     * @param location de dónde salen el id y las rutas (lo devolvió el datalake)
     * @param header   contenido del header.txt de ese libro
     */
    public BookMetadata parse(BookLocation location, String header) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(header, "header");

        String text = normalizeLineEndings(header);

        return new BookMetadata(
                location.id(),
                firstMatch(TITLE, text),
                firstMatch(AUTHOR, text),
                firstMatch(LANGUAGE, text),
                firstMatch(RELEASE_DATE, text),
                location.bodyPath(),
                location.headerPath());
    }

    /** CRLF (Windows) -> LF, para que ningún \r acabe dentro de un valor. */
    static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    /** Grupo 1 de la primera coincidencia, recortado; null si no hay o queda vacío. */
    static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).strip();
        return value.isEmpty() ? null : value;
    }
}