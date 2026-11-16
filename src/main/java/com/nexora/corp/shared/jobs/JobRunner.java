package com.nexora.corp.shared.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ejecuta una tarea programada de forma segura con varias instancias y reinicios:
 * <ul>
 *   <li>candado de sesión de PostgreSQL ({@code pg_try_advisory_lock}): si otra instancia ya la corre, esta se omite;</li>
 *   <li>cada elemento se procesa en su propia transacción: un error no detiene ni revierte a los demás;</li>
 *   <li>los elementos son idempotentes (restricciones únicas en BD), así repetir la tarea tras un fallo no duplica nada;</li>
 *   <li>cada ejecución queda en {@code job_runs} con procesados, fallidos y detalle.</li>
 * </ul>
 */
@Slf4j
@Component
public class JobRunner {

    public record Result(String job, String status, int processed, int failed) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JobRunner(DataSource dataSource, JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result run(String job, LocalDate businessDate, Supplier<List<Long>> items, Consumer<Long> perItem) {
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection, job)) {
                jdbc.update("INSERT INTO job_runs (job, business_date, status, finished_at, detail) VALUES (?, ?, 'SKIPPED', now(), ?)",
                        job, businessDate, "Otra instancia está ejecutando la tarea");
                return new Result(job, "SKIPPED", 0, 0);
            }
            try {
                return execute(job, businessDate, items, perItem);
            } finally {
                unlock(lockConnection, job);
            }
        } catch (java.sql.SQLException e) {
            log.error("No se pudo obtener el candado de la tarea {}", job, e);
            return new Result(job, "FAILED", 0, 0);
        }
    }

    private Result execute(String job, LocalDate businessDate, Supplier<List<Long>> items, Consumer<Long> perItem) {
        Long runId = jdbc.queryForObject("INSERT INTO job_runs (job, business_date, status) VALUES (?, ?, 'RUNNING') RETURNING id",
                Long.class, job, businessDate);
        int processed = 0;
        int failed = 0;
        StringBuilder errors = new StringBuilder();
        List<Long> ids;
        try {
            ids = tx.execute(s -> items.get());
        } catch (RuntimeException e) {
            log.error("Tarea {}: no se pudieron obtener los elementos", job, e);
            finish(runId, "FAILED", 0, 0, e.getMessage());
            return new Result(job, "FAILED", 0, 0);
        }
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                tx.executeWithoutResult(s -> perItem.accept(id));
                processed++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Tarea {}: falló el elemento {}: {}", job, id, e.getMessage());
                if (errors.length() < 800) {
                    errors.append('#').append(id).append(": ").append(e.getMessage()).append("; ");
                }
            }
        }
        String status = failed == 0 ? "SUCCESS" : processed > 0 ? "PARTIAL" : "FAILED";
        finish(runId, status, processed, failed, errors.isEmpty() ? null : errors.toString());
        if (processed > 0 || failed > 0) {
            log.info("Tarea {} ({}): {} procesados, {} fallidos", job, businessDate, processed, failed);
        }
        return new Result(job, status, processed, failed);
    }

    private void finish(Long runId, String status, int processed, int failed, String detail) {
        jdbc.update("UPDATE job_runs SET status = ?, processed = ?, failed = ?, finished_at = now(), detail = ? WHERE id = ?",
                status, processed, failed, detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)), runId);
    }

    private static boolean tryLock(Connection c, String job) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, "nexora-job:" + job);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c, String job) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, "nexora-job:" + job);
            ps.execute();
        } catch (java.sql.SQLException e) {
            log.warn("No se pudo liberar el candado de {}: {}", job, e.getMessage());
        }
    }
}

