package com.dictalab.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class PublicInfoController {
    private final String supportEmail;
    public PublicInfoController(@Value("${dictalab.support-email:}") String email) {supportEmail=email;}
    @GetMapping("/api/public/info")
    public Map<String,String> info() {return Map.of("supportEmail",supportEmail);}
}
