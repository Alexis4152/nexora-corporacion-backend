package com.nexora.corp.shared.storage;

import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.security.SecureTokens;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Archivos fuera del artefacto (volumen persistente). El nombre en disco es aleatorio (nunca el del usuario: sin
 * path traversal), el tipo se determina por la FIRMA del contenido y debe coincidir con la extensión declarada.
 * No se aceptan HTML, SVG, ejecutables ni macros (xlsm/docm).
 */
@Service
public class StorageService {

    public record Stored(String key, String mime, long size, String sha256) {
    }

    private static final Map<String, String> EXT = Map.of("pdf", "application/pdf", "png", "image/png", "jpg", "image/jpeg",
            "jpeg", "image/jpeg", "webp", "image/webp", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "xml", "text/xml");

    private final Path root;
    private final long maxBytes;

    public StorageService(AppProperties props) {
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        this.maxBytes = props.storage().maxBytes();
    }

    public Stored save(byte[] content, String originalName) {
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo está vacío");
        }
        if (content.length > maxBytes) {
            throw new BusinessException("El archivo supera el máximo de " + (maxBytes / 1_048_576) + " MB");
        }
        String ext = extension(originalName);
        String declared = EXT.get(ext);
        if (declared == null) {
            throw new BusinessException("Tipo de archivo no permitido. Usa PDF, PNG, JPG, WEBP, XLSX, DOCX o XML (CFDI)");
        }
        String detected = detect(content, ext);
        if (!declared.equals(detected)) {
            throw new BusinessException("El contenido del archivo no corresponde a su extensión");
        }
        String key = LocalDate.now().getYear() + "/" + String.format("%02d", LocalDate.now().getMonthValue()) + "/"
                + SecureTokens.randomToken() + ".bin";
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            return new Stored(key, detected, content.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo guardar el archivo", e);
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new BusinessException("El archivo ya no está disponible en el almacenamiento");
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new BusinessException("Ruta de archivo no válida");
        }
        return p;
    }

    static String extension(String name) {
        if (name == null || !name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** Tipo por firma (magic bytes). */
    static String detect(byte[] b, String ext) {
        if (starts(b, "%PDF")) {
            return "application/pdf";
        }
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 12 && starts(b, "RIFF") && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            String head = new String(b, 0, Math.min(b.length, 4000), StandardCharsets.ISO_8859_1);
            if (head.contains("vbaProject")) {
                return "macro";
            }
            return "xlsx".equals(ext) ? EXT.get("xlsx") : "docx".equals(ext) ? EXT.get("docx") : "zip";
        }
        String text = new String(b, 0, Math.min(b.length, 200), StandardCharsets.UTF_8).replace("﻿", "").trim();
        if (text.startsWith("<?xml") && !text.toLowerCase(Locale.ROOT).contains("<!doctype")) {
            return "text/xml";
        }
        return "unknown";
    }

    private static boolean starts(byte[] b, String s) {
        if (b.length < s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}

