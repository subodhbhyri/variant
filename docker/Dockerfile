# Four stages (PHASE2_SPEC.md section 3):
#   gelasio  - throwaway: downloads + patches Gelasio RT. Only the resulting
#              .ttf files leave this stage; curl, python3-fonttools and the
#              unpatched source TTFs never reach "os" or anything after it.
#   os       - Ubuntu + LibreOffice + fonts only. No Java, Gradle, curl, or
#              Python here — "dev" and "runtime" each add their own on top.
#   dev      - os + JDK + Gradle + the full source tree (default target: the
#              last stage in this file, so every existing
#              `docker build -t resume-tailor .` / `docker run resume-tailor
#              gradle ...` in this repo keeps working unchanged).
#   build    - dev, run once to produce the CLI fat jar.
#   runtime  - os + a JRE (not JDK) + the non-root "variant" user + only
#              /app/tailor.jar, copied from "build". No source, no
#              corpus/golden, no Gradle, no curl, no Python (P2-T9).

# =============================================================================
# gelasio: Georgia stand-in with Georgia's line height. Stock Gelasio matches
# Georgia's letter widths but its line height is ~46% taller (measured on the
# corpus: a 1-page resume became 2 pages); patch_gelasio.py fixes the vertical
# metrics and renames the family so it can't be confused with an unpatched
# Gelasio install.
# =============================================================================
FROM ubuntu:24.04 AS gelasio

ARG GELASIO_REF=master
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl python3-fonttools ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY tools/patch_gelasio.py /opt/fonts/patch_gelasio.py
RUN mkdir -p /opt/fonts/gelasio-src /opt/fonts/gelasio-rt \
    && for style in Regular Bold Italic BoldItalic; do \
         curl -sfL -o /opt/fonts/gelasio-src/Gelasio-$style.ttf \
           "https://raw.githubusercontent.com/SorkinType/Gelasio/${GELASIO_REF}/fonts/ttf/Gelasio-$style.ttf"; \
       done \
    && python3 /opt/fonts/patch_gelasio.py /opt/fonts/gelasio-src /opt/fonts/gelasio-rt

# =============================================================================
# os: Ubuntu + LibreOffice + the font families in PHASE1_SPEC.md section 2.
# fonts-crosextra-{carlito,caladea}: metric-compatible Calibri/Cambria
# fonts-liberation2: metric-compatible Arial/Times New Roman/Courier New
# fonts-texgyre: TeX Gyre Pagella/Bonum (Book Antiqua/Bookman Old Style)
# fonts-lmodern: Latin Modern (LaTeX-original option, spec Phase D font map)
# fonts-dejavu: Verdana/Lucida Sans Unicode fallback + symbol fallback
# =============================================================================
FROM ubuntu:24.04 AS os

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer \
        fonts-crosextra-carlito \
        fonts-crosextra-caladea \
        fonts-liberation2 \
        fonts-texgyre \
        fonts-lmodern \
        fonts-dejavu \
        fontconfig \
    && rm -rf /var/lib/apt/lists/*

COPY --from=gelasio /opt/fonts/gelasio-rt/ /usr/share/fonts/truetype/gelasio-rt/
RUN fc-cache -f \
    && soffice --headless --version \
    && fc-list | grep -q "Gelasio RT" \
    && echo "font + LibreOffice check OK"

# =============================================================================
# dev (default target): os + JDK + Gradle + the full source tree. This is
# the image every docker-run test/build/corpus-check command in this repo
# has always meant by "resume-tailor" — build the sandbox specifically with
# `docker build --target runtime`.
# =============================================================================
FROM os AS dev

ARG GRADLE_VERSION=8.10

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN curl -sfL -o /tmp/gradle.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
    && rm /tmp/gradle.zip

WORKDIR /app
COPY . /app

CMD ["gradle", "--no-daemon", ":cli:run"]

# =============================================================================
# build: dev, run once to produce the CLI fat jar (PHASE2_SPEC.md section 3).
# =============================================================================
FROM dev AS build
RUN gradle --no-daemon :cli:jar

# =============================================================================
# runtime: os + a JRE (not JDK) + the non-root user + only tailor.jar. No
# source, no corpus/golden, no Gradle, no curl, no Python, no javac (P2-T9).
# =============================================================================
FROM os AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /usr/sbin/nologin --uid 10001 variant

WORKDIR /app
COPY --from=build /app/cli/build/libs/tailor.jar /app/tailor.jar
USER variant

# No ENTRYPOINT: the sandbox's own `docker run` invocation supplies the full
# `java -jar /app/tailor.jar <subcommand> ...` command (see PHASE2_SPEC.md
# section 3's example) as trailing args, which replace CMD entirely. This CMD
# is only the default when none are given.
CMD ["java", "-jar", "/app/tailor.jar"]

# =============================================================================
# An untargeted `docker build` (no --target) builds whichever stage is last in
# this file, and every existing `docker build -t resume-tailor .` / `docker
# run resume-tailor gradle ...` invocation (this repo's whole dev/test
# workflow) assumes that is "dev", not the non-root sandbox above. This
# closing, no-op stage keeps that default; build the sandbox specifically
# with `docker build --target runtime`.
# =============================================================================
FROM dev
