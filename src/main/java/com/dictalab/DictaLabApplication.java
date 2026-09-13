package com.dictalab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DictaLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DictaLabApplication.class, args);
    }
}
