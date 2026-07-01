# Runnable single-process app: Micronaut gateway + Blitz (Rust) renderer via FFM, in one image.
# Blitz-only path needs no Xvfb/JavaFX (lazy); the .so + fonts are bundled.

# --- Rust cdylib ---
FROM rust:1-bookworm AS rustbuild
RUN apt-get update && apt-get install -y --no-install-recommends libfontconfig1-dev pkg-config \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /r
COPY render-ffi/ render-ffi/
RUN cd render-ffi && cargo build --release

# --- Micronaut jar ---
FROM eclipse-temurin:25-jdk AS javabuild
WORKDIR /b
COPY . .
RUN ./mvnw -q -DskipTests package

# --- Runtime ---
FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
        libfontconfig1 libfreetype6 fonts-dejavu-core fonts-liberation \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=javabuild /b/target/image-0.1.jar /app/app.jar
COPY --from=rustbuild /r/render-ffi/target/release/libblitz_render.so /app/libblitz_render.so
EXPOSE 8080
# EXTRA_OPTS lets the load test tune heap / blocking-pool size per run.
ENV EXTRA_OPTS="-XX:MaxRAMPercentage=40"
ENTRYPOINT ["sh","-c","exec java --enable-native-access=ALL-UNNAMED -Dblitz.lib=/app/libblitz_render.so $EXTRA_OPTS -jar /app/app.jar"]
