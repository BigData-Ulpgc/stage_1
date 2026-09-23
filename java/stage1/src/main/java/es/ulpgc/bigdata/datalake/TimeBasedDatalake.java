package es.ulpgc.bigdata.datalake;


import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 *
 *   <root>/YYYYMMDD/HH/<ID>.header.txt
 *   <root>/YYYYMMDD/HH/<ID>.body.txt
 *
 * A diferencia de "book" y "range", la ruta NO se puede calcular a partir del id:
 * depende de cuándo se guardó. Por eso locate tiene que buscar.
 *
 * Política ante duplicados (mismo id guardado en momentos distintos):
 * save no busca copias anteriores (escritura append-only, coste constante);
 * al leer, gana la copia más reciente y listBookIds no repite ids.
 *
 * Invariante: listBookIds() devuelve exactamente los ids para los que
 * locate(id) no está vacío.
 */
public class TimeBasedDatalake implements Datalake {

    private static final String HEADER_SUFFIX = ".header.txt";
    private static final String BODY_SUFFIX = ".body.txt";

    // "yyyy" = año de calendario ("YYYY" sería año de semana ISO: ¡bug a final de diciembre!)
    // "HH"   = hora 00-23             ("hh" sería 01-12 sin AM/PM: ¡dos horas distintas, misma carpeta!)
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH", Locale.ROOT);

    private static final Pattern DAY_DIR = Pattern.compile("\\d{8}");
    private static final Pattern HOUR_DIR = Pattern.compile("[01]\\d|2[0-3]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final Path root;
    private final Clock clock;

    /** Constructor de producción: hora local del sistema. */
    public TimeBasedDatalake(Path root) {
        this(root, Clock.systemDefaultZone());
    }

    /** Constructor que permite fijar la hora (tests y benchmarks reproducibles). */
    public TimeBasedDatalake(Path root, Clock clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return "time";
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    /** Carpeta YYYYMMDD/HH para un instante concreto. */
    Path folderFor(LocalDateTime moment) {
        return root.resolve(DAY_FORMAT.format(moment)).resolve(HOUR_FORMAT.format(moment));
    }

    @Override
    public BookLocation save(RawBook book) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(book.header(), "header");
        Objects.requireNonNull(book.body(), "body");
        if (book.id() < 0) {
            throw new IllegalArgumentException("book_id negativo: " + book.id());
        }

        // Se lee el reloj UNA vez: día y hora salen del mismo instante.
        Path folder = folderFor(LocalDateTime.now(clock));
        Path header = folder.resolve(book.id() + HEADER_SUFFIX);
        Path body = folder.resolve(book.id() + BODY_SUFFIX);
        try {
            Files.createDirectories(folder);
            // Header primero, body después: sin ambos, el libro no cuenta como guardado.
            // (La escritura atómica con .tmp llega en el reto 11.)
            Files.writeString(header, book.header(), StandardCharsets.UTF_8);
            Files.writeString(body, book.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el libro " + book.id(), e);
        }
        return new BookLocation(book.id(), header, body);
    }

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    @Override
    public Optional<BookLocation> locate(int id) {
        if (id < 0) {
            return Optional.empty();
        }
        // Del día y la hora más recientes a los más antiguos: la primera copia
        // completa que aparezca es la más reciente, y ahí paramos.
        for (Path hourDir : hourDirsNewestFirst()) {
            Path header = hourDir.resolve(id + HEADER_SUFFIX);
            Path body = hourDir.resolve(id + BODY_SUFFIX);
            if (Files.isRegularFile(header) && Files.isRegularFile(body)) {
                return Optional.of(new BookLocation(id, header, body));
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
                    Integer id = parseBodyId(file.getFileName().toString());
                    if (id != null
                            && Files.isRegularFile(file)
                            && Files.isRegularFile(hourDir.resolve(id + HEADER_SUFFIX))) {
                        ids.add(id);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo listar " + hourDir, e);
            }
        }
        return new ArrayList<>(ids);
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    /** Todas las carpetas YYYYMMDD/HH válidas, de la más reciente a la más antigua. */
    private List<Path> hourDirsNewestFirst() {
        List<Path> result = new ArrayList<>();
        for (Path dayDir : subdirsNewestFirst(root, DAY_DIR)) {
            result.addAll(subdirsNewestFirst(dayDir, HOUR_DIR));
        }
        return result;
    }

    /**
     * Subcarpetas de parent cuyo nombre encaja con el patrón, en orden descendente.
     * Como los nombres son números con ancho fijo, el orden alfabético coincide
     * con el cronológico.
     */
    private static List<Path> subdirsNewestFirst(Path parent, Pattern namePattern) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> namePattern.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList()); // se materializa ANTES de cerrar el stream
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar " + parent, e);
        }
    }

    /** "1342.body.txt" -> 1342 ; cualquier otra cosa -> null. (Igual que en range; se extrae en el reto 11.) */
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
            return idPart.equals(Integer.toString(id)) ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}