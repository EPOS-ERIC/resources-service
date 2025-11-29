FROM amazoncorretto:21-alpine3.22

ADD target/*.jar app.jar
ADD src/main/resources/log4j2.xml log4j2.xml

RUN apk --no-cache add curl

ENTRYPOINT ["java","-Dlog4j.configurationFile=log4j2.xml", "-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]
