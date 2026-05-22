FROM eclipse-temurin:25.0.1_8-jre-alpine
ARG JAR_FILE_NAME=gateway-alpha
# non-root 컨테이너에서 동작하므로 1024+ 포트 사용. Service 에서 80 → targetPort:http(8080) 매핑.
ARG SERVER_PORT=8080
ARG MANAGEMENT_PORT=8081

ARG PROFILES="prod"
ENV PROFILE=${PROFILES}
COPY ./build/libs/$JAR_FILE_NAME.jar /app.jar

EXPOSE $SERVER_PORT $MANAGEMENT_PORT
ENTRYPOINT exec java -jar -Dspring.profiles.active=${PROFILE} -Duser.timezone=Asia/Seoul /app.jar
