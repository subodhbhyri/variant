#!/bin/sh
# Starts the api or the worker. With APP_TLS=true the api serves HTTPS itself (PHASE6_SPEC.md section 9.3: TLS
# everywhere, including the load balancer to the tasks) with a certificate made for this container at start-up:
# the load balancer encrypts the hop but does not validate the certificate, so a throwaway one is enough, and no
# private key is baked into the image or kept anywhere.
set -eu
if [ "${APP_TLS:-false}" = "true" ]; then
  mkdir -p /tmp/tls
  PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
  rm -f /tmp/tls/keystore.p12
  keytool -genkeypair -alias tailor -keyalg RSA -keysize 2048 -validity 3650 -storetype PKCS12 \
    -keystore /tmp/tls/keystore.p12 -storepass "$PASS" -keypass "$PASS" -dname "CN=tailor-api" >/dev/null 2>&1
  export SERVER_SSL_ENABLED=true SERVER_SSL_KEY_STORE=/tmp/tls/keystore.p12 \
         SERVER_SSL_KEY_STORE_PASSWORD="$PASS" SERVER_SSL_KEY_STORE_TYPE=PKCS12
fi
exec java -jar /app/web.jar
