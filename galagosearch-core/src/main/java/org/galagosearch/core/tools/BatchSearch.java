// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.tools;

import java.util.List;
import org.galagosearch.core.retrieval.Retrieval;
import org.galagosearch.core.retrieval.ScoredDocument;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.SimpleQuery;
import org.galagosearch.core.retrieval.query.StructuredQuery;
import org.galagosearch.tupleflow.Parameters;

/**
 *
 * @author trevor
 */
public class BatchSearch {
    public static Parameters.Value getSmoothing(Parameters parameters) {
        if (parameters.containsKey("smoothing")) {
            return parameters.list("smoothing").get(0);
        }

        return null;
    }

    public static Node parseQuery(String query, Parameters parameters) {
        String queryType = parameters.get("queryType", "complex");

        if (queryType.equals("simple")) {
            return SimpleQuery.parseTree(query);
        }

        return StructuredQuery.parse(query);
    }

    public static String formatScore(double score) {
        double difference = Math.abs(score - (int) score);

        if (difference < 0.00001) {
            return Integer.toString((int) score);
        }
        return String.format("%10.8f", score);
    }

    public static void main(String[] args) throws Exception {
        // read in parameters
        Parameters parameters = new Parameters(args);
        List<Parameters.Value> queries = parameters.list("query");

        // open index
        Retrieval retrieval = Retrieval.instance(parameters.get("index"), parameters);

        // record results requested
        int requested = (int) parameters.get("count", 1000);

        // for each query, run it, get the results, look up the docnos, print in TREC format
        for (Parameters.Value query : queries) {
            // parse the query
            String queryText = query.get("text");
            Node queryRoot = parseQuery(queryText, parameters);

            ScoredDocument[] results = retrieval.runQuery(queryRoot, requested);

            for (int i = 0; i < results.length; i++) {
                String document = retrieval.getDocumentName(results[i].document);
                double score = results[i].score;
                int rank = i + 1;

                System.out.format("%s Q0 %s %d %s galago\n", query.get("number"), document, rank,
                                  formatScore(score));
            }
        }
    }
}
