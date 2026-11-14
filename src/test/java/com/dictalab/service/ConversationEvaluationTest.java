package com.dictalab.service;

import com.dictalab.config.ConversationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationEvaluationTest {
    final ObjectMapper json=new ObjectMapper();
    final GeminiClient ai=mock(GeminiClient.class);
    final ConversationService service=mock(ConversationService.class,CALLS_REAL_METHODS);
    void setup() {
        ReflectionTestUtils.setField(service,"json",json);ReflectionTestUtils.setField(service,"ai",ai);
        ReflectionTestUtils.setField(service,"budget",mock(AiUsageBudget.class));
        ReflectionTestUtils.setField(service,"config",new ConversationProperties(true,"gemini-test",null,null,null,null));
        when(ai.configured()).thenReturn(true);when(ai.evaluationGemini()).thenReturn(true);
    }
    @Test void usesRealNormalizerValidatorReviewAndExactRequestCaptureWithinGatewayDeadline() {
        setup();var fixture=service.evaluationFixtures().path(0);var events=new ArrayList<String>();
        when(ai.completeJson(anyString(),anyString(),eq("gemini-test"),any(Duration.class))).thenAnswer(call->{
            assertThat(((Duration)call.getArgument(3)).toSeconds()).isBetween(1L,60L);
            return ((String)call.getArgument(0)).startsWith("Audit") ? json.readTree("{\"valid\":true,\"reason\":\"\"}")
                    : json.readTree("""
                        {"slots":[{"kind":"cup_type","askVi":"Loại cốc"},{"kind":"collection_place","askVi":"Nơi nhận đồ"}],
                        "utteranceEn":"Please put my coffee in a takeaway cup. I will collect it at the side counter. My bus leaves soon.",
                        "facts":[{"textVi":"Cốc mang đi","evidenceEn":"a takeaway cup","taskType":"direct_detail"},
                        {"textVi":"Quầy bên cạnh","evidenceEn":"the side counter","taskType":"direct_detail"}],
                        "questionVi":"Người nói muốn dùng loại cốc nào và nhận đồ ở đâu?",
                        "translationVi":"Xin cho cà phê của tôi vào cốc mang đi. Tôi sẽ nhận ở quầy bên cạnh. Xe buýt của tôi sắp đi.",
                        "taskVi":"Nghe yêu cầu thay đổi đơn hàng.","skill":"Nắm thông tin cụ thể"}
                        """);
        });
        var result=service.evaluateFixture(14,fixture,"Frozen instruction",false,(step,attempt,value)->{
            events.add(step);if(step.equals("request")) {
                assertThat(value.path("instruction").asText()).isEqualTo("Frozen instruction");
                assertThat(value.path("input").asText()).contains("<turn_brief>");
            }
        });
        assertThat(result.path("facts").size()).isEqualTo(2);
        assertThat(events).containsExactly("request","generated","validation","review");
        verify(ai,times(2)).completeJson(anyString(),anyString(),eq("gemini-test"),any(Duration.class));
    }
    @Test void networkFailureOnRepairPropagatesAndNeverTriggersAnotherRepair() {
        setup();var events=new ArrayList<String>();
        when(ai.completeJson(anyString(),anyString(),anyString(),any(Duration.class)))
                .thenReturn(json.createObjectNode()).thenThrow(new GeminiClient.Failure("TIMEOUT","unknown"));
        assertThatThrownBy(()->service.evaluateFixture(14,service.evaluationFixtures().path(0),"Frozen",true,(step,attempt,value)->events.add(step)))
                .isInstanceOf(GeminiClient.Failure.class);
        assertThat(events).containsExactly("request","generated","rejected","request");
        verify(ai,times(2)).completeJson(anyString(),anyString(),anyString(),any(Duration.class));
    }
}
