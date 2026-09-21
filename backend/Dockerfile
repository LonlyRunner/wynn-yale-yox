FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/wynn-backend-0.1.0.jar app.jar
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
