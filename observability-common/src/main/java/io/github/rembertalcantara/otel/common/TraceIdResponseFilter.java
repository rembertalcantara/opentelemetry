package io.github.rembertalcantara.otel.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the current trace id to every response so a caller can jump straight to the trace:
 * {@code X-Trace-Id} for humans and the W3C Trace Context Level 2 {@code traceresponse} header.
 */
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_RESPONSE_HEADER = "traceresponse";

    private final Tracer tracer;

    public TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            var context = span.context();
            response.setHeader(TRACE_ID_HEADER, context.traceId());
            String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
            response.setHeader(TRACE_RESPONSE_HEADER,
                    "00-%s-%s-%s".formatted(context.traceId(), context.spanId(), flags));
        }
        chain.doFilter(request, response);
    }
}
