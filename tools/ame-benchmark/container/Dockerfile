FROM eclipse-temurin:21-jdk-noble AS java_builder
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src/main ./src/main
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jdk-noble AS ame_builder
RUN apt-get update && apt-get install -y --no-install-recommends cmake g++ && rm -rf /var/lib/apt/lists/*
RUN apt-get update && apt-get install -y --no-install-recommends make && rm -rf /var/lib/apt/lists/*
COPY upstream /build/ame
COPY harness /build/harness
RUN cmake -S /build/harness -B /build/out -DAME_SOURCE=/build/ame -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS="-include cstdint" \
 && cmake --build /build/out --target ame_kb_worker -j 4

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends python3 libstdc++6 \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 10001 agent && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
COPY --from=java_builder --chown=agent:agent /build/target/travel-agent.jar /app/app.jar
COPY --from=ame_builder --chown=agent:agent /build/out/ame_kb_worker /app/ame/ame_kb_worker
COPY --from=ame_builder --chown=agent:agent /build/ame/lexicon/lexicon_src/segmentation/merged.txt /app/ame/source/lexicon/lexicon_src/segmentation/merged.txt
COPY --chown=agent:agent harness/run.py harness/serve.py /app/ame/
COPY --chown=agent:agent harness/fixtures/corpus.json /app/ame/fixtures/corpus.json
COPY --chown=agent:agent harness/cheap_model.json /app/ame/cheap_model.json
COPY --chown=agent:agent harness/AME-LICENSE /app/ame/LICENSE
COPY --chown=agent:agent start.py /app/start.py
ENV PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0" AME_ENABLED=true RAGFLOW_TOP_K=5 AME_RETRIEVAL_MODE=graph AME_CHEAP_MODEL=/app/ame/cheap_model.json
LABEL org.opencontainers.image.title="Travel Agent with AME local retrieval" \
      ame.revision="df68cbf1f2742095901ec97434507d76cba2f61d"
USER agent
EXPOSE 8080
ENTRYPOINT ["python3", "/app/start.py"]
