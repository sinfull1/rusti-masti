# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# Multi-stage build for the HTML->PNG render service.
#   Stage 1: build the shaded jar with JDK 25 (via the project's Maven wrapper).
#   Stage 2: minimal JRE 25 runtime + Xvfb + GTK/X libs + fonts that JavaFX
#            WebView needs (it uses the GTK Glass backend and rasterises text
#            through fontconfig — no fonts => blank/garbled output).
# ---------------------------------------------------------------------------

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Copy the wrapper first so it is cached independently of source churn.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
COPY aot-jar.properties ./
# Online build: pulls the Micronaut/JavaFX/Thymeleaf deps + shade plugin.
RUN ./mvnw -B -DskipTests package

# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# Xvfb (virtual X server) + xauth, plus the native libs JavaFX WebView links against.
# Ubuntu 24.04 (noble) renamed some libs with a "t64" suffix (time_t transition);
# try those first and fall back to the classic names for older bases.
# Best-effort per-package install: a name that differs across Ubuntu releases (e.g. the noble "t64"
# renames) must not abort the whole layer. libxml2/libglib come in transitively via GTK. We then
# hard-fail only if GTK — mandatory for the WebView Glass backend — never installed under any name.
RUN apt-get update && \
    for p in xvfb xauth fontconfig fonts-dejavu-core fonts-liberation \
             libx11-6 libxext6 libxrender1 libxtst6 libxi6 \
             libxslt1.1 libfreetype6 libgl1 \
             libgtk-3-0t64 libglib2.0-0t64 libasound2t64 \
             libgtk-3-0 libglib2.0-0 libasound2 ; do \
        apt-get install -y --no-install-recommends "$p" || echo "SKIP: $p unavailable on this base" ; \
    done && \
    ( dpkg -l | grep -qE 'libgtk-3-0(t64)?' || ( echo "FATAL: libgtk-3 not installed" ; exit 1 ) ) && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/image-0.1.jar /app/app.jar
COPY docker-entry.sh /app/docker-entry.sh
RUN chmod +x /app/docker-entry.sh

EXPOSE 8080

# JVM tuned for a hard ~500 MB cgroup cap. -Xmx does NOT bound WebKit's native heap, so we keep the
# Java heap small (MaxRAMPercentage) and leave headroom for WebKit + Xvfb + Prism native memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=35 -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -Xss512k \
-Djava.awt.headless=true -Dprism.order=sw -Dprism.text=t2k -Dprism.lcdtext=false"

ENTRYPOINT ["/app/docker-entry.sh"]