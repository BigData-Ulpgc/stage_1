package es.ulpgc.bigdata.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Repeats a task and measures how long it takes each time.
 *
 *   for each repetition (warmups + runs):
 *       setup()                    <- NOT measured (e.g. emptying the index)
 *       t0 = nanoTime()
 *       task()                     <- measured
 *       t1 = nanoTime()
 *   warmups are discarded; each run gives one CSV row with repetition 1..runs.
 */
public final class BenchmarkRunner {

    /** Common SPEC methodology: 2 discarded repetitions and 5 measured ones. */
    public static final int N_WARMUP = 2;
    public static final int N_RUNS = 5;

    public static final String METRIC = "elapsed";
    public static final String UNIT = "ms";

    /** An action that may throw checked exceptions (IOException, SQLException...). */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** What is being measured: the CSV columns that do not change between repetitions. */
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

    /** 2 warmups + 5 runs, as set by the SPEC. */
    public static BenchmarkRunner standard() {
        return new BenchmarkRunner(N_WARMUP, N_RUNS);
    }

    /** Nothing to prepare between repetitions. */
    public List<BenchmarkRow> run(Scenario scenario, Action task) {
        return run(scenario, () -> { }, task);
    }

    /**
     * @param setup runs before EVERY repetition (warmups included) and is not measured
     * @param task  what is measured
     * @return one row per measured run (never per warmup), with repetition 1, 2, ... runs
     * @throws BenchmarkException if setup or task fail; partial rows are never returned
     */
    public List<BenchmarkRow> run(Scenario scenario, Action setup, Action task) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(task, "task");

        for (int i = 1; i <= warmups; i++) {
            measureOnce(setup, task, "warmup " + i);          // it runs, but the time is thrown away
        }

        List<BenchmarkRow> rows = new ArrayList<>(runs);
        for (int repetition = 1; repetition <= runs; repetition++) {
            double millis = measureOnce(setup, task, "run " + repetition);
            rows.add(new BenchmarkRow(scenario.language(), scenario.experiment(), scenario.structure(),
                    scenario.datasetSize(), repetition, METRIC, millis, UNIT));
        }
        return List.copyOf(rows);
    }

    /** setup not measured, and task measured with nanoTime. Returns milliseconds. */
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

    /** Error during a benchmark: tells in which repetition it happened. */
    public static final class BenchmarkException extends RuntimeException {
        public BenchmarkException(String message, Throwable cause) {
            super(message + ": " + cause, cause);
        }
    }
}