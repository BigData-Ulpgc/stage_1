package es.ulpgc.bigdata.datalake;


import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Datalake organizado por rangos de 1000 ids (shared/SPEC.md, sección 3):
 *
 *   <root>/<INI>-<FIN>/<ID>.header.txt
 *   <root>/<INI>-<FIN>/<ID>.body.txt
 *
 * con INI = (ID / 1000) * 1000 y FIN = INI + 999, ambos con 5 dígitos.
 * Ejemplo: 1342 -> 01000-01999/1342.body.txt
 *
 * Invariante de diseño: listBookIds() devuelve exactamente los ids
 * para los que locate(id) no está vacío.
 */
public class RangeBasedDatalake implements Datalake {

    private static final int RANGE_SIZE = 1000;
    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";

    /** Nombre válido de carpeta de rango, p. ej. "01000-01999". */
    private static final Pattern RANGE_DIR = Pattern.compile("\\d{5,}-\\d{5,}");
    /** Parte del nombre de fichero que debe ser un id: sólo dígitos. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final Path root;

    public RangeBasedDatalake(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public String name() {
        return "range";
    }

    // ------------------------------------------------------------------
    // Cálculo de rutas (función pura: no toca el disco)
    // ------------------------------------------------------------------

    /** id -> nombre de la carpeta de su rango. */
    static String rangeFolder(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("book_id negativo: " + id);
        }
        int start = (id / RANGE_SIZE) * RANGE_SIZE; // división entera: trunca
        int end = start + RANGE_SIZE - 1;
        return String.format(Locale.ROOT, "%05d-%05d", start, end);
    }

    Path headerPath(int id) {
        return root.resolve(rangeFolder(id)).resolve(id + HEADER_SUFFIX);
    }

    Path bodyPath(int id) {
        return root.resolve(rangeFolder(id)).resolve(id + BODY_SUFFIX);
    }

    // ------------------------------------------------------------------
    // Operaciones del contrato Datalake
    // ------------------------------------------------------------------

    @Override
    public BookLocation save(RawBook book) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(book.header(), "header");
        Objects.requireNonNull(book.body(), "body");

        Path header = headerPath(book.id());
        Path body = bodyPath(book.id());
        try {
            Files.createDirectories(header.getParent());
            // Header primero, body después: locate exige ambos, así que un
            // fallo entre las dos escrituras deja un libro "no guardado".
            // (La escritura atómica con .tmp llega en el reto 11.)
            Files.writeString(header, book.header(), StandardCharsets.UTF_8);
            Files.writeString(body, book.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el libro " + book.id(), e);
        }
        return new BookLocation(book.id(), header, body);
    }

    @Override
    public Optional<BookLocation> locate(int id) {
        if (id < 0) {
            return Optional.empty();
        }
        Path header = headerPath(id);
        Path body = bodyPath(id);
        // Sólo comprobamos existencia: ni leemos contenido ni recorremos carpetas.
        if (Files.isRegularFile(header) && Files.isRegularFile(body)) {
            return Optional.of(new BookLocation(id, header, body));
        }
        return Optional.empty();
    }

    @Override
    public List<Integer> listBookIds() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        // try-with-resources: Files.list mantiene abierto el directorio.
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (isRangeDir(dir)) {
                    collectIdsFrom(dir, ids);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar " + root, e);
        }
        Collections.sort(ids);
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Auxiliares de listBookIds
    // ------------------------------------------------------------------

    private static boolean isRangeDir(Path dir) {
        return Files.isDirectory(dir)
                && RANGE_DIR.matcher(dir.getFileName().toString()).matches();
    }

    private static void collectIdsFrom(Path rangeDir, List<Integer> ids) throws IOException {
        String dirName = rangeDir.getFileName().toString();
        try (Stream<Path> files = Files.list(rangeDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Integer id = parseBodyId(file.getFileName().toString());
                if (id == null) {
                    continue;                              // no es un body válido
                }
                if (!rangeFolder(id).equals(dirName)) {
                    continue;                              // está en un rango que no le toca
                }
                if (!Files.isRegularFile(file)
                        || !Files.isRegularFile(rangeDir.resolve(id + HEADER_SUFFIX))) {
                    continue;                              // falta alguna de las dos partes
                }
                ids.add(id);
            }
        }
    }

    /** "1342.body.txt" -> 1342 ; cualquier otra cosa -> null. */
    private static Integer parseBodyId(String fileName) {
        if (!fileName.endsWith(BODY_SUFFIX)) {
            return null;
        }
        String idPart = fileName.substring(0, fileName.length() - BODY_SUFFIX.length());
        if (!DIGITS.matcher(idPart).matches()) {
            return null;
        }
        try {
            int id = Integer.parseInt(idPart);
            // Rechaza "01342.body.txt": save nunca generaría ese nombre.
            return idPart.equals(Integer.toString(id)) ? id : null;
        } catch (NumberFormatException e) {                // demasiado grande para int
            return null;
        }
    }
}