#!/bin/sh
# Descarga los datos de cada release y los verifica contra las sumas del repo, no contra las que vienen con ellos.
# Uso: fetch-data.sh <SHA256SUMS> <url-del-release> [<SHA256SUMS> <url-del-release> ...]
set -eu
cd /data
while [ $# -ge 2 ]; do
    sums=$1
    url=$2
    shift 2
    # Archivo por archivo: si solo falta uno, solo ese se descarga.
    while read -r sum file; do
        if echo "$sum  $file" | sha256sum -c - >/dev/null 2>&1; then
            continue
        fi
        echo "Descargando $file"
        wget -q -O "$file.part" "$url/$file"
        mv "$file.part" "$file"
    done < "$sums"
    sha256sum -c "$sums"
done
