package org.apache.solr.response;

import org.apache.commons.io.output.TeeWriter;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

public class ISpyResponseWriter implements QueryResponseWriter {

    private JSONResponseWriter jsonResponseWriter = new JSONResponseWriter();

    private String wrappedWT;
    @Override
    public void write(Writer writer, SolrQueryRequest request, SolrQueryResponse response) throws IOException {
        QueryResponseWriter wrappedWriter = request.getCore().getQueryResponseWriter(wrappedWT);
        StringWriter ericCopy = new StringWriter();

        writer = new TeeWriter(writer, ericCopy);
    }

    @Override
    public String getContentType(SolrQueryRequest request, SolrQueryResponse response) {
        QueryResponseWriter wrappedWriter = request.getCore().getQueryResponseWriter(wrappedWT);
        return wrappedWriter.getContentType(request, response);
    }

    @Override
    public void init(NamedList<?> n) {
        if (n != null) {
            wrappedWT = n.get("wrapped-wt").toString();
        }
    }
}
