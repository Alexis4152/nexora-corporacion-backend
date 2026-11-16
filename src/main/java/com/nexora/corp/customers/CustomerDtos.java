package com.nexora.corp.customers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CustomerRequest(
            @NotBlank @Pattern(regexp = "^(FISICA|MORAL)$", message = "Persona física o moral") String personType,
            @NotBlank(message = "Escribe el nombre comercial") @Size(max = 150) String tradeName,
            @Size(max = 250) String legalName,
            @NotBlank(message = "Escribe el contacto principal") @Size(max = 150) String contactName,
            @Pattern(regexp = "^$|^[A-Za-zÑñ&]{3,4}[0-9]{6}[A-Za-z0-9]{3}$", message = "RFC no válido") String rfc,
            @Pattern(regexp = "^$|^\\d{3}$", message = "Clave de régimen fiscal SAT de 3 dígitos") String taxRegime,
            @Pattern(regexp = "^$|^\\d{5}$", message = "Código postal de 5 dígitos") String taxZip,
            @NotBlank(message = "Escribe el correo") @Email(message = "Correo no válido") @Size(max = 150) String email,
            @Pattern(regexp = "^$|^\\d{10}$", message = "10 dígitos") String phone,
            @Pattern(regexp = "^$|^\\d{10,13}$", message = "10 a 13 dígitos") String whatsapp,
            @Size(max = 300) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "^[A-Z]{2}$", message = "País ISO de 2 letras") String country,
            @Size(max = 80) String industry,
            Long ownerUserId,
            @Size(max = 2000) String notes,
            LocalDate customerSince,
            Set<Long> tagIds) {
    }

    public record CustomerRow(Long id, String code, String personType, String tradeName, String contactName, String email,
                              String phone, String city, String state, String ownerName, Long ownerUserId, String relationship,
                              int activeSubs, int totalSubs, BigDecimal mrr, BigDecimal balance, BigDecimal overdue, int daysOverdue,
                              List<String> products, List<TagResponse> tags, LocalDate customerSince) {
    }

    public record CustomerDetail(Long id, String code, String personType, String tradeName, String legalName, String contactName,
                                 String rfc, String taxRegime, String taxZip, boolean fiscalMasked, String email, String phone,
                                 String whatsapp, String address, String city, String state, String country, String industry,
                                 Long ownerUserId, String ownerName, Long sourceProspectId, String notes, boolean archived,
                                 LocalDate customerSince, List<TagResponse> tags, Instant createdAt, int version) {
    }

    public record ContactRequest(@NotBlank(message = "Escribe el nombre") @Size(max = 150) String name,
                                 @Size(max = 100) String position,
                                 @Email(message = "Correo no válido") @Size(max = 150) String email,
                                 @Pattern(regexp = "^$|^\\d{10}$", message = "10 dígitos") String phone,
                                 boolean primary, boolean billing) {
    }

    public record ContactResponse(Long id, String name, String position, String email, String phone, boolean primary, boolean billing) {
        static ContactResponse of(CustomerContact c) {
            return new ContactResponse(c.getId(), c.getName(), c.getPosition(), c.getEmail(), c.getPhone(), c.isPrimary(), c.isBilling());
        }
    }

    public record NoteRequest(@NotBlank(message = "Escribe la nota") @Size(max = 4000) String body, boolean pinned) {
    }

    public record NoteResponse(Long id, String body, boolean pinned, Long createdBy, String authorName, Instant createdAt) {
    }

    public record TagRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color) {
    }

    public record TagResponse(Long id, String name, String color) {
        static TagResponse of(Tag t) {
            return new TagResponse(t.getId(), t.getName(), t.getColor());
        }
    }

    public record TagsRequest(@NotNull Set<Long> tagIds) {
    }
}

