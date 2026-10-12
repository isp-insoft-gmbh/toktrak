FROM docker.io/library/amazoncorretto:27-jdk@sha256:3bca6d83fb33e63fe5386c7f8e290e4f70ecb2a406ae59eaf5cfd6fddca01563 AS build
RUN dnf install -y binutils && dnf clean all
WORKDIR /src
COPY CHANGELOG.md CHANGELOG.md
COPY output/release/CHANGELOG.md output/release/CHANGELOG.md
ENV TOKTRAK_RELEASE_CHANGELOG=1
COPY sources sources
COPY tests tests
COPY tools tools
COPY vendored vendored
RUN java -ea tools/Build.java prod

FROM docker.io/library/debian@sha256:d5ce19d4736f0ebbacd686d1040271a5aeb0cc920f5990c1bfae1717627f0674
ARG VERSION
ARG REVISION
ARG DISPLAY_VERSION=dev
ARG CHANGELOG_SHA256
ENV TOKTRAK_VERSION="${DISPLAY_VERSION}" \
    TOKTRAK_REVISION="${REVISION}"
LABEL org.opencontainers.image.title="TokTrak" \
      org.opencontainers.image.changelog-sha256="$CHANGELOG_SHA256" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.source="https://github.com/isp-insoft-gmbh/toktrak"
COPY --from=build /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/ssl/certs/ca-certificates.crt
COPY --from=build /src/output/runtimes/prod /opt/toktrak
VOLUME ["/data"]
EXPOSE 8080
USER 0
ENTRYPOINT ["/opt/toktrak/bin/java", "-ea", "-m", "toktrak/toktrak.Main"]
