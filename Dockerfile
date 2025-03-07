FROM maven:3.9.5-eclipse-temurin-21 AS build

ENV HOME=/graphhopper

WORKDIR /graphhopper
COPY . .
RUN mvn clean install -DskipTests

FROM eclipse-temurin:21.0.1_12-jre
ENV JAVA_OPTS "-Xmx4g -Xms4g"
RUN mkdir -p /data
WORKDIR /graphhopper
COPY --from=build /graphhopper/web/target/graphhopper-web-11.0-SNAPSHOT.jar ./
COPY graphhopper.sh config-example.yml ./
RUN chmod +x ./graphhopper.sh
VOLUME ["./data"]
EXPOSE 8989 8990
ENTRYPOINT [ "./graphhopper.sh", "-c", "config-example.yml" ]

