package es.ulpgc.bigdata.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Capa de control (shared/SPEC.md, sección 8):
 *
 *   <data>/control/downloaded_books.txt
 *   <data>/control/indexed_books.txt
 *
 * Un id por línea. Se leen como CONJUNTOS: el orden y las repeticiones no importan.
 *
 *   readyToIndex = downloaded − indexed
 *
 * Regla de oro: quien llama marca un id SÓLO cuando la operación ya terminó bien
 * (el libro está en el datalake / el índice hizo flush). Si el programa se corta
 * antes de marcar, el libro se vuelve a procesar, y eso es seguro porque guardar
 * e indexar se pueden repetir sin duplicar nada.
 *
 * Los ficheros se cargan una vez al crear el objeto y se mantienen en memoria;
 * cada marca se añade al final del fichero (append).
 */
public class ControlFiles {

    public static final String DOWNLOADED_FILE = "downloaded_books.txt";
    public static final String INDEXED_FILE = "indexed_books.txt";

    /** Mismo criterio que el datalake: "0" o sin ceros delante, máximo 9 dígitos. */
    private static final Pattern VALID_ID = Pattern.compile("0|[1-9][0-9]{0,8}");

    private final Path downloadedFile;
    private final Path indexedFile;
    private final SortedSet<Integer> downloaded;
    private final SortedSet<Integer> indexed;

    public ControlFiles(Path controlDir) {
        Objects.requireNonNull(controlDir, "controlDir");
        this.downloadedFile = controlDir.resolve(DOWNLOADED_FILE);
        this.indexedFile = controlDir.resolve(INDEXED_FILE);
        this.downloaded = load(downloadedFile);
        this.indexed = load(indexedFile);
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    public SortedSet<Integer> downloaded() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(downloaded));
    }

    public SortedSet<Integer> indexed() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(indexed));
    }

    public boolean isDownloaded(int bookId) {
        return downloaded.contains(bookId);
    }

    public boolean isIndexed(int bookId) {
        return indexed.contains(bookId);
    }

    /** downloaded − indexed: descargados que aún no están en el índice, ordenados. */
    public List<Integer> readyToIndex() {
        SortedSet<Integer> pending = new TreeSet<>(downloaded);
        pending.removeAll(indexed);
        return List.copyOf(pending);
    }

    // ------------------------------------------------------------------
    // Marcas: llamar SÓLO cuando la operación ha terminado bien
    // ------------------------------------------------------------------

    /** Llamar después de que el libro esté guardado en el datalake. */
    public void markDownloaded(int bookId) {
        mark(bookId, downloaded, downloadedFile);
    }

    /** Llamar después de que metadatos e índice estén guardados (flush incluido). */
    public void markIndexed(int bookId) {
        mark(bookId, indexed, indexedFile);
    }

    private static void mark(int bookId, SortedSet<Integer> set, Path file) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        if (set.contains(bookId)) {
            return;                                   // ya marcado: no se duplica en el fichero
        }
        append(file, bookId + "\n");                  // primero el disco...
        set.add(bookId);                              // ...después la memoria
    }

    // ------------------------------------------------------------------
    // Lectura y escritura de un fichero de control
    // ------------------------------------------------------------------

    /**
     * Lee los ids del fichero. Sólo cuentan las líneas COMPLETAS (terminadas en \n):
     * si el programa murió a mitad de escribir "1342\n" y quedó "13", ese trozo se
     * ignora, y además se recorta del fichero para que el siguiente append no lo
     * pegue a otro id ("13" + "84\n" = "1384").
     */
    private static SortedSet<Integer> load(Path file) {
        SortedSet<Integer> ids = new TreeSet<>();
        if (!Files.exists(file)) {
            return ids;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int lastNewline = content.lastIndexOf('\n');
            String complete = content.substring(0, lastNewline + 1);   // "" si no hay ningún \n
            if (complete.length() < content.length()) {
                truncate(file, complete.getBytes(StandardCharsets.UTF_8).length);
            }
            for (String line : complete.split("\n")) {
                String candidate = line.strip();                        // quita espacios y \r de Windows
                if (VALID_ID.matcher(candidate).matches()) {
                    ids.add(Integer.parseInt(candidate));
                }                                                       // vacías o basura: se ignoran
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }

    /** Recorta el fichero dejando sólo los primeros 'size' bytes. */
    private static void truncate(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer bytes = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir en " + file, e);
        }
    }
}