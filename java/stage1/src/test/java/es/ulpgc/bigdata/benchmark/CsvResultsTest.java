package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CsvResultsTest {

    @TempDir Path tmp;

    private static BenchmarkRow row(String structure, int repetition, double value) {
        return new BenchmarkRow("java", "index_build", structure, 100, repetition, "elapsed", value, "ms");
    }

    // --- Formato de una fila ------------------------------------------------------

    @Test
    void laFilaDelEjemploDelSpec() {
        assertEquals("java,index_build,monolithic,100,1,elapsed,1234.5,ms",
                row("monolithic", 1, 1234.5).toCsvLine());
    }

    @Test
    void elValorSiempreConPuntoAunqueElOrdenadorEsteEnEspanol() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"));             // aquí 1234,5 sería lo "normal"
            assertEquals("java,index_build,monolithic,100,1,elapsed,1234.5,ms",
                    row("monolithic", 1, 1234.5).toCsvLine());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void elValorSeRedondeaAMicrosegundosSinNotacionCientifica() {
        assertEquals("0.001", BenchmarkRow.formatValue(0.0012345));
        assertEquals("0.123", BenchmarkRow.formatValue(0.12345));
        assertEquals("12345", BenchmarkRow.formatValue(12345.0));            // bytes, recuentos...
        assertEquals("1500000", BenchmarkRow.formatValue(1_500_000.0));     // no "1.5E+6"
        assertEquals("0", BenchmarkRow.formatValue(0.0000001));
    }

    @Test
    void camposQueRomperianElCsvSeRechazan() {
        assertThrows(IllegalArgumentException.class, () -> row("mono,lithic", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> row("mono\"lithic", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> row("", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> row("monolithic", 0, 1));      // repetition >= 1
        assertThrows(IllegalArgumentException.class, () -> row("monolithic", 1, Double.NaN));
    }

    // --- Ficheros -----------------------------------------------------------------------

    @Test
    void nombreDelFicheroSegunElSpec() {
        assertEquals(tmp.resolve("java_index_build.csv"), CsvResults.fileFor(tmp, "java", "index_build"));
    }

    @Test
    void escribeCabeceraYFilasYSeVuelvenALeerIguales() throws IOException {
        Path file = tmp.resolve("benchmarks/results/java_index_build.csv");          // carpetas nuevas
        List<BenchmarkRow> rows = List.of(row("monolithic", 1, 1234.5), row("monolithic", 2, 1100.25));

        CsvResults.write(file, rows);

        assertEquals("""
                language,experiment,structure,dataset_size,repetition,metric,value,unit
                java,index_build,monolithic,100,1,elapsed,1234.5,ms
                java,index_build,monolithic,100,2,elapsed,1100.25,ms
                """, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(rows, CsvResults.read(file));
    }

    @Test
    void repetirElBenchmarkSustituyeElFicheroPorUnaEjecucionCompleta() {
        Path file = tmp.resolve("java_index_build.csv");
        CsvResults.write(file, List.of(row("monolithic", 1, 999)));

        CsvResults.write(file, List.of(row("monolithic", 1, 10), row("monolithic", 2, 11)));

        assertEquals(List.of(row("monolithic", 1, 10), row("monolithic", 2, 11)), CsvResults.read(file));
    }

    @Test
    void siElBenchmarkFallaLosResultadosAnterioresSiguenIntactos() throws IOException {
        Path file = tmp.resolve("java_index_build.csv");
        CsvResults.write(file, List.of(row("monolithic", 1, 999)));
        String before = Files.readString(file);

        BenchmarkRunner runner = new BenchmarkRunner(0, 5);
        Scenario scenario = new Scenario("java", "index_build", "monolithic", 100);
        assertThrows(BenchmarkRunner.BenchmarkException.class, () -> {
            List<BenchmarkRow> rows = runner.run(scenario, () -> { throw new IOException("fallo"); });
            CsvResults.write(file, rows);                                           // nunca se llega aquí
        });

        assertEquals(before, Files.readString(file));
    }

    @Test
    void noDejaTemporales() throws IOException {
        CsvResults.write(tmp.resolve("java_index_build.csv"), List.of(row("monolithic", 1, 1)));

        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(List.of("java_index_build.csv"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void variasEstructurasEnElMismoExperimentoSonAnalizables() {
        // Así lo usará el reto 27: se juntan las filas de todas las estructuras y se escribe una vez.
        BenchmarkRunner runner = new BenchmarkRunner(1, 3);
        List<BenchmarkRow> all = new ArrayList<>();
        for (String structure : List.of("book", "range", "time")) {
            all.addAll(runner.run(new Scenario("java", "datalake_write", structure, 10), () -> { }));
        }
        Path file = CsvResults.fileFor(tmp, "java", "datalake_write");
        CsvResults.write(file, all);

        List<BenchmarkRow> read = CsvResults.read(file);
        assertEquals(9, read.size());
        // Cada (estructura, repetición) aparece una sola vez: se puede agrupar sin ambigüedad.
        assertEquals(9, read.stream().map(r -> r.structure() + "#" + r.repetition()).distinct().count());
    }

    @Test
    void unFicheroSinLaCabeceraDelSpecSeRechaza() throws IOException {
        Path file = tmp.resolve("raro.csv");
        Files.writeString(file, "a,b,c\n1,2,3\n");

        assertThrows(IllegalArgumentException.class, () -> CsvResults.read(file));
    }
}