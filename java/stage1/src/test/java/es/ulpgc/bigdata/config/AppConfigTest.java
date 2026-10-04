package es.ulpgc.bigdata.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @TempDir Path tmp;

    @AfterEach
    void clearSystemProperties() {
        AppConfig.KEYS.forEach(System::clearProperty);
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    // --- Values and paths ---------------------------------------------------------------

    @Test
    void losValoresPorDefectoSiguenElSpec() {
        AppConfig c = AppConfig.defaults();

        assertEquals("time", c.datalakeStructure());
        assertEquals("monolithic", c.indexStructure());
        assertEquals(Path.of("data/datalake/time"), c.datalakeDir());
        assertEquals(Path.of("data/datamarts/metadata.db"), c.metadataDb());
        assertEquals(Path.of("data/datamarts/inverted_index.json"), c.monolithicIndexFile());
        assertEquals(Path.of("data/datamarts/inverted_index"), c.hierarchicalIndexDir());
        assertEquals(Path.of("data/control"), c.controlDir());
        assertEquals(Path.of("../../shared/book_ids.txt"), c.bookIdsFile());
        assertEquals(Path.of("../../sample_dataset/book_ids.txt"), c.sampleBookIdsFile());
        assertEquals(Path.of("../../sample_dataset/raw"), c.sampleRawDir());
        assertEquals(Path.of("benchmarks/results"), c.benchmarkResultsDir());
        assertEquals(Duration.ofSeconds(10), c.connectTimeout());
        assertEquals(Duration.ofSeconds(15), c.requestTimeout());
    }

    @Test
    void cambiarDataDirMueveTodasLasRutasALaVez() {
        AppConfig c = AppConfig.fromProperties(props("data.dir", "/srv/engine", "datalake.structure", "book"));

        assertEquals(Path.of("/srv/engine/datalake/book"), c.datalakeDir());
        assertEquals(Path.of("/srv/engine/datamarts/metadata.db"), c.metadataDb());
        assertEquals(Path.of("/srv/engine/control"), c.controlDir());
        assertEquals(c.withDataDir(Path.of("x")).metadataDb(), Path.of("x/datamarts/metadata.db"));
    }

    // --- Priority order: default < file < -D --------------------------------------------------

    @Test
    void elFicheroSobrescribeLosValoresPorDefectoYMenosDLoSobrescribeAEl() throws IOException {
        Path file = tmp.resolve("app.properties");
        Files.writeString(file, """
                # comentario
                datalake.structure = range
                index.structure = hierarchical
                http.request.timeout.seconds = 30
                """);

        AppConfig fromFile = AppConfig.load(file);
        assertEquals("range", fromFile.datalakeStructure());
        assertEquals("hierarchical", fromFile.indexStructure());
        assertEquals(Duration.ofSeconds(30), fromFile.requestTimeout());
        assertEquals(Duration.ofSeconds(10), fromFile.connectTimeout());       // not in the file

        System.setProperty("index.structure", "memory");
        assertEquals("memory", AppConfig.load(file).indexStructure());
    }

    // --- Errors at startup, not halfway ----------------------------------------------------------

    @Test
    void unaEstructuraDesconocidaFallaAlCargarYDiceLasOpciones() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromProperties(props("datalake.structure", "tiem")));

        assertTrue(e.getMessage().contains("[book, range, time]"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromProperties(props("index.structure", "sqlite")));
    }

    @Test
    void unaClaveConErrataEsUnError() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.fromProperties(props("index.structur", "mongo")));
    }

    @Test
    void losTimeoutsDebenSerNumerosPositivos() {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromProperties(props("http.connect.timeout.seconds", "diez")));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromProperties(props("http.request.timeout.seconds", "0")));
    }

    // --- Criterion: the data paths are not repeated across several classes ----------------------

    @Test
    void ningunaClaseFueraDeAppConfigEscribeRutasDeDatos() throws IOException {
        List<String> forbidden = List.of("\"inverted_index.json\"", "\"metadata.db\"", "\"datamarts\"",
                "\"../../shared", "\"benchmarks/", "\"data/", "\"book_ids.txt\"", "\"stopwords.txt\"",
                "\"queries.txt\"", "Duration.ofSeconds(");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith(Path.of("config", "AppConfig.java"))) {
                    continue;
                }
                String code = Files.readString(file);
                for (String literal : forbidden) {
                    if (code.contains(literal)) {
                        offenders.add(file.getFileName() + " contiene " + literal);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }
}
