FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /app

# Cache dependencies, including tools.build for the :build alias -- plain
# `clojure -P` skips it, so every source change would download it again.
COPY deps.edn ./
RUN clojure -P && clojure -P -T:build

# Build uberjar
COPY build.clj ./
COPY src/ src/
COPY resources/ resources/
RUN clojure -T:build uber

# ---

FROM eclipse-temurin:21-jre-alpine

# The boost banner (/og/boost.png) is drawn with Java2D, which needs freetype
# to rasterise a glyph. The alpine JRE image ships neither that nor any font.
# The face itself is bundled in the jar rather than installed, so the picture
# does not change with the base image -- but without freetype present Java2D
# substitutes silently and draws an empty box onto a note that is already
# signed. fontconfig is here for the same reason: cheap, and its absence fails
# as a blank image rather than an error.
RUN apk add --no-cache freetype fontconfig

WORKDIR /app
COPY --from=builder /app/target/boostbox.jar boostbox.jar

EXPOSE 8080

# Railway bills memory by the minute and sets a container limit far above what
# this needs. Uncapped, the JVM takes a quarter of that limit as its heap
# ceiling, grows into it under a crawler burst and keeps it: locally, 16
# parallel homepage renders took an uncapped JVM to 3.5 GB resident, and it
# was still there after a minute idle. 512m leaves room for the banner's
# worst-case art (banner/max-art-pixels, ~144 MB decoded). G1 with a periodic
# GC hands the heap back once traffic stops -- the same burst settled from
# ~750 MB to 550 MB and falling, where SerialGC kept its high-water mark until
# a restart. -Xms64m keeps the floor low whatever the container limit is.
# ExitOnOutOfMemoryError makes an OOM a restart instead of a process limping
# on without whichever thread it killed. Setting JAVA_OPTS on the service
# replaces all of these outright.
CMD ["sh", "-c", "BB_PORT=${PORT:-8080} exec java ${JAVA_OPTS:--Xms64m -Xmx512m -XX:+UseG1GC -XX:G1PeriodicGCInterval=30000 -XX:+ExitOnOutOfMemoryError} -Djava.awt.headless=true -jar boostbox.jar"]
