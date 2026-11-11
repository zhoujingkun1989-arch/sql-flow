package io.sqlflow.server.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Standardized binary & file attachment response builder exposing {@code Content-Disposition}.
 */
public final class DownloadHelper {

    private DownloadHelper() {
    }

    public static ResponseEntity<byte[]> attachment(byte[] bytes, String fileName, MediaType mediaType) {
        String safeName = (fileName == null || fileName.isBlank()) ? "download.bin" : fileName;
        String encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(mediaType != null ? mediaType : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment;filename=" + safeName + ";filename*=UTF-8''" + encoded)
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(bytes != null ? bytes : new byte[0]);
    }

    public static ResponseEntity<byte[]> bytesAttachment(byte[] bytes, String fileName, MediaType mediaType) {
        return attachment(bytes, fileName, mediaType);
    }

    public static ResponseEntity<byte[]> textAttachment(String content, String fileName, MediaType mediaType) {
        byte[] bytes = content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return attachment(bytes, fileName, mediaType);
    }

    public static ResponseEntity<byte[]> fileAttachment(Path file, String downloadName, MediaType mediaType) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String fname = (downloadName != null && !downloadName.isBlank())
                    ? downloadName
                    : file.getFileName().toString();
            return attachment(bytes, fname, mediaType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream attachment " + file + ": " + e.getMessage(), e);
        }
    }
}
