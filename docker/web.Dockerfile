# The api/worker image (PHASE6_SPEC.md section 2): one image, APP_ROLE picks the role.
# No LibreOffice here: rendering is the renderer service's job, and this image has no use for it.
# Build from the repo root:  docker build -f docker/web.Dockerfile -t tailor-web .

FROM ubuntu:24.04 AS build
ENV DEBIAN_FRONTEND=noninteractive
ARG GRADLE_VERSION=8.10
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN curl -sfL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
    && rm /tmp/gradle.zip
WORKDIR /app
COPY . /app
RUN gradle --no-daemon :web:bootJar -x test

FROM ubuntu:24.04 AS runtime
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /usr/sbin/nologin --uid 10001 tailor
WORKDIR /app
COPY --from=build /app/web/build/libs/web.jar /app/web.jar
USER tailor
EXPOSE 8080
# APP_ROLE=api (default) or worker; read by WebApplication.
CMD ["java", "-jar", "/app/web.jar"]
