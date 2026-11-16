package com.nexora.corp.shared.jobs;

import com.nexora.corp.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Observabilidad de las tareas programadas: última ejecución de cada una y las ejecuciones recientes con detalle. */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobRunsController {

    private final JdbcTemplate jdbc;

    @GetMapping
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> runs() {
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT DISTINCT ON (job) job, status, business_date, started_at, finished_at, processed, failed, detail
                FROM job_runs ORDER BY job, started_at DESC""");
        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT id, job, status, business_date, started_at, finished_at, processed, failed, detail FROM job_runs
                WHERE processed > 0 OR failed > 0 OR status <> 'SUCCESS' ORDER BY started_at DESC LIMIT 60""");
        return ApiResponse.ok(Map.of("latest", latest, "recent", recent));
    }
}
