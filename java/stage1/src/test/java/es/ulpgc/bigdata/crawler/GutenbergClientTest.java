package es.ulpgc.bigdata.crawler;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class GutenbergClientTest {

    private final GutenbergClient client = new GutenbergClient(Duration.ofSeconds(10), Duration.ofSeconds(15));

    @Test
    void descargaUnLibroConocido() {
        Optional<String> result = client.fetch(1342); // Pride and Prejudice

        assertTrue(result.isPresent());
        assertFalse(result.get().isEmpty());
        assertTrue(result.get().contains("PROJECT GUTENBERG"));
    }

    @Test
    void unIdInexistenteDevuelveOptionalVacio() {
        Optional<String> result = client.fetch(999_999_999);

        assertTrue(result.isEmpty());
    }
}