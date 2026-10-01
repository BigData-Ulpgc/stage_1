package es.ulpgc.bigdata.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Cliente responsable únicamente de descargar el texto crudo de un libro
 * de Project Gutenberg dado su id. No separa header/body ni guarda nada
 * en disco: eso es responsabilidad de otras clases (BookSplitter, Datalake).
 */
public class GutenbergClient implements BookSource {

    private static final String URL_TEMPLATE =
            "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /** Los timeouts vienen de AppConfig (http.connect.timeout.seconds, http.request.timeout.seconds). */
    public GutenbergClient(Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
    }

    /**
     * Descarga el texto completo del libro con el id dado.
     *
     * @return el texto si la descarga tuvo éxito (HTTP 2xx),
     *         Optional.empty() si el libro no está disponible o hay un
     *         fallo de red/timeout.
     */
    @Override
    public Optional<String> fetch(int bookId) {
        URI uri = URI.create(String.format(URL_TEMPLATE, bookId, bookId));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
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