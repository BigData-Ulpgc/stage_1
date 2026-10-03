package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.BenchmarkRunner.BenchmarkException;
import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRunnerTest {

    private static final Scenario SCENARIO = new Scenario("java", "index_build", "monolithic", 100);

    // --- Challenge criteria --------------------------------------------------

    @Test
    void losWarmupsSeEjecutanPeroNoAparecenComoResultados() {
        List<String> calls = new ArrayList<>();

        List<BenchmarkRow> rows = new BenchmarkRunner(2, 5).run(SCENARIO, () -> calls.add("task"));

        assertEquals(7, calls.size());                     // 2 warmups + 5 runs are executed
        assertEquals(5, rows.size());                      // only the 5 runs are returned
    }

    @Test
    void cadaRunTieneUnaRepetitionDistintaDe1aN() {
        List<BenchmarkRow> rows = new BenchmarkRunner(2, 5).run(SCENARIO, () -> { });

        assertEquals(List.of(1, 2, 3, 4, 5), rows.stream().map(BenchmarkRow::repetition).toList());
    }

    @Test
    void cadaFilaLlevaElEsquemaComun() {
        BenchmarkRow row = new BenchmarkRunner(0, 1).run(SCENARIO, () -> { }).get(0);

        assertEquals("java", row.language());
        assertEquals("index_build", row.experiment());
        assertEquals("monolithic", row.structure());
        assertEquals(100, row.datasetSize());
        assertEquals("elapsed", row.metric());
        assertEquals("ms", row.unit());
    }

    // --- setup ------------------------------------------------------------------

    @Test
    void elSetupSeEjecutaAntesDeCadaRepeticionTambienDeLosWarmups() {
        List<String> log = new ArrayList<>();

        new BenchmarkRunner(2, 3).run(SCENARIO, () -> log.add("setup"), () -> log.add("task"));

        assertEquals(List.of(
                "setup", "task", "setup", "task",                       // warmups
                "setup", "task", "setup", "task", "setup", "task"),     // runs
                log);
    }

    @Test
    void elSetupNoSeMide() {
        List<BenchmarkRow> rows = new BenchmarkRunner(1, 3).run(SCENARIO,
                () -> Thread.sleep(200),                   // slow setup
                () -> { });                                // instant task

        for (BenchmarkRow row : rows) {
            assertTrue(row.value() < 100, "el setup se coló en la medida: " + row.value() + " ms");
        }
    }

    @Test
    void laTareaSeMideEnMilisegundos() {
        List<BenchmarkRow> rows = new BenchmarkRunner(0, 2).run(SCENARIO, () -> Thread.sleep(50));

        for (BenchmarkRow row : rows) {
            assertTrue(row.value() >= 50 && row.value() < 1000, "valor inesperado: " + row.value());
        }
    }

    // --- Errors -------------------------------------------------------------------------

    @Test
    void siLaTareaFallaSeSabeEnQueRepeticionYNoHayFilasAMedias() {
        int[] calls = {0};
        BenchmarkException e = assertThrows(BenchmarkException.class,
                () -> new BenchmarkRunner(2, 5).run(SCENARIO, () -> {
                    if (++calls[0] == 4) {                   // 2 warmups + 2nd run
                        throw new IOException("disco lleno (simulado)");
                    }
                }));

        assertTrue(e.getMessage().contains("run 2"), e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void siElSetupFallaTambienSeIndica() {
        BenchmarkException e = assertThrows(BenchmarkException.class,
                () -> new BenchmarkRunner(1, 1).run(SCENARIO, () -> { throw new IOException("x"); }, () -> { }));

        assertTrue(e.getMessage().contains("setup") && e.getMessage().contains("warmup 1"), e.getMessage());
    }

    // --- Configuration -------------------------------------------------------------------

    @Test
    void standardUsaLaMetodologiaDelSpec() {
        List<String> calls = new ArrayList<>();
        List<BenchmarkRow> rows = BenchmarkRunner.standard().run(SCENARIO, () -> calls.add("task"));

        assertEquals(BenchmarkRunner.N_WARMUP + BenchmarkRunner.N_RUNS, calls.size());
        assertEquals(5, rows.size());
    }

    @Test
    void valoresDeConfiguracionInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunner(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunner(2, 0));
        assertEquals(1, new BenchmarkRunner(0, 1).run(SCENARIO, () -> { }).size());   // no warmups is fine
    }
}