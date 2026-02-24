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

WORKDIR /app
COPY --from=builder /app/target/boostbox.jar boostbox.jar

EXPOSE 8080

CMD ["sh", "-c", "BB_PORT=${PORT:-8080} exec java -jar boostbox.jar"]
