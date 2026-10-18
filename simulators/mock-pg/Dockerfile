FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S pg && adduser -S pg -G pg
COPY simulators/mock-pg/build/libs/*.jar app.jar
RUN chown -R pg:pg /app
USER pg
EXPOSE 8092
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8092/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
