# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
COPY shared-contracts/pom.xml shared-contracts/pom.xml
COPY shared-security/pom.xml shared-security/pom.xml
COPY agendamento-service/pom.xml agendamento-service/pom.xml
COPY notificacao-service/pom.xml notificacao-service/pom.xml
COPY historico-service/pom.xml historico-service/pom.xml
COPY quality-gates/pom.xml quality-gates/pom.xml

COPY shared-contracts/src shared-contracts/src
COPY shared-security/src shared-security/src
COPY agendamento-service/src agendamento-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package -pl agendamento-service -am && \
    cp "$(find agendamento-service/target -maxdepth 1 -name '*-exec.jar' -type f -print -quit)" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 \
  CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

