FROM node:22-alpine AS builder

WORKDIR /app

# Cache dependencies
COPY package.json package-lock.json* ./
RUN npm ci

# Build
COPY tsconfig.json ./
COPY src/ src/
RUN npm run build

# ---

FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --omit=dev

COPY --from=builder /app/dist/ dist/
COPY resources/ resources/

EXPOSE 8080

CMD ["sh", "-c", "BB_PORT=${PORT:-8080} exec node dist/index.js"]
