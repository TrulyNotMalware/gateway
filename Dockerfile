FROM eclipse-temurin:25.0.1_8-jre-alpine
ARG JAR_FILE_NAME=gateway-alpha
# non-root 컨테이너에서 동작하므로 1024+ 포트 사용. Service 에서 80 → targetPort:http(8080) 매핑.
ARG SERVER_PORT=8080
ARG MANAGEMENT_PORT=8081

COPY ./build/libs/$JAR_FILE_NAME.jar /app.jar

EXPOSE $SERVER_PORT $MANAGEMENT_PORT
# exec (JSON) form: java becomes PID 1 → SIGTERM 이 JVM 까지 전달 → graceful shutdown 동작.
# Spring profile 은 SPRING_PROFILES_ACTIVE env (configmap) 로 설정.
ENTRYPOINT ["java", "-jar", "-Duser.timezone=Asia/Seoul", "/app.jar"]
