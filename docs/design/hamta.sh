#!/bin/sh
# Hämtar en Stitch-skärm: hamta.sh <namn> <screenshot-url> <html-url>
cd "$(dirname "$0")" && curl -sL -o "$1.png" "$2=w${4:-780}" && curl -sL -o "$1.html" "$3" || exit 1
# Utan inloggning svarar Google med en inloggningssida i stället för skärmens HTML.
if grep -q 'accounts.google.com' "$1.html"; then rm -f "$1.html"; echo "$1: HTML kräver inloggning, bara PNG sparad" >&2; exit 1; fi
echo "$1: $(stat -c%s "$1.png") B png, $(stat -c%s "$1.html") B html"
