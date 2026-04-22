FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
# Port is injected via SERVER_PORT env var (7081/7082 in docker-compose)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
