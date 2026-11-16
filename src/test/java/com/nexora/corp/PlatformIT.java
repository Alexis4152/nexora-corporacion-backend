package com.nexora.corp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.corp.shared.domain.BillingPeriod;
import com.nexora.corp.shared.security.Totp;
import com.nexora.corp.subscriptions.domain.PeriodMath;
import com.nexora.corp.subscriptions.domain.Pricing;
import com.nexora.corp.support.Api;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de punta a punta contra PostgreSQL real (Testcontainers) y la API HTTP tal como la usa el
 * frontend. Cubren los casos críticos del documento maestro: seguridad, cobranza, MRR, ciclo de vida y auditoría.
 * Cada prueba crea sus propios datos, así no dependen del orden.
 */
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIT {

    static final String ROOT_EMAIL = "root@nexora.test";
    static final String ROOT_PASSWORD = "Prueba-Integral-2026!Q";
    static final String PASSWORD = "Usuario-Pruebas-2026!Z";
    static final ZoneId ZONE = ZoneId.of("America/Mexico_City");

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // Arranque explícito: con ciclo PER_CLASS Spring crea el contexto antes que la extensión de Testcontainers.
        DB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("app.bootstrap.super-admin-email", () -> ROOT_EMAIL);
        r.add("app.bootstrap.super-admin-password", () -> ROOT_PASSWORD);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder encoder;

    Api root;
    long pos;
    long hotel;
    long taxis;
    long posBasic;
    long posPro;
    long hotelPro;
    long taxisBasic;

    @BeforeAll
    void setUp() {
        root = new Api(port);
        assertThat(root.login(ROOT_EMAIL, ROOT_PASSWORD).status()).isEqualTo(200);
        pos = product("POS");
        hotel = product("HOTEL");
        taxis = product("TAXIS");
        posBasic = plan(pos, "BASICO", "499");
        posPro = plan(pos, "PRO", "899");
        hotelPro = plan(hotel, "PRO", "899");
        taxisBasic = plan(taxis, "BASICO", "350");
    }

    // ── Seguridad ──────────────────────────────────────────────────────────────────────────

    @Test
    void sesionConRefreshRotativoYProteccionCsrf() {
        Api api = new Api(port);
        assertThat(api.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(api.login(ROOT_EMAIL, "Contrasena-Incorrecta-1!").status()).isEqualTo(401);
        assertThat(api.login(ROOT_EMAIL, ROOT_PASSWORD).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/auth/me").data().path("email").asText()).isEqualTo(ROOT_EMAIL);

        String first = api.refreshCookie();
        assertThat(first).startsWith("nxc_rt=");
        assertThat(api.postWithoutXhr("/api/v1/auth/refresh").status()).isNotEqualTo(200);

        Api.Res refreshed = api.post("/api/v1/auth/refresh", null);
        assertThat(refreshed.status()).isEqualTo(200);
        api.remember(refreshed);
        assertThat(api.refreshCookie()).isNotEqualTo(first);

        // El refresh anterior ya se consumió: reutilizarlo (robo de cookie) no da sesión.
        Api thief = new Api(port);
        thief.useRefreshCookie(first);
        assertThat(thief.post("/api/v1/auth/refresh", null).status()).isEqualTo(401);
    }

    @Test
    void bloqueoTrasIntentosFallidos() {
        String email = user("FINANZAS");
        Api api = new Api(port);
        for (int i = 0; i < 5; i++) {
            assertThat(api.login(email, "Equivocada-" + i + "-Clave!").status()).isEqualTo(401);
        }
        Api.Res locked = api.login(email, PASSWORD);
        assertThat(locked.status()).isNotEqualTo(200);
        assertThat(locked.body().path("message").asText()).containsIgnoringCase("bloque");
    }

    @Test
    void mfaTotpConCodigoDeUnSoloUso() {
        String email = user("ADMINISTRADOR");
        Api api = new Api(port);
        api.login(email, PASSWORD);
        String secret = api.post("/api/v1/account/mfa/setup", null).data().path("secret").asText();
        Api.Res enabled = api.post("/api/v1/account/mfa/enable", Map.of("code", Totp.codeAt(secret, Instant.now())));
        assertThat(enabled.status()).isEqualTo(200);
        assertThat(enabled.data().path("codes").size()).isGreaterThanOrEqualTo(8);

        Api second = new Api(port);
        Api.Res step1 = second.login(email, PASSWORD);
        assertThat(step1.status()).isEqualTo(200);
        assertThat(step1.data().path("mfaRequired").asBoolean()).isTrue();
        assertThat(step1.data().hasNonNull("accessToken")).isFalse();
        String code = Totp.codeAt(secret, Instant.now().plusSeconds(30));
        Api.Res step2 = second.post("/api/v1/auth/mfa", Map.of("mfaToken", step1.data().path("mfaToken").asText(), "code", code));
        assertThat(step2.status()).isEqualTo(200);
        second.remember(step2);
        assertThat(second.get("/api/v1/auth/me").data().path("mfaEnabled").asBoolean()).isTrue();

        // El mismo código no sirve dos veces (protección contra repetición).
        Api third = new Api(port);
        Api.Res again = third.login(email, PASSWORD);
        assertThat(third.post("/api/v1/auth/mfa", Map.of("mfaToken", again.data().path("mfaToken").asText(), "code", code)).status())
                .isNotEqualTo(200);
    }

    @Test
    void ventasNoVeFinanzasNiDatosFiscales() {
        long customer = customer();
        Api ventas = new Api(port);
        ventas.login(user("VENTAS"), PASSWORD);
        assertThat(ventas.get("/api/v1/metrics/saas").status()).isEqualTo(403);
        assertThat(ventas.post("/api/v1/payments", paymentBody(customer, "100", "SPEI", ref()), Api.key()).status()).isEqualTo(403);
        assertThat(ventas.post("/api/v1/subscriptions/run-cycle", null).status()).isEqualTo(403);

        JsonNode masked = ventas.get("/api/v1/customers/" + customer).data();
        JsonNode full = root.get("/api/v1/customers/" + customer).data();
        assertThat(masked.path("fiscalMasked").asBoolean()).isTrue();
        assertThat(masked.path("rfc").asText()).isNotEqualTo(full.path("rfc").asText());
        assertThat(full.path("fiscalMasked").asBoolean()).isFalse();

        Api auditor = new Api(port);
        auditor.login(user("AUDITOR"), PASSWORD);
        assertThat(auditor.get("/api/v1/customers/" + customer).status()).isEqualTo(200);
        assertThat(auditor.post("/api/v1/customers", customerBody()).status()).isEqualTo(403);
    }

    @Test
    void operacionesSensiblesExigenReautenticacion() {
        long customer = customer();
        long sub = subscribe(customer, pos, posBasic, today(), false);
        Api api = new Api(port);
        api.login(user("FINANZAS"), PASSWORD);
        Api.Res pay = api.post("/api/v1/payments", paymentBody(customer, "578.84", "SPEI", ref()), Api.key());
        assertThat(pay.status()).isEqualTo(200);
        long paymentId = pay.data().path("payment").path("id").asLong();
        assertThat(chargeStatus(sub)).isEqualTo("PAID");

        Api.Res denied = api.post("/api/v1/payments/" + paymentId + "/reverse", Map.of("reason", "Rebotó en el banco"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("REAUTH_REQUIRED");

        assertThat(api.post("/api/v1/auth/reauth", Map.of("password", PASSWORD)).status()).isEqualTo(200);
        Api.Res reversed = api.post("/api/v1/payments/" + paymentId + "/reverse", Map.of("reason", "Rebotó en el banco"));
        assertThat(reversed.status()).isEqualTo(200);
        assertThat(reversed.data().path("payment").path("status").asText()).isEqualTo("REVERSED");
        assertThat(chargeStatus(sub)).isEqualTo("OPEN");
        assertThat(balance(sub)).isEqualByComparingTo("578.84");
        // La aplicación queda revertida con motivo, no borrada.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_allocations WHERE payment_id = ? AND reversed_at IS NOT NULL",
                Integer.class, paymentId)).isEqualTo(1);
    }

    // ── Cobranza ───────────────────────────────────────────────────────────────────────────

    @Test
    void clienteConDosProductosSumaSuMrr() {
        long customer = customer();
        subscribe(customer, pos, posBasic, today(), false);
        subscribe(customer, hotel, hotelPro, today(), false);
        assertThat(jdbc.queryForObject("SELECT SUM(mrr) FROM subscriptions WHERE customer_id = ?", BigDecimal.class, customer))
                .isEqualByComparingTo("1398");
        assertThat(jdbc.queryForObject("SELECT SUM(total) FROM charges WHERE customer_id = ?", BigDecimal.class, customer))
                .isEqualByComparingTo("1621.68");
        // El ledger de eventos cuadra con el MRR vigente.
        assertThat(jdbc.queryForObject("""
                SELECT SUM(mrr_after - mrr_before) FROM subscription_events e JOIN subscriptions s ON s.id = e.subscription_id
                WHERE s.customer_id = ?""", BigDecimal.class, customer)).isEqualByComparingTo("1398");
        Api.Res dup = root.post("/api/v1/subscriptions", subscriptionBody(customer, pos, posBasic, today(), false));
        assertThat(dup.status()).isEqualTo(400);
        assertThat(dup.code()).isEqualTo("DUPLICATE_SUBSCRIPTION");
    }

    @Test
    void pagoParcialIdempotenteYDuplicadoPorReferencia() {
        long customer = customer();
        long sub = subscribe(customer, hotel, hotelPro, today(), false);
        String key = Api.key();
        String reference = ref();
        Api.Res first = root.post("/api/v1/payments", paymentBody(customer, "500", "SPEI", reference), key);
        assertThat(first.status()).isEqualTo(200);
        assertThat(chargeStatus(sub)).isEqualTo("PARTIAL");
        assertThat(balance(sub)).isEqualByComparingTo("542.84");

        // Doble clic / reintento: misma llave → el mismo pago, sin duplicar.
        Api.Res replay = root.post("/api/v1/payments", paymentBody(customer, "500", "SPEI", reference), key);
        assertThat(replay.data().path("payment").path("id").asLong()).isEqualTo(first.data().path("payment").path("id").asLong());
        // Otra captura con la misma referencia bancaria → rechazada.
        assertThat(root.post("/api/v1/payments", paymentBody(customer, "500", "SPEI", reference), Api.key()).status()).isEqualTo(409);
        // Sin llave de idempotencia no se registra.
        assertThat(root.post("/api/v1/payments", paymentBody(customer, "1", "CASH", null), null).status()).isEqualTo(400);

        Api.Res rest = root.post("/api/v1/payments", paymentBody(customer, "600", "TRANSFER", ref()), Api.key());
        assertThat(rest.status()).isEqualTo(200);
        assertThat(chargeStatus(sub)).isEqualTo("PAID");
        assertThat(rest.data().path("payment").path("unapplied").decimalValue()).isEqualByComparingTo("57.16");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE customer_id = ?", Integer.class, customer)).isEqualTo(2);
    }

    @Test
    void pagosConcurrentesConLaMismaReferenciaSoloRegistranUno() throws Exception {
        long customer = customer();
        subscribe(customer, pos, posBasic, today(), false);
        String reference = ref();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return root.post("/api/v1/payments", paymentBody(customer, "578.84", "SPEI", reference), Api.key()).status();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE reference = ?", Integer.class, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(paid_amount) FROM charges WHERE customer_id = ?", BigDecimal.class, customer))
                .isEqualByComparingTo("578.84");
    }

    // ── Ciclo de vida ──────────────────────────────────────────────────────────────────────

    @Test
    void cambioDePlanAMitadDePeriodoProrratea() {
        long customer = customer();
        long sub = subscribe(customer, pos, posBasic, today().minusDays(10), false);
        JsonNode before = root.get("/api/v1/subscriptions/" + sub).data().path("row");
        LocalDate periodStart = LocalDate.parse(before.path("currentPeriodStart").asText());
        LocalDate periodEnd = LocalDate.parse(before.path("currentPeriodEnd").asText());

        Api.Res down = root.post("/api/v1/subscriptions/" + sub + "/change-plan",
                Map.of("planId", posBasic, "billingPeriod", "MONTHLY", "price", "300", "effective", "IMMEDIATE"));
        assertThat(down.status()).isEqualTo(400);

        Api.Res up = root.post("/api/v1/subscriptions/" + sub + "/change-plan",
                Map.of("planId", posPro, "billingPeriod", "MONTHLY", "effective", "IMMEDIATE"));
        assertThat(up.status()).isEqualTo(200);
        BigDecimal expected = Pricing.proration(new BigDecimal("499"), new BigDecimal("899"),
                PeriodMath.remaining(today(), periodEnd), PeriodMath.days(periodStart, periodEnd));
        assertThat(expected.signum()).isPositive();
        assertThat(jdbc.queryForObject("SELECT subtotal FROM charges WHERE subscription_id = ? AND type = 'PRORATION'", BigDecimal.class, sub))
                .isEqualByComparingTo(expected);
        assertThat(up.data().path("row").path("mrr").decimalValue()).isEqualByComparingTo("899");
        assertThat(lastMovement(up.data())).isEqualTo("EXPANSION");
    }

    @Test
    void cancelarDuranteLaPruebaNoGeneraCobrosNiChurn() {
        long customer = customer();
        long sub = subscribe(customer, pos, posBasic, today(), true);
        JsonNode trial = root.get("/api/v1/subscriptions/" + sub).data();
        assertThat(trial.path("row").path("status").asText()).isEqualTo("TRIAL");
        assertThat(trial.path("row").path("mrr").decimalValue()).isZero();

        Api.Res cancelled = root.post("/api/v1/subscriptions/" + sub + "/cancel",
                Map.of("mode", "IMMEDIATE", "reason", "NOT_USING", "waiveOpenBalance", false));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.data().path("row").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charges WHERE subscription_id = ?", Integer.class, sub)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscription_events WHERE subscription_id = ? AND movement = 'CHURN'",
                Integer.class, sub)).isZero();
        // Una prueba nunca convertida no se "reactiva": se convierte a plan pagado a propósito.
        assertThat(root.post("/api/v1/subscriptions/" + sub + "/reactivate", null).status()).isEqualTo(400);
    }

    @Test
    void cancelacionYReactivacionClasificanElMrr() {
        long customer = customer();
        long sub = subscribe(customer, hotel, hotelPro, today(), false);
        Api.Res cancelled = root.post("/api/v1/subscriptions/" + sub + "/cancel",
                Map.of("mode", "IMMEDIATE", "reason", "PRICE", "waiveOpenBalance", false));
        assertThat(cancelled.data().path("row").path("mrr").decimalValue()).isZero();
        assertThat(lastMovement(cancelled.data())).isEqualTo("CHURN");

        Api.Res back = root.post("/api/v1/subscriptions/" + sub + "/reactivate", null);
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.data().path("row").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(back.data().path("row").path("mrr").decimalValue()).isEqualByComparingTo("899");
        assertThat(lastMovement(back.data())).isEqualTo("REACTIVATION");
    }

    @Test
    void suspensionPorAdeudoYPagoPosteriorNoReactivaSolo() {
        jdbc.update("UPDATE products SET suspension_policy = 'AUTO_AFTER_GRACE' WHERE id = ?", taxis);
        long customer = customer();
        Map<String, Object> body = subscriptionBody(customer, taxis, taxisBasic, today().minusDays(40), false);
        body.put("graceDays", 5);
        long sub = root.post("/api/v1/subscriptions", body).data().path("row").path("id").asLong();

        runCycle();
        assertThat(status(sub)).isEqualTo("GRACE");
        runCycle();
        assertThat(status(sub)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT mrr FROM subscriptions WHERE id = ?", BigDecimal.class, sub)).isZero();

        BigDecimal owed = jdbc.queryForObject("SELECT SUM(balance) FROM charge_balances WHERE subscription_id = ? AND status IN ('OPEN','PARTIAL')",
                BigDecimal.class, sub);
        assertThat(root.post("/api/v1/payments", paymentBody(customer, owed.toPlainString(), "SPEI", ref()), Api.key()).status()).isEqualTo(200);
        runCycle();
        // Al corriente pero sigue suspendida: reactivar es una decisión humana.
        assertThat(status(sub)).isEqualTo("SUSPENDED");
        assertThat(root.post("/api/v1/subscriptions/" + sub + "/reactivate", null).status()).isEqualTo(200);
        assertThat(status(sub)).isEqualTo("ACTIVE");
    }

    @Test
    void renovacionDeFinDeMesYTareasIdempotentes() {
        LocalDate start = today().minusMonths(2).withDayOfMonth(1).plusMonths(1).minusDays(1);
        long customer = customer();
        long sub = subscribe(customer, hotel, hotelPro, start, false);

        runCycle();
        int charges = chargeCount(sub);
        runCycle();
        runCycle();
        assertThat(chargeCount(sub)).isEqualTo(charges);

        LocalDate expectedStart = start;
        while (!PeriodMath.periodEnd(expectedStart, BillingPeriod.MONTHLY, start.getDayOfMonth()).isAfter(today().minusDays(1))) {
            expectedStart = PeriodMath.nextStart(expectedStart, BillingPeriod.MONTHLY, start.getDayOfMonth());
        }
        JsonNode row = root.get("/api/v1/subscriptions/" + sub).data().path("row");
        assertThat(LocalDate.parse(row.path("currentPeriodStart").asText())).isEqualTo(expectedStart);
        assertThat(LocalDate.parse(row.path("currentPeriodEnd").asText()))
                .isEqualTo(PeriodMath.periodEnd(expectedStart, BillingPeriod.MONTHLY, start.getDayOfMonth()));
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT period_start) = COUNT(*) FROM charges WHERE subscription_id = ? AND type = 'SUBSCRIPTION'",
                Boolean.class, sub)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_runs WHERE job = 'subscriptions.renewals'", Integer.class)).isGreaterThanOrEqualTo(3);
    }

    // ── CRM ────────────────────────────────────────────────────────────────────────────────

    @Test
    void conversionDeProspectoEsTransaccional() {
        String biz = "Hotel Prueba " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> prospect = new HashMap<>(Map.of("contactName", "Laura Méndez", "businessName", biz, "source", "REFERRAL",
                "interests", List.of(Map.of("productId", hotel))));
        long id = root.post("/api/v1/prospects", prospect).data().path("id").asLong();
        assertThat(root.post("/api/v1/prospects/" + id + "/stage", Map.of("stage", "QUALIFIED")).status()).isEqualTo(200);

        Map<String, Object> customer = customerBody();
        customer.put("tradeName", biz);
        int customersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class);
        Map<String, Object> bad = new HashMap<>(Map.of("customer", customer, "productId", hotel, "planId", posBasic,
                "billingPeriod", "MONTHLY", "startDate", today().toString(), "trial", false));
        assertThat(root.post("/api/v1/prospects/" + id + "/convert", bad).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class)).isEqualTo(customersBefore);

        Map<String, Object> good = new HashMap<>(bad);
        good.put("planId", hotelPro);
        good.put("firstPayment", Map.of("amount", "1042.84", "method", "TRANSFER", "reference", ref()));
        Api.Res won = root.post("/api/v1/prospects/" + id + "/convert", good);
        assertThat(won.status()).isEqualTo(200);
        long sub = won.data().path("subscriptionId").asLong();
        assertThat(won.data().path("paymentId").asLong()).isPositive();
        assertThat(chargeStatus(sub)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT stage FROM prospects WHERE id = ?", String.class, id)).isEqualTo("WON");
        assertThat(jdbc.queryForObject("SELECT source_prospect_id FROM customers WHERE id = ?", Long.class,
                won.data().path("customerId").asLong())).isEqualTo(id);
    }

    // ── Integridad ─────────────────────────────────────────────────────────────────────────

    @Test
    void auditoriaYMovimientosFinancierosSonInmutables() {
        long customer = customer();
        long sub = subscribe(customer, pos, posBasic, today(), false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SUBSCRIPTION_CREATED' AND entity_id = ?",
                Integer.class, String.valueOf(sub))).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET action = 'X'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM charges WHERE subscription_id = ?", sub)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE subscription_events SET mrr_after = 0 WHERE subscription_id = ?", sub))
                .isInstanceOf(DataAccessException.class);
        // Un pago no puede aplicarse por encima del saldo del cargo (CHECK en BD además de la validación de servicio).
        assertThatThrownBy(() -> jdbc.update("UPDATE charges SET paid_amount = total + 1 WHERE subscription_id = ?", sub))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void apiDeLicenciasExigeLlave() {
        Api anon = new Api(port);
        assertThat(anon.get("/api/integration/v1/license?customer=CL-000001").status()).isEqualTo(401);
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────────

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static String ref() {
        return "REF" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
    }

    long product(String code) {
        return jdbc.queryForObject("SELECT id FROM products WHERE code = ?", Long.class, code);
    }

    long plan(long productId, String code, String monthly) {
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("code", code);
        body.put("name", code.charAt(0) + code.substring(1).toLowerCase());
        body.put("trialDays", 14);
        body.put("prices", List.of(Map.of("billingPeriod", "MONTHLY", "amount", monthly, "taxIncluded", false)));
        Api.Res r = root.post("/api/v1/plans", body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data().path("id").asLong();
    }

    Map<String, Object> customerBody() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> m = new HashMap<>();
        m.put("personType", "MORAL");
        m.put("tradeName", "Cliente " + n);
        m.put("legalName", "Cliente " + n + " SA de CV");
        m.put("contactName", "Contacto " + n);
        m.put("rfc", "CLI" + ThreadLocalRandom.current().nextInt(100000, 999999) + "AB" + ThreadLocalRandom.current().nextInt(1, 9));
        m.put("taxRegime", "601");
        m.put("taxZip", "64000");
        m.put("email", "cliente-" + n + "@ejemplo.mx");
        m.put("country", "MX");
        return m;
    }

    long customer() {
        Api.Res r = root.post("/api/v1/customers", customerBody());
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data().path("id").asLong();
    }

    Map<String, Object> subscriptionBody(long customer, long product, long plan, LocalDate start, boolean trial) {
        Map<String, Object> m = new HashMap<>();
        m.put("customerId", customer);
        m.put("productId", product);
        m.put("planId", plan);
        m.put("billingPeriod", "MONTHLY");
        m.put("startDate", start.toString());
        m.put("trial", trial);
        return m;
    }

    long subscribe(long customer, long product, long plan, LocalDate start, boolean trial) {
        Api.Res r = root.post("/api/v1/subscriptions", subscriptionBody(customer, product, plan, start, trial));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data().path("row").path("id").asLong();
    }

    static Map<String, Object> paymentBody(long customer, String amount, String method, String reference) {
        Map<String, Object> m = new HashMap<>();
        m.put("customerId", customer);
        m.put("amount", amount);
        m.put("receivedOn", today().toString());
        m.put("method", method);
        m.put("reference", reference);
        m.put("allocationMode", "AUTO");
        m.put("confirmNow", true);
        return m;
    }

    String user(String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@nexora.test";
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, first_name, last_name) VALUES (?, ?, 'Prueba', ?) RETURNING id""",
                Long.class, email, encoder.encode(PASSWORD), role);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        return email;
    }

    void runCycle() {
        assertThat(root.post("/api/v1/subscriptions/run-cycle", null).status()).isEqualTo(200);
    }

    String status(long sub) {
        return jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, sub);
    }

    String chargeStatus(long sub) {
        return jdbc.queryForObject("SELECT status FROM charges WHERE subscription_id = ? AND type = 'SUBSCRIPTION' ORDER BY id LIMIT 1",
                String.class, sub);
    }

    BigDecimal balance(long sub) {
        return jdbc.queryForObject("SELECT balance FROM charge_balances WHERE subscription_id = ? AND type = 'SUBSCRIPTION' ORDER BY id LIMIT 1",
                BigDecimal.class, sub);
    }

    int chargeCount(long sub) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM charges WHERE subscription_id = ?", Integer.class, sub);
    }

    /** Los eventos llegan del más reciente al más antiguo. */
    static String lastMovement(JsonNode detail) {
        for (JsonNode e : detail.path("events")) {
            String m = e.path("movement").asText("NONE");
            if (!"NONE".equals(m) && !m.isBlank() && !"null".equals(m)) {
                return m;
            }
        }
        return "NONE";
    }
}
