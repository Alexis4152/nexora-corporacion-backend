package com.nexora.corp.customers;

import com.nexora.corp.customers.CustomerDtos.ContactRequest;
import com.nexora.corp.customers.CustomerDtos.ContactResponse;
import com.nexora.corp.customers.CustomerDtos.CustomerDetail;
import com.nexora.corp.customers.CustomerDtos.CustomerRequest;
import com.nexora.corp.customers.CustomerDtos.CustomerRow;
import com.nexora.corp.customers.CustomerDtos.NoteRequest;
import com.nexora.corp.customers.CustomerDtos.NoteResponse;
import com.nexora.corp.customers.CustomerDtos.TagRequest;
import com.nexora.corp.customers.CustomerDtos.TagResponse;
import com.nexora.corp.customers.CustomerDtos.TagsRequest;
import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<PageResponse<CustomerRow>> list(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) String relationship,
                                                       @RequestParam(required = false) Long productId,
                                                       @RequestParam(required = false) Long tagId,
                                                       @RequestParam(required = false) Long ownerId,
                                                       @RequestParam(required = false) Boolean overdue,
                                                       @RequestParam(defaultValue = "false") boolean archived,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(new CustomerService.Filters(q, relationship, productId, tagId, ownerId, overdue, archived),
                PageQuery.of(page, size, sort)));
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<CustomerDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAuthority('customers.create')")
    public ApiResponse<CustomerDetail> create(@Valid @RequestBody CustomerRequest request) {
        return ApiResponse.ok(service.create(request), "Cliente registrado");
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<CustomerDetail> update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        return ApiResponse.ok(service.update(id, request), "Cliente actualizado");
    }

    @PostMapping("/customers/{id}/archive")
    @PreAuthorize("hasAuthority('customers.archive')")
    public ApiResponse<CustomerDetail> archive(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(service.setArchived(id, Boolean.TRUE.equals(body.get("archived"))));
    }

    @GetMapping("/customers/{id}/contacts")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<List<ContactResponse>> contacts(@PathVariable Long id) {
        return ApiResponse.ok(service.contacts(id));
    }

    @PostMapping("/customers/{id}/contacts")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<ContactResponse> addContact(@PathVariable Long id, @Valid @RequestBody ContactRequest request) {
        return ApiResponse.ok(service.saveContact(id, null, request), "Contacto agregado");
    }

    @PutMapping("/customers/{id}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<ContactResponse> updateContact(@PathVariable Long id, @PathVariable Long contactId,
                                                      @Valid @RequestBody ContactRequest request) {
        return ApiResponse.ok(service.saveContact(id, contactId, request), "Contacto actualizado");
    }

    @DeleteMapping("/customers/{id}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<Void> deleteContact(@PathVariable Long id, @PathVariable Long contactId) {
        service.deleteContact(id, contactId);
        return ApiResponse.ok(null, "Contacto eliminado");
    }

    @GetMapping("/customers/{id}/notes")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<List<NoteResponse>> notes(@PathVariable Long id) {
        return ApiResponse.ok(service.notes(id));
    }

    @PostMapping("/customers/{id}/notes")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<NoteResponse> addNote(@PathVariable Long id, @Valid @RequestBody NoteRequest request) {
        return ApiResponse.ok(service.addNote(id, request), "Nota agregada");
    }

    @PostMapping("/customers/{id}/notes/{noteId}/pin")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<NoteResponse> pin(@PathVariable Long id, @PathVariable Long noteId) {
        return ApiResponse.ok(service.togglePin(id, noteId));
    }

    @DeleteMapping("/customers/{id}/notes/{noteId}")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<Void> deleteNote(@PathVariable Long id, @PathVariable Long noteId) {
        service.deleteNote(id, noteId);
        return ApiResponse.ok(null, "Nota eliminada");
    }

    @PutMapping("/customers/{id}/tags")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<CustomerDetail> setTags(@PathVariable Long id, @Valid @RequestBody TagsRequest request) {
        return ApiResponse.ok(service.setTags(id, request.tagIds()));
    }

    @GetMapping("/tags")
    public ApiResponse<List<TagResponse>> tags() {
        return ApiResponse.ok(service.allTags());
    }

    @PostMapping("/tags")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<TagResponse> createTag(@Valid @RequestBody TagRequest request) {
        return ApiResponse.ok(service.createTag(request), "Etiqueta creada");
    }

    @DeleteMapping("/tags/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Void> deleteTag(@PathVariable Long id) {
        service.deleteTag(id);
        return ApiResponse.ok(null, "Etiqueta eliminada");
    }
}

