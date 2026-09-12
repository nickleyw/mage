FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /source
COPY . .
RUN mvn -B -DskipTests -pl Mage.WebBridge -am install
RUN mvn -B -DskipTests -pl Mage.WebBridge dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/runtime-libs

FROM eclipse-temurin:17-jre-jammy

RUN useradd --create-home --uid 10001 xmage \
    && mkdir -p /opt/xmage /data \
    && chown -R xmage:xmage /opt/xmage /data
COPY --from=build /source/Mage.WebBridge/target/mage-web-bridge-1.4.61.jar /opt/xmage/app.jar
COPY --from=build /source/Mage.WebBridge/target/runtime-libs/ /opt/xmage/lib/
COPY --chown=xmage:xmage Mage.WebBridge/docker-entrypoint.sh /opt/xmage/docker-entrypoint.sh
RUN chmod 0755 /opt/xmage/docker-entrypoint.sh

USER xmage
WORKDIR /data
ENV PORT=8080
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["/opt/xmage/docker-entrypoint.sh"]
