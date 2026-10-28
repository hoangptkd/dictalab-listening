FROM maven:3.9.12-eclipse-temurin-17@sha256:a0603aab698040d9c94259f379ec0487da1678560748d6c7508483034033c53d AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre@sha256:bdc6c4d91efc63515085e1288be79290cb4806c5e5148dd8035d49b24fa55707
WORKDIR /app
COPY --from=build /build/target/dictalab-progress-0.1.0-SNAPSHOT.jar /app/progress.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Xmx192m -XX:MaxMetaspaceSize=128m"
ENTRYPOINT ["java", "-jar", "/app/progress.jar"]
