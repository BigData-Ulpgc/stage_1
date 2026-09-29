package es.ulpgc.bigdata.datamart.index;

import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.BookMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Puente datalake -> datamarts:
 *
 *   header.txt --(MetadataParser)--> MetadataRepository (SQLite)
 *   body.txt   --(Tokenizer)-------> InvertedIndex (JSON, carpetas, Mongo...)
 *
 * Orden de trabajo:
 *  1. Leer y calcular TODO (header, body, metadatos, términos) sin escribir nada.
 *     Si algo falla aquí, no queda ningún estado a medias.
 *  2. Guardar metadatos.
 *  3. Añadir al índice y hacer flush.
 * Sólo cuando termina bien, quien llama puede marcar el libro como indexado (reto 24).
 *
 * Indexar dos veces el mismo libro es seguro: save es un upsert y addDocument no duplica ids.
 */
public class Indexer {

    private final Datalake datalake;
    private final MetadataParser parser;
    private final MetadataRepository metadata;
    private final Tokenizer tokenizer;
    private final InvertedIndex index;

    public Indexer(Datalake datalake, MetadataParser parser, MetadataRepository metadata,
                   Tokenizer tokenizer, InvertedIndex index) {
        this.datalake = Objects.requireNonNull(datalake, "datalake");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * Indexa un libro que ya está en el datalake.
     *
     * @return los metadatos guardados, o vacío si el libro no está en el datalake
     *         (en ese caso no se toca ni SQLite ni el índice).
     * @throws RuntimeException si falla la lectura, SQLite o el índice. El libro NO
     *         debe marcarse como indexado; reindexarlo más tarde es seguro.
     */
    public Optional<BookMetadata> index(int bookId) {
        Optional<BookLocation> found = datalake.locate(bookId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BookLocation location = found.get();

        // 1. Leer y calcular. Nada de lo de aquí escribe.
        String header = read(location.headerPath());
        String body = read(location.bodyPath());
        BookMetadata bookMetadata = parser.parse(location, header);   // incluye las rutas
        Set<String> terms = tokenizer.uniqueTerms(body);               // sólo el body

        // 2. Metadatos primero: un libro con metadatos pero sin índice es invisible
        //    para la búsqueda; lo contrario daría resultados sin título ni autor.
        metadata.save(bookMetadata);

        // 3. Índice después, y persistido antes de devolver.
        index.addDocument(bookId, terms);
        index.flush();

        return Optional.of(bookMetadata);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }
}