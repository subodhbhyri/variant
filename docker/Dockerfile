# Phase 1 build/run image. Pins LibreOffice + fonts because calibration
# numbers (PHASE1_SPEC.md section 2) depend on both.
FROM eclipse-temurin:21-jdk-noble AS base

ARG GRADLE_VERSION=8.10
ARG GELASIO_REF=master

ENV DEBIAN_FRONTEND=noninteractive

# --- LibreOffice + the font families in spec section 2 -----------------------
# fonts-crosextra-{carlito,caladea}: metric-compatible Calibri/Cambria
# fonts-liberation2: metric-compatible Arial/Times New Roman/Courier New
# fonts-texgyre: TeX Gyre Pagella/Bonum (Book Antiqua/Bookman Old Style)
# fonts-lmodern: Latin Modern (LaTeX-original option, spec Phase D font map)
# fonts-dejavu: Verdana/Lucida Sans Unicode fallback + symbol fallback
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer \
        fonts-crosextra-carlito \
        fonts-crosextra-caladea \
        fonts-liberation2 \
        fonts-texgyre \
        fonts-lmodern \
        fonts-dejavu \
        fontconfig \
        python3-fonttools \
        curl \
        unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/fonts

# --- Gelasio RT: Georgia stand-in with Georgia's line height -----------------
# Stock Gelasio matches Georgia's letter widths but its line height is ~46%
# taller (measured on the corpus: a 1-page resume became 2 pages). We patch
# the vertical metrics and rename the family so it can't be confused with an
# unpatched Gelasio install. See tools/patch_gelasio.py for the numbers.
COPY tools/patch_gelasio.py /opt/fonts/patch_gelasio.py
RUN mkdir -p /opt/fonts/gelasio-src /usr/share/fonts/truetype/gelasio-rt \
    && for style in Regular Bold Italic BoldItalic; do \
         curl -sfL -o /opt/fonts/gelasio-src/Gelasio-$style.ttf \
           "https://raw.githubusercontent.com/SorkinType/Gelasio/${GELASIO_REF}/fonts/ttf/Gelasio-$style.ttf"; \
       done \
    && python3 /opt/fonts/patch_gelasio.py /opt/fonts/gelasio-src /usr/share/fonts/truetype/gelasio-rt \
    && fc-cache -f

# --- Gradle --------------------------------------------------------------
RUN curl -sfL -o /tmp/gradle.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
    && rm /tmp/gradle.zip

WORKDIR /app
COPY . /app

# Sanity check the two things step 1.1's tests depend on: LibreOffice starts,
# and the font we just patched is registered under its new name.
RUN soffice --headless --version \
    && fc-list | grep -q "Gelasio RT" \
    && echo "font + LibreOffice check OK"

CMD ["gradle", "--no-daemon", ":cli:run"]
