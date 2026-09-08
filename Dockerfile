FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /app

# Cache dependencies
COPY deps.edn ./
RUN clojure -P

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

CMD ["sh", "-c", "BB_PORT=${PORT:-8080} exec java -Djava.awt.headless=true -jar boostbox.jar"]
