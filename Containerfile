FROM docker.io/library/eclipse-temurin@sha256:6a0a8c4a2bc692c1b101f7f509e2aade12b05aec1cfd31f8c9370f2addfd2f71 AS build
WORKDIR /src
COPY sources sources
COPY tests tests
COPY tools tools
COPY vendored vendored
RUN java -ea tools/Build.java prod

FROM docker.io/library/debian@sha256:6788062a1b42ac281f053ac876170b79a3eaed5d61383b8ed7eaca6c6965f3b1
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
