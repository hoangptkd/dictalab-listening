package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in offline research only; never changes production validation or uses real learner data. */
@EnabledIfSystemProperty(named="conversation.distillation", matches="true")
class ConversationDistillationTest {
    @Test void exportProductionContract() throws Exception {
        var json=new ObjectMapper();
        var service=mock(ConversationService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"json",json);
        var rowClass=Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor=rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var catalogue=new ArrayList<Map<String,Object>>();
        var levels=new LinkedHashMap<String,Object>();
        for (String level : List.of("A1","A2","B1")) {
            levels.put(level,Map.of("instruction",ConversationGenerationPrompt.instruction(level),
                    "exampleInput",ConversationGenerationPrompt.exampleInput(level),
                    "exampleOutput",json.readTree(ConversationGenerationPrompt.example(level))));
            for (var scene : ConversationContent.SCENES) for (var branch : ConversationContent.branches(scene.id())) {
                var starter=ConversationContent.starter(json,branch.opening(),level);
                var state=json.createObjectNode().put("curatedStarter",true).put("practiceFocus","");
                state.putObject("sessionBlueprint").put("contentVersion",1).put("branchId",branch.id()).put("journey",branch.journey());
                state.putArray("recentPractice");
                var turn=state.putArray("turns").addObject().put("done",true);
                turn.set("prompt",starter);
                var grades=turn.putArray("attempts").addObject().putObject("evaluation").putArray("facts");
                starter.path("facts").forEach(f -> grades.addObject().put("factId",f.path("id").asText()).put("status","CAPTURED"));
                Map<String,Object> input=ReflectionTestUtils.invokeMethod(service,"promptContext",
                        constructor.newInstance("synthetic",0L,scene.id(),level,state,0L));
                assertEquals(2,input.get("turnNumber"));
                catalogue.add(Map.of("scenarioId",scene.id(),"level",level,"branchId",branch.id(),"input",input));
            }
        }
        var path=Path.of("data/conversation-distillation/production-contract.json");
        Files.createDirectories(path.getParent());
        String document=json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "promptVersion",ConversationGenerationPrompt.VERSION,"instruction",ConversationGenerationPrompt.INSTRUCTION,
                "schema",json.readTree(ConversationLocalClient.SCHEMA),"levels",levels,"catalogue",catalogue));
        if(Files.exists(path)) assertEquals(json.readTree(Files.readString(path,StandardCharsets.UTF_8)),json.readTree(document),
                "Production contract changed; use a new research directory, do not mix datasets");
        else Files.writeString(path,document,StandardCharsets.UTF_8);
        System.out.println("Exported production contract: "+catalogue.size()+" contexts, "+path);
    }

    @Test @EnabledIfSystemProperty(named="conversation.benchmark50", matches="true")
    void collectFiftyFirstOutputs() throws Exception {
        var json=new ObjectMapper();
        var root=Path.of("data/conversation-distillation");
        var inputs=Files.readAllLines(root.resolve("benchmark-inputs.jsonl"),StandardCharsets.UTF_8);
        assertEquals(50,inputs.size());
        var report=root.resolve("baseline-qwen35-9b.jsonl");
        var completed=new HashMap<String,String>();
        if(Files.exists(report)) for(var line:Files.readAllLines(report,StandardCharsets.UTF_8)) {
            var row=json.readTree(line); completed.put(row.path("id").asText(),row.path("inputHash").asText());
        }
        var config=new ConversationGenerationProperties("ollama",null,"qwen3.5:9b",600);
        var client=new ConversationLocalClient(json,config,new SimpleMeterRegistry(),ObservationRegistry.NOOP);
        for (var line:inputs) {
            var item=json.readTree(line);
            String id=item.path("id").asText(), hash=item.path("inputHash").asText();
            if(completed.containsKey(id)) {assertEquals(hash,completed.get(id),"Inputs changed; do not mix runs"); continue;}
            var row=new LinkedHashMap<String,Object>();
            row.put("id",id); row.put("inputHash",hash); row.put("model",config.localModel());
            row.put("promptVersion",ConversationGenerationPrompt.VERSION); row.put("input",item.path("input"));
            row.put("startedAt",Instant.now().toString());
            long started=System.nanoTime();
            System.out.println("START benchmark "+id);
            try {row.put("output",client.completeJson(item.path("input").path("difficulty").asText(),
                    json.writeValueAsString(item.path("input")),Duration.ofSeconds(600)));}
            catch(RuntimeException failure) {row.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());}
            row.put("seconds",Math.round((System.nanoTime()-started)/1e7)/100.0);
            Files.writeString(report,json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println("DONE benchmark "+id+" output="+row.containsKey("output")+" seconds="+row.get("seconds"));
        }
        assertEquals(50,Files.readAllLines(report,StandardCharsets.UTF_8).size(),"Collection count, not content quality");
    }
}
