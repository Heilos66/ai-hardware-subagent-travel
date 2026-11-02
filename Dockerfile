# CI Dockerfile — uses Huawei internal base image + JDK from build environment.
# Local build: run "tar -czf jdk.tar.gz -C $(dirname $JAVA_HOME) $(basename $JAVA_HOME)" first.
ARG RUNTIME_IMAGE=registry-cbu.huawei.com/hce_docker/hce_arm:3.0.2606.1
FROM ${RUNTIME_IMAGE}

# Install JDK from build context (packaged by build.sh from $JAVA_HOME)
COPY jdk.tar.gz /tmp/jdk.tar.gz
RUN mkdir -p /opt/jdk \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/jdk --strip-components=1 \
    && rm /tmp/jdk.tar.gz \
    && ln -s /opt/jdk/bin/java /usr/local/bin/java
ENV JAVA_HOME=/opt/jdk
ENV PATH=/opt/jdk/bin:$PATH

RUN groupadd --gid 10001 agent && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
ENV PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0"
USER agent
EXPOSE 8080
# AgentArts probes GET /ping; the application must be ready before it returns Healthy.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# CI copies the JAR produced by mvn verify; no compilation inside Docker.
COPY --chown=agent:agent target/travel-agent.jar ./app.jar
