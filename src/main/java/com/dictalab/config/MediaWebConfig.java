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
    private final com.dictalab.service.MediaStorageService media;
    private final boolean allAudioS3;

    public MediaWebConfig(MediaStorageProperties properties, PersonalContentProperties personalContent,
                          com.dictalab.service.MediaStorageService media,
                          @org.springframework.beans.factory.annotation.Value("${dictalab.media.s3.all-audio:false}") boolean allAudioS3) {
        this.properties = properties;
        this.personalContent = personalContent;
        this.media = media;
        this.allAudioS3 = allAudioS3;
        if (allAudioS3 && !properties.s3().enabled()) throw new IllegalStateException("All audio requires S3");
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response, Object handler) throws java.io.IOException {
                String path = org.springframework.web.util.UriUtils.decode(
                        request.getRequestURI().substring(request.getContextPath().length()), java.nio.charset.StandardCharsets.UTF_8);
                if (!java.util.Set.of("GET", "HEAD").contains(request.getMethod())
                        || !path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp3|m4a|wav|ogg|aac|flac)$")
                        || path.startsWith("/media/s3/")) return true;
                var location = media.legacyAudioLocation(path);
                if (location.isPresent()) {
                    if (media.proxyAudioEnabled()) {
                        media.serveLegacyAudio(path, request, response);
                        return false;
                    }
                    response.setStatus(307);
                    response.setHeader("Location", location.get().toString());
                    response.setHeader("Cache-Control", "no-store");
                    response.setHeader("Referrer-Policy", "no-referrer");
                    return false;
                }
                if (allAudioS3) {
                    response.setStatus(503);
                    response.setHeader("Cache-Control", "no-store");
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/audio/**", "/media/**", "/private-media/**");
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
