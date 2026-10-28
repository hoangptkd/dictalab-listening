package com.dictalab.vocabulary;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounds JSON before deserialization, including chunked requests; metric tags never contain user IDs. */
@Component
@Order(100)
public class VocabularyRequestFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES=4*1024*1024+4096;
    private final MeterRegistry metrics;
    public VocabularyRequestFilter(MeterRegistry metrics) { this.metrics=metrics; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/v1/vocabulary/") && !path.startsWith("/api/admin/vocabulary/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        Timer.Sample sample=Timer.start(metrics);
        try {
            if(java.util.Set.of("POST","PATCH","PUT").contains(request.getMethod())) {
                if(request.getContentLengthLong()>MAX_BODY_BYTES) { reject(response); return; }
                byte[] body=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
                if(body.length>MAX_BODY_BYTES) { reject(response); return; }
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override public int getContentLength() { return body.length; }
                    @Override public long getContentLengthLong() { return body.length; }
                    @Override public ServletInputStream getInputStream() {
                        ByteArrayInputStream input=new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override public int read() { return input.read(); }
                            @Override public int read(byte[] bytes,int offset,int length) { return input.read(bytes,offset,length); }
                            @Override public boolean isFinished() { return input.available()==0; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous vocabulary JSON only"); }
                        };
                    }
                    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
                },response);
            } else chain.doFilter(request,response);
        } finally {
            String method=java.util.Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(request.getMethod())?request.getMethod():"OTHER";
            sample.stop(Timer.builder("dictalab.vocabulary.requests").tag("method",method)
                    .tag("status",Integer.toString(response.getStatus())).register(metrics));
        }
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":413,\"message\":\"Yêu cầu từ vựng vượt giới hạn kích thước.\"}");
    }
}
