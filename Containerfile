FROM docker.io/library/eclipse-temurin:26-jdk@sha256:317693565b302133e01e1990fe04514b4894670530f80a55584453f82df94d04 AS build
WORKDIR /src
COPY CHANGELOG.md CHANGELOG.md
COPY sources sources
COPY tests tests
COPY tools tools
COPY vendored vendored
RUN java -ea tools/Build.java prod

FROM docker.io/library/debian@sha256:d5ce19d4736f0ebbacd686d1040271a5aeb0cc920f5990c1bfae1717627f0674
ARG VERSION
ARG REVISION
ARG DISPLAY_VERSION=dev
ENV TOKTRAK_VERSION="${DISPLAY_VERSION}" \
    TOKTRAK_REVISION="${REVISION}"
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
