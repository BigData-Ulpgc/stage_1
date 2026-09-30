package es.ulpgc.bigdata.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Repite una tarea y mide cuánto tarda cada vez.
 *
 *   para cada repetición (warmups + runs):
 *       setup()                    <- NO se mide (p. ej. vaciar el índice)
 *       t0 = nanoTime()
 *       task()                     <- SÍ se mide
 *       t1 = nanoTime()
 *   los warmups se descartan; cada run da una fila CSV con repetition 1..runs.
 */
public final class BenchmarkRunner {

    /** Metodología común del SPEC: 2 repeticiones descartadas y 5 medidas. */
    public static final int N_WARMUP = 2;
    public static final int N_RUNS = 5;

    public static final String METRIC = "elapsed";
    public static final String UNIT = "ms";

    /** Una acción que puede lanzar excepciones comprobadas (IOException, SQLException...). */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** Qué se está midiendo: las columnas del CSV que no cambian entre repeticiones. */
    public record Scenario(String language, String experiment, String structure, int datasetSize) {
    }

    private final int warmups;
    private final int runs;

    public BenchmarkRunner(int warmups, int runs) {
        if (warmups < 0) {
            throw new IllegalArgumentException("warmups no puede ser negativo: " + warmups);
        }
        if (runs < 1) {
            throw new IllegalArgumentException("hace falta al menos 1 run medido: " + runs);
        }
        this.warmups = warmups;
        this.runs = runs;
    }

    /** 2 warmups + 5 runs, como fija el SPEC. */
    public static BenchmarkRunner standard() {
        return new BenchmarkRunner(N_WARMUP, N_RUNS);
    }

    /** Sin nada que preparar entre repeticiones. */
    public List<BenchmarkRow> run(Scenario scenario, Action task) {
        return run(scenario, () -> { }, task);
    }

    /**
     * @param setup se ejecuta antes de CADA repetición (también de los warmups) y no se mide
     * @param task  lo que se mide
     * @return una fila por run medido (nunca por warmup), con repetition 1, 2, ... runs
     * @throws BenchmarkException si setup o task fallan; no se devuelven filas a medias
     */
    public List<BenchmarkRow> run(Scenario scenario, Action setup, Action task) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(task, "task");

        for (int i = 1; i <= warmups; i++) {
            measureOnce(setup, task, "warmup " + i);          // se ejecuta, pero el tiempo se tira
        }

        List<BenchmarkRow> rows = new ArrayList<>(runs);
        for (int repetition = 1; repetition <= runs; repetition++) {
            double millis = measureOnce(setup, task, "run " + repetition);
            rows.add(new BenchmarkRow(scenario.language(), scenario.experiment(), scenario.structure(),
                    scenario.datasetSize(), repetition, METRIC, millis, UNIT));
        }
        return List.copyOf(rows);
    }

    /** setup sin medir, y task medido con nanoTime. Devuelve milisegundos. */
    private static double measureOnce(Action setup, Action task, String label) {
        try {
            setup.run();
        } catch (Exception e) {
            throw new BenchmarkException("Falló el setup en " + label, e);
        }
        long start = System.nanoTime();
        try {
            task.run();
        } catch (Exception e) {
            throw new BenchmarkException("Falló la tarea en " + label, e);
        }
        long elapsedNanos = System.nanoTime() - start;
        return elapsedNanos / 1_000_000.0;
    }

    /** Error durante un benchmark: indica en qué repetición ocurrió. */
    public static final class BenchmarkException extends RuntimeException {
        public BenchmarkException(String message, Throwable cause) {
            super(message + ": " + cause, cause);
        }
    }
}