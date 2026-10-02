package es.ulpgc.bigdata.query;

import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * AND search engine (shared/SPEC.md, section 7):
 *
 *   query -> tokens -> posting lists -> intersection
 *
 * It never reads books: it only uses the Tokenizer and the InvertedIndex, whatever
 * its physical organisation (memory, JSON, folders or Mongo).
 */
public class SearchService {

    private final Tokenizer tokenizer;
    private final InvertedIndex index;

    public SearchService(Tokenizer tokenizer, InvertedIndex index) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * Ids of the books that contain ALL the terms of the query,
     * sorted from lowest to highest. If the query has no valid
     * term (empty, only stopwords or 1-letter words), returns [].
     */
    public List<Integer> search(String query) {
        Objects.requireNonNull(query, "query");

        // 1. Same tokenizer as when indexing. uniqueTerms already removes repetitions
        //    and keeps the order of first appearance.
        Set<String> terms = tokenizer.uniqueTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        // 2. Posting list of each term. If one is empty, the AND is already empty:
        //    there is no need to even ask for the others (in Mongo or on disk, each one costs).
        List<List<Integer>> postingLists = new ArrayList<>();
        for (String term : terms) {
            List<Integer> postings = index.postings(term);
            if (postings.isEmpty()) {
                return List.of();
            }
            postingLists.add(postings);
        }

        // 3. Shortest lists first: the result can never be longer
        //    than the shortest list, so each intersection does less work.
        postingLists.sort(Comparator.comparingInt(List::size));

        // 4. Chained intersections, stopping as soon as the result is empty.
        List<Integer> result = postingLists.get(0);
        for (int i = 1; i < postingLists.size() && !result.isEmpty(); i++) {
            result = intersect(result, postingLists.get(i));
        }
        return List.copyOf(result);
    }

    /**
     * Intersection of two SORTED lists without repetitions, with two pointers.
     * Each step moves at least one pointer forward, so it takes at most n + m steps.
     */
    static List<Integer> intersect(List<Integer> a, List<Integer> b) {
        List<Integer> result = new ArrayList<>(Math.min(a.size(), b.size()));
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            int x = a.get(i);
            int y = b.get(j);
            if (x == y) {          // it is in both: it is kept and both move forward
                result.add(x);
                i++;
                j++;
            } else if (x < y) {    // x cannot be in b (everything left in b is greater)
                i++;
            } else {               // y cannot be in a
                j++;
            }
        }
        return result;
    }
}