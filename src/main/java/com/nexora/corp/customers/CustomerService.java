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
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.audit.Changes;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Folios;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Directorio corporativo. Un cliente nunca se duplica por contratar otro producto: RFC y correo son únicos. Los datos
 * fiscales solo se muestran con {@code customers.fiscal_read}; los importes, solo con permiso financiero.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    public static final Set<String> GENERIC_RFC = Set.of("XAXX010101000", "XEXX010101000");

    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final CustomerNoteRepository notes;
    private final TagRepository tags;
    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final Folios folios;
    private final AuditService audit;

    public record Filters(String q, String relationship, Long productId, Long tagId, Long ownerId, Boolean overdue, boolean archived) {
    }

    private static final Map<String, String> SORT = Map.of(
            "tradeName", "lower(c.trade_name)", "code", "c.code", "since", "c.customer_since", "mrr", "o.mrr",
            "balance", "o.balance", "overdue", "o.overdue", "daysOverdue", "o.days_overdue");

    @Transactional(readOnly = true)
    public PageResponse<CustomerRow> list(Filters f, PageQuery page) {
        boolean money = SecurityUtils.has("reports.financial_read") || SecurityUtils.has("charges.read") || SecurityUtils.has("collections.read");
        Sql sql = new Sql()
                .and(!f.archived(), "NOT c.archived", "x", null)
                .and(f.archived(), "c.archived", "z", null)
                .and(Sql.has(f.q()), "(lower(c.trade_name) LIKE :q OR lower(c.contact_name) LIKE :q OR lower(c.email) LIKE :q "
                        + "OR lower(c.code) LIKE :q OR lower(COALESCE(c.legal_name, '')) LIKE :q OR c.phone LIKE :q)", "q", Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(Sql.has(f.relationship()), "o.relationship = :rel", "rel", f.relationship())
                .and(f.productId() != null, "EXISTS (SELECT 1 FROM subscriptions s WHERE s.customer_id = c.id AND s.product_id = :pid "
                        + "AND s.status NOT IN ('CANCELLED', 'EXPIRED'))", "pid", f.productId())
                .and(f.tagId() != null, "EXISTS (SELECT 1 FROM customer_tags ct WHERE ct.customer_id = c.id AND ct.tag_id = :tag)", "tag", f.tagId())
                .and(f.ownerId() != null, "c.owner_user_id = :owner", "owner", f.ownerId())
                .and(Boolean.TRUE.equals(f.overdue()), "o.overdue > 0", "y", null);
        String from = " FROM customers c JOIN customer_overview o ON o.customer_id = c.id LEFT JOIN users u ON u.id = c.owner_user_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + sql.where(), sql.params(), Long.class);
        List<CustomerRow> rows = jdbc.query("""
                SELECT c.id, c.code, c.person_type, c.trade_name, c.contact_name, c.email, c.phone, c.city, c.state, c.owner_user_id,
                       u.first_name || ' ' || u.last_name AS owner_name, o.relationship, o.active_subs, o.total_subs, o.mrr, o.balance,
                       o.overdue, o.days_overdue, c.customer_since,
                       ARRAY(SELECT DISTINCT p.code FROM subscriptions s JOIN products p ON p.id = s.product_id
                             WHERE s.customer_id = c.id AND s.status NOT IN ('CANCELLED', 'EXPIRED') ORDER BY p.code) AS products,
                       ARRAY(SELECT t.id || '|' || t.color || '|' || t.name FROM customer_tags ct JOIN tags t ON t.id = ct.tag_id
                             WHERE ct.customer_id = c.id ORDER BY t.name) AS tags
                """ + from + sql.where() + page.orderBy(SORT, "lower(c.trade_name)") + page.limit(), sql.params(),
                (rs, i) -> new CustomerRow(rs.getLong("id"), rs.getString("code"), rs.getString("person_type"), rs.getString("trade_name"),
                        rs.getString("contact_name"), rs.getString("email"), rs.getString("phone"), rs.getString("city"), rs.getString("state"),
                        rs.getString("owner_name"), (Long) rs.getObject("owner_user_id"), rs.getString("relationship"), rs.getInt("active_subs"),
                        rs.getInt("total_subs"), money ? rs.getBigDecimal("mrr") : null, money ? rs.getBigDecimal("balance") : null,
                        money ? rs.getBigDecimal("overdue") : null, rs.getInt("days_overdue"), strings(rs.getArray("products")),
                        tagsOf(rs.getArray("tags")), rs.getDate("customer_since").toLocalDate()));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    static List<TagResponse> tagsOf(Array array) throws SQLException {
        List<TagResponse> out = new ArrayList<>();
        for (String s : strings(array)) {
            String[] p = s.split("\\|", 3);
            out.add(new TagResponse(Long.valueOf(p[0]), p[2], p[1]));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public CustomerDetail get(Long id) {
        Customer c = customers.findWithTagsById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        return detail(c);
    }

    CustomerDetail detail(Customer c) {
        boolean fiscal = SecurityUtils.has("customers.fiscal_read");
        String ownerName = c.getOwnerUserId() == null ? null : jdbc.query("SELECT first_name || ' ' || last_name FROM users WHERE id = :id",
                Map.of("id", c.getOwnerUserId()), (ResultSet rs, int i) -> rs.getString(1)).stream().findFirst().orElse(null);
        return new CustomerDetail(c.getId(), c.getCode(), c.getPersonType(), c.getTradeName(), c.getLegalName(), c.getContactName(),
                fiscal ? c.getRfc() : mask(c.getRfc()), fiscal ? c.getTaxRegime() : null, fiscal ? c.getTaxZip() : null, !fiscal,
                c.getEmail(), c.getPhone(), c.getWhatsapp(), c.getAddress(), c.getCity(), c.getState(), c.getCountry(), c.getIndustry(),
                c.getOwnerUserId(), ownerName, c.getSourceProspectId(), c.getNotes(), c.isArchived(), c.getCustomerSince(),
                c.getTags().stream().map(TagResponse::of).sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList(),
                c.getCreatedAt(), c.getVersion());
    }

    private static String mask(String rfc) {
        return rfc == null ? null : "•••••••••" + rfc.substring(Math.max(0, rfc.length() - 3));
    }

    @Transactional
    public CustomerDetail create(CustomerRequest r) {
        Customer c = new Customer();
        c.setCode(folios.next("customer_code_seq", "CL", 5));
        apply(c, r, true);
        c.setCustomerSince(r.customerSince() != null ? r.customerSince() : settings.today());
        assertUnique(c, null);
        Customer saved = customers.save(c);
        audit.record(AuditEntry.success("customers", "CUSTOMER_CREATED", "Customer", saved.getId())
                .after(Map.of("code", saved.getCode(), "tradeName", saved.getTradeName())).build());
        return detail(saved);
    }

    /** Alta interna (conversión de prospecto) con los mismos controles de duplicidad. */
    @Transactional
    public Customer createEntity(CustomerRequest r, Long sourceProspectId) {
        Customer c = new Customer();
        c.setCode(folios.next("customer_code_seq", "CL", 5));
        apply(c, r, true);
        c.setSourceProspectId(sourceProspectId);
        c.setCustomerSince(r.customerSince() != null ? r.customerSince() : settings.today());
        assertUnique(c, null);
        Customer saved = customers.save(c);
        audit.record(AuditEntry.success("customers", "CUSTOMER_CREATED", "Customer", saved.getId())
                .after(Map.of("code", saved.getCode(), "tradeName", saved.getTradeName(), "fromProspect", sourceProspectId)).build());
        return saved;
    }

    @Transactional
    public CustomerDetail update(Long id, CustomerRequest r) {
        Customer c = customers.findWithTagsById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        boolean fiscal = SecurityUtils.has("customers.fiscal_read");
        Changes ch = new Changes().track("tradeName", c.getTradeName(), r.tradeName()).track("email", c.getEmail(), r.email().toLowerCase(Locale.ROOT))
                .track("contactName", c.getContactName(), r.contactName()).track("ownerUserId", c.getOwnerUserId(), r.ownerUserId())
                .track("phone", c.getPhone(), blank(r.phone()));
        if (fiscal) {
            ch.track("rfc", c.getRfc(), upper(r.rfc())).track("legalName", c.getLegalName(), blank(r.legalName()))
                    .track("taxRegime", c.getTaxRegime(), blank(r.taxRegime())).track("taxZip", c.getTaxZip(), blank(r.taxZip()));
        }
        // Sin permiso fiscal, los datos fiscales ocultos no se pueden sobrescribir (el formulario los recibe enmascarados).
        apply(c, r, fiscal);
        if (r.customerSince() != null) {
            c.setCustomerSince(r.customerSince());
        }
        assertUnique(c, id);
        if (!ch.isEmpty()) {
            audit.record(AuditEntry.success("customers", "CUSTOMER_UPDATED", "Customer", id).before(ch.before()).after(ch.after()).build());
        }
        return detail(customers.save(c));
    }

    private void apply(Customer c, CustomerRequest r, boolean fiscal) {
        c.setPersonType(r.personType());
        c.setTradeName(r.tradeName().trim());
        c.setContactName(r.contactName().trim());
        if (fiscal) {
            String rfc = upper(r.rfc());
            if (rfc != null && !GENERIC_RFC.contains(rfc)) {
                int expected = "MORAL".equals(r.personType()) ? 12 : 13;
                if (rfc.length() != expected) {
                    throw new BusinessException("El RFC de una persona " + ("MORAL".equals(r.personType()) ? "moral tiene 12" : "física tiene 13") + " caracteres");
                }
            }
            c.setRfc(rfc);
            c.setLegalName(blank(r.legalName()));
            c.setTaxRegime(blank(r.taxRegime()));
            c.setTaxZip(blank(r.taxZip()));
        }
        c.setEmail(r.email().trim().toLowerCase(Locale.ROOT));
        c.setPhone(blank(r.phone()));
        c.setWhatsapp(blank(r.whatsapp()));
        c.setAddress(blank(r.address()));
        c.setCity(blank(r.city()));
        c.setState(blank(r.state()));
        c.setCountry(r.country() == null || r.country().isBlank() ? "MX" : r.country());
        c.setIndustry(blank(r.industry()));
        if (r.ownerUserId() != null) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id AND active", Map.of("id", r.ownerUserId()), Integer.class);
            if (exists == null || exists == 0) {
                throw new BusinessException("El ejecutivo asignado no existe o está inactivo");
            }
        }
        c.setOwnerUserId(r.ownerUserId());
        c.setNotes(blank(r.notes()));
        if (r.tagIds() != null) {
            c.setTags(new HashSet<>(tags.findAllById(r.tagIds())));
        }
    }

    private void assertUnique(Customer c, Long selfId) {
        customers.findFirstByEmailAndArchivedFalse(c.getEmail()).filter(o -> !o.getId().equals(selfId)).ifPresent(o -> {
            throw new ConflictException("Ya existe el cliente " + o.getCode() + " (" + o.getTradeName() + ") con ese correo");
        });
        if (c.getRfc() != null && !GENERIC_RFC.contains(c.getRfc())) {
            customers.findFirstByRfc(c.getRfc()).filter(o -> !o.getId().equals(selfId)).ifPresent(o -> {
                throw new ConflictException("Ya existe el cliente " + o.getCode() + " (" + o.getTradeName() + ") con ese RFC");
            });
        }
    }

    /** Cliente existente que coincide por correo o RFC (evita duplicados al convertir prospectos). */
    @Transactional(readOnly = true)
    public Customer findDuplicate(String email, String rfc) {
        if (email != null) {
            var byEmail = customers.findFirstByEmailAndArchivedFalse(email.trim().toLowerCase(Locale.ROOT));
            if (byEmail.isPresent()) {
                return byEmail.get();
            }
        }
        if (rfc != null && !rfc.isBlank() && !GENERIC_RFC.contains(rfc.toUpperCase(Locale.ROOT))) {
            return customers.findFirstByRfc(rfc.trim().toUpperCase(Locale.ROOT)).orElse(null);
        }
        return null;
    }

    @Transactional
    public CustomerDetail setArchived(Long id, boolean archived) {
        Customer c = customers.findWithTagsById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (archived) {
            Integer live = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM subscriptions WHERE customer_id = :id AND status NOT IN ('CANCELLED', 'EXPIRED')""",
                    Map.of("id", id), Integer.class);
            if (live != null && live > 0) {
                throw new BusinessException("El cliente tiene suscripciones vigentes; cancélalas antes de archivarlo");
            }
            BigDecimal balance = jdbc.queryForObject("SELECT balance FROM customer_overview WHERE customer_id = :id", Map.of("id", id), BigDecimal.class);
            if (balance != null && balance.signum() > 0) {
                throw new BusinessException("El cliente tiene saldo pendiente; no se puede archivar");
            }
        }
        c.setArchived(archived);
        audit.record(AuditEntry.success("customers", archived ? "CUSTOMER_ARCHIVED" : "CUSTOMER_RESTORED", "Customer", id).build());
        return detail(c);
    }

    public Customer require(Long id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
    }

    // ── Contactos ───────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ContactResponse> contacts(Long customerId) {
        require(customerId);
        return contacts.findByCustomerIdOrderByPrimaryDescNameAsc(customerId).stream().map(ContactResponse::of).toList();
    }

    @Transactional
    public ContactResponse saveContact(Long customerId, Long contactId, ContactRequest r) {
        require(customerId);
        CustomerContact c = contactId == null ? CustomerContact.builder().customerId(customerId).build()
                : contacts.findById(contactId).filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Contacto no encontrado"));
        c.setName(r.name().trim());
        c.setPosition(blank(r.position()));
        c.setEmail(r.email() == null || r.email().isBlank() ? null : r.email().trim().toLowerCase(Locale.ROOT));
        c.setPhone(blank(r.phone()));
        c.setPrimary(r.primary());
        c.setBilling(r.billing());
        if (r.primary()) {
            contacts.findByCustomerIdOrderByPrimaryDescNameAsc(customerId).stream().filter(x -> !x.getId().equals(contactId))
                    .forEach(x -> x.setPrimary(false));
        }
        CustomerContact saved = contacts.save(c);
        audit.record(AuditEntry.success("customers", contactId == null ? "CONTACT_ADDED" : "CONTACT_UPDATED", "Customer", customerId)
                .detail(saved.getName()).build());
        return ContactResponse.of(saved);
    }

    @Transactional
    public void deleteContact(Long customerId, Long contactId) {
        CustomerContact c = contacts.findById(contactId).filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Contacto no encontrado"));
        contacts.delete(c);
        audit.record(AuditEntry.success("customers", "CONTACT_REMOVED", "Customer", customerId).detail(c.getName()).build());
    }

    // ── Notas ───────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<NoteResponse> notes(Long customerId) {
        require(customerId);
        return jdbc.query("""
                SELECT n.id, n.body, n.pinned, n.created_by, u.first_name || ' ' || u.last_name AS author, n.created_at
                FROM customer_notes n LEFT JOIN users u ON u.id = n.created_by WHERE n.customer_id = :id
                ORDER BY n.pinned DESC, n.created_at DESC""", Map.of("id", customerId),
                (rs, i) -> new NoteResponse(rs.getLong(1), rs.getString(2), rs.getBoolean(3), (Long) rs.getObject(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant()));
    }

    @Transactional
    public NoteResponse addNote(Long customerId, NoteRequest r) {
        require(customerId);
        CustomerNote n = notes.save(CustomerNote.builder().customerId(customerId).body(r.body().trim()).pinned(r.pinned())
                .createdBy(SecurityUtils.currentUserId()).build());
        return notes(customerId).stream().filter(x -> x.id().equals(n.getId())).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteNote(Long customerId, Long noteId) {
        CustomerNote n = notes.findById(noteId).filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Nota no encontrada"));
        if (!java.util.Objects.equals(n.getCreatedBy(), SecurityUtils.currentUserId()) && !SecurityUtils.requirePrincipal().isSuperAdmin()) {
            throw new BusinessException("Solo quien escribió la nota puede eliminarla");
        }
        notes.delete(n);
    }

    @Transactional
    public NoteResponse togglePin(Long customerId, Long noteId) {
        CustomerNote n = notes.findById(noteId).filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Nota no encontrada"));
        n.setPinned(!n.isPinned());
        return notes(customerId).stream().filter(x -> x.id().equals(noteId)).findFirst().orElseThrow();
    }

    // ── Etiquetas ───────────────────────────────────────────────────────────────────────

    public List<TagResponse> allTags() {
        return tags.findAllByOrderByNameAsc().stream().map(TagResponse::of).toList();
    }

    @Transactional
    public TagResponse createTag(TagRequest r) {
        if (tags.existsByNameIgnoreCase(r.name().trim())) {
            throw new ConflictException("Ya existe esa etiqueta");
        }
        return TagResponse.of(tags.save(Tag.builder().name(r.name().trim()).color(r.color().toLowerCase()).build()));
    }

    @Transactional
    public void deleteTag(Long id) {
        tags.deleteById(id);
    }

    @Transactional
    public CustomerDetail setTags(Long customerId, Set<Long> tagIds) {
        Customer c = customers.findWithTagsById(customerId).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        List<String> before = c.getTags().stream().map(Tag::getName).sorted().toList();
        c.setTags(new HashSet<>(tags.findAllById(tagIds)));
        audit.record(AuditEntry.success("customers", "CUSTOMER_TAGS_CHANGED", "Customer", customerId)
                .before(Map.of("tags", before)).after(Map.of("tags", c.getTags().stream().map(Tag::getName).sorted().toList())).build());
        return detail(c);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}

