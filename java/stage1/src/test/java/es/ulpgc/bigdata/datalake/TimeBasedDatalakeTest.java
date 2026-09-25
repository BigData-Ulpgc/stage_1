package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeBasedDatalakeTest {

    private static final ZoneId ZONE = ZoneId.of("Atlantic/Canary");

    @TempDir Path tmp;

    /** Datalake cuyo "ahora" está fijado en la fecha y hora indicadas. */
    private Datalake at(int year, int month, int day, int hour, int minute) {
        LocalDateTime moment = LocalDateTime.of(year, month, day, hour, minute);
        Clock fixed = Clock.fixed(moment.atZone(ZONE).toInstant(), ZONE);
        return new TimeBasedDatalake(tmp.resolve("time"), fixed);
    }

    private Path time(String relative) {
        return tmp.resolve("time").resolve(relative);
    }

    // --- Estructura física -------------------------------------------------

    @Test
    void guardaEnYYYYMMDDBarraHH() throws IOException {
        BookLocation loc = at(2026, 9, 23, 14, 5).save(new RawBook(1342, "HEADER", "BODY ñ"));

        assertEquals(time("20260923/14/1342.body.txt"), loc.bodyPath());
        assertEquals(time("20260923/14/1342.header.txt"), loc.headerPath());
        assertEquals("BODY ñ", Files.readString(loc.bodyPath(), StandardCharsets.UTF_8));
    }

    @Test
    void horaConCeroYFormato24h() {
        assertEquals(time("20260105/07/5.body.txt"),
                at(2026, 1, 5, 7, 0).save(new RawBook(5, "H", "B")).bodyPath());
        assertEquals(time("20260105/19/6.body.txt"),
                at(2026, 1, 5, 19, 30).save(new RawBook(6, "H", "B")).bodyPath());
    }

    @Test
    void finalDeDiciembreUsaElAnioDeCalendario() {
        // Con el patrón "YYYY" (año de semana) esto saldría 20261229.
        assertEquals(time("20251229/10/7.body.txt"),
                at(2025, 12, 29, 10, 0).save(new RawBook(7, "H", "B")).bodyPath());
    }

    // --- locate ------------------------------------------------------------

    @Test
    void locateEncuentraUnLibroAntiguoSinConocerSuHora() {
        BookLocation original = at(2026, 9, 23, 14, 5).save(new RawBook(1342, "H", "B"));

        Datalake unMesDespues = at(2026, 10, 23, 9, 0);
        assertEquals(original.bodyPath(), unMesDespues.locate(1342).orElseThrow().bodyPath());
    }

    @Test
    void locateInexistenteEsVacio() {
        at(2026, 9, 23, 14, 5).save(new RawBook(1342, "H", "B"));
        assertTrue(at(2026, 9, 23, 14, 5).locate(1343).isEmpty());
    }

    @Test
    void locateSinHeaderNoCuentaComoGuardado() throws IOException {
        Path dir = Files.createDirectories(time("20260923/14"));
        Files.writeString(dir.resolve("1342.body.txt"), "solo body");
        Datalake d = at(2026, 9, 23, 14, 5);
        assertTrue(d.locate(1342).isEmpty());
        assertEquals(List.of(), d.listBookIds());
    }

    // --- Duplicados --------------------------------------------------------

    @Test
    void guardadoDosVecesGanaLaCopiaMasRecienteYNoSeRepite() {
        at(2026, 9, 23, 14, 5).save(new RawBook(1342, "H1", "B1"));
        Datalake despues = at(2026, 10, 1, 9, 0);
        despues.save(new RawBook(1342, "H2", "B2"));

        assertEquals(time("20261001/09/1342.body.txt"), despues.locate(1342).orElseThrow().bodyPath());
        assertEquals(List.of(1342), despues.listBookIds());
    }

    @Test
    void copiaRecienteIncompletaCaeALaAnteriorCompleta() throws IOException {
        at(2026, 1, 5, 7, 0).save(new RawBook(5, "H", "B"));
        Path nueva = Files.createDirectories(time("20261105/08"));
        Files.writeString(nueva.resolve("5.body.txt"), "sin header");

        Datalake d = at(2026, 11, 5, 8, 0);
        assertEquals(time("20260105/07/5.body.txt"), d.locate(5).orElseThrow().bodyPath());
    }

    // --- listBookIds -------------------------------------------------------

    @Test
    void listaOrdenadaEntreDiasYHoras() {
        at(2026, 9, 23, 14, 0).save(new RawBook(1342, "H", "B"));
        at(2026, 1, 5, 7, 0).save(new RawBook(5, "H", "B"));
        at(2026, 1, 5, 19, 0).save(new RawBook(84, "H", "B"));
        assertEquals(List.of(5, 84, 1342), at(2026, 9, 23, 15, 0).listBookIds());
    }

    @Test
    void ignoraBasura() throws IOException {
        Datalake d = at(2026, 9, 23, 14, 5);
        d.save(new RawBook(1342, "H", "B"));

        Path hora = time("20260923/14");
        Files.writeString(hora.resolve("88.body.txt.tmp"), "x");
        Files.writeString(hora.resolve("abc.body.txt"), "x");
        Files.writeString(hora.resolve("notas.txt"), "x");

        // Libros completos en carpetas con nombre no válido: no deben contar.
        for (String carpeta : List.of("hola/10", "20260923/25", "2026092/10")) {
            Path dir = Files.createDirectories(time(carpeta));
            Files.writeString(dir.resolve("99.body.txt"), "x");
            Files.writeString(dir.resolve("99.header.txt"), "x");
        }
        Files.writeString(time("suelto.body.txt"), "x");

        assertEquals(List.of(1342), d.listBookIds());
        assertTrue(d.locate(99).isEmpty());
    }

    @Test
    void listadoYLocateSonCoherentes() {
        at(2026, 1, 5, 7, 0).save(new RawBook(1, "H", "B"));
        at(2026, 9, 23, 14, 0).save(new RawBook(1342, "H", "B"));
        Datalake d = at(2026, 9, 23, 15, 0);
        for (int id : d.listBookIds()) {
            assertTrue(d.locate(id).isPresent(), "locate debería encontrar " + id);
        }
    }

    @Test
    void datalakeVacioNoFalla() {
        Datalake d = at(2026, 9, 23, 14, 5);
        assertEquals(List.of(), d.listBookIds());
        assertTrue(d.locate(1).isEmpty());
    }
}