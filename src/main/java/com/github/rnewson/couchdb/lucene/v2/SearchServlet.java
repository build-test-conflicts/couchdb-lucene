package com.github.rnewson.couchdb.lucene.v2;

import static com.github.rnewson.couchdb.lucene.v2.ServletUtils.getBooleanParameter;
import static com.github.rnewson.couchdb.lucene.v2.ServletUtils.getIntParameter;
import static com.github.rnewson.couchdb.lucene.v2.ServletUtils.getParameter;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;

import com.github.rnewson.couchdb.lucene.util.Analyzers;
import com.github.rnewson.couchdb.lucene.util.StopWatch;

public final class SearchServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final LuceneHolder holder;

    SearchServlet(final LuceneHolder holder) throws IOException {
        this.holder = holder;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException,
            IOException {
        if (req.getParameter("q") == null) {
            resp.sendError(400, "Missing q attribute.");
            return;
        }

        // Refresh reader and searcher unless stale=ok.
        if (!"ok".equals(req.getParameter("stale"))) {
            holder.reopenReader();
        }

        final boolean debug = getBooleanParameter(req, "debug");
        final boolean rewrite_query = getBooleanParameter(req, "rewrite_query");

        final IndexSearcher searcher = holder.borrowSearcher();
        try {
            // Check for 304 - Not Modified.
            req.getHeader("If-None-Match");
            final String etag = getETag(searcher);
            if (!debug && etag.equals(req.getHeader("If-None-Match"))) {
                resp.setStatus(304);
                return;
            }

            // Parse query.
            final Analyzer analyzer = Analyzers.getAnalyzer(getParameter(req, "analyzer", "standard"));
            final QueryParser parser = new QueryParser(Constants.DEFAULT_FIELD, analyzer);

            final Query q;
            try {
                q = parser.parse(req.getParameter("q"));
            } catch (final ParseException e) {
                resp.sendError(400, "Bad query syntax.");
                return;
            }

            final JSONObject json = new JSONObject();
            json.put("q", q.toString());
            json.put("etag", etag);

            if (rewrite_query) {
                final Query rewritten_q = q.rewrite(searcher.getIndexReader());
                json.put("rewritten_q", rewritten_q.toString());

                final JSONObject freqs = new JSONObject();

                final Set<Term> terms = new HashSet<Term>();
                rewritten_q.extractTerms(terms);
                for (final Object term : terms) {
                    final int freq = searcher.docFreq((Term) term);
                    freqs.put(term, freq);
                }
                json.put("freqs", freqs);
            } else {
                // Perform the search.
                final TopDocs td;
                final StopWatch stopWatch = new StopWatch();

                final boolean include_docs = getBooleanParameter(req, "include_docs");
                final int limit = getIntParameter(req, "limit", 25);
                final Sort sort = toSort(req.getParameter("sort"));
                final int skip = getIntParameter(req, "skip", 0);

                // RESTORE THIS FEATURE Filter out items from other views.
                /*
                 * Filter filter = new TermsFilter(); ((TermsFilter)
                 * filter).addTerm(new Term(Constants.VIEW, this.viewname));
                 * filter = FilterCache.get(this.viewname, filter);
                 */
                Filter filter = null;

                if (sort == null) {
                    td = searcher.search(q, filter, skip + limit);
                } else {
                    td = searcher.search(q, filter, skip + limit, sort);
                }
                stopWatch.lap("search");

                // Fetch matches (if any).
                final int max = max(0, min(td.totalHits - skip, limit));
                final JSONArray rows = new JSONArray();
                final String[] fetch_ids = new String[max];
                for (int i = skip; i < skip + max; i++) {
                    final Document doc = searcher.doc(td.scoreDocs[i].doc);
                    final JSONObject row = new JSONObject();
                    final JSONObject fields = new JSONObject();

                    // Include stored fields.
                    for (Object f : doc.getFields()) {
                        Field fld = (Field) f;

                        if (!fld.isStored())
                            continue;
                        String name = fld.name();
                        String value = fld.stringValue();
                        if (value != null) {
                            if (Constants.ID.equals(name)) {
                                row.put("id", value);
                            } else {
                                if (!fields.has(name)) {
                                    fields.put(name, value);
                                } else {
                                    final Object obj = fields.get(name);
                                    if (obj instanceof String) {
                                        final JSONArray arr = new JSONArray();
                                        arr.add((String) obj);
                                        arr.add(value);
                                        fields.put(name, arr);
                                    } else {
                                        assert obj instanceof JSONArray;
                                        ((JSONArray) obj).add(value);
                                    }
                                }
                            }
                        }
                    }

                    if (!Float.isNaN(td.scoreDocs[i].score)) {
                        row.put("score", td.scoreDocs[i].score);
                    }
                    // Include sort order (if any).
                    if (td instanceof TopFieldDocs) {
                        final FieldDoc fd = (FieldDoc) ((TopFieldDocs) td).scoreDocs[i];
                        row.put("sort_order", fd.fields);
                    }
                    // Fetch document (if requested).
                    if (include_docs) {
                        fetch_ids[i - skip] = doc.get(Constants.ID);
                    }
                    if (fields.size() > 0) {
                        row.put("fields", fields);
                    }
                    rows.add(row);
                }
                // Fetch documents (if requested).
                if (include_docs) {
                    /*
                     * TODO RESTORE THIS FEATURE! final JSONArray fetched_docs =
                     * DB.getDocs(dbname, fetch_ids).getJSONArray("rows"); for
                     * (int i = 0; i < max; i++) {
                     * rows.getJSONObject(i).put("doc",
                     * fetched_docs.getJSONObject(i).getJSONObject("doc")); }
                     */
                }
                stopWatch.lap("fetch");

                json.put("skip", skip);
                json.put("limit", limit);
                json.put("total_rows", td.totalHits);
                json.put("search_duration", stopWatch.getElapsed("search"));
                json.put("fetch_duration", stopWatch.getElapsed("fetch"));
                // Include sort info (if requested).
                if (td instanceof TopFieldDocs) {
                    json.put("sort_order", toString(((TopFieldDocs) td).fields));
                }
                json.put("rows", rows);
            }

            // Determine content type of response.
            if (getBooleanParameter(req, "force_json") || req.getHeader("Accept").contains("application/json")) {
                resp.setContentType("application/json");
            } else {
                resp.setContentType("text/plain;charset=utf-8");
            }

            // Cache-related headers.
            resp.setHeader("ETag", etag);
            resp.setHeader("Cache-Control", "must-revalidate");

            // Write response.
            final Writer writer = resp.getWriter();
            try {
                final String callback = req.getParameter("callback");
                final String body;
                if (callback != null) {
                    body = String.format("%s(%s)", callback, json);
                } else {
                    body = json.toString(debug ? 2 : 0);
                }
                writer.write(body);
            } finally {
                writer.close();
            }
        } finally {
            holder.returnSearcher(searcher);
        }
    }

    private String getETag(final IndexSearcher searcher) {
        return Long.toHexString(searcher.getIndexReader().getVersion());
    }

    private Sort toSort(final String sort) {
        if (sort == null) {
            return null;
        } else {
            final String[] split = sort.split(",");
            final SortField[] sort_fields = new SortField[split.length];
            for (int i = 0; i < split.length; i++) {
                String tmp = split[i];
                final boolean reverse = tmp.charAt(0) == '\\';
                // Strip sort order character.
                if (tmp.charAt(0) == '\\' || tmp.charAt(0) == '/') {
                    tmp = tmp.substring(1);
                }
                final boolean has_type = tmp.indexOf(':') != -1;
                if (!has_type) {
                    sort_fields[i] = new SortField(tmp, SortField.STRING, reverse);
                } else {
                    final String field = tmp.substring(0, tmp.indexOf(':'));
                    final String type = tmp.substring(tmp.indexOf(':') + 1);
                    int type_int = SortField.STRING;
                    if ("int".equals(type)) {
                        type_int = SortField.INT;
                    } else if ("float".equals(type)) {
                        type_int = SortField.FLOAT;
                    } else if ("double".equals(type)) {
                        type_int = SortField.DOUBLE;
                    } else if ("long".equals(type)) {
                        type_int = SortField.LONG;
                    } else if ("date".equals(type)) {
                        type_int = SortField.LONG;
                    } else if ("string".equals(type)) {
                        type_int = SortField.STRING;
                    }
                    sort_fields[i] = new SortField(field, type_int, reverse);
                }
            }
            return new Sort(sort_fields);
        }
    }

    private String toString(final SortField[] sortFields) {
        final JSONArray result = new JSONArray();
        for (final SortField field : sortFields) {
            final JSONObject col = new JSONObject();
            col.element("field", field.getField());
            col.element("reverse", field.getReverse());

            final String type;
            switch (field.getType()) {
            case SortField.DOC:
                type = "doc";
                break;
            case SortField.SCORE:
                type = "score";
                break;
            case SortField.INT:
                type = "int";
                break;
            case SortField.LONG:
                type = "long";
                break;
            case SortField.BYTE:
                type = "byte";
                break;
            case SortField.CUSTOM:
                type = "custom";
                break;
            case SortField.DOUBLE:
                type = "double";
                break;
            case SortField.FLOAT:
                type = "float";
                break;
            case SortField.SHORT:
                type = "short";
                break;
            case SortField.STRING:
                type = "string";
                break;
            default:
                type = "unknown";
                break;
            }
            col.element("type", type);
            result.add(col);
        }
        return result.toString();
    }

}
