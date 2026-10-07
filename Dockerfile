FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 gateway
COPY --from=build /app/target/llm-gateway-*.jar app.jar
USER gateway
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
