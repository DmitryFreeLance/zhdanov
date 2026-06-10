#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 +79991234567"
  exit 1
fi

RAW_PHONE="$1"
DIGITS="$(printf '%s' "$RAW_PHONE" | tr -cd '0-9')"

if [[ ${#DIGITS} -eq 10 ]]; then
  DIGITS="7${DIGITS}"
elif [[ ${#DIGITS} -eq 11 && ${DIGITS:0:1} == "8" ]]; then
  DIGITS="7${DIGITS:1}"
elif [[ ${#DIGITS} -eq 11 && ${DIGITS:0:1} == "7" ]]; then
  DIGITS="${DIGITS}"
else
  echo "Invalid phone number. Use +79991234567"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${ROOT_DIR}/data/wb-session-imports"
TARGET_PATH="${TARGET_DIR}/${DIGITS}.json"

mkdir -p "${TARGET_DIR}"

(cd "${ROOT_DIR}" && mvn -q -DskipTests package)

echo "Opening real browser for WB login..."
echo "Phone: +${DIGITS}"
echo "Storage state will be saved to: ${TARGET_PATH}"

cd "${ROOT_DIR}"
APP_MODE=bootstrap-wb-session \
APP_WILDBERRIES_STORAGE_STATE_PATH="${TARGET_PATH}" \
java -jar target/wb-max-bot-1.0.0.jar

echo
echo "Done. File saved:"
echo "${TARGET_PATH}"
