# 1) build the React + Tailwind frontend
FROM node:24-slim AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) build the Spring Boot API with the frontend bundled as static resources (also runs the evaluation tests)
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B package

# 3) slim runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=api /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
