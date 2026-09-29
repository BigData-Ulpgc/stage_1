package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests que cualquier Datalake debe pasar, sea cual sea su organización física.
 * Cada test se ejecuta tres veces: book, range y time.
 */
class DatalakeContractTest {

    @TempDir Path tmp;

    /** Las tres implementaciones: nombre esperado + receta para crear una nueva. */
    static Stream<Arguments> structures() {
        return Stream.of(
                Arguments.of("book", (Function<Path, Datalake>) BookBasedDatalake::new),
                Arguments.of("range", (Function<Path, Datalake>) RangeBasedDatalake::new),
                Arguments.of("time", (Function<Path, Datalake>) TimeBasedDatalake::new));
    }

    /** Datalake nuevo y vacío en su propia subcarpeta temporal. */
    private Datalake create(String name, Function<Path, Datalake> factory) {
        return factory.apply(tmp.resolve(name));
    }

    // ------------------------------------------------------------------
    // Tus tests del reto 7, ahora para las tres estructuras
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void cumpleElContratoDatalake(String name, Function<Path, Datalake> factory) {
        // Tipo declarado como Datalake, no como la clase concreta:
        // el test no sabe (ni le importa) cuál es la implementación.
        Datalake datalake = create(name, factory);

        assertEquals(name, datalake.name());

        BookLocation location = datalake.save(new RawBook(10, "h", "b"));
        assertNotNull(location);

        Optional<BookLocation> found = datalake.locate(10);
        assertTrue(found.isPresent());

        assertEquals(List.of(10), datalake.listBookIds());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void listBookIdsIgnoraCarpetasSinLibroValido(String name, Function<Path, Datalake> factory)
            throws IOException {
        Datalake datalake = create(name, factory);
        datalake.save(new RawBook(10, "h", "b"));

        // carpeta basura: existe pero no tiene ningún libro
        Files.createDirectories(tmp.resolve(name).resolve("basura"));

        assertEquals(List.of(10), datalake.listBookIds());
    }

    // ------------------------------------------------------------------
    // Contrato del reto 10
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void vacioNoFalla(String name, Function<Path, Datalake> factory) {
        Datalake d = create(name, factory);
        assertEquals(List.of(), d.listBookIds());
        assertTrue(d.locate(1342).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void idaYVueltaConservaElContenido(String name, Function<Path, Datalake> factory) throws IOException {
        Datalake d = create(name, factory);
        d.save(new RawBook(1342, "Title: Pride", "It is a truth… ñ"));

        BookLocation loc = d.locate(1342).orElseThrow();
        assertEquals("Title: Pride", Files.readString(loc.headerPath(), StandardCharsets.UTF_8));
        assertEquals("It is a truth… ñ", Files.readString(loc.bodyPath(), StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void guardarDosVecesNoDuplicaEnElListado(String name, Function<Path, Datalake> factory) {
        Datalake d = create(name, factory);
        d.save(new RawBook(1342, "H", "B"));
        d.save(new RawBook(1342, "H2", "B2"));
        assertEquals(List.of(1342), d.listBookIds());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void libroSinHeaderDesaparece(String name, Function<Path, Datalake> factory) throws IOException {
        Datalake d = create(name, factory);
        d.save(new RawBook(1, "H", "B"));
        BookLocation loc = d.save(new RawBook(1342, "H", "B"));

        Files.delete(loc.headerPath());                 // simula un guardado a medias

        assertTrue(d.locate(1342).isEmpty());
        assertEquals(List.of(1), d.listBookIds());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void ignoraBasura(String name, Function<Path, Datalake> factory) throws IOException {
        Datalake d = create(name, factory);
        BookLocation loc = d.save(new RawBook(1342, "H", "B"));

        // Junto al libro: temporales y ficheros ajenos. Se colocan a partir de las
        // rutas que devolvió save, así sirve para las tres estructuras.
        Files.writeString(Path.of(loc.bodyPath() + ".tmp"), "x");
        Files.writeString(Path.of(loc.headerPath() + ".tmp"), "x");
        Files.writeString(loc.bodyPath().resolveSibling("notas.txt"), "x");

        // En la raíz: un fichero suelto y una carpeta con nombre inválido que imita
        // los nombres de fichero de las tres estructuras.
        Path root = tmp.resolve(name);
        Files.writeString(root.resolve("suelto.body.txt"), "x");
        Path basura = Files.createDirectories(root.resolve("basura"));
        for (String f : List.of("header.txt", "body.txt", "99.header.txt", "99.body.txt")) {
            Files.writeString(basura.resolve(f), "x");
        }

        assertEquals(List.of(1342), d.listBookIds());
        assertTrue(d.locate(99).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void listadoYLocateSonCoherentes(String name, Function<Path, Datalake> factory) throws IOException {
        Datalake d = create(name, factory);
        for (int id = 0; id < 30; id++) {
            d.save(new RawBook(id * 137, "H", "B"));
        }
        Files.delete(d.locate(137).orElseThrow().bodyPath());

        List<Integer> listed = d.listBookIds();
        assertFalse(listed.contains(137));
        for (int id : listed) {
            assertTrue(d.locate(id).isPresent(), name + ": locate debería encontrar " + id);
        }
    }

    // ------------------------------------------------------------------
    // Tests nuevos tras mejorar listBookIds
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void listadoEsInmodificable(String name, Function<Path, Datalake> factory) {
        Datalake d = create(name, factory);

        assertThrows(UnsupportedOperationException.class, () -> d.listBookIds().add(1));

        d.save(new RawBook(84, "H", "B"));
        assertThrows(UnsupportedOperationException.class, () -> d.listBookIds().add(1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void listaOrdenadaConMuchosLibrosDesordenados(String name, Function<Path, Datalake> factory) {
        Datalake d = create(name, factory);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(i * 37);                        // repartidos entre varios rangos
        }
        Collections.shuffle(ids, new Random(42));   // semilla fija: siempre el mismo desorden
        for (int id : ids) {
            d.save(new RawBook(id, "H", "B"));
        }

        List<Integer> esperado = new ArrayList<>(ids);
        Collections.sort(esperado);
        assertEquals(esperado, d.listBookIds());
    }

    // ------------------------------------------------------------------
    // Reto 11: interrupciones durante save
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void interrupcionAntesDeMoverElBodyNoDejaLibroValido(String name, Function<Path, Datalake> factory)
            throws IOException {
        Datalake d = create(name, factory);
        d.save(new RawBook(1, "H", "B"));
        BookLocation loc = d.save(new RawBook(1342, "H", "B"));

        // Simula que el proceso murió después de escribir body.tmp pero antes de moverlo:
        // el header está en su sitio y el body sólo existe como .tmp.
        Files.move(loc.bodyPath(), Path.of(loc.bodyPath() + ".tmp"));

        assertTrue(d.locate(1342).isEmpty());
        assertEquals(List.of(1), d.listBookIds());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structures")
    void reintentarTrasLaInterrupcionRecuperaElLibro(String name, Function<Path, Datalake> factory)
            throws IOException {
        Datalake d = create(name, factory);
        BookLocation loc = d.save(new RawBook(1342, "H", "B"));
        Files.move(loc.bodyPath(), Path.of(loc.bodyPath() + ".tmp"));

        BookLocation again = d.save(new RawBook(1342, "H", "B definitivo"));

        assertEquals(List.of(1342), d.listBookIds());
        assertEquals("B definitivo",
                Files.readString(d.locate(1342).orElseThrow().bodyPath(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(Path.of(again.bodyPath() + ".tmp")));
    }
}