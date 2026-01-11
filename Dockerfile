# Multi-stage Dockerfile for building and running the Spring Boot app
# Stage 1: Build with Maven (usar Java 25)
FROM maven:3.9.6-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package -DskipITs

# Stage 2: Run (Java 25)
FROM eclipse-temurin:25-jre-jammy
ARG JAR_FILE=target/*.jar
COPY --from=build /app/${JAR_FILE} /app/app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
