package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic local smoke test, not a CEFR/semantic benchmark; never calls a paid API. */
@EnabledIfSystemProperty(named="conversation.evaluate", matches="true")
class ConversationLocalEvaluationTest {
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
