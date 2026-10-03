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
 * Where the benchmark books come from. Never from the network: they are read from a
 * datalake that already exists (the one filled by the pipeline, or sample_dataset/) or generated.
 */
public final class BenchmarkBooks {

    private BenchmarkBooks() {
    }

    /** All the books of an already filled datalake, in id order. */
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
     * Made-up books, but always the same for the same seed (tests, or when there is no
     * dataset yet). Ids spread across several ranges of 1000, like the real ones.
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

    /** The words of shared/queries.txt: this way the benchmark queries find books. */
    private static final String[] QUERY_WORDS = {"adventure", "island", "love", "ship", "sea", "king",
            "queen", "monster", "creature", "whale", "detective", "crime", "war", "peace", "mother", "father"};

    /**
     * Books for the index benchmark: 'synthetic' only has 16 words, and an index
     * of 16 terms looks nothing like one built from real books.
     *
     * Here there are 'vocabularySize' words, chosen with Zipf's law, as in a real text:
     * the word of rank r appears with probability proportional to 1/r. A few are in
     * every book and most are in very few, and the vocabulary grows as N grows.
     *
     * The query words go in spread-out ranks (10, 40, 90, ... 2560): some are
     * in almost every book and others in few, so the ANDs do not always return everything.
     * The rest are made-up words "xaa", "xab"... (none of them is a stopword).
     */
    public static List<RawBook> syntheticZipf(int count, int tokensPerBook, int vocabularySize, long seed) {
        if (vocabularySize < 3000) {
            throw new IllegalArgumentException("vocabularySize debe ser >= 3000 (rangos de las consultas)");
        }
        String[] vocabulary = new String[vocabularySize];
        for (int i = 0; i < QUERY_WORDS.length; i++) {
            vocabulary[10 * (i + 1) * (i + 1) - 1] = QUERY_WORDS[i];       // rank 10, 40, 90...
        }
        int next = 0;
        for (int r = 0; r < vocabularySize; r++) {
            if (vocabulary[r] == null) {
                vocabulary[r] = "x" + base26(next++);
            }
        }

        double[] cumulative = new double[vocabularySize];                  // P(rank <= r), to search with binarySearch
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

    /** 0 -> "aa", 1 -> "ab", 26 -> "ba"... always at least 2 letters. */
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