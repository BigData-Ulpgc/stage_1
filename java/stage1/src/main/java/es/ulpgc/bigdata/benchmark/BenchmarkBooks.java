package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * De dónde salen los libros del benchmark. Nunca de la red: se leen de un datalake
 * que ya existe (el que llenó el pipeline, o sample_dataset/) o se generan.
 */
public final class BenchmarkBooks {

    private BenchmarkBooks() {
    }

    /** Todos los libros de un datalake ya lleno, en orden de id. */
    public static List<RawBook> fromDatalake(Datalake source) {
        List<RawBook> books = new ArrayList<>();
        for (int id : source.listBookIds()) {
            BookLocation loc = source.locate(id).orElseThrow();
            try {
                books.add(new RawBook(id,
                        Files.readString(loc.headerPath(), StandardCharsets.UTF_8),
                        Files.readString(loc.bodyPath(), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo leer el libro " + id, e);
            }
        }
        return List.copyOf(books);
    }

    /**
     * Libros inventados pero siempre iguales para la misma semilla (tests, o si aún
     * no hay dataset). Ids repartidos entre varios rangos de 1000, como los reales.
     */
    public static List<RawBook> synthetic(int count, int bodyKilobytes, long seed) {
        Random random = new Random(seed);
        String[] words = {"whale", "sea", "captain", "ship", "love", "letter", "house", "war",
                "peace", "night", "garden", "river", "king", "queen", "truth", "fortune"};
        List<RawBook> books = new ArrayList<>(count);
        int id = 1;
        for (int i = 0; i < count; i++) {
            id += 1 + random.nextInt(700);                   // 2, 350, 901, 1542...
            StringBuilder body = new StringBuilder(bodyKilobytes * 1024);
            while (body.length() < bodyKilobytes * 1024) {
                body.append(words[random.nextInt(words.length)]).append(' ');
            }
            String header = "Title: Synthetic Book " + id + "\nAuthor: Author " + (id % 37)
                    + "\nRelease date: January 1, 2000 [eBook #" + id + "]\nLanguage: English";
            books.add(new RawBook(id, header, body.toString()));
        }
        return List.copyOf(books);
    }
}