FROM gradle:8.13-jdk21-alpine AS build
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S hospital && adduser -S hospital -G hospital
COPY --from=build /workspace/build/libs/hospital-reservation.jar /app/app.jar
ENV PORT=8080 SERVER_ADDRESS=0.0.0.0 SERVER_FORWARD_HEADERS_STRATEGY=framework
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0"
USER hospital
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
