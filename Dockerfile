# Stage 1 has Maven 3.9.16 and a full JDK 25, which includes the Java compiler.
FROM maven:3.9.16-eclipse-temurin-25-alpine AS build
WORKDIR /workspace

# Copy dependency descriptors first so Docker can reuse the download layer when
# application source changes but dependencies do not.
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN ./mvnw -B -DskipTests dependency:go-offline

# Now copy source and produce the executable JAR. CI runs tests before image build.
COPY src src
RUN ./mvnw -B clean package -DskipTests

# Stage 2 needs only a Java 25 runtime.
FROM eclipse-temurin:25.0.3_9-jre-alpine
WORKDIR /app

# wget supports the Compose healthcheck; the dedicated user avoids running as root.
RUN apk add --no-cache wget \
    && addgroup -S adept \
    && adduser -S adept -G adept

# Copy only the packaged application from the build stage.
COPY --from=build /workspace/target/*.jar /app/app.jar
USER adept

# Documents the listening port; Compose still decides whether to publish it.
EXPOSE 8080

# Limit heap sizing relative to the container's memory limit and start the JAR.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
