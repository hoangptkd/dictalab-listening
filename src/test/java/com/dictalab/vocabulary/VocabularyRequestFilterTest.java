package com.dictalab.vocabulary;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class VocabularyRequestFilterTest {
    @Test void capsUnknownLengthBodiesBeforeJsonAndTracksBoundedMetricTags() throws Exception {
        var metrics=new SimpleMeterRegistry(); var filter=new VocabularyRequestFilter(metrics);
        var oversized=new MockHttpServletRequest("POST","/api/v1/vocabulary/imports/preview") {
            @Override public long getContentLengthLong() { return -1; }
        };
        oversized.setContent(new byte[VocabularyRequestFilter.MAX_BODY_BYTES+1]);
        var response=new MockHttpServletResponse();
        filter.doFilter(oversized,response,(req,res)->{throw new AssertionError("Oversized body reached controller");});
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(metrics.get("dictalab.vocabulary.requests").tags("method","POST","status","413").timer().count()).isEqualTo(1);
        byte[] body="{\"term\":\"tiếng Việt\"}".getBytes(StandardCharsets.UTF_8);
        var request=new MockHttpServletRequest("POST","/api/v1/vocabulary/decks"); request.setContent(body);
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertThat(req.getInputStream().readAllBytes()).isEqualTo(body));
        filter.doFilter(new MockHttpServletRequest("CUSTOM","/api/v1/vocabulary/decks"),new MockHttpServletResponse(),(req,res)->{});
        assertThat(metrics.get("dictalab.vocabulary.requests").tags("method","OTHER","status","200").timer().count()).isEqualTo(1);
    }
}
