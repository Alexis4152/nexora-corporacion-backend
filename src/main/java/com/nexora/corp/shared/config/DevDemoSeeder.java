package com.nexora.corp.shared.config;

import com.nexora.corp.billing.BillingDtos.CollectionActionRequest;
import com.nexora.corp.billing.BillingDtos.PaymentRequest;
import com.nexora.corp.billing.CollectionsService;
import com.nexora.corp.billing.PaymentService;
import com.nexora.corp.catalog.CatalogDtos.CouponRequest;
import com.nexora.corp.catalog.CatalogDtos.PlanRequest;
import com.nexora.corp.catalog.CatalogDtos.PriceRequest;
import com.nexora.corp.catalog.CatalogService;
import com.nexora.corp.crm.ConversionService;
import com.nexora.corp.crm.CrmDtos;
import com.nexora.corp.crm.ProspectService;
import com.nexora.corp.crm.ProspectStage;
import com.nexora.corp.customers.CustomerDtos.CustomerRequest;
import com.nexora.corp.customers.CustomerService;
import com.nexora.corp.expenses.ExpenseService;
import com.nexora.corp.identity.Role;
import com.nexora.corp.identity.RoleRepository;
import com.nexora.corp.identity.User;
import com.nexora.corp.identity.UserRepository;
import com.nexora.corp.quotes.QuoteService;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.domain.BillingPeriod;
import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.PermissionResolver;
import com.nexora.corp.shared.util.Validation;
import com.nexora.corp.subscriptions.SubscriptionDtos;
import com.nexora.corp.subscriptions.SubscriptionJobs;
import com.nexora.corp.subscriptions.SubscriptionService;
import com.nexora.corp.support.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * SOLO perfil dev con {@code APP_DEMO_DATA=true}: crea usuarios por rol, planes y precios de ejemplo y SIMULA 14 meses
 * de operación día por día usando los servicios reales (no inserta resultados "a mano"): prospectos que avanzan en el
 * embudo, conversiones, cargos por la tarea programada, pagos puntuales, tardíos y morosos, upgrades con prorrateo,
 * cancelaciones, suspensiones, tickets, gastos y gestiones de cobranza. Todo es ficticio y se identifica como demo.
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class DevDemoSeeder implements ApplicationRunner {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final String[] FIRST = {"Ana", "Carlos", "María", "José", "Lucía", "Fernando", "Gabriela", "Miguel", "Valeria", "Ricardo",
            "Daniela", "Alejandro", "Paola", "Héctor", "Mariana", "Eduardo", "Karla", "Raúl", "Fernanda", "Óscar", "Adriana", "Iván", "Claudia",
            "Sergio", "Renata", "Arturo", "Ximena", "Jorge", "Itzel", "Rodrigo"};
    private static final String[] LAST = {"García", "Martínez", "López", "Hernández", "González", "Pérez", "Rodríguez", "Sánchez", "Ramírez",
            "Flores", "Torres", "Rivera", "Gómez", "Díaz", "Cruz", "Morales", "Reyes", "Ortiz", "Gutiérrez", "Chávez", "Ruiz", "Mendoza",
            "Aguilar", "Vargas", "Castillo", "Jiménez", "Romero", "Herrera", "Medina", "Navarro"};
    private static final String[][] CITIES = {{"Guadalajara", "Jalisco"}, {"Monterrey", "Nuevo León"}, {"Ciudad de México", "Ciudad de México"},
            {"Puebla", "Puebla"}, {"Querétaro", "Querétaro"}, {"León", "Guanajuato"}, {"Mérida", "Yucatán"}, {"Cancún", "Quintana Roo"},
            {"Aguascalientes", "Aguascalientes"}, {"Morelia", "Michoacán"}, {"Oaxaca", "Oaxaca"}, {"Tijuana", "Baja California"},
            {"San Luis Potosí", "San Luis Potosí"}, {"Veracruz", "Veracruz"}, {"Chihuahua", "Chihuahua"}, {"Toluca", "Estado de México"}};
    private static final Map<String, String[]> BUSINESSES = Map.of(
            "POS", new String[]{"Abarrotes La Esperanza", "Ferretería El Tornillo", "Papelería Arcoíris", "Minisúper Don Chuy", "Farmacia San Rafael",
                    "Boutique Lucía", "Zapatería El Paso Firme", "Tlapalería Hermanos Ruiz", "Vinos y Licores La Cava", "Refaccionaria Express",
                    "Mueblería Confort", "Óptica Visión Clara", "Dulcería La Piñata", "Tienda Naturista Vida Verde"},
            "HOTEL", new String[]{"Hotel Boutique Casa Azul", "Posada del Sol", "Hotel Real de Minas", "Hostal Los Arcos", "Hotel Playa Esmeralda",
                    "Gran Hotel Colonial", "Cabañas Bosque Encantado", "Hotel Ejecutivo Plaza", "Villas del Mar", "Hacienda Santa Clara"},
            "CITAS", new String[]{"Consultorio Dental Sonrisa", "Clínica Veterinaria Patitas", "Estética Glamour", "Barbería El Bigote",
                    "Fisioterapia Movimiento", "Spa Serenidad", "Psicología Integral Mente Sana", "Nutrióloga Balance", "Clínica Dermatológica Piel",
                    "Laboratorio Clínico Análisis"},
            "RESTAURANTES", new String[]{"Taquería El Güero", "Restaurante La Hacienda", "Cafetería Aroma", "Mariscos El Pescador", "Pizzería Napoli",
                    "Fonda Doña Mary", "Sushi Zen", "Cocina Económica La Abuela", "Hamburguesas El Rancho", "Panadería La Espiga"},
            "TAXIS", new String[]{"Transportes Ejecutivos del Bajío", "Taxis Seguros Metropolitanos", "Radio Taxi Express", "Sitio 25 Centro",
                    "Movilidad Turística del Caribe", "Flotilla Ágil", "Taxis Aeropuerto Plus"},
            "GYM", new String[]{"Gimnasio Titán", "CrossFit Furia", "Yoga Shanti Studio", "Box Club Campeones", "Fitness Center Energía",
                    "Spinning Pulse", "Gimnasio Olimpo"},
            "ECOMMERCE", new String[]{"Moda Urbana Online", "Artesanías de Oaxaca", "TecnoShop MX", "Café de Altura Chiapas", "Joyería Plata Fina",
                    "Mascotas Felices Store", "Hogar y Decoración Lumen"});
    private static final Map<String, int[]> PRICES = Map.of("POS", new int[]{499, 899, 1499}, "HOTEL", new int[]{899, 1599, 2899},
            "CITAS", new int[]{349, 649, 1199}, "RESTAURANTES", new int[]{599, 1099, 1899}, "TAXIS", new int[]{449, 849, 1499},
            "GYM", new int[]{399, 749, 1299}, "ECOMMERCE", new int[]{549, 999, 1799});

    private final AppProperties props;
    private final SimulatableClock clock;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final PermissionResolver permissionResolver;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final ProspectService prospects;
    private final ConversionService conversion;
    private final SubscriptionService subscriptions;
    private final SubscriptionJobs jobs;
    private final PaymentService payments;
    private final CollectionsService collections;
    private final ExpenseService expenses;
    private final TicketService tickets;
    private final QuoteService quotes;
    private final SettingsService settings;

    private final Random rnd = new Random(20261018L);
    private final Map<String, Long> userIds = new HashMap<>();
    private final Map<String, Long> productIds = new LinkedHashMap<>();
    private final Map<Long, List<Long>> plansByProduct = new HashMap<>();
    private final Map<Long, String> profile = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private int failures;
    private int refSeq = 1000;

    private record OpenProspect(Long id, Long productId, LocalDate nextStep) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.bootstrap().demoData()) {
            return;
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        String password = props.bootstrap().demoPassword();
        if (password == null || Validation.passwordProblem(password, null) != null) {
            log.warn("APP_DEMO_PASSWORD no cumple la política; no se generan datos de demostración");
            return;
        }
        long started = System.currentTimeMillis();
        try {
            seedUsers(password);
            asUser("superadmin");
            seedCatalog();
            simulate();
        } finally {
            clock.reset();
            SecurityContextHolder.clearContext();
        }
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_deliveries");
        log.info("Datos de demostración generados en {} s ({} operaciones simuladas fallaron por reglas de negocio)",
                (System.currentTimeMillis() - started) / 1000, failures);
    }

    // ── Usuarios y catálogo ──────────────────────────────────────────────────────────────

    private void seedUsers(String password) {
        Long superId = jdbc.queryForObject("SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id "
                + "WHERE r.code = 'SUPER_ADMIN' ORDER BY u.id LIMIT 1", Long.class);
        userIds.put("superadmin", superId);
        Object[][] demo = {{"admin", "admin@nexora.mx", "Daniela", "Ortiz", "Dirección de operaciones", "ADMINISTRADOR"},
                {"finanzas", "finanzas@nexora.mx", "Roberto", "Méndez", "Contralor", "FINANZAS"},
                {"ventas", "ventas@nexora.mx", "Sofía", "Ramírez", "Ejecutiva comercial", "VENTAS"},
                {"ventas2", "jorge.castillo@nexora.mx", "Jorge", "Castillo", "Ejecutivo comercial", "VENTAS"},
                {"soporte", "soporte@nexora.mx", "Luis", "Hernández", "Líder de soporte", "SOPORTE"},
                {"auditor", "auditor@nexora.mx", "Patricia", "Vega", "Auditoría interna", "AUDITOR"}};
        for (Object[] d : demo) {
            Role role = roles.findByCode((String) d[5]).orElseThrow();
            User u = users.save(User.builder().email((String) d[1]).firstName((String) d[2]).lastName((String) d[3]).jobTitle((String) d[4])
                    .passwordHash(passwordEncoder.encode(password)).roles(new HashSet<>(List.of(role))).build());
            userIds.put((String) d[0], u.getId());
        }
        // Sesión técnica para que las operaciones sensibles simuladas pasen la verificación de reautenticación.
        UUID session = UUID.randomUUID();
        for (Long id : userIds.values()) {
            UUID s = id.equals(superId) ? session : UUID.randomUUID();
            jdbc.update("INSERT INTO user_sessions (id, user_id, expires_at, revoked_at, revoked_reason, reauth_at) VALUES (?, ?, now() + interval '1 hour', now(), 'DEMO_SEED', now())",
                    s, id);
        }
    }

    private void seedCatalog() {
        List<Map<String, Object>> products = jdbc.queryForList("SELECT id, code FROM products ORDER BY sort_order");
        String[][] tiers = {{"BASICO", "Básico"}, {"PROFESIONAL", "Profesional"}, {"EMPRESARIAL", "Empresarial"}};
        String[][] features = {{"1 sucursal", "Hasta 3 usuarios", "Reportes básicos", "Soporte por correo"},
                {"Hasta 3 sucursales", "Hasta 10 usuarios", "Reportes avanzados", "Soporte prioritario", "Integraciones"},
                {"Sucursales ilimitadas", "Usuarios ilimitados", "BI y exportaciones", "Gerente de cuenta", "SLA 99.9 %", "API"}};
        for (Map<String, Object> p : products) {
            String code = (String) p.get("code");
            Long pid = ((Number) p.get("id")).longValue();
            productIds.put(code, pid);
            int[] prices = PRICES.get(code);
            List<Long> plans = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                BigDecimal monthly = BigDecimal.valueOf(prices[i]);
                var plan = catalog.createPlan(new PlanRequest(pid, tiers[i][0], tiers[i][1], "Plan " + tiers[i][1] + " de " + code,
                        List.of(features[i]), Map.of(), i == 0 ? Integer.valueOf(3) : i == 1 ? Integer.valueOf(10) : null, i == 0 ? Integer.valueOf(1) : i == 1 ? Integer.valueOf(3) : null, 14, i == 1, false, true,
                        null, null, i, List.of(new PriceRequest(BillingPeriod.MONTHLY, monthly, false),
                        new PriceRequest(BillingPeriod.QUARTERLY, monthly.multiply(BigDecimal.valueOf(3)).multiply(new BigDecimal("0.95")).setScale(0, RoundingMode.HALF_UP), false),
                        new PriceRequest(BillingPeriod.ANNUAL, monthly.multiply(BigDecimal.TEN), false))));
                plans.add(plan.id());
            }
            catalog.createPlan(new PlanRequest(pid, "PERSONALIZADO", "Personalizado", "Condiciones negociadas por cliente", List.of("A la medida"),
                    Map.of(), null, null, null, false, true, true, null, null, 9, List.of()));
            plansByProduct.put(pid, plans);
        }
        // Taxis suspende automáticamente al terminar la gracia (demuestra la política explícita por producto).
        jdbc.update("UPDATE products SET suspension_policy = 'AUTO_AFTER_GRACE' WHERE code = 'TAXIS'");
        jdbc.update("UPDATE products SET system_url = 'http://localhost:5180' WHERE code = 'HOTEL'");
        catalog.createCoupon(new CouponRequest("BIENVENIDA20", "20 % los primeros 3 meses", "PERCENT", new BigDecimal("20"), 3, null, null, null, null));
        catalog.createCoupon(new CouponRequest("HOTEL500", "$500 de descuento en el primer pago de HOTEL", "AMOUNT", new BigDecimal("500"), 1,
                productIds.get("HOTEL"), null, null, 200));
    }

    // ── Simulación ───────────────────────────────────────────────────────────────────────

    private void simulate() {
        LocalDate today = LocalDate.now(MX);
        LocalDate start = today.minusMonths(14).withDayOfMonth(1);
        List<OpenProspect> open = new ArrayList<>();
        Map<Long, LocalDate> trialDecision = new HashMap<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            final LocalDate day = d;
            clock.set(day.atTime(11, rnd.nextInt(50)).atZone(MX).toInstant());
            boolean early = day.isBefore(start.plusMonths(2));
            // Prospectos nuevos (más al inicio para arrancar la base de clientes).
            int newProspects = early ? 2 + rnd.nextInt(3) : rnd.nextInt(10) < 6 ? 1 + rnd.nextInt(2) : 0;
            for (int i = 0; i < newProspects; i++) {
                safe(() -> open.add(newProspect(day)));
            }
            advanceProspects(open, day);
            if (rnd.nextInt(100) < (early ? 25 : 20)) {
                safe(() -> directTrial(day, trialDecision));
            }
            decideTrials(trialDecision, day);
            customerBehavior(day);
            if (day.getDayOfMonth() == 1) {
                safe(() -> monthlyExpenses(day));
                safe(() -> reconcile(day));
            }
            if (rnd.nextInt(100) < 55) {
                safe(() -> newTicket(day));
            }
            safe(() -> progressTickets(day));
            asUser("superadmin");
            jobs.runCycle();
            asUser("finanzas");
            safe(() -> pay(day));
            safe(() -> collectionWork(day));
        }
        asUser("superadmin");
        jobs.runCycle();
    }

    private OpenProspect newProspect(LocalDate day) {
        String owner = rnd.nextBoolean() ? "ventas" : "ventas2";
        asUser(owner);
        String code = pick(new ArrayList<>(productIds.keySet()));
        String business = uniqueName(code);
        String[] city = CITIES[rnd.nextInt(CITIES.length)];
        String contact = pick(FIRST) + " " + pick(LAST);
        Long productId = productIds.get(code);
        List<CrmDtos.InterestDto> interests = new ArrayList<>(List.of(new CrmDtos.InterestDto(productId, plansByProduct.get(productId).get(rnd.nextInt(3)))));
        if (rnd.nextInt(100) < 15) {
            String other = pick(new ArrayList<>(productIds.keySet()));
            if (!other.equals(code)) {
                interests.add(new CrmDtos.InterestDto(productIds.get(other), null));
            }
        }
        String source = pick(new String[]{"WEBSITE", "REFERRAL", "SOCIAL", "ADS", "EVENT", "COLD_CALL", "WHATSAPP", "PARTNER"});
        BigDecimal value = BigDecimal.valueOf(PRICES.get(code)[rnd.nextInt(3)] * 12L);
        var d = prospects.create(new CrmDtos.ProspectRequest(contact, business, phone(), null, slug(business) + "@clientes-demo.mx", city[0], city[1], "MX",
                industry(code), source, userIds.get(owner), value.multiply(new BigDecimal("1.2")), value, null,
                clock.instant().plusSeconds(86_400L * (1 + rnd.nextInt(5))), "Prospecto de demostración", interests));
        jdbc.update("UPDATE prospects SET created_at = ? WHERE id = ?", Timestamp.from(clock.instant()), d.id());
        return new OpenProspect(d.id(), productId, day.plusDays(1 + rnd.nextInt(4)));
    }

    private static final ProspectStage[] FLOW = {ProspectStage.NEW, ProspectStage.CONTACTED, ProspectStage.QUALIFIED, ProspectStage.DEMO_SCHEDULED,
            ProspectStage.DEMO_DONE, ProspectStage.QUOTE_SENT, ProspectStage.NEGOTIATION};

    private void advanceProspects(List<OpenProspect> open, LocalDate day) {
        List<OpenProspect> next = new ArrayList<>();
        for (OpenProspect op : open) {
            if (op.nextStep().isAfter(day)) {
                next.add(op);
                continue;
            }
            try {
                Map<String, Object> row = jdbc.queryForMap("SELECT stage, owner_user_id FROM prospects WHERE id = ?", op.id());
                ProspectStage stage = ProspectStage.valueOf((String) row.get("stage"));
                asUserId(((Number) row.get("owner_user_id")).longValue());
                int idx = java.util.Arrays.asList(FLOW).indexOf(stage);
                int roll = rnd.nextInt(100);
                if (idx < 0) {
                    continue;
                }
                prospects.addActivity(op.id(), null, new CrmDtos.ActivityRequest(pick(new String[]{"CALL", "MESSAGE", "EMAIL", "MEETING"}),
                        pick(new String[]{"Llamada de seguimiento", "Envío de información", "Resolución de dudas", "Reunión con el dueño"}),
                        "Seguimiento comercial", null, true, pick(new String[]{"Interesado", "Pide propuesta", "Lo consultará", "Muy interesado"})));
                jdbc.update("UPDATE sales_activities SET created_at = ?, completed_at = ? WHERE id = (SELECT MAX(id) FROM sales_activities WHERE prospect_id = ?)",
                        Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), op.id());
                if (idx >= 2 && roll < 12) {
                    prospects.changeStage(op.id(), new CrmDtos.StageRequest(ProspectStage.LOST, pick(new String[]{"PRICE", "COMPETITOR", "NO_BUDGET",
                            "NO_RESPONSE", "TIMING", "FEATURES"}), "Decidió no continuar"));
                    continue;
                }
                if (idx < 2 && roll < 8) {
                    prospects.changeStage(op.id(), new CrmDtos.StageRequest(ProspectStage.DISCARDED, null, null));
                    continue;
                }
                if (stage == ProspectStage.NEGOTIATION || (idx >= 4 && roll > 88)) {
                    asUser("admin");
                    convert(op, day);
                    continue;
                }
                ProspectStage target = FLOW[idx + 1];
                prospects.changeStage(op.id(), new CrmDtos.StageRequest(target, null, null));
                if (target == ProspectStage.DEMO_SCHEDULED) {
                    prospects.addActivity(op.id(), null, new CrmDtos.ActivityRequest("DEMO", "Demostración del sistema", null,
                            day.plusDays(2).atTime(10 + rnd.nextInt(7), 0).atZone(MX).toInstant(), false, null));
                }
                if (target == ProspectStage.QUOTE_SENT) {
                    quoteFor(op);
                }
                next.add(new OpenProspect(op.id(), op.productId(), day.plusDays(2 + rnd.nextInt(6))));
            } catch (RuntimeException e) {
                fail(e);
            }
        }
        open.clear();
        open.addAll(next);
    }

    private void quoteFor(OpenProspect op) {
        Long planId = plansByProduct.get(op.productId()).get(1);
        Map<String, Object> plan = jdbc.queryForMap("SELECT pl.name, p.name AS product, pp.amount FROM plans pl JOIN products p ON p.id = pl.product_id "
                + "JOIN plan_prices pp ON pp.plan_id = pl.id AND pp.billing_period = 'ANNUAL' AND pp.valid_to IS NULL WHERE pl.id = ?", planId);
        var q = quotes.create(new QuoteService.QuoteRequest(null, op.id(), "Propuesta " + plan.get("product") + " · Plan " + plan.get("name"),
                settings.today().plusDays(15), "Precios sin IVA. Incluye implementación y capacitación en línea.", BigDecimal.ZERO,
                List.of(new QuoteService.ItemRequest(op.productId(), planId, BillingPeriod.ANNUAL, plan.get("product") + " · Plan " + plan.get("name"), 1,
                        (BigDecimal) plan.get("amount"), BigDecimal.ZERO), new QuoteService.ItemRequest(null, null, null, "Implementación y migración de datos",
                        1, new BigDecimal("1500"), new BigDecimal("1500")))));
        quotes.setStatus(q.quote().id(), "SENT");
    }

    private void convert(OpenProspect op, LocalDate day) {
        Map<String, Object> p = jdbc.queryForMap("SELECT * FROM prospects WHERE id = ?", op.id());
        List<Long> plans = plansByProduct.get(op.productId());
        Long planId = plans.get(weightedTier());
        BillingPeriod period = rnd.nextInt(100) < 72 ? BillingPeriod.MONTHLY : rnd.nextBoolean() ? BillingPeriod.ANNUAL : BillingPeriod.QUARTERLY;
        boolean trial = rnd.nextInt(100) < 20;
        String coupon = !trial && rnd.nextInt(100) < 15 ? "BIENVENIDA20" : null;
        String business = (String) p.get("business_name");
        String[] city = {(String) p.get("city"), (String) p.get("state")};
        boolean moral = rnd.nextInt(100) < 55;
        CustomerRequest customer = new CustomerRequest(moral ? "MORAL" : "FISICA", business, moral ? business + " S.A. de C.V." : null,
                (String) p.get("contact_name"), rfc(business, moral), moral ? "601" : "612", String.format("%05d", 20000 + rnd.nextInt(70000)),
                (String) p.get("email"), (String) p.get("phone"), null, "Av. " + pick(LAST) + " " + (100 + rnd.nextInt(900)), city[0], city[1], "MX",
                (String) p.get("industry"), ((Number) p.get("owner_user_id")).longValue(), null, day, null);
        BigDecimal firstAmount = null;
        if (!trial) {
            BigDecimal price = jdbc.queryForObject("SELECT amount FROM plan_prices WHERE plan_id = ? AND billing_period = ? AND valid_to IS NULL",
                    BigDecimal.class, planId, period.name());
            BigDecimal base = coupon != null ? price.multiply(new BigDecimal("0.80")) : price;
            firstAmount = base.multiply(new BigDecimal("1.16")).setScale(2, RoundingMode.HALF_UP);
        }
        var result = conversion.convert(op.id(), new CrmDtos.ConvertRequest(null, customer, op.productId(), planId, period, null, day, trial, trial ? 14 : null,
                coupon, firstAmount == null || rnd.nextInt(100) < 25 ? null
                : new CrmDtos.FirstPayment(firstAmount, pick(new String[]{"SPEI", "TRANSFER", "CARD"}), ref("CONV"), day)));
        assignProfile(result.customerId());
    }

    private void directTrial(LocalDate day, Map<Long, LocalDate> trialDecision) {
        asUser("admin");
        String code = pick(new ArrayList<>(productIds.keySet()));
        String business = uniqueName(code);
        String[] city = CITIES[rnd.nextInt(CITIES.length)];
        var c = customers.create(new CustomerRequest("FISICA", business, null, pick(FIRST) + " " + pick(LAST), null, null, null,
                slug(business) + "@clientes-demo.mx", phone(), null, null, city[0], city[1], "MX", industry(code), userIds.get(rnd.nextBoolean() ? "ventas" : "ventas2"),
                "Alta directa desde el sitio web", day, null));
        Long productId = productIds.get(code);
        var s = subscriptions.create(new SubscriptionDtos.CreateRequest(c.id(), productId, plansByProduct.get(productId).get(rnd.nextInt(2)), BillingPeriod.MONTHLY,
                null, day, true, 14, null, null, null, null, null, null, null, "Prueba desde el sitio web", false, null));
        trialDecision.put(s.getId(), day.plusDays(12));
        assignProfile(c.id());
    }

    private void decideTrials(Map<Long, LocalDate> trialDecision, LocalDate day) {
        asUser("admin");
        trialDecision.entrySet().removeIf(e -> {
            if (e.getValue().isAfter(day)) {
                return false;
            }
            if (rnd.nextInt(100) < 62) {
                safe(() -> subscriptions.convertTrial(e.getKey()));
            }
            return true;
        });
    }

    /** Expansiones, upgrades, degradaciones y cancelaciones voluntarias. */
    private void customerBehavior(LocalDate day) {
        asUser("admin");
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT s.id, s.customer_id, s.product_id, s.plan_id, s.billing_period, s.status FROM subscriptions s
                WHERE s.status IN ('ACTIVE', 'GRACE') AND s.start_date < ?""", day.minusDays(30));
        for (Map<String, Object> s : active) {
            Long id = ((Number) s.get("id")).longValue();
            Long productId = ((Number) s.get("product_id")).longValue();
            Long planId = ((Number) s.get("plan_id")).longValue();
            List<Long> plans = plansByProduct.get(productId);
            int tier = plans.indexOf(planId);
            int roll = rnd.nextInt(10_000);
            if (roll < 8 && tier >= 0 && tier < 2 && "ACTIVE".equals(s.get("status"))) {
                safe(() -> subscriptions.changePlan(id, new SubscriptionDtos.ChangePlanRequest(plans.get(tier + 1),
                        BillingPeriod.valueOf((String) s.get("billing_period")), null, "IMMEDIATE")));
            } else if (roll < 11 && tier > 0) {
                safe(() -> subscriptions.changePlan(id, new SubscriptionDtos.ChangePlanRequest(plans.get(tier - 1),
                        BillingPeriod.valueOf((String) s.get("billing_period")), null, "END_OF_PERIOD")));
            } else if (roll < 16) {
                safe(() -> subscriptions.cancel(id, new SubscriptionDtos.CancelRequest(rnd.nextInt(4) == 0 ? "IMMEDIATE" : "END_OF_PERIOD",
                        pick(new String[]{"PRICE", "MISSING_FEATURES", "COMPETITOR", "CLOSED_BUSINESS", "NOT_USING", "SERVICE_ISSUES"}),
                        "Cancelación de demostración", false)));
            } else if (roll < 23) {
                Long customerId = ((Number) s.get("customer_id")).longValue();
                String other = pick(new ArrayList<>(productIds.keySet()));
                Long otherId = productIds.get(other);
                if (!otherId.equals(productId)) {
                    safe(() -> subscriptions.create(new SubscriptionDtos.CreateRequest(customerId, otherId, plansByProduct.get(otherId).get(rnd.nextInt(2)),
                            BillingPeriod.MONTHLY, null, day, false, null, null, null, null, null, null, null, null, "Venta cruzada", false, null)));
                }
            }
        }
    }

    /** Pagos según el perfil del cliente: puntual, tardío o moroso. */
    private void pay(LocalDate day) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT ch.id, ch.customer_id, ch.subscription_id, ch.due_date, ch.total - ch.paid_amount - ch.adjusted_amount AS balance, ch.status
                FROM charges ch WHERE ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date <= ? ORDER BY ch.due_date""", day.plusDays(3));
        Set<Long> paidToday = new HashSet<>();
        for (Map<String, Object> ch : open) {
            Long customerId = ((Number) ch.get("customer_id")).longValue();
            if (paidToday.contains(customerId)) {
                continue;
            }
            Long chargeId = ((Number) ch.get("id")).longValue();
            LocalDate due = ((java.sql.Date) ch.get("due_date")).toLocalDate();
            String prof = profile.getOrDefault(customerId, "punctual");
            int h = Math.abs((int) (chargeId * 2654435761L % 1000));
            LocalDate payDay = switch (prof) {
                case "late" -> due.plusDays(2 + h % 9);
                case "bad" -> h % 3 == 0 ? due.plusDays(500) : due.plusDays(22 + h % 45);
                default -> due.minusDays(h % 4);
            };
            if (day.isBefore(payDay)) {
                continue;
            }
            BigDecimal balance = (BigDecimal) ch.get("balance");
            BigDecimal amount = "late".equals(prof) && "OPEN".equals(ch.get("status")) && h % 5 == 0
                    ? balance.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP) : balance;
            String method = pick(new String[]{"SPEI", "SPEI", "TRANSFER", "CARD", "DEPOSIT", "CASH"});
            payments.register(new PaymentRequest(customerId, amount, day, method, "CASH".equals(method) ? null : ref(method), null, "AUTO",
                    null, null, !day.isAfter(LocalDate.now(MX).minusDays(3)) || h % 3 != 0), UUID.randomUUID().toString());
            paidToday.add(customerId);
            Long subId = ch.get("subscription_id") == null ? null : ((Number) ch.get("subscription_id")).longValue();
            if (subId != null) {
                String status = jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, subId);
                Integer overdue = jdbc.queryForObject("SELECT COUNT(*) FROM charges WHERE subscription_id = ? AND status IN ('OPEN', 'PARTIAL') AND due_date < ?",
                        Integer.class, subId, day);
                if ("SUSPENDED".equals(status) && overdue != null && overdue == 0) {
                    asUser("admin");
                    safe(() -> subscriptions.reactivate(subId));
                    asUser("finanzas");
                }
            }
        }
    }

    /** Gestiones de cobranza, suspensión manual de morosos y cancelación por falta de pago. */
    private void collectionWork(LocalDate day) {
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT s.id, s.customer_id, s.status, s.grace_days, ? - MIN(ch.due_date) AS days FROM subscriptions s
                JOIN charges ch ON ch.subscription_id = s.id AND ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date < ?
                WHERE s.status IN ('GRACE', 'SUSPENDED') GROUP BY s.id""", day, day)) {
            Long subId = ((Number) r.get("id")).longValue();
            Long customerId = ((Number) r.get("customer_id")).longValue();
            int days = ((Number) r.get("days")).intValue();
            int grace = ((Number) r.get("grace_days")).intValue();
            if (days % 7 == 3) {
                boolean promise = rnd.nextInt(3) == 0;
                safe(() -> collections.addAction(new CollectionActionRequest(customerId, subId, promise ? "PROMISE" : pick(new String[]{"CALL", "EMAIL", "WHATSAPP"}),
                        promise ? "El cliente promete pagar esta semana" : "Se contactó al cliente por su adeudo", promise ? day.plusDays(5) : null,
                        promise ? new BigDecimal("500") : null, day.plusDays(4), userIds.get("finanzas"))));
            }
            if ("GRACE".equals(r.get("status")) && days > grace + 6 && ("bad".equals(profile.get(customerId)) || days > grace + 20)) {
                asUser("admin");
                safe(() -> subscriptions.suspend(subId, "Falta de pago"));
                asUser("finanzas");
            }
            if ("SUSPENDED".equals(r.get("status")) && days > 80) {
                asUser("admin");
                safe(() -> subscriptions.cancel(subId, new SubscriptionDtos.CancelRequest("IMMEDIATE", "NON_PAYMENT", "Cancelada por adeudo prolongado", false)));
                asUser("finanzas");
            }
        }
    }

    private void reconcile(LocalDate day) {
        for (Long id : jdbc.queryForList("SELECT id FROM payments WHERE status = 'CAPTURED' AND received_on < ?", Long.class, day.minusDays(3))) {
            safe(() -> payments.confirm(id));
        }
        for (Long id : jdbc.queryForList("SELECT id FROM payments WHERE status = 'CONFIRMED' AND received_on < ?", Long.class, day.minusDays(10))) {
            safe(() -> payments.reconcile(id));
        }
    }

    private void monthlyExpenses(LocalDate day) {
        asUser("finanzas");
        Map<String, Long> cat = new HashMap<>();
        jdbc.query("SELECT name, id FROM expense_categories", rs -> {
            cat.put(rs.getString(1), rs.getLong(2));
        });
        int month = day.getMonthValue();
        long customersNow = jdbc.queryForObject("SELECT COUNT(*) FROM customer_overview WHERE relationship = 'ACTIVE'", Long.class);
        for (var e : productIds.entrySet()) {
            expense("Servidores " + e.getKey() + " (nube)", 600 + rnd.nextInt(900) + customersNow * 8, day, cat.get("Servidores"), e.getValue(), "AWS México", "MONTHLY");
        }
        expense("Google Workspace", 2350, day, cat.get("Licencias"), null, "Google", "MONTHLY");
        expense("Campañas Meta Ads", 7000 + rnd.nextInt(9000), day, cat.get("Publicidad"), null, "Meta Platforms", "MONTHLY");
        expense("Google Ads", 4000 + rnd.nextInt(6000), day, cat.get("Publicidad"), null, "Google", "MONTHLY");
        expense("Comisiones del equipo comercial", 3000 + rnd.nextInt(5000), day, cat.get("Comisiones de venta"), null, null, "MONTHLY");
        expense("Herramientas de soporte (Zendesk)", 1890, day, cat.get("Soporte"), null, "Zendesk", "MONTHLY");
        expense("Renta de oficina", 18500, day, cat.get("Gastos operativos"), null, "Inmobiliaria Centro", "MONTHLY");
        if (month == 1) {
            expense("Renovación de dominios", 4200, day, cat.get("Dominios"), null, "GoDaddy", "ANNUAL");
        }
    }

    private void expense(String concept, long amount, LocalDate day, Long category, Long productId, String supplier, String periodicity) {
        expenses.create(new ExpenseService.ExpenseRequest(concept, BigDecimal.valueOf(amount), day, category, productId, supplier, periodicity, null, null));
    }

    private void newTicket(LocalDate day) {
        asUser("soporte");
        List<Map<String, Object>> subs = jdbc.queryForList("SELECT customer_id, product_id FROM subscriptions WHERE status IN ('ACTIVE', 'GRACE', 'TRIAL') ORDER BY random() LIMIT 1");
        if (subs.isEmpty()) {
            return;
        }
        String[][] topics = {{"No puedo imprimir tickets", "INCIDENT"}, {"¿Cómo agrego un usuario?", "QUESTION"}, {"Error al cerrar el corte de caja", "BUG"},
                {"Solicito capacitación para nuevo personal", "TRAINING"}, {"Aclaración de mi último cargo", "BILLING"}, {"Quiero agregar una sucursal", "REQUEST"},
                {"El reporte mensual tarda mucho", "INCIDENT"}, {"Cambio de datos fiscales", "REQUEST"}, {"No me llegan los correos de recuperación", "BUG"}};
        String[] t = topics[rnd.nextInt(topics.length)];
        String priority = pick(new String[]{"LOW", "MEDIUM", "MEDIUM", "MEDIUM", "HIGH", "URGENT"});
        var created = tickets.create(new TicketService.TicketRequest(((Number) subs.get(0).get("customer_id")).longValue(),
                ((Number) subs.get(0).get("product_id")).longValue(), t[0], "Reporte del cliente: " + t[0].toLowerCase(Locale.ROOT) + ".", t[1], priority,
                userIds.get("soporte")));
        jdbc.update("UPDATE support_tickets SET created_at = ?, updated_at = ? WHERE id = ?", Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
                created.ticket().id());
    }

    private void progressTickets(LocalDate day) {
        asUser("soporte");
        for (Map<String, Object> t : jdbc.queryForList("SELECT id, status, created_at FROM support_tickets WHERE status NOT IN ('RESOLVED', 'CLOSED')")) {
            Long id = ((Number) t.get("id")).longValue();
            Instant created = ((Timestamp) t.get("created_at")).toInstant();
            long ageDays = java.time.Duration.between(created, clock.instant()).toDays();
            String status = (String) t.get("status");
            Instant now = clock.instant();
            if ("OPEN".equals(status) && ageDays >= 0 && rnd.nextInt(100) < 70) {
                tickets.comment(id, new TicketService.CommentRequest("Hola, ya estamos revisando tu caso."));
                tickets.changeStatus(id, new TicketService.StatusRequest(TicketService.Status.IN_PROGRESS, null));
                jdbc.update("UPDATE support_tickets SET first_response_at = ?, updated_at = ? WHERE id = ?",
                        Timestamp.from(created.plusSeconds(1800L + rnd.nextInt(20_000))), Timestamp.from(now), id);
            } else if ("IN_PROGRESS".equals(status) && ageDays >= 1 && rnd.nextInt(100) < 45) {
                tickets.changeStatus(id, new TicketService.StatusRequest(TicketService.Status.RESOLVED, "Se orientó al cliente y se verificó la solución."));
                jdbc.update("UPDATE support_tickets SET resolved_at = ?, updated_at = ? WHERE id = ?", Timestamp.from(now), Timestamp.from(now), id);
            } else if ("RESOLVED".equals(status) && rnd.nextInt(100) < 30) {
                tickets.changeStatus(id, new TicketService.StatusRequest(TicketService.Status.CLOSED, null));
            }
        }
        jdbc.update("UPDATE support_comments SET created_at = ? WHERE created_at > now() - interval '5 seconds'", Timestamp.from(clock.instant()));
    }

    // ── Apoyo ────────────────────────────────────────────────────────────────────────────

    private void assignProfile(Long customerId) {
        int r = rnd.nextInt(100);
        profile.put(customerId, r < 68 ? "punctual" : r < 93 ? "late" : "bad");
    }

    private int weightedTier() {
        int r = rnd.nextInt(100);
        return r < 45 ? 0 : r < 85 ? 1 : 2;
    }

    private void asUser(String key) {
        asUserId(userIds.get(key));
    }

    private void asUserId(Long id) {
        int version = jdbc.queryForObject("SELECT permissions_version FROM users WHERE id = ?", Integer.class, id);
        var resolved = permissionResolver.resolve(id, version);
        Map<String, Object> u = jdbc.queryForMap("SELECT email, first_name || ' ' || last_name AS name FROM users WHERE id = ?", id);
        UUID session = jdbc.queryForObject("SELECT id FROM user_sessions WHERE user_id = ? AND revoked_reason = 'DEMO_SEED' LIMIT 1", UUID.class, id);
        AuthPrincipal p = new AuthPrincipal(id, session, (String) u.get("email"), (String) u.get("name"), resolved.roles(), resolved.permissions());
        List<SimpleGrantedAuthority> auths = resolved.permissions().stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, auths));
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void fail(RuntimeException e) {
        failures++;
        if (failures <= 8) {
            log.debug("Operación de demo omitida: {}", e.getMessage());
        }
    }

    private String uniqueName(String code) {
        String[] names = BUSINESSES.get(code);
        for (int i = 0; i < 40; i++) {
            String base = names[rnd.nextInt(names.length)];
            String name = i < 10 ? base : base + " " + CITIES[rnd.nextInt(CITIES.length)][0];
            if (usedNames.add(name)) {
                return name;
            }
        }
        String fallback = names[0] + " " + (usedNames.size() + 1);
        usedNames.add(fallback);
        return fallback;
    }

    private String rfc(String name, boolean moral) {
        String letters = slug(name).replace("-", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "") + "XXXX";
        String head = letters.substring(0, moral ? 3 : 4);
        String date = String.format("%02d%02d%02d", 80 + rnd.nextInt(20), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
        String tail = "" + (char) ('A' + rnd.nextInt(26)) + (char) ('A' + rnd.nextInt(26)) + rnd.nextInt(10);
        return head + date + tail;
    }

    private String phone() {
        return "33" + String.format("%08d", rnd.nextInt(100_000_000));
    }

    private String ref(String prefix) {
        return prefix + "-" + LocalDate.now(clock).getYear() + "-" + (refSeq++);
    }

    private static String industry(String code) {
        return switch (code) {
            case "POS" -> "Comercio minorista";
            case "HOTEL" -> "Hotelería";
            case "CITAS" -> "Salud y belleza";
            case "RESTAURANTES" -> "Alimentos y bebidas";
            case "TAXIS" -> "Transporte";
            case "GYM" -> "Deporte y bienestar";
            default -> "Comercio electrónico";
        };
    }

    private static String slug(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private <T> T pick(List<T> list) {
        return list.get(rnd.nextInt(list.size()));
    }

    private String pick(String[] arr) {
        return arr[rnd.nextInt(arr.length)];
    }
}
