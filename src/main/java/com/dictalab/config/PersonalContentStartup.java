package com.dictalab.config;

import com.dictalab.service.PersonalContentImportService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PersonalContentStartup implements ApplicationRunner {

    private final PersonalContentImportService importService;

    public PersonalContentStartup(PersonalContentImportService importService) {
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        importService.importConfiguredManifest();
    }
}
