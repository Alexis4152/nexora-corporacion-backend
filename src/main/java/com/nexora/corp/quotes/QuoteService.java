package com.nexora.corp.quotes;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.domain.BillingPeriod;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Folios;
import com.nexora.corp.shared.util.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cotizaciones versionadas a partir del catálogo. Una cotización enviada ya no se edita: se crea una versión nueva
 * (la anterior queda como SUPERSEDED). PDF con la marca corporativa.
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    public record ItemRequest(Long productId, Long planId, BillingPeriod billingPeriod,
                              @NotBlank(message = "Describe el concepto") @Size(max = 300) String description,
                              @NotNull @Min(1) @Max(999) Integer quantity,
                              @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
                              @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discount) {
    }

    public record QuoteRequest(Long customerId, Long prospectId, @NotBlank(message = "Escribe el título") @Size(max = 200) String title,
                               @NotNull(message = "Indica la vigencia") LocalDate validUntil, @Size(max = 4000) String conditions,
                               @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discount,
                               @NotEmpty(message = "Agrega al menos un concepto") List<@Valid ItemRequest> items) {
    }

    public record ItemRow(Long id, Long productId, String productCode, Long planId, String planName, BillingPeriod billingPeriod,
                          String description, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal lineTotal) {
    }

    public record QuoteRow(Long id, String folio, int versionNo, Long customerId, Long prospectId, String targetName, String title,
                           String status, LocalDate validUntil, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total,
                           String currency, String ownerName, Instant createdAt, Instant sentAt) {
    }

    public record QuoteDetail(QuoteRow quote, String conditions, BigDecimal taxRate, List<ItemRow> items, List<QuoteRow> versions, int version) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Folios folios;
    private final SettingsService settings;
    private final AuditService audit;

    private static final String SELECT = """
            SELECT q.*, COALESCE(c.trade_name, p.business_name) AS target_name, u.first_name || ' ' || u.last_name AS owner_name
            FROM quotes q LEFT JOIN customers c ON c.id = q.customer_id LEFT JOIN prospects p ON p.id = q.prospect_id
            LEFT JOIN users u ON u.id = q.owner_user_id""";

    @Transactional(readOnly = true)
    public PageResponse<QuoteRow> list(String q, String status, Long customerId, Long prospectId, Long ownerId, PageQuery page) {
        Sql sql = new Sql()
                .and(Sql.has(q), "(lower(q.folio) LIKE :q OR lower(q.title) LIKE :q OR lower(COALESCE(c.trade_name, p.business_name)) LIKE :q)",
                        "q", Sql.has(q) ? Sql.like(q) : null)
                .and(Sql.has(status), "q.status = :st", "st", status)
                .and(!Sql.has(status), "q.status <> 'SUPERSEDED'", "ss", null)
                .and(customerId != null, "q.customer_id = :cid", "cid", customerId)
                .and(prospectId != null, "q.prospect_id = :pid", "pid", prospectId)
                .and(ownerId != null, "q.owner_user_id = :owner", "owner", ownerId)
                .and(!SecurityUtils.has("prospects.read_all") && !SecurityUtils.has("reports.financial_read"),
                        "(q.owner_user_id = :me OR q.customer_id IS NOT NULL)", "me", SecurityUtils.currentUserId());
        String from = " FROM quotes q LEFT JOIN customers c ON c.id = q.customer_id LEFT JOIN prospects p ON p.id = q.prospect_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + sql.where(), sql.params(), Long.class);
        List<QuoteRow> rows = jdbc.query(SELECT + sql.where() + " ORDER BY q.created_at DESC" + page.limit(), sql.params(), (rs, i) -> row(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public QuoteDetail get(Long id) {
        List<QuoteDetail> found = jdbc.query(SELECT + " WHERE q.id = :id", Map.of("id", id), (rs, i) -> {
            QuoteRow row = row(rs);
            return new QuoteDetail(row, rs.getString("conditions"), rs.getBigDecimal("tax_rate"), items(id),
                    jdbc.query(SELECT + " WHERE q.folio = :f ORDER BY q.version_no DESC", Map.of("f", row.folio()), (r2, j) -> row(r2)),
                    rs.getInt("version"));
        });
        if (found.isEmpty()) {
            throw new NotFoundException("Cotización no encontrada");
        }
        return found.get(0);
    }

    @Transactional
    public QuoteDetail create(QuoteRequest r) {
        return get(insert(r, folios.next("quote_folio_seq", "COT", 5), 1));
    }

    private Long insert(QuoteRequest r, String folio, int versionNo) {
        validate(r);
        BigDecimal rate = settings.taxRate();
        Totals t = totals(r, rate);
        Long id = jdbc.queryForObject("""
                INSERT INTO quotes (folio, version_no, customer_id, prospect_id, title, valid_until, conditions, currency, tax_rate, subtotal,
                                    discount, tax, total, owner_user_id, created_by)
                VALUES (:f, :v, :c, :p, :t, :vu, :cond, :cur, :rate, :sub, :disc, :tax, :tot, :me, :me) RETURNING id""",
                new MapSqlParameterSource().addValue("f", folio).addValue("v", versionNo).addValue("c", r.customerId()).addValue("p", r.prospectId())
                        .addValue("t", r.title().trim()).addValue("vu", r.validUntil()).addValue("cond", blank(r.conditions()))
                        .addValue("cur", settings.currency()).addValue("rate", rate).addValue("sub", t.subtotal()).addValue("disc", t.discount())
                        .addValue("tax", t.tax()).addValue("tot", t.total()).addValue("me", SecurityUtils.currentUserId()), Long.class);
        int order = 0;
        for (ItemRequest it : r.items()) {
            BigDecimal unit = Money.of(it.unitPrice());
            BigDecimal disc = Money.of(it.discount());
            BigDecimal line = unit.multiply(BigDecimal.valueOf(it.quantity())).subtract(disc);
            jdbc.update("""
                    INSERT INTO quote_items (quote_id, product_id, plan_id, billing_period, description, quantity, unit_price, discount, line_total, sort_order)
                    VALUES (:q, :p, :pl, :bp, :d, :qty, :u, :disc, :lt, :o)""", new MapSqlParameterSource().addValue("q", id)
                    .addValue("p", it.productId()).addValue("pl", it.planId()).addValue("bp", it.billingPeriod() == null ? null : it.billingPeriod().name())
                    .addValue("d", it.description().trim()).addValue("qty", it.quantity()).addValue("u", unit).addValue("disc", disc)
                    .addValue("lt", line).addValue("o", order++));
        }
        audit.record(AuditEntry.success("quotes", versionNo == 1 ? "QUOTE_CREATED" : "QUOTE_VERSIONED", "Quote", id)
                .after(Map.of("folio", folio, "version", versionNo, "total", t.total())).build());
        return id;
    }

    record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
    }

    static Totals totals(QuoteRequest r, BigDecimal rate) {
        BigDecimal subtotal = r.items().stream().map(it -> Money.of(it.unitPrice()).multiply(BigDecimal.valueOf(it.quantity()))
                .subtract(Money.of(it.discount()))).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = Money.of(r.discount());
        if (discount.compareTo(subtotal) > 0) {
            throw new BusinessException("El descuento no puede ser mayor al subtotal");
        }
        BigDecimal tax = subtotal.subtract(discount).multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return new Totals(subtotal, discount, tax, subtotal.subtract(discount).add(tax));
    }

    private void validate(QuoteRequest r) {
        if ((r.customerId() == null) == (r.prospectId() == null)) {
            throw new BusinessException("La cotización es para un cliente o para un prospecto");
        }
        if (r.validUntil().isBefore(settings.today())) {
            throw new BusinessException("La vigencia no puede estar en el pasado");
        }
        for (ItemRequest it : r.items()) {
            BigDecimal gross = Money.of(it.unitPrice()).multiply(BigDecimal.valueOf(it.quantity()));
            if (Money.of(it.discount()).compareTo(gross) > 0) {
                throw new BusinessException("El descuento de «" + it.description() + "» supera su importe");
            }
            if (it.planId() != null) {
                Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM plans WHERE id = :pl AND (:p::bigint IS NULL OR product_id = :p)",
                        new MapSqlParameterSource("pl", it.planId()).addValue("p", it.productId()), Integer.class);
                if (ok == null || ok == 0) {
                    throw new BusinessException("Plan no válido en «" + it.description() + "»");
                }
            }
        }
    }

    @Transactional
    public QuoteDetail update(Long id, QuoteRequest r) {
        QuoteDetail q = get(id);
        if (!"DRAFT".equals(q.quote().status())) {
            throw new BusinessException("Solo se editan borradores. Crea una versión nueva.");
        }
        validate(r);
        Totals t = totals(r, q.taxRate());
        jdbc.update("DELETE FROM quote_items WHERE quote_id = :id", Map.of("id", id));
        jdbc.update("""
                UPDATE quotes SET customer_id = :c, prospect_id = :p, title = :t, valid_until = :vu, conditions = :cond, subtotal = :sub,
                  discount = :disc, tax = :tax, total = :tot, updated_at = now(), version = version + 1 WHERE id = :id""",
                new MapSqlParameterSource().addValue("c", r.customerId()).addValue("p", r.prospectId()).addValue("t", r.title().trim())
                        .addValue("vu", r.validUntil()).addValue("cond", blank(r.conditions())).addValue("sub", t.subtotal())
                        .addValue("disc", t.discount()).addValue("tax", t.tax()).addValue("tot", t.total()).addValue("id", id));
        int order = 0;
        for (ItemRequest it : r.items()) {
            BigDecimal unit = Money.of(it.unitPrice());
            BigDecimal disc = Money.of(it.discount());
            jdbc.update("""
                    INSERT INTO quote_items (quote_id, product_id, plan_id, billing_period, description, quantity, unit_price, discount, line_total, sort_order)
                    VALUES (:q, :p, :pl, :bp, :d, :qty, :u, :disc, :lt, :o)""", new MapSqlParameterSource().addValue("q", id)
                    .addValue("p", it.productId()).addValue("pl", it.planId()).addValue("bp", it.billingPeriod() == null ? null : it.billingPeriod().name())
                    .addValue("d", it.description().trim()).addValue("qty", it.quantity()).addValue("u", unit).addValue("disc", disc)
                    .addValue("lt", unit.multiply(BigDecimal.valueOf(it.quantity())).subtract(disc)).addValue("o", order++));
        }
        return get(id);
    }

    @Transactional
    public QuoteDetail setStatus(Long id, String status) {
        QuoteDetail q = get(id);
        String from = q.quote().status();
        boolean ok = switch (status) {
            case "SENT" -> "DRAFT".equals(from);
            case "ACCEPTED", "REJECTED" -> "SENT".equals(from);
            default -> false;
        };
        if (!ok) {
            throw new BusinessException("Transición de estado no válida");
        }
        if ("ACCEPTED".equals(status) && q.quote().validUntil().isBefore(settings.today())) {
            throw new BusinessException("La cotización ya venció; crea una versión nueva");
        }
        jdbc.update("""
                UPDATE quotes SET status = :st, sent_at = CASE WHEN :st = 'SENT' THEN now() ELSE sent_at END,
                  decided_at = CASE WHEN :st IN ('ACCEPTED', 'REJECTED') THEN now() ELSE decided_at END, updated_at = now(), version = version + 1
                WHERE id = :id""", Map.of("st", status, "id", id));
        if ("SENT".equals(status) && q.quote().prospectId() != null) {
            jdbc.update("""
                    UPDATE prospects SET stage = 'QUOTE_SENT', probability = 65, stage_changed_at = now()
                    WHERE id = :p AND stage IN ('QUALIFIED', 'DEMO_SCHEDULED', 'DEMO_DONE')""", Map.of("p", q.quote().prospectId()));
        }
        audit.record(AuditEntry.success("quotes", "QUOTE_" + status, "Quote", id).before(Map.of("status", from)).after(Map.of("status", status)).build());
        return get(id);
    }

    @Transactional
    public QuoteDetail newVersion(Long id) {
        QuoteDetail q = get(id);
        if ("ACCEPTED".equals(q.quote().status())) {
            throw new BusinessException("Una cotización aceptada no se versiona");
        }
        Integer max = jdbc.queryForObject("SELECT MAX(version_no) FROM quotes WHERE folio = :f", Map.of("f", q.quote().folio()), Integer.class);
        LocalDate valid = q.quote().validUntil().isBefore(settings.today().plusDays(15)) ? settings.today().plusDays(15) : q.quote().validUntil();
        QuoteRequest copy = new QuoteRequest(q.quote().customerId(), q.quote().prospectId(), q.quote().title(), valid, q.conditions(),
                q.quote().discount(), q.items().stream().map(it -> new ItemRequest(it.productId(), it.planId(), it.billingPeriod(), it.description(),
                it.quantity(), it.unitPrice(), it.discount())).toList());
        Long newId = insert(copy, q.quote().folio(), (max == null ? 1 : max) + 1);
        // La nueva versión sigue siendo del responsable original, aunque la genere otro usuario.
        jdbc.update("UPDATE quotes SET owner_user_id = (SELECT owner_user_id FROM quotes WHERE id = :old) WHERE id = :id",
                Map.of("old", id, "id", newId));
        jdbc.update("UPDATE quotes SET status = 'SUPERSEDED', updated_at = now() WHERE folio = :f AND id <> :id AND status IN ('DRAFT', 'SENT', 'EXPIRED', 'REJECTED')",
                Map.of("f", q.quote().folio(), "id", newId));
        return get(newId);
    }

    @Scheduled(cron = "0 10 2 * * *")
    @Transactional
    public void expire() {
        jdbc.update("UPDATE quotes SET status = 'EXPIRED', updated_at = now() WHERE status IN ('DRAFT', 'SENT') AND valid_until < nx_today()", Map.of());
    }

    public byte[] pdf(Long id) {
        QuoteDetail q = get(id);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.LETTER, 40, 40, 40, 40);
            PdfWriter.getInstance(doc, out);
            doc.open();
            Color brand = Color.decode(settings.string(Setting.PRIMARY_COLOR));
            Color ink = new Color(15, 23, 42);
            Color muted = new Color(100, 116, 139);
            NumberFormat mf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
            DateTimeFormatter day = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            PdfPTable head = new PdfPTable(new float[]{3, 2});
            head.setWidthPercentage(100);
            PdfPCell left = new PdfPCell();
            left.setBorder(0);
            left.addElement(new Paragraph(settings.string(Setting.COMPANY_NAME), new Font(Font.HELVETICA, 18, Font.BOLD, brand)));
            String contact = String.join(" · ", List.of(settings.string(Setting.COMPANY_EMAIL), settings.string(Setting.COMPANY_PHONE),
                    settings.string(Setting.COMPANY_WEBSITE)).stream().filter(s -> !s.isBlank()).toList());
            if (!contact.isBlank()) {
                left.addElement(new Paragraph(contact, new Font(Font.HELVETICA, 8, Font.NORMAL, muted)));
            }
            head.addCell(left);
            PdfPCell right = new PdfPCell();
            right.setBorder(0);
            right.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph t = new Paragraph("COTIZACIÓN", new Font(Font.HELVETICA, 14, Font.BOLD, ink));
            t.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(t);
            Paragraph f = new Paragraph(q.quote().folio() + " · v" + q.quote().versionNo(), new Font(Font.HELVETICA, 9, Font.NORMAL, muted));
            f.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(f);
            Paragraph v = new Paragraph("Vigente hasta " + q.quote().validUntil().format(day), new Font(Font.HELVETICA, 9, Font.BOLD, brand));
            v.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(v);
            head.addCell(right);
            doc.add(head);
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Para: " + q.quote().targetName(), new Font(Font.HELVETICA, 11, Font.BOLD, ink)));
            doc.add(new Paragraph(q.quote().title(), new Font(Font.HELVETICA, 10, Font.NORMAL, muted)));
            doc.add(new Paragraph(" "));
            PdfPTable items = new PdfPTable(new float[]{5, 1.2f, 2, 1.6f, 2});
            items.setWidthPercentage(100);
            Font hf = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
            for (String h : List.of("Concepto", "Cant.", "Precio unitario", "Descuento", "Importe")) {
                PdfPCell c = new PdfPCell(new Phrase(h, hf));
                c.setBackgroundColor(brand);
                c.setPadding(6);
                c.setBorder(0);
                items.addCell(c);
            }
            Font bf = new Font(Font.HELVETICA, 9, Font.NORMAL, ink);
            for (ItemRow it : q.items()) {
                String desc = it.description() + (it.billingPeriod() != null ? " (" + it.billingPeriod().label() + ")" : "");
                for (String val : List.of(desc, String.valueOf(it.quantity()), mf.format(it.unitPrice()), mf.format(it.discount()), mf.format(it.lineTotal()))) {
                    PdfPCell c = new PdfPCell(new Phrase(val, bf));
                    c.setPadding(6);
                    c.setBorderColor(new Color(226, 232, 240));
                    c.setBorderWidthLeft(0);
                    c.setBorderWidthRight(0);
                    c.setBorderWidthTop(0);
                    if (!val.equals(desc)) {
                        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    }
                    items.addCell(c);
                }
            }
            doc.add(items);
            PdfPTable totals = new PdfPTable(new float[]{4, 2});
            totals.setWidthPercentage(45);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            String rate = q.taxRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
            for (Object[] row : new Object[][]{{"Subtotal", q.quote().subtotal()}, {"Descuento", q.quote().discount()},
                    {"IVA " + rate + " %", q.quote().tax()}, {"Total " + q.quote().currency(), q.quote().total()}}) {
                boolean last = String.valueOf(row[0]).startsWith("Total");
                Font tf = new Font(Font.HELVETICA, last ? 11 : 9, last ? Font.BOLD : Font.NORMAL, last ? brand : ink);
                PdfPCell a = new PdfPCell(new Phrase(String.valueOf(row[0]), tf));
                PdfPCell b = new PdfPCell(new Phrase(mf.format(row[1]), tf));
                a.setBorder(0);
                b.setBorder(0);
                a.setPadding(4);
                b.setPadding(4);
                b.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totals.addCell(a);
                totals.addCell(b);
            }
            doc.add(new Paragraph(" "));
            doc.add(totals);
            if (q.conditions() != null) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("Condiciones", new Font(Font.HELVETICA, 10, Font.BOLD, ink)));
                doc.add(new Paragraph(q.conditions(), new Font(Font.HELVETICA, 8.5f, Font.NORMAL, muted)));
            }
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Precios en " + q.quote().currency() + ". Este documento es una cotización y no constituye un comprobante fiscal.",
                    new Font(Font.HELVETICA, 7.5f, Font.ITALIC, muted)));
            doc.close();
            audit.record(AuditEntry.success("quotes", "QUOTE_PDF", "Quote", id).build());
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el PDF", e);
        }
    }

    private List<ItemRow> items(Long id) {
        return jdbc.query("""
                SELECT i.id, i.product_id, p.code, i.plan_id, pl.name, i.billing_period, i.description, i.quantity, i.unit_price, i.discount, i.line_total
                FROM quote_items i LEFT JOIN products p ON p.id = i.product_id LEFT JOIN plans pl ON pl.id = i.plan_id
                WHERE i.quote_id = :id ORDER BY i.sort_order""", Map.of("id", id), (rs, i) -> new ItemRow(rs.getLong(1), (Long) rs.getObject(2),
                rs.getString(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6) == null ? null : BillingPeriod.valueOf(rs.getString(6)),
                rs.getString(7), rs.getInt(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11)));
    }

    private static QuoteRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp sent = rs.getTimestamp("sent_at");
        return new QuoteRow(rs.getLong("id"), rs.getString("folio"), rs.getInt("version_no"), (Long) rs.getObject("customer_id"),
                (Long) rs.getObject("prospect_id"), rs.getString("target_name"), rs.getString("title"), rs.getString("status"),
                rs.getDate("valid_until").toLocalDate(), rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount"), rs.getBigDecimal("tax"),
                rs.getBigDecimal("total"), rs.getString("currency"), rs.getString("owner_name"), rs.getTimestamp("created_at").toInstant(),
                sent == null ? null : sent.toInstant());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

