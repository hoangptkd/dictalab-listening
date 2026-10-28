package com.dictalab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DictaLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DictaLabApplication.class, args);
    }

    @Bean
    @Profile("aurora")
    public FlywayMigrationStrategy auroraSchemaValidation() {
        return flyway -> {
            flyway.validate();
            if (flyway.info().pending().length != 0) {
                throw new IllegalStateException("Run pending Aurora migrations with the owner identity before starting Core");
            }
        };
    }
}
