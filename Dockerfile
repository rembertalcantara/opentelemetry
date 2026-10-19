# Builds either service: docker build --build-arg MODULE=order-service .
FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE
WORKDIR /workspace
COPY pom.xml .
COPY observability-common/pom.xml observability-common/
COPY inventory-service/pom.xml inventory-service/
COPY order-service/pom.xml order-service/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE} -am dependency:go-offline || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/${MODULE}-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER 1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
