FROM docker.io/library/eclipse-temurin@sha256:931683c941b88633f9abce786a3ce05c15b57b9f485447daae26ea9ec9b0e26f AS build
WORKDIR /src
COPY sources sources
COPY tests tests
COPY tools tools
COPY vendored vendored
RUN java -ea tools/Build.java prod

FROM docker.io/library/debian@sha256:d8f17b92dc7ff10f9c1fdecab0ad21103d1d24aed823c3a0359e4f50adfab3eb
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="TokTrak" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.source="https://github.com/isp-insoft-gmbh/toktrak"
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /src/output/runtimes/prod /opt/toktrak
VOLUME ["/data"]
EXPOSE 8080
USER 0
ENTRYPOINT ["/opt/toktrak/bin/java", "-ea", "-m", "toktrak/toktrak.Main"]
