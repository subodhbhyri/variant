# The api/worker image (PHASE6_SPEC.md sections 2 and 10): one image, APP_ROLE picks the role.
# No LibreOffice here: rendering is the renderer service's job. Contains the MiniLM model files, fetched at
# build time and checked against the SHA-256 pins the engine itself enforces at startup, and ONNX Runtime's and
# DJL's native libraries extracted at build time so /tmp never needs to be executable (PHASE5_SPEC.md section 7).
#
# Build from the repo root:  docker build -f docker/web.Dockerfile -t tailor-web .

# -----------------------------------------------------------------------------
# models: the all-MiniLM-L6-v2 files. A hash that differs from the pin fails the build.
# -----------------------------------------------------------------------------
FROM ubuntu:24.04 AS models
ARG MODEL_BASE_URL=https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main
# The same pins as engine/.../MiniLmEmbedder.java.
ARG MODEL_SHA256=6FD5D72FE4589F189F8EBC006442DBB529BB7CE38F8082112682524616046452
ARG TOKENIZER_SHA256=BE50C3628F2BF5BB5E3A7F17B1F74611B2561A3A27EEAB05E5AA30F411572037
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /models/all-MiniLM-L6-v2 \
    && curl -sfL -o /models/all-MiniLM-L6-v2/model.onnx "${MODEL_BASE_URL}/onnx/model.onnx" \
    && curl -sfL -o /models/all-MiniLM-L6-v2/tokenizer.json "${MODEL_BASE_URL}/tokenizer.json" \
    && echo "$(echo ${MODEL_SHA256} | tr 'A-F' 'a-f')  /models/all-MiniLM-L6-v2/model.onnx" | sha256sum -c - \
    && echo "$(echo ${TOKENIZER_SHA256} | tr 'A-F' 'a-f')  /models/all-MiniLM-L6-v2/tokenizer.json" | sha256sum -c -

# -----------------------------------------------------------------------------
# build: the Spring Boot jar, then the native libraries out of their jars.
# -----------------------------------------------------------------------------
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
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
        amd64) onnx_arch=linux-x64; tok_arch=linux-x86_64 ;; \
        arm64) onnx_arch=linux-aarch64; tok_arch=linux-aarch64 ;; \
        *) echo "unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    onnx_jar=$(find /root/.gradle -iname 'onnxruntime-*.jar' ! -iname '*sources*' ! -iname '*javadoc*' | head -1); \
    tok_jar=$(find /root/.gradle -iname 'tokenizers-*.jar' ! -iname '*sources*' ! -iname '*javadoc*' | head -1); \
    test -n "$onnx_jar" && test -n "$tok_jar"; \
    mkdir -p /opt/native-libs/onnxruntime /opt/native-libs/tokenizers; \
    unzip -q -j "$onnx_jar" "ai/onnxruntime/native/${onnx_arch}/*" -d /opt/native-libs/onnxruntime; \
    unzip -q -j "$tok_jar" "native/lib/${tok_arch}/cpu/libtokenizers.so" -d /opt/native-libs/tokenizers

# -----------------------------------------------------------------------------
# runtime: a JRE, a non-root user, the jar, the model and the libraries (root-owned, read-only).
# -----------------------------------------------------------------------------
FROM ubuntu:24.04 AS runtime
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /usr/sbin/nologin --uid 10001 tailor
WORKDIR /app
COPY --from=build /app/web/build/libs/web.jar /app/web.jar
COPY --from=build /opt/native-libs /opt/native-libs
COPY --from=models /models /opt/models
ENV VARIANT_MODEL_DIR=/opt/models/all-MiniLM-L6-v2 \
    APP_MATCH_EMBEDDER=minilm \
    JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -Donnxruntime.native.path=/opt/native-libs/onnxruntime -DRUST_LIBRARY_PATH=/opt/native-libs/tokenizers/libtokenizers.so"
USER tailor
EXPOSE 8080
# APP_ROLE=api (default) or worker; read by application.yml and WebApplication.
CMD ["java", "-jar", "/app/web.jar"]
