package es.ulpgc.bigdata.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Cliente responsable únicamente de descargar el texto crudo de un libro
 * de Project Gutenberg dado su id. No separa header/body ni guarda nada
 * en disco: eso es responsabilidad de otras clases (BookSplitter, Datalake).
 */
public class GutenbergClient {

    private static final String URL_TEMPLATE =
            "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;

    public GutenbergClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Descarga el texto completo del libro con el id dado.
     *
     * @return el texto si la descarga tuvo éxito (HTTP 2xx),
     *         Optional.empty() si el libro no está disponible o hay un
     *         fallo de red/timeout.
     */
    public Optional<String> fetch(int bookId) {
        URI uri = URI.create(String.format(URL_TEMPLATE, bookId, bookId));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(response.body());
            }
            return Optional.empty();

        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}