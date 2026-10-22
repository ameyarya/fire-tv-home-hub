#!/usr/bin/env bash
# HTTPS for the phone page: https://smart-home.local:8080 with no browser warnings.
#
# Makes your home's own certificate authority (once; kept on this computer in ~/.config/fire-tv-home-hub, private),
# signs a certificate for smart-home.local and the TV's address with it, and hands that to Smart Home on the TV.
# Then, on each phone, open http://smart-home.local:8765/setup once to trust the authority.
# Run it again whenever the TV's address changes (the authority stays, so phones needn't trust it again).
set -euo pipefail
. "$(dirname "$0")/lib/tv.sh"
DIR="${XDG_CONFIG_HOME:-$HOME/.config}/fire-tv-home-hub"
mkdir -p "$DIR" && chmod 700 "$DIR"
cd "$DIR"
umask 077
if [ ! -f ca.key ]; then
  echo "Making your home certificate authority (once)…"
  openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 -keyout ca.key -out ca.pem \
    -subj "/CN=Smart Home home CA/O=fire-tv-home-hub" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
fi
echo "Signing a certificate for smart-home.local and $TV_HOST…"
openssl req -newkey rsa:2048 -nodes -sha256 -keyout server.key -out server.csr -subj "/CN=smart-home.local" 2>/dev/null
cat > server.ext <<EXT
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:smart-home.local,IP:$TV_HOST
EXT
# 820 days: within what phones accept for certificates from an authority you installed yourself (825).
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 820 -sha256 -extfile server.ext -out server.pem 2>/dev/null
# Android 11 reads the classic PKCS#12 encryption, not OpenSSL 3's default.
openssl pkcs12 -export -inkey server.key -in server.pem -certfile ca.pem -name smart-home -passout pass:smart-home \
  -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 -out server.p12
P12=$(base64 < server.p12 | tr -d '\n'); CA=$(base64 < ca.pem | tr -d '\n')
adb connect "$TV" >/dev/null
out=$(adb -s "$TV" shell am broadcast -a com.homecontrol.tv.HTTPS_CERT -n com.homecontrol.tv/.ControlReceiver --es p12 "$P12" --es ca "$CA")
case "$out" in *'"https":true'*) ;; *) echo "Smart Home didn't take the certificate: $out" >&2; exit 1 ;; esac
rm -f server.csr server.ext
echo "Done. On each phone, open http://smart-home.local:8765/setup once, then use https://smart-home.local:8080"
