# ---- Build: compila el JAR (las pruebas corren en local/CI, requieren Docker para Testcontainers) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Runtime: solo JRE + JAR, usuario sin privilegios, zona UTC (la zona del negocio se configura en la app) ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && mkdir -p /data/storage && chown -R app:app /app /data
COPY --from=build /build/target/*.jar app.jar
USER app
# Documentos y PDF: montar un VOLUMEN PERSISTENTE en /data/storage (Coolify > Persistent Storage).
# Sin volumen, los archivos se perderían en el siguiente redeploy.
VOLUME ["/data/storage"]
ENV SPRING_PROFILES_ACTIVE=prod \
    APP_STORAGE_DIR=/data/storage \
    SERVER_PORT=8091 \
    TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8091
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8091/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
