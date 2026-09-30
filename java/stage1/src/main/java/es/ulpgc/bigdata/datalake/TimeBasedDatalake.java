package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Datalake organizado por fecha y hora local de la descarga (shared/SPEC.md, sección 3):
 *   <root>/YYYYMMDD/HH/<ID>.header.txt
 *   <root>/YYYYMMDD/HH/<ID>.body.txt
 *
 * La ruta NO se puede calcular a partir del id, así que locate tiene que buscar.
 * save es append-only; ante copias repetidas de un id gana la más reciente
 * y listBookIds no repite ids.
 */
public class TimeBasedDatalake extends AbstractFileDatalake {

    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";

    // "yyyy" = año de calendario ("YYYY" sería año de semana ISO)
    // "HH"   = hora 00-23             ("hh" sería 01-12 sin AM/PM)
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH", Locale.ROOT);

    private static final Pattern DAY_DIR = Pattern.compile("\\d{8}");
    private static final Pattern HOUR_DIR = Pattern.compile("[01]\\d|2[0-3]");

    private final Clock clock;

    public TimeBasedDatalake(Path root) {
        this(root, Clock.systemDefaultZone());
    }

    public TimeBasedDatalake(Path root, Clock clock) {
        super(root);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return "time";
    }

    Path folderFor(LocalDateTime moment) {
        return root.resolve(DAY_FORMAT.format(moment)).resolve(HOUR_FORMAT.format(moment));
    }

    @Override
    public BookLocation save(RawBook book) {
        Path folder = folderFor(LocalDateTime.now(clock));   // se lee el reloj UNA vez
        return writeBook(book,
                folder.resolve(book.id() + HEADER_SUFFIX),
                folder.resolve(book.id() + BODY_SUFFIX));
    }

    @Override
    public Optional<BookLocation> locate(int id) {
        if (id < 0) {
            return Optional.empty();
        }
        for (Path hourDir : hourDirsNewestFirst()) {
            Optional<BookLocation> found = completeBook(id,
                    hourDir.resolve(id + HEADER_SUFFIX),
                    hourDir.resolve(id + BODY_SUFFIX));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Integer> listBookIds() {
        SortedSet<Integer> ids = new TreeSet<>(); // ordena y elimina repetidos
        for (Path hourDir : hourDirsNewestFirst()) {
            try (Stream<Path> files = Files.list(hourDir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    Integer id = parseIdWithSuffix(file.getFileName().toString(), BODY_SUFFIX);
                    if (id != null && isCompleteBook(hourDir.resolve(id + HEADER_SUFFIX), file)) {
                        ids.add(id);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo listar " + hourDir, e);
            }
        }
        return List.copyOf(ids);
    }

    /** Todas las carpetas YYYYMMDD/HH válidas, de la más reciente a la más antigua. */
    private List<Path> hourDirsNewestFirst() {
        List<Path> result = new ArrayList<>();
        for (Path dayDir : subdirsNewestFirst(root, DAY_DIR)) {
            result.addAll(subdirsNewestFirst(dayDir, HOUR_DIR));
        }
        return result;
    }

    private static List<Path> subdirsNewestFirst(Path parent, Pattern namePattern) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> namePattern.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar " + parent, e);
        }
    }
}