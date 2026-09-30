package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** Las palabras de shared/queries.txt: así las consultas del benchmark encuentran libros. */
    private static final String[] QUERY_WORDS = {"adventure", "island", "love", "ship", "sea", "king",
            "queen", "monster", "creature", "whale", "detective", "crime", "war", "peace", "mother", "father"};

    /**
     * Libros para el benchmark del índice: 'synthetic' sólo tiene 16 palabras, y un índice
     * de 16 términos no se parece en nada al de libros reales.
     *
     * Aquí hay 'vocabularySize' palabras y se eligen con la ley de Zipf, como en un texto real:
     * la palabra de rango r sale con probabilidad proporcional a 1/r. Unas pocas están en
     * todos los libros y la mayoría en muy pocos, y el vocabulario crece al crecer N.
     *
     * Las palabras de las consultas van en rangos repartidos (10, 40, 90, ... 2560): unas
     * están en casi todos los libros y otras en pocos, así los AND no devuelven siempre todo.
     * El resto son palabras inventadas "xaa", "xab"... (ninguna es stopword).
     */
    public static List<RawBook> syntheticZipf(int count, int tokensPerBook, int vocabularySize, long seed) {
        if (vocabularySize < 3000) {
            throw new IllegalArgumentException("vocabularySize debe ser >= 3000 (rangos de las consultas)");
        }
        String[] vocabulary = new String[vocabularySize];
        for (int i = 0; i < QUERY_WORDS.length; i++) {
            vocabulary[10 * (i + 1) * (i + 1) - 1] = QUERY_WORDS[i];       // rango 10, 40, 90...
        }
        int next = 0;
        for (int r = 0; r < vocabularySize; r++) {
            if (vocabulary[r] == null) {
                vocabulary[r] = "x" + base26(next++);
            }
        }

        double[] cumulative = new double[vocabularySize];                  // P(rango <= r), para buscar con binarySearch
        double sum = 0;
        for (int r = 0; r < vocabularySize; r++) {
            sum += 1.0 / (r + 1);
            cumulative[r] = sum;
        }

        Random random = new Random(seed);
        List<RawBook> books = new ArrayList<>(count);
        int id = 1;
        for (int i = 0; i < count; i++) {
            id += 1 + random.nextInt(700);
            StringBuilder body = new StringBuilder(tokensPerBook * 6);
            for (int t = 0; t < tokensPerBook; t++) {
                int rank = Arrays.binarySearch(cumulative, random.nextDouble() * sum);
                body.append(vocabulary[rank < 0 ? -rank - 1 : rank]).append(' ');
            }
            String header = "Title: Synthetic Book " + id + "\nAuthor: Author " + (id % 37)
                    + "\nRelease date: January 1, 2000 [eBook #" + id + "]\nLanguage: English";
            books.add(new RawBook(id, header, body.toString()));
        }
        return List.copyOf(books);
    }

    /** 0 -> "aa", 1 -> "ab", 26 -> "ba"... siempre al menos 2 letras. */
    private static String base26(int n) {
        StringBuilder s = new StringBuilder();
        do {
            s.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        while (s.length() < 2) {
            s.append('a');
        }
        return s.reverse().toString();
    }
}