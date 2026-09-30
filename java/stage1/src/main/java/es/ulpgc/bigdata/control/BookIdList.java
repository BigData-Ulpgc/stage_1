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
 * Lee shared/book_ids.txt (shared/SPEC.md, sección 1): los libros del dataset
 * común, uno por línea, en el MISMO orden en Java, Python y C.
 * Se ignoran las líneas vacías y las que empiezan por '#'.
 */
public final class BookIdList {

    private static final Pattern VALID_ID = Pattern.compile("0|[1-9][0-9]{0,8}");

    private BookIdList() {
    }

    /**
     * @return los ids en el orden del fichero, sin repetidos
     * @throws IllegalArgumentException si una línea no es un id: es un fichero que
     *         escribe el equipo a mano, y una errata no debe pasar desapercibida
     */
    public static List<Integer> load(Path file) {
        try {
            Set<Integer> ids = new LinkedHashSet<>();          // conserva el orden, quita repetidos
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