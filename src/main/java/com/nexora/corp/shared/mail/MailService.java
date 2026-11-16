package com.nexora.corp.shared.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.security.CredentialEncryptionService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Properties;

/**
 * Envío real de correo con el SMTP configurado en Integraciones (contraseña cifrada en BD). Sin SMTP configurado
 * NO se simula el envío: el resultado lo dice y quien llama lo registra como omitido.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    public record Result(boolean sent, boolean configured, String error) {
        static Result ok() {
            return new Result(true, true, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryption;

    private record Smtp(String host, int port, String username, String password, String from, boolean starttls) {
    }

    private Smtp smtp() {
        List<Smtp> rows = jdbc.query("SELECT enabled, config::text, secret_enc FROM integration_configs WHERE code = 'SMTP'", (rs, i) -> {
            if (!rs.getBoolean(1)) {
                return null;
            }
            try {
                JsonNode c = objectMapper.readTree(rs.getString(2));
                String secret = rs.getString(3);
                return new Smtp(c.path("host").asText(""), c.path("port").asInt(587), c.path("username").asText(""),
                        secret == null ? null : encryption.decrypt(secret), c.path("from").asText(""), c.path("starttls").asBoolean(true));
            } catch (Exception e) {
                return null;
            }
        });
        Smtp s = rows.isEmpty() ? null : rows.get(0);
        return s == null || s.host().isBlank() || s.from().isBlank() ? null : s;
    }

    public boolean configured() {
        return smtp() != null;
    }

    public Result send(String to, String subject, String html) {
        Smtp s = smtp();
        if (s == null) {
            return new Result(false, false, "SMTP no configurado");
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(s.host());
            sender.setPort(s.port());
            if (!s.username().isBlank()) {
                sender.setUsername(s.username());
                sender.setPassword(s.password());
            }
            Properties p = sender.getJavaMailProperties();
            p.put("mail.smtp.auth", String.valueOf(!s.username().isBlank()));
            p.put("mail.smtp.starttls.enable", String.valueOf(s.starttls()));
            p.put("mail.smtp.connectiontimeout", "10000");
            p.put("mail.smtp.timeout", "15000");
            p.put("mail.smtp.writetimeout", "15000");
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(s.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            return Result.ok();
        } catch (Exception e) {
            log.warn("No se pudo enviar correo a {}: {}", mask(to), e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, true, msg.length() > 450 ? msg.substring(0, 450) : msg);
        }
    }

    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}

