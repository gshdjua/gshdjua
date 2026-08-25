FROM maven:3.9.9-eclipse-temurin-8 AS builder

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:8-jre-jammy

WORKDIR /app/backend
COPY --from=builder /workspace/target/springboot-web-demo-1.0.0.jar app.jar
RUN mkdir -p /app/music /app/evaluation

EXPOSE 8082
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/backend/app.jar"]
