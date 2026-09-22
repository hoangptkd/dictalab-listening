package com.dictalab.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class MediaWebConfig implements WebMvcConfigurer {

    private final MediaStorageProperties properties;
    private final PersonalContentProperties personalContent;

    public MediaWebConfig(MediaStorageProperties properties, PersonalContentProperties personalContent) {
        this.properties = properties;
        this.personalContent = personalContent;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = properties.root().toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate());
        if (personalContent.enabled()) {
            String personalLocation = personalContent.root().toAbsolutePath().normalize().toUri().toString();
            registry.addResourceHandler("/private-media/**")
                    .addResourceLocations(personalLocation)
                    .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate());
        }
    }
}
