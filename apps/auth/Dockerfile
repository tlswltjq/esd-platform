FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S stove && adduser -S stove -G stove

ARG JAR_FILE=apps/auth/build/libs/*.jar
COPY ${JAR_FILE} app.jar
RUN chown -R stove:stove /app
USER stove

EXPOSE 8091
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8091/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
