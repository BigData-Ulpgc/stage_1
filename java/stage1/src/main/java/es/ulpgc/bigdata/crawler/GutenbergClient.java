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
 * Client whose only responsibility is downloading the raw text of a
 * Project Gutenberg book given its id. It does not split header/body or save anything
 * to disk: that is the responsibility of other classes (BookSplitter, Datalake).
 */
public class GutenbergClient implements BookSource {

    private static final String URL_TEMPLATE =
            "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /** The timeouts come from AppConfig (http.connect.timeout.seconds, http.request.timeout.seconds). */
    public GutenbergClient(Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
    }

    /**
     * Downloads the full text of the book with the given id.
     *
     * @return the text if the download succeeded (HTTP 2xx),
     *         Optional.empty() if the book is not available or there is a
     *         network failure/timeout.
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