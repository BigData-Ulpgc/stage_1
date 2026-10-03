package es.ulpgc.bigdata.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads shared/book_ids.txt (shared/SPEC.md, section 1): the books of the common
 * dataset, one per line, in the SAME order in Java, Python and C.
 * Empty lines and those starting with '#' are ignored.
 */
public final class BookIdList {

    private static final Pattern VALID_ID = Pattern.compile("0|[1-9][0-9]{0,8}");

    private BookIdList() {
    }

    /**
     * @return the ids in file order, without duplicates
     * @throws IllegalArgumentException if a line is not an id: it is a file the team
     *         writes by hand, and a typo must not go unnoticed
     */
    public static List<Integer> load(Path file) {
        try {
            Set<Integer> ids = new LinkedHashSet<>();          // keeps the order, removes duplicates
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!VALID_ID.matcher(line).matches()) {
                    throw new IllegalArgumentException(
                            file + ", línea " + (i + 1) + ": \"" + line + "\" no es un book_id válido");
                }
                ids.add(Integer.parseInt(line));
            }
            return List.copyOf(ids);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }
}