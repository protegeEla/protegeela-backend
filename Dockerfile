FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/protegeela-*.jar /app/protegeela.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/protegeela.jar"]
