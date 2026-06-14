# 第一階段：用 Gradle + JDK 17 編譯 Spring Boot jar。
# 這讓本機即使還是 Java 8，也能透過 Docker build 成功。
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY backend ./backend
RUN gradle :backend:bootJar --no-daemon

# 第二階段：只帶 JRE 17 和 jar，映像檔比完整 JDK 小。
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
