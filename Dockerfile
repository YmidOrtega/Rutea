# syntax=docker/dockerfile:1.7
# La app y el mapa en una imagen: Spring sirve el frontend compilado en el mismo puerto que la API.

FROM node:22.23.4-alpine AS web
WORKDIR /web
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS app
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY rutea-solver rutea-solver
COPY rutea-domain rutea-domain
COPY rutea-geo rutea-geo
COPY rutea-sim rutea-sim
COPY rutea-app rutea-app
COPY rutea-bench/pom.xml rutea-bench/pom.xml
COPY rutea-tools/pom.xml rutea-tools/pom.xml
COPY --from=web /web/dist rutea-app/src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl rutea-app -am package -DskipTests

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN useradd --system --uid 10001 rutea
WORKDIR /app
COPY --from=app /src/rutea-app/target/rutea-app-exec.jar rutea-app.jar
USER rutea
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "rutea-app.jar"]
