FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /app

# Cache dependencies
COPY deps.edn ./
RUN clojure -P

# Build uberjar
COPY build.clj ./
COPY src/ src/
RUN clojure -T:build uber

# ---

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/boostbox.jar boostbox.jar

EXPOSE 8080
ENV BB_PORT=8080

CMD ["java", "-jar", "boostbox.jar"]
