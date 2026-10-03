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
 * Control layer (shared/SPEC.md, section 8):
 *
 *   <data>/control/downloaded_books.txt
 *   <data>/control/indexed_books.txt
 *
 * One id per line. They are read as SETS: order and repetitions do not matter.
 *
 *   readyToIndex = downloaded − indexed
 *
 * Golden rule: the caller marks an id ONLY when the operation has already finished successfully
 * (the book is in the datalake / the index has flushed). If the program stops
 * before marking, the book is processed again, and that is safe because saving
 * and indexing can be repeated without duplicating anything.
 *
 * The files are loaded once when the object is created and kept in memory;
 * each mark is added to the end of the file (append).
 */
public class ControlFiles {

    public static final String DOWNLOADED_FILE = "downloaded_books.txt";
    public static final String INDEXED_FILE = "indexed_books.txt";

    /** Same criterion as the datalake: "0" or no leading zeros, at most 9 digits. */
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
    // Queries
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

    /** downloaded − indexed: downloaded books not yet in the index, sorted. */
    public List<Integer> readyToIndex() {
        SortedSet<Integer> pending = new TreeSet<>(downloaded);
        pending.removeAll(indexed);
        return List.copyOf(pending);
    }

    // ------------------------------------------------------------------
    // Marks: call ONLY when the operation has finished successfully
    // ------------------------------------------------------------------

    /** Call after the book has been saved in the datalake. */
    public void markDownloaded(int bookId) {
        mark(bookId, downloaded, downloadedFile);
    }

    /** Call after metadata and index have been saved (flush included). */
    public void markIndexed(int bookId) {
        mark(bookId, indexed, indexedFile);
    }

    private static void mark(int bookId, SortedSet<Integer> set, Path file) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        if (set.contains(bookId)) {
            return;                                   // already marked: not duplicated in the file
        }
        append(file, bookId + "\n");                  // disk first...
        set.add(bookId);                              // ...then memory
    }

    // ------------------------------------------------------------------
    // Reading and writing a control file
    // ------------------------------------------------------------------

    /**
     * Reads the ids from the file. Only COMPLETE lines (ending in \n) count:
     * if the program died halfway through writing "1342\n" and "13" was left, that fragment is
     * ignored, and it is also trimmed from the file so the next append does not
     * glue it to another id ("13" + "84\n" = "1384").
     */
    private static SortedSet<Integer> load(Path file) {
        SortedSet<Integer> ids = new TreeSet<>();
        if (!Files.exists(file)) {
            return ids;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int lastNewline = content.lastIndexOf('\n');
            String complete = content.substring(0, lastNewline + 1);   // "" if there is no \n at all
            if (complete.length() < content.length()) {
                truncate(file, complete.getBytes(StandardCharsets.UTF_8).length);
            }
            for (String line : complete.split("\n")) {
                String candidate = line.strip();                        // removes spaces and Windows \r
                if (VALID_ID.matcher(candidate).matches()) {
                    ids.add(Integer.parseInt(candidate));
                }                                                       // empty or garbage: ignored
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }

    /** Truncates the file, keeping only the first 'size' bytes. */
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