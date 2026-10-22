#!/bin/sh
# Descarga los datos de OSM del release y los verifica contra las sumas del repo, no contra las que vienen con ellos.
set -eu
cd /data
if sha256sum -c /expected/SHA256SUMS >/dev/null 2>&1; then
    echo "Datos de OSM ya presentes y verificados."
    exit 0
fi
for file in suba.rgraph suba-sitios-sensibles.csv; do
    wget -q -O "$file.part" "$RELEASE_URL/$file"
    mv "$file.part" "$file"
done
sha256sum -c /expected/SHA256SUMS
