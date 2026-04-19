FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY build/libs/relay-server-all.jar app.jar

EXPOSE 8080

ENV PORT=8080

ENTRYPOINT ["java", "-jar", "app.jar"]
