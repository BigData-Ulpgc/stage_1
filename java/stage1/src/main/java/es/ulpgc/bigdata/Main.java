package es.ulpgc.bigdata;

import es.ulpgc.bigdata.config.AppConfig;
import es.ulpgc.bigdata.control.BookIdList;
import es.ulpgc.bigdata.control.ControlFiles;
import es.ulpgc.bigdata.control.StepResult;
import es.ulpgc.bigdata.model.BookMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin layer: interprets the command, loads AppConfig, asks SearchEngine (which uses the
 * factories) for the assembled system and shows the result. There is NO business logic here:
 * what to download, how to index or how to intersect live in PipelineController, Indexer and SearchService.
 *
 * Usage:  java ... Main [--config file.properties] <command>
 *   pipeline [steps] [--offline]   downloads/indexes at most 'steps' books (default 10)
 *   search <query>                 AND search with the active index
 *   status [--offline]             downloaded, indexed and pending
 *   config                         effective configuration
 *
 * --offline: no network. The books are the 15 of sample_dataset/, read from sample_dataset/raw/
 * instead of Project Gutenberg (SearchEngine.openOffline). Everything after the fetch is identical.
 *
 * Changing the structure does not touch code:  -Ddatalake.structure=book -Dindex.structure=hierarchical
 */
public final class Main {

    private static final int DEFAULT_STEPS = 10;
    private static final String OFFLINE = "--offline";

    private Main() {
    }

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>(List.of(args));
        Path configFile = null;
        if (rest.size() >= 2 && rest.get(0).equals("--config")) {
            configFile = Path.of(rest.get(1));
            rest = rest.subList(2, rest.size());
        }
        if (rest.isEmpty()) {
            usage();
            System.exit(2);
        }
        try {
            AppConfig config = configFile != null ? AppConfig.load(configFile) : AppConfig.load();
            String command = rest.get(0);
            List<String> params = new ArrayList<>(rest.subList(1, rest.size()));
            switch (command) {
                case "pipeline" -> {
                    boolean offline = params.remove(OFFLINE);
                    pipeline(config, params.isEmpty() ? DEFAULT_STEPS : parseSteps(params.get(0)), offline);
                }
                case "search" -> search(config, String.join(" ", params));
                case "status" -> status(config, params.remove(OFFLINE));
                case "config" -> System.out.println(config.describe());
                default -> {
                    System.err.println("Comando desconocido: " + command);
                    usage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {                 // wrong configuration or arguments
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Commands: connect pieces and show results
    // ------------------------------------------------------------------

    private static void pipeline(AppConfig config, int steps, boolean offline) {
        if (offline) {
            System.out.println("offline: libros leídos de " + config.sampleRawDir().toAbsolutePath().normalize());
        }
        try (SearchEngine engine = offline ? SearchEngine.openOffline(config) : SearchEngine.open(config)) {
            System.out.println("datalake=" + engine.datalake().name() + "  index=" + engine.index().name());
            List<StepResult> results = engine.pipeline().runUntilIdle(steps);
            for (StepResult r : results) {
                System.out.println(r.action() + " " + r.bookId() + (r.detail().isEmpty() ? "" : "  " + r.detail()));
            }
            if (results.size() < steps) {
                System.out.println("IDLE: no queda nada por descargar ni indexar");
            }
        }
    }

    private static void search(AppConfig config, String query) {
        try (SearchEngine engine = SearchEngine.open(config)) {
            List<Integer> ids = engine.search().search(query);
            System.out.println(ids.size() + " libros para \"" + query + "\" (index=" + engine.index().name() + ")");
            for (int id : ids) {
                String title = engine.metadata().findById(id).map(BookMetadata::title).orElse("(sin metadatos)");
                System.out.println("  " + id + "  " + title);
            }
        }
    }

    private static void status(AppConfig config, boolean offline) {
        ControlFiles control = new ControlFiles(config.controlDir());
        Path dataset = offline ? config.sampleBookIdsFile() : config.bookIdsFile();
        System.out.println("dataset:     " + BookIdList.load(dataset).size() + (offline ? " (sample_dataset)" : ""));
        System.out.println("descargados: " + control.downloaded().size());
        System.out.println("indexados:   " + control.indexed().size());
        System.out.println("pendientes:  " + control.readyToIndex());
    }

    private static int parseSteps(String value) {
        try {
            int steps = Integer.parseInt(value);
            if (steps > 0) {
                return steps;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException("el número de pasos debe ser un entero positivo: \"" + value + "\"");
    }

    private static void usage() {
        System.err.println("""
                Uso: Main [--config fichero.properties] <comando>
                  pipeline [pasos] [--offline]   descarga e indexa (por defecto 10 pasos)
                  search <consulta>              búsqueda AND
                  status [--offline]             estado de la capa de control
                  config                         configuración efectiva
                --offline: sin red, con los 15 libros de sample_dataset/raw/
                Cualquier clave se puede cambiar con -Dclave=valor, p. ej. -Dindex.structure=hierarchical""");
    }
}
