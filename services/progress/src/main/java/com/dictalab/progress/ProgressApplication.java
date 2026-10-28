package com.dictalab.progress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
public class ProgressApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProgressApplication.class, args);
    }

    @Bean
    @Profile("aurora")
    public FlywayMigrationStrategy auroraSchemaValidation() {
        return flyway -> {
            flyway.validate();
            if (flyway.info().pending().length != 0) {
                throw new IllegalStateException("Run pending Aurora migrations with the owner identity before starting Progress");
            }
        };
    }
}
