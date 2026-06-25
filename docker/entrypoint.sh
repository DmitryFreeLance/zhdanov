#!/usr/bin/env bash
set -euo pipefail

TRUSTED_CERTS_DIR="${APP_TRUSTED_CERTS_DIR:-/app/certs}"
IMPORT_ENABLED="${APP_IMPORT_TRUSTED_CERTS:-true}"
JAVA_CACERTS_PASSWORD="${APP_JAVA_CACERTS_PASSWORD:-changeit}"

find_cacerts() {
  if [[ -n "${JAVA_HOME:-}" && -f "${JAVA_HOME}/lib/security/cacerts" ]]; then
    printf '%s\n' "${JAVA_HOME}/lib/security/cacerts"
    return 0
  fi

  local java_bin java_home
  java_bin="$(readlink -f "$(command -v java)")"
  java_home="$(dirname "$(dirname "${java_bin}")")"
  if [[ -f "${java_home}/lib/security/cacerts" ]]; then
    printf '%s\n' "${java_home}/lib/security/cacerts"
    return 0
  fi

  return 1
}

import_trusted_certs() {
  if [[ "${IMPORT_ENABLED}" != "true" ]]; then
    echo "Skipping custom trusted certificate import because APP_IMPORT_TRUSTED_CERTS=${IMPORT_ENABLED}"
    return 0
  fi

  if [[ ! -d "${TRUSTED_CERTS_DIR}" ]]; then
    echo "No trusted certificate directory found at ${TRUSTED_CERTS_DIR}, continuing with default Java truststore"
    return 0
  fi

  local cacerts
  cacerts="$(find_cacerts)" || {
    echo "Could not locate Java cacerts truststore" >&2
    return 1
  }

  shopt -s nullglob
  local cert_files=("${TRUSTED_CERTS_DIR}"/*.crt "${TRUSTED_CERTS_DIR}"/*.cer "${TRUSTED_CERTS_DIR}"/*.pem)
  shopt -u nullglob

  if [[ ${#cert_files[@]} -eq 0 ]]; then
    echo "Trusted certificate directory ${TRUSTED_CERTS_DIR} is present but contains no .crt/.cer/.pem files"
    return 0
  fi

  echo "Importing custom trusted certificates from ${TRUSTED_CERTS_DIR} into ${cacerts}"
  local cert_file alias_name
  for cert_file in "${cert_files[@]}"; do
    alias_name="custom-$(basename "${cert_file}" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9._-]/-/g')"
    keytool -delete -alias "${alias_name}" -keystore "${cacerts}" -storepass "${JAVA_CACERTS_PASSWORD}" >/dev/null 2>&1 || true
    keytool -importcert -noprompt \
      -alias "${alias_name}" \
      -file "${cert_file}" \
      -keystore "${cacerts}" \
      -storepass "${JAVA_CACERTS_PASSWORD}" >/dev/null
    echo "Imported trusted certificate ${cert_file} as alias ${alias_name}"
  done
}

import_trusted_certs

exec "$@"
