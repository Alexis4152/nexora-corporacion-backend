# Nexora Corporación · Backend

Plataforma interna de Nexora Systems para administrar la operación comercial y financiera de todos sus productos SaaS
(POS, Hotel, Citas, Restaurantes, Taxis, Gym, E-commerce y los que se agreguen desde el catálogo): CRM, clientes,
suscripciones, cobranza, pagos, indicadores SaaS, soporte, cotizaciones, gastos y reportes.

**Stack:** Java 17 · Spring Boot 3.2 · PostgreSQL 16+ · Flyway · JWT + refresh rotativo · Testcontainers.
Frontend: `../nexora-corporacion-frontend` (React 18 + Vite + Tailwind).

## Arranque local

```bash
cp .env.example .env          # completa secretos (openssl rand -base64 48)
createdb nexora_corporacion   # PostgreSQL local
./mvnw spring-boot:run        # http://localhost:8091  ·  Swagger: /swagger-ui.html (solo dev)
```

- El primer arranque crea el `SUPER_ADMIN` con `APP_BOOTSTRAP_SUPER_ADMIN_EMAIL/PASSWORD` (solo si no existe ninguno).
- Con `APP_DEMO_DATA=true` (únicamente perfil `dev`) se generan 14 meses de operación de demostración **a través de los
  servicios reales** y usuarios por rol con `APP_DEMO_PASSWORD`. El perfil `prod` se niega a arrancar con esa opción.

## Pruebas

```bash
./mvnw test        # unitarias + integración (requiere Docker para Testcontainers)
```

| Suite | Qué cubre |
|---|---|
| `PeriodMathTest`, `PricingTest`, `MrrMovementTest` | fin de mes, bisiestos, IVA incluido/excluido, descuentos, prorrateo, clasificación del MRR |
| `TotpTest`, `ValidationTest`, `ProductionReadinessValidatorTest` | RFC 6238, repetición de códigos, política de contraseñas, configuración insegura |
| `HealthServiceTest`, `ProspectStageTest`, `TableExporterTest` | salud del cliente explicable, embudo, inyección de fórmulas en CSV |
| `PlatformIT` (PostgreSQL real) | refresh rotativo y anti-CSRF, bloqueo por intentos, MFA, permisos por rol y datos fiscales, reautenticación, dos productos por cliente, pago parcial, idempotencia, pagos concurrentes duplicados, prorrateo, cancelación en prueba, cancelación y reactivación, suspensión y pago posterior, renovación de fin de mes, tareas idempotentes, conversión transaccional de prospecto, inmutabilidad de auditoría y movimientos |

## Documentación

| Documento | Contenido |
|---|---|
| [01-ARQUITECTURA](docs/01-ARQUITECTURA.md) | módulos, capas, convenciones, tareas programadas |
| [02-MODELO-DE-DATOS](docs/02-MODELO-DE-DATOS.md) | tablas, vistas de lectura, restricciones de integridad |
| [03-SEGURIDAD](docs/03-SEGURIDAD.md) | sesiones, MFA, permisos, reautenticación, auditoría |
| [04-REGLAS-FINANCIERAS](docs/04-REGLAS-FINANCIERAS.md) | ciclo de vida, cargos, pagos, cobranza, estados |
| [06-INDICADORES-SAAS](docs/06-INDICADORES-SAAS.md) | fórmulas de MRR, churn, NRR, LTV, CAC |
| [07-DESPLIEGUE](docs/07-DESPLIEGUE.md) | variables, Docker, respaldo, checklist de producción |

