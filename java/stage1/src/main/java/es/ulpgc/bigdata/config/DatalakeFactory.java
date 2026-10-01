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
 * Traduce un nombre de configuración ("book", "range", "time") a un Datalake.
 *
 * Es el ÚNICO sitio con "new XxxDatalake" fuera de los tests: quien usa el datalake
 * (BookDownloader, Indexer...) sólo conoce la interfaz Datalake. Añadir una estructura
 * nueva = una clase + una línea en este switch + su nombre en NAMES.
 */
public final class DatalakeFactory {

    /** Nombres válidos en datalake.structure (los del SPEC, sección 3). */
    public static final List<String> NAMES = List.of("book", "range", "time");

    private DatalakeFactory() {
    }

    /** La estructura activa, en su carpeta <data>/datalake/<estructura>. */
    public static Datalake create(AppConfig config) {
        return create(config.datalakeStructure(), config.datalakeDir(), Clock.systemDefaultZone());
    }

    /**
     * @param clock sólo lo usa "time" (los benchmarks pasan un reloj simulado)
     * @throws IllegalArgumentException si el nombre no es una estructura conocida
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
