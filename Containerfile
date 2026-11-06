FROM docker.io/library/amazoncorretto:27-jdk@sha256:95d651262e5a4dd7230cf2fbf3a18c39ebb7931593b45907a48ae33a9277d4ae AS build
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

FROM docker.io/library/debian@sha256:bfc36aecdece0387418759dba474aa856c026ca8087da3606b41b90ab0509c99
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
