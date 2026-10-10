#!/usr/bin/env bash
# Creates Tandem's release signing key and stores it as GitHub secrets for release.yml.
#
# Run it ONCE, by hand, on a trusted machine with `openssl` and an authenticated `gh`
# (repo admin). It:
#   1. creates a PKCS12 keystore (RSA 4096, alias "tandem", valid 30 years) with a random
#      password in ~/tandem-signing (directory 700, files 600),
#   2. sets the repository secrets TANDEM_KEYSTORE_BASE64, TANDEM_KEYSTORE_PASSWORD,
#      TANDEM_KEY_ALIAS and TANDEM_KEY_PASSWORD (values go through stdin, never argv/echo),
#   3. sets the repository VARIABLE TANDEM_CERT_SHA256 (public; release.yml refuses to publish
#      an APK signed with any other certificate),
#   4. prints only the certificate's SHA-256 fingerprint.
#
# It refuses to overwrite an existing keystore: losing or replacing the key means every
# installed copy must be uninstalled before an update can be installed.
#
# Usage:
#   scripts/create-signing-key.sh                 # create key + set secrets
#   scripts/create-signing-key.sh --upload-only   # re-upload secrets from an existing key
# Environment (optional):
#   TANDEM_SIGNING_DIR  default: ~/tandem-signing
#   TANDEM_REPO         default: Demacish/tandem
#
# See docs/RELEASE.md (Swedish) for the whole release procedure and the offline backup.
set -euo pipefail
set +x # never trace: commands below handle secrets

SIGNING_DIR="${TANDEM_SIGNING_DIR:-$HOME/tandem-signing}"
REPO="${TANDEM_REPO:-Demacish/tandem}"
# The secrets go into this GitHub environment, which only v* tags may use (release.yml).
ENVIRONMENT="${TANDEM_ENVIRONMENT:-release}"
ALIAS="tandem"
VALIDITY_DAYS=10958 # 30 years
KEYSTORE="$SIGNING_DIR/tandem-release.p12"
PASSWORD_FILE="$SIGNING_DIR/keystore-password.txt"
CERT="$SIGNING_DIR/tandem-release-cert.pem"

UPLOAD_ONLY=false
case "${1:-}" in
  "") ;;
  --upload-only) UPLOAD_ONLY=true ;;
  -h | --help)
    sed -n '2,25p' "$0"
    exit 0
    ;;
  *)
    echo "Unknown argument: $1 (use --help)" >&2
    exit 2
    ;;
esac

die() {
  echo "ERROR: $*" >&2
  exit 1
}

for tool in openssl gh base64; do
  command -v "$tool" > /dev/null || die "'$tool' is not installed"
done
gh auth status > /dev/null 2>&1 || die "gh is not logged in (run: gh auth login)"
gh repo view "$REPO" > /dev/null || die "cannot access repository $REPO"

umask 077

create_key() {
  if [ -e "$KEYSTORE" ] || [ -e "$PASSWORD_FILE" ]; then
    die "$KEYSTORE or $PASSWORD_FILE already exists. Refusing to overwrite the release key.
       Use --upload-only to re-upload the existing key, or move the old files away yourself
       (only if you are absolutely sure: a new key breaks updates of installed apps)."
  fi
  mkdir -p "$SIGNING_DIR"
  chmod 700 "$SIGNING_DIR"

  local work
  work="$(mktemp -d "$SIGNING_DIR/.work.XXXXXX")"
  # shellcheck disable=SC2064 # expand $work now
  trap "rm -rf '$work'" EXIT

  # Random password written straight to a file (no trailing newline), never to the terminal.
  # Hex: safe for keytool, Gradle and gh without quoting issues.
  openssl rand -hex 32 | tr -d '\n' > "$PASSWORD_FILE"
  chmod 600 "$PASSWORD_FILE"

  # Private key and self-signed certificate (unencrypted only inside the 700 work dir).
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$work/key.pem" 2> /dev/null
  openssl req -new -x509 -sha256 -key "$work/key.pem" -days "$VALIDITY_DAYS" \
    -subj "/CN=Tandem/O=Tandem" -out "$work/cert.pem"

  # PKCS12 with modern encryption (readable by keytool/AGP/apksigner on JDK 17+).
  openssl pkcs12 -export -name "$ALIAS" -inkey "$work/key.pem" -in "$work/cert.pem" \
    -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg SHA256 \
    -passout "file:$PASSWORD_FILE" -out "$KEYSTORE"
  chmod 600 "$KEYSTORE"

  cp "$work/cert.pem" "$CERT" # public certificate, for reference
  chmod 600 "$CERT"

  # Sanity check: the keystore opens with the password and contains the certificate.
  openssl pkcs12 -in "$KEYSTORE" -passin "file:$PASSWORD_FILE" -nokeys -clcerts 2> /dev/null \
    | openssl x509 -noout > /dev/null || die "the new keystore could not be read back"
  rm -rf "$work"
  trap - EXIT
  echo "Created $KEYSTORE (RSA 4096, alias $ALIAS, valid $VALIDITY_DAYS days)."
}

upload_secrets() {
  [ -f "$KEYSTORE" ] || die "$KEYSTORE not found"
  [ -f "$PASSWORD_FILE" ] || die "$PASSWORD_FILE not found"
  # All values are read from stdin by gh; nothing secret appears in argv or on the terminal.
  gh api "repos/$REPO/environments/$ENVIRONMENT" --silent 2> /dev/null \
    || die "GitHub environment '$ENVIRONMENT' does not exist in $REPO (see docs/RELEASE.md)"
  base64 < "$KEYSTORE" | tr -d '\n' | gh secret set TANDEM_KEYSTORE_BASE64 --repo "$REPO" --env "$ENVIRONMENT" > /dev/null
  gh secret set TANDEM_KEYSTORE_PASSWORD --repo "$REPO" --env "$ENVIRONMENT" < "$PASSWORD_FILE" > /dev/null
  # PKCS12 has a single password: the key password is the keystore password.
  gh secret set TANDEM_KEY_PASSWORD --repo "$REPO" --env "$ENVIRONMENT" < "$PASSWORD_FILE" > /dev/null
  printf '%s' "$ALIAS" | gh secret set TANDEM_KEY_ALIAS --repo "$REPO" --env "$ENVIRONMENT" > /dev/null
  # The fingerprint is public; it stays a repository variable.
  gh variable set TANDEM_CERT_SHA256 --repo "$REPO" --body "$(fingerprint)" > /dev/null
  echo "Set secrets TANDEM_KEYSTORE_BASE64, TANDEM_KEYSTORE_PASSWORD, TANDEM_KEY_ALIAS and"
  echo "TANDEM_KEY_PASSWORD in environment '$ENVIRONMENT', and variable TANDEM_CERT_SHA256, on $REPO."
}

fingerprint() {
  openssl pkcs12 -in "$KEYSTORE" -passin "file:$PASSWORD_FILE" -nokeys -clcerts 2> /dev/null \
    | openssl x509 -noout -fingerprint -sha256 \
    | sed 's/^.*=//'
}

if [ "$UPLOAD_ONLY" = false ]; then
  create_key
fi
upload_secrets

cat << EOF

Certificate SHA-256 fingerprint (public, also needed for Android developer verification):
  $(fingerprint)

IMPORTANT - do this now:
  1. Copy BOTH files into your password manager (as attachments):
       $KEYSTORE
       $PASSWORD_FILE
  2. Make an offline backup as well (see docs/RELEASE.md, "Offline-backup").
  Without the key, no update can ever be installed over the existing app.
EOF
