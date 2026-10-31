FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system aicoo && useradd --system --gid aicoo aicoo
COPY --from=build --chown=aicoo:aicoo /build/target/aicoo-backend-1.0.0.jar /app/aicoo.jar
USER aicoo
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/aicoo.jar"]
