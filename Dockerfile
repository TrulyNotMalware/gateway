FROM eclipse-temurin:25.0.1_8-jre-alpine
ARG JAR_FILE_NAME=gateway-alpha
ARG SERVER_PORT=80

ARG PROFILES="prod"
ENV PROFILE=${PROFILES}
COPY ./build/libs/$JAR_FILE_NAME.jar /app.jar

EXPOSE $SERVER_PORT
ENTRYPOINT exec java -jar -Dspring.profiles.active=${PROFILE} -Duser.timezone=Asia/Seoul /app.jar
