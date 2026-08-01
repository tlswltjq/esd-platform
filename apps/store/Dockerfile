# 빌드 산출물(bootJar)을 담는 런타임 이미지.
# 사전 조건: ./gradlew :apps:store:bootJar
# 빌드 명령 : docker build -f apps/store/Dockerfile -t stove/store .  (컨텍스트 = 레포 루트)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S stove && adduser -S stove -G stove

ARG JAR_FILE=apps/store/build/libs/*.jar
COPY ${JAR_FILE} app.jar
RUN chown -R stove:stove /app
USER stove

EXPOSE 8087
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8087/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
