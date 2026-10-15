package com.dictalab.web;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** Keep the existing deployment probe on the app port; detailed metrics use the loopback management port. */
@RestController
public class HealthController {
    private final HealthEndpoint health;
    public HealthController(HealthEndpoint health){this.health=health;}
    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String,String>> health(){
        String status=health.health().getStatus().getCode();
        return ResponseEntity.status(status.equals("UP")?200:503).body(Map.of("status",status));
    }
}
