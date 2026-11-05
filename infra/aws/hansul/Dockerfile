FROM eclipse-temurin:25-jre
WORKDIR /app
COPY server.jar /app/server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55", "-jar", "/app/server.jar"]
