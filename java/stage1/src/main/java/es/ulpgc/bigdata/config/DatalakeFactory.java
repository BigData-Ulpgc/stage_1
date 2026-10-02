package es.ulpgc.bigdata.config;

import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datalake.RangeBasedDatalake;
import es.ulpgc.bigdata.datalake.TimeBasedDatalake;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Turns a configuration name ("book", "range", "time") into a Datalake.
 *
 * It is the ONLY place with "new XxxDatalake" outside the tests: whoever uses the datalake
 * (BookDownloader, Indexer...) only knows the Datalake interface. Adding a new structure
 * = one class + one line in this switch + its name in NAMES.
 */
public final class DatalakeFactory {

    /** Valid names in datalake.structure (the SPEC ones, section 3). */
    public static final List<String> NAMES = List.of("book", "range", "time");

    private DatalakeFactory() {
    }

    /** The active structure, in its folder <data>/datalake/<structure>. */
    public static Datalake create(AppConfig config) {
        return create(config.datalakeStructure(), config.datalakeDir(), Clock.systemDefaultZone());
    }

    /**
     * @param clock only used by "time" (the benchmarks pass a simulated clock)
     * @throws IllegalArgumentException if the name is not a known structure
     */
    public static Datalake create(String name, Path root, Clock clock) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(clock, "clock");
        return switch (Objects.requireNonNull(name, "name")) {
            case "book" -> new BookBasedDatalake(root);
            case "range" -> new RangeBasedDatalake(root);
            case "time" -> new TimeBasedDatalake(root, clock);
            default -> throw new IllegalArgumentException(
                    "Estructura de datalake desconocida: \"" + name + "\". Opciones: " + NAMES);
        };
    }
}
