package com.dictalab.scenario;

import com.dictalab.security.DictaLabPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v2/scenarios")
public class ScenarioController {
    private final ScenarioProgramRegistry registry;
    private final ScenarioGoalService goals;
    private final ScenarioProperties properties;
    private final ScenarioRelease release;
    public ScenarioController(ScenarioProgramRegistry registry, ScenarioGoalService goals, ScenarioProperties properties, ScenarioRelease release) {
        this.registry = registry; this.goals = goals; this.properties = properties; this.release=release;
    }

    public record GoalProgram(String code, int version, String status, String displayNameVi,
                              List<ScenarioProgram.TargetScale> targetScales) {}
    public record Catalog(String contractVersion, boolean enabled, boolean generationAvailable,
                          List<String> supportedPracticeModes, List<GoalProgram> goalPrograms,
                          List<GoalProgram> generationPrograms, List<String> difficultyProfiles) {}

    @GetMapping("/catalog")
    public ResponseEntity<Catalog> catalog(@AuthenticationPrincipal DictaLabPrincipal owner) {
        boolean available=release.available();
        return noStore(new Catalog("scenario-public.v1", properties.enabled(), available, available ? List.of("guided-listening") : List.of(),
                properties.enabled() ? registry.definitions().stream().filter(p -> p.status() != ScenarioProgram.Status.RETIRED)
                        .map(p -> new GoalProgram(p.code(), p.version(), p.status().name(), p.displayNameVi(), p.targetScales())).toList() : List.of(),
                available ? List.of(new GoalProgram(release.program().code(),release.program().version(),release.program().status().name(),release.program().displayNameVi(),release.program().targetScales())) : List.of(),
                available ? release.program().difficultyProfiles().stream().map(ScenarioProgram.DifficultyProfile::id).toList() : List.of()));
    }

    // Kept readable when writes are disabled, so disabling rollout does not hide saved goals.
    @GetMapping("/learning-goals")
    public ResponseEntity<List<ScenarioGoalService.Goal>> goals(@AuthenticationPrincipal DictaLabPrincipal owner) {
        return noStore(goals.list(owner.id()));
    }

    @PutMapping(value = "/learning-goal", consumes = "application/json")
    public ResponseEntity<ScenarioGoalService.Goal> save(@AuthenticationPrincipal DictaLabPrincipal owner,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) throws IOException {
        var bytes = http.getInputStream().readNBytes(ScenarioJson.MAX_REQUEST_BYTES + 1);
        if (bytes.length > ScenarioJson.MAX_REQUEST_BYTES)
            throw new ScenarioFailure(413, "REQUEST_TOO_LARGE", "Yêu cầu vượt giới hạn 32 KiB.");
        ScenarioGoalRequest request;
        try { request = ScenarioJson.read(bytes, ScenarioGoalRequest.class); }
        catch (RuntimeException invalid) { throw new ScenarioFailure(400, "INVALID_REQUEST", "Mục tiêu hoặc định dạng yêu cầu không hợp lệ."); }
        return noStore(goals.save(owner.id(), key, request));
    }

    static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
