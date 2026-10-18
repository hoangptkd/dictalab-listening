package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic local smoke test, not a CEFR/semantic benchmark; never calls a paid API. */
@EnabledIfSystemProperty(named="conversation.evaluate", matches="true")
class ConversationLocalEvaluationTest {
    @Test
    @EnabledIfSystemProperty(named="conversation.matrix", matches="true")
    void exerciseIndependentCasesWithProductionContext() throws Exception {
        var json = new ObjectMapper();
        var config = new ConversationGenerationProperties("ollama",System.getProperty("conversation.url"),
                System.getProperty("conversation.model"),Integer.getInteger("conversation.timeout-seconds",180));
        var client = new ConversationLocalClient(json,config,new SimpleMeterRegistry(),ObservationRegistry.NOOP);
        var validator = mock(ConversationService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(validator,"json",json);
        var cases = List.of(
                Map.of("id","cafe-a1-followup","scenario","cafe","level","A1","turn",2),
                Map.of("id","directions-a2-followup","scenario","directions","level","A2","turn",2),
                Map.of("id","hotel-b1-followup","scenario","hotel","level","B1","turn",2),
                Map.of("id","hotel-a2-continuity","scenario","hotel","level","A2","turn",3),
                Map.of("id","hotel-a2-transfer","scenario","hotel","level","A2","turn",4),
                Map.of("id","cafe-a1-repeat-session","scenario","cafe","level","A1","turn",2),
                Map.of("id","cafe-a1-ten-recent-sessions","scenario","cafe","level","A1","turn",2),
                Map.of("id","directions-a2-practice-focus","scenario","directions","level","A2","turn",2),
                Map.of("id","hotel-b1-missed-transfer","scenario","hotel","level","B1","turn",4));
        var rows = new ArrayList<Map<String,Object>>();
        JsonNode firstCafeTask = null;
        var report = Path.of("target/conversation-local-matrix-evaluation.json");
        for (var spec : cases) {
            String id=spec.get("id").toString(), scenario=spec.get("scenario").toString(), level=spec.get("level").toString();
            int turn=(Integer)spec.get("turn");
            var state = fixture(json,scenario,level,turn,id.contains("missed"));
            if (id.contains("practice-focus")) state.put("practiceFocus","Luyện nhận diện phương tiện và điểm xuống bằng dữ kiện mới.");
            if (id.contains("repeat-session")) {
                JsonNode previous=firstCafeTask!=null ? firstCafeTask
                        : ConversationContent.starter(json,ConversationContent.branches(scenario).get(1).opening(),level);
                state.withArray("recentPractice").addObject().put("branchId","synthetic-prior")
                        .putArray("tasks").add(previous.deepCopy());
            }
            if (id.contains("ten-recent")) {
                var branches=ConversationContent.branches(scenario);
                for (int i=0;i<10;i++) {
                    var tasks=state.withArray("recentPractice").addObject().put("branchId",branches.get(i%branches.size()).id()).putArray("tasks");
                    for (int j=0;j<4;j++) tasks.add(ConversationContent.starter(json,branches.get((i+j)%branches.size()).opening(),level));
                }
            }
            Object savedRow = syntheticRow(scenario,level,state);
            Map<String,Object> actualContext=ReflectionTestUtils.invokeMethod(validator,"promptContext",savedRow);
            var input = new LinkedHashMap<String,Object>(actualContext);
            var row = new LinkedHashMap<String,Object>();
            row.put("id",id); row.put("scenario",scenario); row.put("level",level); row.put("turn",turn);
            row.put("input",new LinkedHashMap<>(input)); row.put("fixtureState",state);
            var attempts = new ArrayList<Map<String,Object>>(); row.put("attempts",attempts);
            long started=System.nanoTime(), deadline=started+java.util.concurrent.TimeUnit.SECONDS.toNanos(config.localFlowTimeoutSeconds());
            System.out.println("START independent case="+id);
            try {
                for (int attempt=0;attempt<2;attempt++) {
                    var detail = new LinkedHashMap<String,Object>(); attempts.add(detail);
                    long callStarted=System.nanoTime();
                    JsonNode output;
                    try {
                        output=client.completeJson(level,json.writeValueAsString(input),Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
                    } finally { detail.put("seconds",Math.round((System.nanoTime()-callStarted)/1e7)/100.0); }
                    detail.put("output",output); row.put("output",output);
                    try {
                        ReflectionTestUtils.invokeMethod(validator,"validateNextPrompt",savedRow,output);
                        row.put("validated",true);
                        if (id.equals("cafe-a1-followup")) firstCafeTask=output;
                        break;
                    } catch (IllegalStateException invalid) {
                        detail.put("validationError",invalid.getMessage());
                        System.out.println("REJECT case="+id+" attempt="+(attempt+1)+" reason="+invalid.getMessage());
                        if (attempt==1) throw invalid;
                        input.put("validationError",invalid.getMessage()); input.put("previousInvalidPrompt",output);
                    }
                }
            } catch (RuntimeException failure) {
                row.put("validated",false); row.put("failure",failure.getMessage());
            }
            row.put("seconds",Math.round((System.nanoTime()-started)/1e7)/100.0);
            if (id.contains("repeat-session")) row.put("priorGeneratedTaskAvailable",firstCafeTask!=null);
            rows.add(row);
            Files.createDirectories(report.getParent());
            Files.writeString(report,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "promptVersion",ConversationGenerationPrompt.VERSION,"model",config.localModel(),
                    "localTimeoutSeconds",config.localTimeoutSeconds(),"createdAt",Instant.now().toString(),
                    "independentSyntheticFixtures",true,"cloudSemanticReviewIncluded",false,"cases",rows)),java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("DONE case="+id+" valid="+row.get("validated")+" seconds="+row.get("seconds"));
        }
        assertEquals(cases.size(),rows.stream().filter(r -> Boolean.TRUE.equals(r.get("validated"))).count(),"Inspect "+report);
    }

    private Object syntheticRow(String scenario,String level,ObjectNode state) throws Exception {
        var rowClass=Arrays.stream(ConversationService.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor=rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        return constructor.newInstance("synthetic",0L,scenario,level,state,0L);
    }

    private ObjectNode fixture(ObjectMapper json,String scenario,String level,int nextTurn,boolean missed) {
        var branch=ConversationContent.branches(scenario).get(0);
        var state=json.createObjectNode().put("curatedStarter",true).put("practiceFocus","");
        state.putObject("sessionBlueprint").put("contentVersion",1).put("branchId",branch.id()).put("journey",branch.journey());
        state.putArray("recentPractice");
        var prompts=new ArrayList<JsonNode>();
        prompts.add(ConversationContent.starter(json,branch.opening(),level));
        if (nextTurn>=3) prompts.add(json.readTree("""
                {"taskVi":"Nghe giờ ăn sáng và trả chìa khóa.","questionVi":"Bữa sáng bắt đầu mấy giờ và cần trả chìa khóa trước mấy giờ?",
                "skill":"Nắm thông tin cụ thể","utteranceEn":"Breakfast in the garden starts at seven in the morning. Please return your room key at reception before eleven when you leave. Our staff can help you carry your bags.",
                "translationVi":"Bữa sáng ngoài vườn bắt đầu lúc bảy giờ sáng. Xin trả chìa khóa tại lễ tân trước mười một giờ khi rời đi. Nhân viên có thể giúp mang hành lý.",
                "facts":[{"id":"f1","textVi":"Bảy giờ sáng","evidenceEn":"seven in the morning"},{"id":"f2","textVi":"Trước mười một giờ","evidenceEn":"before eleven"}]}
                """));
        if (nextTurn>=4) prompts.add(json.readTree("""
                {"taskVi":"Nghe thông tin thẻ và hỗ trợ trong phòng.","questionVi":"Thẻ chào mừng có thông tin gì và cần gọi bộ phận nào để xin khăn sạch?",
                "skill":"Nắm thông tin cụ thể","utteranceEn":"Your welcome card has the internet password, so please keep it in your room. If you need clean towels, call housekeeping using the telephone beside your bed. The staff can bring them upstairs.",
                "translationVi":"Thẻ chào mừng có mật khẩu mạng, hãy giữ trong phòng. Nếu cần khăn sạch, hãy gọi bộ phận buồng phòng bằng điện thoại cạnh giường. Nhân viên có thể mang khăn lên.",
                "facts":[{"id":"f1","textVi":"Mật khẩu mạng","evidenceEn":"the internet password"},{"id":"f2","textVi":"Bộ phận buồng phòng","evidenceEn":"housekeeping"}]}
                """));
        var turns=state.putArray("turns");
        for (var prompt : prompts) {
            var turn=turns.addObject().put("done",true); turn.set("prompt",prompt);
            var grade=turn.putArray("attempts").addObject().putObject("evaluation");
            var facts=grade.putArray("facts");
            prompt.path("facts").forEach(f -> facts.addObject().put("factId",f.path("id").asText()).put("status",missed?"MISSED":"CAPTURED"));
        }
        return state;
    }

    @Test void exerciseLevelsContinuityAndTransferWithRealLocalModel() throws Exception {
        var json = new ObjectMapper();
        var config = new ConversationGenerationProperties("ollama",System.getProperty("conversation.url"),
                System.getProperty("conversation.model"),Integer.getInteger("conversation.timeout-seconds",180));
        var client = new ConversationLocalClient(json,config,new SimpleMeterRegistry(),ObservationRegistry.NOOP);
        var validator = mock(ConversationService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(validator,"json",json);
        var rowClass = Arrays.stream(ConversationService.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var rowConstructor = rowClass.getDeclaredConstructors()[0]; rowConstructor.setAccessible(true);
        var rows = new ArrayList<Map<String,Object>>();
        for (String level : List.of("A1","A2","B1")) {
            var history = new ArrayList<Map<String,Object>>();
            var opening = ConversationContent.starter(json,"hotel",level);
            history.add(Map.of("utteranceEn",opening.path("utteranceEn"),"skill",opening.path("skill"),
                    "facts",opening.path("facts"),"evaluation",Map.of()));
            int minimum = level.equals("A1") ? 8 : level.equals("A2") ? 18 : 30;
            int maximum = level.equals("A1") ? 35 : level.equals("A2") ? 55 : 90;
            for (int turn=2;turn<=4;turn++) {
                var input = new LinkedHashMap<String,Object>(Map.of("scenario","Khách sạn","difficulty",level,"turnNumber",turn,
                        "levelProfile",Map.of("minWords",minimum,"maxWords",maximum),"listeningSkills",ConversationContent.SKILLS,
                        "scenarioJourney","1 check in; 2 hotel services; 3 practical arrangements; 4 new hotel encounter",
                        "history",List.copyOf(history),"practiceFocus",""));
                var row = new LinkedHashMap<String,Object>();
                row.put("level",level); row.put("turn",turn); row.put("input",input);
                long started = System.nanoTime();
                long deadline = started + java.util.concurrent.TimeUnit.SECONDS.toNanos(config.localFlowTimeoutSeconds());
                var attempts = new ArrayList<Map<String,Object>>();
                try {
                    for (int attempt=0;attempt<2;attempt++) {
                        // Like production: repair rejected task content once, never retry a transport failure.
                        var output = client.completeJson(level,json.writeValueAsString(input),
                                Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
                        attempts.add(Map.of("output",output)); row.put("output",output);
                        try {
                            var state=json.createObjectNode().put("curatedStarter",true);
                            var turns=state.putArray("turns");
                            for (var previous : history) turns.addObject().set("prompt",json.valueToTree(previous));
                            Object savedRow=rowConstructor.newInstance("synthetic",0L,"hotel",level,state,0L);
                            ReflectionTestUtils.invokeMethod(validator,"validateNextPrompt",savedRow,output);
                            int words = output.path("utteranceEn").asText().split("\\s+").length;
                            if (words<minimum || words>maximum || (level.equals("A1") && output.path("facts").size()!=2)
                                    || !ConversationContent.SKILLS.containsValue(output.path("skill").asText()))
                                throw new IllegalStateException("Level bounds or skill mismatch: require "+minimum+"-"+maximum+" English words");
                            row.put("validated",true);
                            history.add(Map.of("utteranceEn",output.path("utteranceEn"),"skill",output.path("skill"),
                                    "facts",output.path("facts"),"evaluation",Map.of()));
                            break;
                        } catch (IllegalStateException invalid) {
                            if (attempt==1) throw invalid;
                            input.put("validationError",invalid.getMessage()); input.put("previousInvalidPrompt",output);
                        }
                    }
                } catch (RuntimeException failure) {
                    row.put("validated",false); row.put("failure",failure.getMessage());
                }
                row.put("seconds",Math.round((System.nanoTime()-started)/1e7)/100.0);
                row.put("attempts",attempts);
                rows.add(row);
                System.out.println(level+" turn="+turn+" valid="+row.get("validated")+" seconds="+row.get("seconds"));
                // A real learner cannot advance after a failed task; never simulate an impossible history.
                if (!Boolean.TRUE.equals(row.get("validated"))) break;
            }
        }
        var report = Path.of("target/conversation-local-evaluation.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("promptVersion",ConversationGenerationPrompt.VERSION,
                "model",config.localModel(),"createdAt",Instant.now().toString(),"cases",rows)),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(9,rows.stream().filter(r -> Boolean.TRUE.equals(r.get("validated"))).count(),"Inspect target/conversation-local-evaluation.json");
    }
}
