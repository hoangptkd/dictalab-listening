package com.dictalab.web;

import com.dictalab.service.MediaStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {
    private final MediaStorageService media;

    public MediaController(MediaStorageService media) { this.media = media; }

    // Spring Security authenticates the JWT cookie before a private S3 link is issued.
    @GetMapping("/media/s3/{filename}")
    public ResponseEntity<Void> audio(@PathVariable String filename) {
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(media.signedAudioUrl(filename))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }
}
