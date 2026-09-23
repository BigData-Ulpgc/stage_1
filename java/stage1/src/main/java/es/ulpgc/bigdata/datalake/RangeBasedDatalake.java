package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Datalake organizado por rangos de 1000 ids (shared/SPEC.md, sección 3):
 *   <root>/<INI>-<FIN>/<ID>.header.txt
 *   <root>/<INI>-<FIN>/<ID>.body.txt
 * con INI = (ID / 1000) * 1000 y FIN = INI + 999, ambos con 5 dígitos.
 */
public class RangeBasedDatalake extends AbstractFileDatalake {

    private static final int RANGE_SIZE = 1000;
    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";
    private static final Pattern RANGE_DIR = Pattern.compile("\\d{5,}-\\d{5,}");

    public RangeBasedDatalake(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "range";
    }

    /** id -> nombre de la carpeta de su rango. */
    static String rangeFolder(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("book_id negativo: " + id);
        }
        int start = (id / RANGE_SIZE) * RANGE_SIZE;
        int end = start + RANGE_SIZE - 1;
        return String.format(Locale.ROOT, "%05d-%05d", start, end);
    }

    Path headerPath(int id) {
        return root.resolve(rangeFolder(id)).resolve(id + HEADER_SUFFIX);
    }

    Path bodyPath(int id) {
        return root.resolve(rangeFolder(id)).resolve(id + BODY_SUFFIX);
    }

    @Override
    public BookLocation save(RawBook book) {
        return writeBook(book, headerPath(book.id()), bodyPath(book.id()));
    }

    @Override
    public Optional<BookLocation> locate(int id) {
        if (id < 0) {
            return Optional.empty();
        }
        return completeBook(id, headerPath(id), bodyPath(id));
    }

    @Override
    public List<Integer> listBookIds() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
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

    private static boolean isRangeDir(Path dir) {
        return Files.isDirectory(dir)
                && RANGE_DIR.matcher(dir.getFileName().toString()).matches();
    }

    private static void collectIdsFrom(Path rangeDir, List<Integer> ids) throws IOException {
        String dirName = rangeDir.getFileName().toString();
        try (Stream<Path> files = Files.list(rangeDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Integer id = parseIdWithSuffix(file.getFileName().toString(), BODY_SUFFIX);
                if (id != null
                        && rangeFolder(id).equals(dirName)                 // en su rango
                        && isCompleteBook(rangeDir.resolve(id + HEADER_SUFFIX), file)) {
                    ids.add(id);
                }
            }
        }
    }
}