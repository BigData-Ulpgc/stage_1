package es.ulpgc.bigdata.datamart.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tokenizador del contrato común (shared/SPEC.md, sección 5), idéntico en Java, Python y C:
 *
 *  1. Se recorre el texto carácter a carácter.
 *  2. A-Z pasa a a-z; a-z y 0-9 forman parte del token.
 *  3. Cualquier otro carácter (espacio, puntuación, apóstrofe, no ASCII) es separador.
 *  4. Se descartan los tokens de longitud < 2.
 *  5. Se descartan las stopwords.
 *
 * No usa toLowerCase() ni Character.isLetter(): ambos siguen reglas Unicode
 * que C no puede reproducir byte a byte.
 */
public class Tokenizer {

    static final int MIN_LENGTH = 2;

    private final Set<String> stopwords;

    public Tokenizer(Set<String> stopwords) {
        this.stopwords = Set.copyOf(Objects.requireNonNull(stopwords, "stopwords"));
    }

    /**
     * Carga shared/stopwords.txt: una por línea; se ignoran las líneas vacías
     * y las que empiezan por '#'.
     */
    public static Tokenizer fromStopwordsFile(Path file) {
        try {
            Set<String> words = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String word = line.strip();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word);
                }
            }
            return new Tokenizer(words);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron leer las stopwords de " + file, e);
        }
    }

    /** Todos los tokens, en orden y con repeticiones: "Boat, BOAT!" -> [boat, boat]. */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        forEachToken(text, tokens::add);
        return Collections.unmodifiableList(tokens);
    }

    /**
     * Cada término una sola vez, en el orden en que aparece por primera vez:
     * "Boat, BOAT!" -> [boat]. Es lo que cada libro aporta al índice.
     */
    public Set<String> uniqueTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        forEachToken(text, terms::add);
        return Collections.unmodifiableSet(terms);   // Set.copyOf perdería el orden
    }

    /** El único bucle del tokenizador; tokenize y uniqueTerms sólo cambian dónde se guarda. */
    private void forEachToken(String text, Consumer<String> sink) {
        Objects.requireNonNull(text, "text");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                current.append((char) (c + ('a' - 'A')));     // mayúscula ASCII -> minúscula
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                current.append(c);
            } else {
                emit(current, sink);                          // separador: cierra el token
            }
        }
        emit(current, sink);                                  // el texto puede acabar en mitad de un token
    }

    /** Entrega el token acumulado si pasa los filtros, y vacía el acumulador. */
    private void emit(StringBuilder current, Consumer<String> sink) {
        if (current.length() >= MIN_LENGTH) {
            String token = current.toString();
            if (!stopwords.contains(token)) {
                sink.accept(token);
            }
        }
        current.setLength(0);
    }
}