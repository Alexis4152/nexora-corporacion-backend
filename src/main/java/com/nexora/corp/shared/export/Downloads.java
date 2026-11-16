package com.nexora.corp.shared.export;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Respuesta de descarga segura: adjunto, sin sniffing de tipo ni caché. */
public final class Downloads {

    private Downloads() {
    }

    public static ResponseEntity<byte[]> of(TableExporter.ExportFile f) {
        return of(f.filename(), f.contentType(), f.content());
    }

    public static ResponseEntity<byte[]> of(String filename, String contentType, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }
}

