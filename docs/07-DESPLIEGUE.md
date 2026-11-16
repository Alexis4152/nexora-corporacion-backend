# 07 · Despliegue (Coolify)

## Componentes

| Componente | Origen | Puerto | Dominio sugerido |
|---|---|---|---|
| Base de datos | PostgreSQL 16 de Coolify | 5432 (solo red interna) | — |
| Backend | repo `nexora-corporacion-backend`, su `Dockerfile` (JRE 17, usuario sin privilegios) | 8091 | `https://api-corp.<dominio>` |
| Frontend | repo `nexora-corporacion-frontend`, su `Dockerfile` (build Vite + nginx con CSP) | 80 | `https://corp.<dominio>` |

Frontend y API son aplicaciones distintas: el nginx del frontend **no** reenvía `/api`. El frontend llama al API por la
URL de `VITE_API_URL`. Como ambos subdominios cuelgan del mismo dominio, son el mismo *sitio* y la cookie del refresh
token funciona con `SameSite=Strict`.

## DNS

Dos registros **A** apuntando a la IP del servidor de Coolify: `corp` y `api-corp`. Coolify emite los certificados
HTTPS (Let's Encrypt) en cuanto el DNS resuelve.

## Base de datos

PostgreSQL 16 en Coolify con base `nexora_corporacion`, respaldo automático diario. No la publiques a internet: el
backend la usa por la red interna. El esquema lo crea Flyway al arrancar (V1…V7).

## Backend: variables (Coolify > Environment Variables)

| Variable | Valor |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (ya viene en la imagen) |
| `DB_URL` | `jdbc:postgresql://<host interno de Coolify>:5432/nexora_corporacion` |
| `DB_USERNAME` / `DB_PASSWORD` | Los del PostgreSQL de Coolify |
| `APP_JWT_SECRET`, `APP_ENCRYPTION_KEY` | Dos valores **distintos** de `openssl rand -base64 48` (≥ 48 caracteres). **No cambies `APP_ENCRYPTION_KEY` después**: cifra las credenciales guardadas (SMTP, integraciones). |
| `APP_FRONTEND_URL` | `https://corp.<dominio>` |
| `APP_CORS_ALLOWED_ORIGINS` | `https://corp.<dominio>` |
| `APP_COOKIE_SECURE` | `true` |
| `APP_COOKIE_SAMESITE` | `Strict` (mismo dominio). Si el API quedara en otro dominio: `None`. |
| `APP_STORAGE_DIR` | `/data/storage` (ya viene en la imagen) |
| `APP_BOOTSTRAP_SUPER_ADMIN_EMAIL` / `APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD` | Solo el primer arranque; bórralas después |

`APP_DEMO_DATA` está prohibido en producción: con ella la aplicación no arranca. `ProductionReadinessValidator` también
detiene el arranque si falta un secreto, si `APP_COOKIE_SECURE` no es `true` o si el CORS apunta a `localhost`.

**Persistent Storage:** agrega un **Volume Mount** (no *Bind Mount*) con destino `/data/storage`. Sin él, documentos y
PDF se pierden en cada redeploy; una carpeta del servidor montada directo quedaría de `root` y el backend no arrancaría.

**Health check:** `/actuator/health` (ya definido en el `Dockerfile`). **Instancias:** una sola (el límite de solicitudes
vive en memoria).

## Frontend: build

| Build Variable | Valor |
|---|---|
| `VITE_API_URL` | `https://api-corp.<dominio>/api`. Se incrusta al compilar: si cambia, vuelve a desplegar. El `Dockerfile` también la usa para permitir ese origen en la CSP (`connect-src`). |

## Después del primer arranque

1. Entrar con el SUPER_ADMIN, activar MFA y retirar las variables `APP_BOOTSTRAP_*` (redeploy).
2. *Configuración*: datos de la empresa, zona horaria, IVA, días de gracia y tramos de cobranza.
3. *Catálogo*: planes y precios reales de cada producto.
4. *Integraciones*: SMTP para correos (sin SMTP las notificaciones quedan en pantalla y la bandeja de salida espera).
5. Crear un usuario por socio/rol; cada uno cambia su contraseña temporal al entrar.

## Lista de verificación

- [ ] `https://api-corp.<dominio>/actuator/health` responde `{"status":"UP"}`.
- [ ] El log muestra Flyway hasta la V7 sin errores.
- [ ] Iniciar sesión en `https://corp.<dominio>`, recargar la página y seguir dentro (cookie del refresh token).
- [ ] Subir un documento, hacer redeploy del backend y comprobar que sigue disponible (volumen).
- [ ] Descargar un PDF de cotización y un reporte en Excel.
- [ ] MFA activo para SUPER_ADMIN, administradores y finanzas.

## Respaldo

- Respaldo diario de PostgreSQL en Coolify con retención de 30 días; prueba restaurarlo en otra base al menos una vez.
- Respaldo del volumen `/data/storage` (la base solo guarda rutas).
- La bitácora y los movimientos financieros son inmutables: restaurar es la única forma de «deshacer».
