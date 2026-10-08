# Build with: docker buildx build --platform linux/arm64 -t travel-agent:0.2.0 --load .
FROM eclipse-temurin:21-jdk-noble AS builder
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src/main ./src/main
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-noble
RUN groupadd --gid 10001 agent && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
COPY --from=builder --chown=agent:agent /build/target/travel-agent.jar ./app.jar
ENV PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0"
USER agent
EXPOSE 8080
# AgentArts probes GET /ping; the application must be ready before it returns Healthy.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
