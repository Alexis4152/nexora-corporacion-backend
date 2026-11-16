package com.nexora.corp.shared.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Límite de solicitudes con ventana fija en PostgreSQL (tabla UNLOGGED). A diferencia del token bucket en memoria
 * de HOTEL, funciona igual con varias instancias del backend: todas cuentan sobre el mismo contador.
 */
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final String HIT = """
            INSERT INTO rate_limit_counters (bucket_key, window_start, hits) VALUES (?, now(), 1)
            ON CONFLICT (bucket_key) DO UPDATE SET
              hits = CASE WHEN rate_limit_counters.window_start < now() - make_interval(secs => ?) THEN 1
                          ELSE rate_limit_counters.hits + 1 END,
              window_start = CASE WHEN rate_limit_counters.window_start < now() - make_interval(secs => ?) THEN now()
                          ELSE rate_limit_counters.window_start END
            RETURNING hits, GREATEST(1, CEIL(EXTRACT(EPOCH FROM (window_start + make_interval(secs => ?) - now()))))::bigint""";

    private final JdbcTemplate jdbc;

    /** @return 0 si se permite; si no, segundos a esperar. */
    public long tryConsume(String key, int capacity, Duration window) {
        long secs = window.toSeconds();
        String safeKey = key.length() > 200 ? key.substring(0, 200) : key;
        return jdbc.queryForObject(HIT, (rs, i) -> rs.getInt(1) <= capacity ? 0L : rs.getLong(2), safeKey, secs, secs, secs);
    }

    @Scheduled(fixedRate = 900_000)
    void purge() {
        jdbc.update("DELETE FROM rate_limit_counters WHERE window_start < now() - interval '1 day'");
    }
}

