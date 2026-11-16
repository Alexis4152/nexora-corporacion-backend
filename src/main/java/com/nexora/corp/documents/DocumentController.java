package com.nexora.corp.documents;

import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.export.Downloads;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private static final Set<String> CATEGORIES = Set.of("CONTRACT", "RECEIPT", "QUOTE", "EVIDENCE", "INVOICE", "COMMERCIAL", "OTHER");

    private final DocumentService service;

    @GetMapping
    @PreAuthorize("hasAuthority('documents.read')")
    public ApiResponse<List<DocumentService.DocumentRow>> list(@RequestParam String ownerType, @RequestParam Long ownerId) {
        return ApiResponse.ok(service.list(ownerType, ownerId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('documents.upload')")
    public ApiResponse<DocumentService.DocumentRow> upload(@RequestParam String ownerType, @RequestParam Long ownerId,
                                                           @RequestParam(defaultValue = "OTHER") String category,
                                                           @RequestParam(required = false) String title,
                                                           @RequestPart("file") MultipartFile file) throws IOException {
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException("Categoría no válida");
        }
        if (title != null && title.length() > 200) {
            throw new BusinessException("El título es demasiado largo");
        }
        return ApiResponse.ok(service.upload(ownerType, ownerId, category, title, file.getOriginalFilename(), file.getBytes()), "Documento adjuntado");
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('documents.read')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        DocumentService.Download d = service.download(id);
        return Downloads.of(d.filename(), d.mime(), d.content());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('documents.delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, "Documento retirado");
    }
}

