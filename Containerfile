FROM docker.io/library/eclipse-temurin@sha256:26e946fcdc82e0648096c2aac37cf62dbfe11e3ac8160c16e37aa63d0994fa14 AS build
WORKDIR /src
COPY sources sources
COPY tests tests
COPY tools tools
COPY vendored vendored
RUN java -ea tools/Build.java prod

FROM docker.io/library/debian@sha256:362e64223cc0da95422b3b13c045186fc0a81250e765d31c025fbddf257f6143
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
