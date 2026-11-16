package com.nexora.corp.crm;

import com.nexora.corp.crm.CrmDtos.ActivityRequest;
import com.nexora.corp.crm.CrmDtos.ActivityResponse;
import com.nexora.corp.crm.CrmDtos.BoardColumn;
import com.nexora.corp.crm.CrmDtos.InterestDto;
import com.nexora.corp.crm.CrmDtos.ProspectCard;
import com.nexora.corp.crm.CrmDtos.ProspectDetail;
import com.nexora.corp.crm.CrmDtos.ProspectRequest;
import com.nexora.corp.crm.CrmDtos.StageRequest;
import com.nexora.corp.customers.Customer;
import com.nexora.corp.customers.CustomerService;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.audit.Changes;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * CRM de prospectos. Autorización a nivel de registro: sin {@code prospects.read_all} cada vendedor ve y opera solo
 * sus prospectos (los ajenos responden 404, no 403, para no revelar su existencia).
 */
@Service
@RequiredArgsConstructor
public class ProspectService {

    private final ProspectRepository prospects;
    private final SalesActivityRepository activities;
    private final CustomerService customers;
    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    public record Filters(String q, ProspectStage stage, Long productId, Long ownerId, String source, boolean openOnly, Boolean stale) {
    }

    private static final Map<String, String> SORT = Map.of("created", "p.created_at", "value", "p.estimated_value",
            "nextContact", "p.next_contact_at", "business", "lower(p.business_name)", "probability", "p.probability");

    private static boolean seesAll() {
        return SecurityUtils.has("prospects.read_all");
    }

    private Sql filters(Filters f) {
        AuthPrincipal me = SecurityUtils.requirePrincipal();
        int staleDays = settings.integer(Setting.PROSPECT_STALE_DAYS);
        return new Sql()
                .and(!seesAll(), "p.owner_user_id = :me", "me", me.userId())
                .and(Sql.has(f.q()), "(lower(p.business_name) LIKE :q OR lower(p.contact_name) LIKE :q OR lower(COALESCE(p.email, '')) LIKE :q "
                        + "OR COALESCE(p.phone, '') LIKE :q)", "q", Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(f.stage() != null, "p.stage = :stage", "stage", f.stage() == null ? null : f.stage().name())
                .and(f.productId() != null, "EXISTS (SELECT 1 FROM prospect_interests i WHERE i.prospect_id = p.id AND i.product_id = :pid)",
                        "pid", f.productId())
                .and(f.ownerId() != null, "p.owner_user_id = :owner", "owner", f.ownerId())
                .and(Sql.has(f.source()), "p.source = :source", "source", f.source())
                .and(f.openOnly(), "p.stage NOT IN ('WON', 'LOST', 'DISCARDED')", "o", null)
                .and(Boolean.TRUE.equals(f.stale()), "p.stage NOT IN ('WON', 'LOST', 'DISCARDED') AND COALESCE(GREATEST(p.stage_changed_at, "
                        + "(SELECT MAX(a.created_at) FROM sales_activities a WHERE a.prospect_id = p.id)), p.created_at) < now() - make_interval(days => :stale)",
                        "stale", staleDays)
                .param("staleDays", staleDays);
    }

    private static final String CARD_SELECT = """
            SELECT p.id, p.business_name, p.contact_name, p.email, p.phone, p.city, p.source, p.stage, p.probability, p.estimated_value,
                   p.owner_user_id, u.first_name || ' ' || u.last_name AS owner_name, p.next_contact_at, p.stage_changed_at, p.created_at,
                   p.converted_customer_id, p.lost_reason,
                   ARRAY(SELECT pr.code FROM prospect_interests i JOIN products pr ON pr.id = i.product_id WHERE i.prospect_id = p.id ORDER BY pr.code) AS products,
                   (p.stage NOT IN ('WON', 'LOST', 'DISCARDED') AND COALESCE(GREATEST(p.stage_changed_at,
                      (SELECT MAX(a.created_at) FROM sales_activities a WHERE a.prospect_id = p.id)), p.created_at)
                      < now() - make_interval(days => :staleDays)) AS stale
            FROM prospects p LEFT JOIN users u ON u.id = p.owner_user_id""";

    private ProspectCard card(ResultSet rs) throws SQLException {
        Timestamp next = rs.getTimestamp("next_contact_at");
        return new ProspectCard(rs.getLong("id"), rs.getString("business_name"), rs.getString("contact_name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("city"), rs.getString("source"), ProspectStage.valueOf(rs.getString("stage")),
                rs.getInt("probability"), rs.getBigDecimal("estimated_value"), (Long) rs.getObject("owner_user_id"), rs.getString("owner_name"),
                next == null ? null : next.toInstant(), rs.getTimestamp("stage_changed_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                Arrays.asList((String[]) rs.getArray("products").getArray()), (Long) rs.getObject("converted_customer_id"),
                rs.getString("lost_reason"), rs.getBoolean("stale"));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProspectCard> list(Filters f, PageQuery page) {
        Sql sql = filters(f);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM prospects p" + sql.where(), sql.params(), Long.class);
        List<ProspectCard> rows = jdbc.query(CARD_SELECT + sql.where() + page.orderBy(SORT, "p.created_at DESC") + page.limit(),
                sql.params(), (rs, i) -> card(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    /** Tablero Kanban: etapas abiertas completas; ganados/perdidos solo de los últimos 30 días. */
    @Transactional(readOnly = true)
    public List<BoardColumn> board(Filters f) {
        Sql sql = filters(new Filters(f.q(), null, f.productId(), f.ownerId(), f.source(), false, f.stale()))
                .and("(p.stage NOT IN ('WON', 'LOST', 'DISCARDED') OR p.stage_changed_at > now() - interval '30 days')");
        List<ProspectCard> cards = jdbc.query(CARD_SELECT + sql.where() + " ORDER BY p.probability DESC, p.estimated_value DESC NULLS LAST, p.created_at DESC LIMIT 600",
                sql.params(), (rs, i) -> card(rs));
        Map<ProspectStage, List<ProspectCard>> byStage = new LinkedHashMap<>();
        for (ProspectStage s : ProspectStage.values()) {
            byStage.put(s, new ArrayList<>());
        }
        cards.forEach(c -> byStage.get(c.stage()).add(c));
        return byStage.entrySet().stream().map(e -> {
            BigDecimal value = e.getValue().stream().map(c -> c.estimatedValue() == null ? BigDecimal.ZERO : c.estimatedValue())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal weighted = e.getValue().stream().map(c -> c.estimatedValue() == null ? BigDecimal.ZERO
                    : c.estimatedValue().multiply(BigDecimal.valueOf(c.probability())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new BoardColumn(e.getKey(), e.getValue().size(), value, weighted, e.getValue());
        }).toList();
    }

    @Transactional(readOnly = true)
    public ProspectDetail get(Long id) {
        Prospect p = visible(id);
        String owner = p.getOwnerUserId() == null ? null : userName(p.getOwnerUserId());
        Customer dup = p.getConvertedCustomerId() == null ? customers.findDuplicate(p.getEmail(), null) : null;
        return new ProspectDetail(p.getId(), p.getContactName(), p.getBusinessName(), p.getPhone(), p.getWhatsapp(), p.getEmail(), p.getCity(),
                p.getState(), p.getCountry(), p.getIndustry(), p.getSource(), p.getOwnerUserId(), owner, p.getEstimatedBudget(),
                p.getEstimatedValue(), p.getProbability(), p.getStage(), p.getStageChangedAt(), p.getNextContactAt(), p.getComments(),
                p.getLostReason(), p.getLostDetail(), p.getConvertedCustomerId(), p.getConvertedAt(),
                p.getInterests().stream().map(i -> new InterestDto(i.getProductId(), i.getPlanId())).toList(), activitiesOf(id, null),
                p.getCreatedAt(), p.getVersion(), dup == null ? null : dup.getId(), dup == null ? null : dup.getTradeName());
    }

    @Transactional
    public ProspectDetail create(ProspectRequest r) {
        Prospect p = new Prospect();
        apply(p, r);
        AuthPrincipal me = SecurityUtils.requirePrincipal();
        if (p.getOwnerUserId() == null) {
            p.setOwnerUserId(me.userId());
        } else if (!seesAll() && !p.getOwnerUserId().equals(me.userId())) {
            throw new BusinessException("Solo puedes registrar prospectos a tu nombre");
        }
        p.setProbability(r.probability() != null ? r.probability() : ProspectStage.NEW.probability());
        p.setStageChangedAt(clock.instant());
        Prospect saved = prospects.save(p);
        audit.record(AuditEntry.success("prospects", "PROSPECT_CREATED", "Prospect", saved.getId())
                .after(Map.of("business", saved.getBusinessName(), "owner", saved.getOwnerUserId())).build());
        return get(saved.getId());
    }

    @Transactional
    public ProspectDetail update(Long id, ProspectRequest r) {
        Prospect p = visible(id);
        if (!p.getStage().isOpen() && p.getStage() == ProspectStage.WON) {
            throw new BusinessException("Una oportunidad ganada ya no se edita");
        }
        if (r.ownerUserId() != null && !r.ownerUserId().equals(p.getOwnerUserId()) && !seesAll()) {
            throw new BusinessException("No puedes reasignar prospectos");
        }
        Changes ch = new Changes().track("businessName", p.getBusinessName(), r.businessName()).track("owner", p.getOwnerUserId(), r.ownerUserId())
                .track("estimatedValue", p.getEstimatedValue(), r.estimatedValue()).track("nextContactAt", p.getNextContactAt(), r.nextContactAt());
        apply(p, r);
        if (r.probability() != null) {
            p.setProbability(r.probability());
        }
        if (!ch.isEmpty()) {
            audit.record(AuditEntry.success("prospects", "PROSPECT_UPDATED", "Prospect", id).before(ch.before()).after(ch.after()).build());
        }
        return get(id);
    }

    private void apply(Prospect p, ProspectRequest r) {
        p.setContactName(r.contactName().trim());
        p.setBusinessName(r.businessName().trim());
        p.setPhone(blank(r.phone()));
        p.setWhatsapp(blank(r.whatsapp()));
        p.setEmail(r.email() == null || r.email().isBlank() ? null : r.email().trim().toLowerCase(Locale.ROOT));
        p.setCity(blank(r.city()));
        p.setState(blank(r.state()));
        p.setCountry(r.country() == null || r.country().isBlank() ? "MX" : r.country());
        p.setIndustry(blank(r.industry()));
        p.setSource(r.source());
        if (r.ownerUserId() != null) {
            p.setOwnerUserId(r.ownerUserId());
        }
        p.setEstimatedBudget(r.estimatedBudget());
        p.setEstimatedValue(r.estimatedValue());
        p.setNextContactAt(r.nextContactAt());
        p.setComments(blank(r.comments()));
        var ids = new HashSet<Long>();
        for (InterestDto i : r.interests()) {
            if (!ids.add(i.productId())) {
                throw new BusinessException("Un producto aparece dos veces en los intereses");
            }
            Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = :p", Map.of("p", i.productId()), Integer.class);
            if (ok == null || ok == 0) {
                throw new BusinessException("Producto de interés no válido");
            }
            if (i.planId() != null) {
                Integer planOk = jdbc.queryForObject("SELECT COUNT(*) FROM plans WHERE id = :id AND product_id = :p",
                        Map.of("id", i.planId(), "p", i.productId()), Integer.class);
                if (planOk == null || planOk == 0) {
                    throw new BusinessException("El plan de interés no corresponde al producto");
                }
            }
        }
        p.getInterests().clear();
        r.interests().forEach(i -> p.getInterests().add(new ProspectInterest(i.productId(), i.planId())));
    }

    @Transactional
    public ProspectDetail changeStage(Long id, StageRequest r) {
        Prospect p = visibleForUpdate(id);
        ProspectStage from = p.getStage();
        if (!from.canMoveTo(r.stage())) {
            throw new BusinessException("No se puede pasar de " + label(from) + " a " + label(r.stage()));
        }
        if (r.stage() == ProspectStage.LOST && (r.lostReason() == null || r.lostReason().isBlank())) {
            throw new BusinessException("Indica el motivo de pérdida");
        }
        p.setStage(r.stage());
        p.setStageChangedAt(clock.instant());
        p.setProbability(r.stage().probability());
        if (r.stage() == ProspectStage.LOST) {
            p.setLostReason(r.lostReason());
            p.setLostDetail(blank(r.lostDetail()));
        } else if (!r.stage().isOpen() || from == ProspectStage.LOST || from == ProspectStage.DISCARDED) {
            if (r.stage() != ProspectStage.DISCARDED) {
                p.setLostReason(null);
                p.setLostDetail(null);
            }
        }
        activities.save(SalesActivity.builder().prospectId(id).type("STAGE_CHANGE").subject(label(from) + " → " + label(r.stage()))
                .body(r.stage() == ProspectStage.LOST ? r.lostDetail() : null).completedAt(clock.instant())
                .userId(SecurityUtils.currentUserId()).build());
        audit.record(AuditEntry.success("prospects", "PROSPECT_STAGE_CHANGED", "Prospect", id)
                .before(Map.of("stage", from.name())).after(Map.of("stage", r.stage().name())).build());
        return get(id);
    }

    @Transactional
    public ActivityResponse addActivity(Long prospectId, Long customerId, ActivityRequest r) {
        if (prospectId != null) {
            visible(prospectId);
        } else {
            customers.require(customerId);
        }
        Instant now = clock.instant();
        SalesActivity a = activities.save(SalesActivity.builder().prospectId(prospectId).customerId(customerId).type(r.type())
                .subject(r.subject().trim()).body(blank(r.body())).scheduledAt(r.scheduledAt())
                .completedAt(r.completed() || r.scheduledAt() == null ? now : null).outcome(blank(r.outcome()))
                .userId(SecurityUtils.currentUserId()).build());
        if (prospectId != null && r.scheduledAt() != null && r.scheduledAt().isAfter(now)) {
            prospects.findById(prospectId).ifPresent(p -> {
                if (p.getNextContactAt() == null || r.scheduledAt().isBefore(p.getNextContactAt()) || p.getNextContactAt().isBefore(now)) {
                    p.setNextContactAt(r.scheduledAt());
                }
            });
        }
        return activitiesOf(prospectId, customerId).stream().filter(x -> x.id().equals(a.getId())).findFirst().orElseThrow();
    }

    @Transactional
    public ActivityResponse completeActivity(Long activityId, String outcome) {
        SalesActivity a = activities.findById(activityId).orElseThrow(() -> new NotFoundException("Actividad no encontrada"));
        if (a.getProspectId() != null) {
            visible(a.getProspectId());
        }
        if (a.getCompletedAt() != null) {
            throw new BusinessException("La actividad ya está completada");
        }
        a.setCompletedAt(clock.instant());
        a.setOutcome(blank(outcome));
        return activitiesOf(a.getProspectId(), a.getCustomerId()).stream().filter(x -> x.id().equals(activityId)).findFirst().orElseThrow();
    }

    /** Agenda comercial: actividades programadas pendientes (propias o de todos con read_all). */
    @Transactional(readOnly = true)
    public List<ActivityResponse> upcoming(Instant until) {
        MapSqlParameterSource params = new MapSqlParameterSource("until", Timestamp.from(until)).addValue("me", SecurityUtils.currentUserId());
        String scope = seesAll() ? "" : " AND (a.user_id = :me OR p.owner_user_id = :me)";
        return jdbc.query(ACTIVITY_SELECT + " WHERE a.completed_at IS NULL AND a.scheduled_at IS NOT NULL AND a.scheduled_at <= :until"
                + scope + " ORDER BY a.scheduled_at LIMIT 200", params, (rs, i) -> activity(rs));
    }

    private static final String ACTIVITY_SELECT = """
            SELECT a.id, a.prospect_id, a.customer_id, a.type, a.subject, a.body, a.scheduled_at, a.completed_at, a.outcome, a.user_id,
                   u.first_name || ' ' || u.last_name AS user_name, a.created_at, COALESCE(p.business_name, c.trade_name) AS target
            FROM sales_activities a LEFT JOIN users u ON u.id = a.user_id LEFT JOIN prospects p ON p.id = a.prospect_id
            LEFT JOIN customers c ON c.id = a.customer_id""";

    List<ActivityResponse> activitiesOf(Long prospectId, Long customerId) {
        MapSqlParameterSource params = new MapSqlParameterSource("p", prospectId).addValue("c", customerId);
        String where = prospectId != null ? " WHERE a.prospect_id = :p" : " WHERE a.customer_id = :c";
        return jdbc.query(ACTIVITY_SELECT + where + " ORDER BY COALESCE(a.scheduled_at, a.created_at) DESC LIMIT 300", params, (rs, i) -> activity(rs));
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> customerActivities(Long customerId) {
        customers.require(customerId);
        return activitiesOf(null, customerId);
    }

    private static ActivityResponse activity(ResultSet rs) throws SQLException {
        Timestamp s = rs.getTimestamp("scheduled_at");
        Timestamp c = rs.getTimestamp("completed_at");
        return new ActivityResponse(rs.getLong("id"), (Long) rs.getObject("prospect_id"), (Long) rs.getObject("customer_id"), rs.getString("type"),
                rs.getString("subject"), rs.getString("body"), s == null ? null : s.toInstant(), c == null ? null : c.toInstant(),
                rs.getString("outcome"), (Long) rs.getObject("user_id"), rs.getString("user_name"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("target"));
    }

    Prospect visible(Long id) {
        Prospect p = prospects.findWithInterestsById(id).orElseThrow(() -> new NotFoundException("Prospecto no encontrado"));
        assertVisible(p);
        return p;
    }

    Prospect visibleForUpdate(Long id) {
        Prospect p = prospects.lockById(id).orElseThrow(() -> new NotFoundException("Prospecto no encontrado"));
        assertVisible(p);
        return p;
    }

    private static void assertVisible(Prospect p) {
        if (!seesAll() && !Objects.equals(p.getOwnerUserId(), SecurityUtils.requirePrincipal().userId())) {
            throw new NotFoundException("Prospecto no encontrado");
        }
    }

    private String userName(Long id) {
        return jdbc.query("SELECT first_name || ' ' || last_name FROM users WHERE id = :id", Map.of("id", id), (rs, i) -> rs.getString(1))
                .stream().findFirst().orElse(null);
    }

    public static String label(ProspectStage s) {
        return switch (s) {
            case NEW -> "Nuevo";
            case CONTACTED -> "Contactado";
            case QUALIFIED -> "Calificado";
            case DEMO_SCHEDULED -> "Demostración programada";
            case DEMO_DONE -> "Demostración realizada";
            case QUOTE_SENT -> "Cotización enviada";
            case NEGOTIATION -> "Negociación";
            case WON -> "Ganado";
            case LOST -> "Perdido";
            case DISCARDED -> "Descartado";
        };
    }

    static Duration staleAfter(SettingsService settings) {
        return Duration.ofDays(settings.integer(Setting.PROSPECT_STALE_DAYS));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

