package com.dictalab.scenario;

import com.dictalab.security.DictaLabPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import static com.dictalab.scenario.ScenarioController.noStore;

@RestController
@RequestMapping("/api/v2/scenarios")
public class ScenarioLearningController {
    private final ScenarioEngine engine;
    private final ScenarioOperations operations;
    public ScenarioLearningController(ScenarioEngine engine,ScenarioOperations operations) { this.engine=engine; this.operations=operations; }

    @GetMapping("/suggestions")
    ResponseEntity<JsonNode> suggestions() { return noStore(engine.suggestions()); }
    @GetMapping("/recommendations")
    ResponseEntity<JsonNode> recommendations(@AuthenticationPrincipal DictaLabPrincipal owner) { return noStore(engine.recommendations(owner.id())); }
    @PostMapping(value="/drafts",consumes="application/json")
    ResponseEntity<JsonNode> draft(@AuthenticationPrincipal DictaLabPrincipal owner,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.createDraft(owner.id(),key,read(http,ScenarioEngine.DraftRequest.class)));
    }
    @GetMapping("/drafts/{id}")
    ResponseEntity<JsonNode> draft(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String id) { return noStore(engine.draft(owner.id(),id)); }
    @PostMapping(value="/sessions",consumes="application/json")
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal DictaLabPrincipal owner,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.createSession(owner.id(),key,read(http,ScenarioEngine.SessionRequest.class)));
    }
    @GetMapping("/sessions/{id}")
    ResponseEntity<JsonNode> session(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String id) { return noStore(engine.session(owner.id(),id)); }
    @GetMapping("/sessions")
    ResponseEntity<JsonNode> history(@AuthenticationPrincipal DictaLabPrincipal owner,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String before,@RequestParam(required=false) String beforeId) {
        return noStore(engine.history(owner.id(),status,limit,before,beforeId));
    }
    @GetMapping("/operations/{id}")
    ResponseEntity<JsonNode> operation(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String id) { return noStore(operations.get(owner.id(),id)); }
    @PostMapping(value="/operations/{id}/cancel",consumes="application/json")
    ResponseEntity<JsonNode> cancel(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.cancel(owner.id(),id,key,read(http,ScenarioEngine.VersionRequest.class)));
    }
    @PostMapping(value="/sessions/{session}/activities/{activity}/answers",consumes="application/json")
    ResponseEntity<JsonNode> answers(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String session,@PathVariable String activity,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.answer(owner.id(),session,activity,key,read(http,ScenarioEngine.AnswersRequest.class)));
    }
    @PostMapping(value="/sessions/{session}/activities/{activity}/help",consumes="application/json")
    ResponseEntity<JsonNode> help(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String session,@PathVariable String activity,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.help(owner.id(),session,activity,key,read(http,ScenarioEngine.HelpRequest.class)));
    }
    @PostMapping(value="/sessions/{session}/activities/{activity}/play",consumes="application/json")
    ResponseEntity<JsonNode> play(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String session,@PathVariable String activity,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.play(owner.id(),session,activity,key,read(http,ScenarioEngine.PlayRequest.class)));
    }
    @PostMapping(value="/sessions/{session}/activities/{activity}/complete",consumes="application/json")
    ResponseEntity<JsonNode> complete(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String session,@PathVariable String activity,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.completeActivity(owner.id(),session,activity,key,read(http,ScenarioEngine.VersionRequest.class)));
    }
    @PostMapping(value="/sessions/{session}/remixes",consumes="application/json")
    ResponseEntity<JsonNode> remix(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String session,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest http) throws IOException {
        return noStore(engine.remix(owner.id(),session,key,read(http,ScenarioEngine.RemixRequest.class)));
    }
    @GetMapping("/media/{id}")
    void media(@AuthenticationPrincipal DictaLabPrincipal owner,@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        engine.serveMedia(owner.id(),id,request,response);
    }
    static <T> T read(HttpServletRequest request,Class<T> type) throws IOException {
        byte[] bytes=request.getInputStream().readNBytes(ScenarioJson.MAX_REQUEST_BYTES+1);
        if (bytes.length>ScenarioJson.MAX_REQUEST_BYTES) throw new ScenarioFailure(413,"REQUEST_TOO_LARGE","Yêu cầu vượt giới hạn 32 KiB.");
        try { return ScenarioJson.read(bytes,type); }
        catch (RuntimeException invalid) { throw ScenarioData.invalid(); }
    }
}
