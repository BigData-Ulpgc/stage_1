package es.ulpgc.bigdata.crawler;

import es.ulpgc.bigdata.model.RawBook;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the raw text downloaded from Gutenberg into header and body,
 * discarding the footer. It does no I/O and knows nothing about the datalake.
 */
public class BookSplitter {

    private static final Pattern START_MARKER = Pattern.compile(
            "\\*\\*\\* START OF (THE|THIS) PROJECT GUTENBERG EBOOK.*");
    private static final Pattern END_MARKER = Pattern.compile(
            "\\*\\*\\* END OF (THE|THIS) PROJECT GUTENBERG EBOOK.*");

    public Optional<RawBook> split(int bookId, String rawText) {
        String normalized = rawText.replace("\r\n", "\n");

        Matcher startMatcher = START_MARKER.matcher(normalized);
        if (!startMatcher.find()) {
            return Optional.empty();
        }

        Matcher endMatcher = END_MARKER.matcher(normalized);
        if (!endMatcher.find(startMatcher.end())) {
            return Optional.empty();
        }

        int startLineEnd = normalized.indexOf('\n', startMatcher.end());
        if (startLineEnd == -1) {
            return Optional.empty();
        }

        String header = normalized.substring(0, startMatcher.start()).trim();
        String body = normalized.substring(startLineEnd, endMatcher.start()).trim();

        return Optional.of(new RawBook(bookId, header, body));
    }
}