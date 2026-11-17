#!/usr/bin/env bash
# Signe Orbite avec la nouvelle clé privée (mot de passe dans le secret GitHub ORBITE_KEY_PASS),
# accompagnée de la preuve de rotation signée par l'ancienne clé :
#  - Android (9 et plus) accepte la mise à jour par-dessus les versions déjà installées, données conservées ;
#  - ensuite, il refuse toute « mise à jour » signée seulement avec l'ancienne clé (retour interdit).
# Sans le secret, l'APK reste signée avec l'ancienne clé (avertissement), sauf si la rotation a déjà
# été publiée (keystore/rotation-active) : la fabrication s'arrête alors, sinon la mise à jour
# ne pourrait plus s'installer sur le téléphone.
set -euo pipefail
IN="$1"
OUT="$2"
cp "$IN" orbite-ancienne-cle.apk
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -n1)
AS="${BT}apksigner"
echo "apksigner : $AS (version $("$AS" version))"

if [ -z "${ORBITE_KEY_PASS:-}" ]; then
  if [ -f keystore/rotation-active ]; then
    echo "::error::Le secret ORBITE_KEY_PASS est absent : la mise à jour ne pourrait pas s'installer sur le téléphone."
    exit 1
  fi
  echo "::warning::Secret ORBITE_KEY_PASS absent : APK signée avec l'ancienne clé seulement."
  cp "$IN" "$OUT"
  echo "ancienne" > signature.txt
  exit 0
fi

"$AS" rotate --out lineage.bin \
  --old-signer --ks keystore/orbite.jks --ks-key-alias orbite --ks-pass pass:orbite2026 --set-rollback false \
  --new-signer --ks keystore/orbite-v2.p12 --ks-key-alias orbite2 --ks-pass env:ORBITE_KEY_PASS
"$AS" sign \
  --ks keystore/orbite.jks --ks-key-alias orbite --ks-pass pass:orbite2026 \
  --next-signer --ks keystore/orbite-v2.p12 --ks-key-alias orbite2 --ks-pass env:ORBITE_KEY_PASS \
  --lineage lineage.bin --rotation-min-sdk-version 28 \
  --out "$OUT" "$IN"
"$AS" verify --verbose --print-certs "$OUT" > verif.txt
grep -E "Verified using|DN:|SHA-256 digest|minSdk|maxSdk" verif.txt || true
"$AS" lineage --in "$OUT" --print-certs || true
grep -q "O=Orbite agenda" verif.txt || { echo "::error::la nouvelle clé n'apparaît pas dans la signature"; exit 1; }
grep -qE "Verified using v3(\.0)? scheme.*: true" verif.txt || { echo "::error::signature v3 (rotation) absente"; exit 1; }
echo "rotation" > signature.txt
echo "Signature : nouvelle clé privée, rotation depuis l'ancienne clé (retour à l'ancienne clé interdit)"
