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
 * Capa fina: interpreta el comando, carga AppConfig, pide el sistema montado a
 * SearchEngine (que usa las factories) y muestra el resultado. Aquí NO hay lógica de negocio:
 * qué descargar, cómo indexar o cómo intersecar viven en PipelineController, Indexer y SearchService.
 *
 * Uso:  java ... Main [--config fichero.properties] <comando>
 *   pipeline [pasos]    descarga/indexa como mucho 'pasos' libros (por defecto 10)
 *   search <consulta>   búsqueda AND con el índice activo
 *   status              descargados, indexados y pendientes
 *   config              configuración efectiva
 *
 * Cambiar de estructura no toca código:  -Ddatalake.structure=book -Dindex.structure=hierarchical
 */
public final class Main {

    private static final int DEFAULT_STEPS = 10;

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
            List<String> params = rest.subList(1, rest.size());
            switch (command) {
                case "pipeline" -> pipeline(config, params.isEmpty() ? DEFAULT_STEPS : Integer.parseInt(params.get(0)));
                case "search" -> search(config, String.join(" ", params));
                case "status" -> status(config);
                case "config" -> System.out.println(config.describe());
                default -> {
                    System.err.println("Comando desconocido: " + command);
                    usage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {                 // configuración o argumentos mal puestos
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Comandos: conectar piezas y mostrar resultados
    // ------------------------------------------------------------------

    private static void pipeline(AppConfig config, int steps) {
        try (SearchEngine engine = SearchEngine.open(config)) {
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

    private static void status(AppConfig config) {
        ControlFiles control = new ControlFiles(config.controlDir());
        System.out.println("dataset:     " + BookIdList.load(config.bookIdsFile()).size());
        System.out.println("descargados: " + control.downloaded().size());
        System.out.println("indexados:   " + control.indexed().size());
        System.out.println("pendientes:  " + control.readyToIndex());
    }

    private static void usage() {
        System.err.println("""
                Uso: Main [--config fichero.properties] <comando>
                  pipeline [pasos]    descarga e indexa (por defecto 10 pasos)
                  search <consulta>   búsqueda AND
                  status              estado de la capa de control
                  config              configuración efectiva
                Cualquier clave se puede cambiar con -Dclave=valor, p. ej. -Dindex.structure=hierarchical""");
    }
}
