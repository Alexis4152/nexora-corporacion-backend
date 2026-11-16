package com.nexora.corp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Cliente HTTP mínimo para pruebas de integración: habla con la API como el frontend (Bearer en memoria, refresh en
 * cookie httpOnly, cabecera X-Requested-With). Sin dependencias de Spring para no esconder el contrato real.
 */
public class Api {

    public record Res(int status, JsonNode body, String setCookie) {
        public JsonNode data() {
            return body.path("data");
        }

        public String code() {
            return body.path("code").asText(null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    private String token;
    private String refreshCookie;

    public Api(int port) {
        this.base = "http://localhost:" + port;
    }

    public static String key() {
        return UUID.randomUUID().toString();
    }

    public Res login(String email, String password) {
        Res r = post("/api/v1/auth/login", java.util.Map.of("email", email, "password", password));
        remember(r);
        return r;
    }

    public void remember(Res r) {
        if (r.status() == 200 && r.data().hasNonNull("accessToken")) {
            token = r.data().get("accessToken").asText();
        }
        if (r.setCookie() != null && r.setCookie().startsWith("nxc_rt=")) {
            refreshCookie = r.setCookie().substring(0, r.setCookie().indexOf(';'));
        }
    }

    public String refreshCookie() {
        return refreshCookie;
    }

    public void useRefreshCookie(String cookie) {
        refreshCookie = cookie;
    }

    public void clearToken() {
        token = null;
    }

    public Res get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), true, null);
    }

    public Res post(String path, Object body) {
        return post(path, body, null);
    }

    public Res post(String path, Object body, String idempotencyKey) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(write(body))), true, idempotencyKey);
    }

    public Res put(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).PUT(HttpRequest.BodyPublishers.ofString(write(body))), true, null);
    }

    /** POST sin la cabecera anti-CSRF (para comprobar que el refresh la exige). */
    public Res postWithoutXhr(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()), false, null);
    }

    private Res send(HttpRequest.Builder b, boolean xhr, String idempotencyKey) {
        b.header("Content-Type", "application/json").header("Accept", "application/json");
        if (xhr) {
            b.header("X-Requested-With", "XMLHttpRequest");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (refreshCookie != null) {
            b.header("Cookie", refreshCookie);
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = res.body() == null || res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body());
            return new Res(res.statusCode(), body, res.headers().firstValue("Set-Cookie").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object body) {
        try {
            return body == null ? "" : JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
