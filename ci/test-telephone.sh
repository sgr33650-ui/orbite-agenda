#!/usr/bin/env bash
# Test de bout en bout d'Orbite sur un téléphone Android virtuel.
# 1. installe l'APK  2. crée un agenda de test  3. lance Orbite en mode test
# 4. vérifie la lecture native de l'agenda, l'import, le stockage, le chiffrement et l'affichage
# 5. vérifie le coffre (lié à la puce, verrouillage sans perte de saisie, photos chiffrées, sauvegarde),
#    l'ouverture par empreinte digitale, l'écran protégé et les applications ouvertes depuis le coffre.
# Avant tout : la mise à jour s'installe par-dessus la version publiée précédente (données conservées)
# et une fausse mise à jour signée avec l'ancienne clé est refusée.
set -u
APK="${1:-Orbite.apk}"
PKG=com.orbite.agenda
TAG=ORBITE_SELFTEST
fail() { echo "::error::ÉCHEC : $1"; exit 1; }

# Attend une ligne du journal de test contenant le motif donné (60 s maximum)
wait_for() {
  local pat="$1" out=""
  for i in $(seq 1 30); do
    out=$(adb logcat -d -s "$TAG" | tr -d '\r' | grep -E "$pat" | tail -n1)
    [ -n "$out" ] && { echo "$out"; return 0; }
    sleep 2
  done
  return 1
}


echo "== Téléphone virtuel réglé sur l'heure de Paris"
adb root >/dev/null 2>&1 || true
sleep 3; adb wait-for-device
adb shell setprop persist.sys.timezone Europe/Paris || true
TZ_DEV=$(adb shell getprop persist.sys.timezone | tr -d '\r')
echo "Fuseau du téléphone : $TZ_DEV"
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 2147483647 || true

echo "== Installation de l'APK"
OUT=$(adb install -r "$APK" 2>&1); echo "$OUT"
echo "$OUT" | grep -q "Success" || fail "installation de l'APK ($OUT)"
adb shell pm grant $PKG android.permission.READ_CALENDAR || fail "autorisation agenda"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
if [ "$(cat signature.txt 2>/dev/null)" = "rotation" ] && [ -s orbite-ancienne-cle.apk ]; then
  echo "== Fausse mise à jour signée avec l'ancienne clé (publique)"
  OUT=$(adb install -r orbite-ancienne-cle.apk 2>&1); echo "$OUT"
  echo "$OUT" | grep -q "Success" && fail "une fausse mise à jour signée avec l'ancienne clé a été acceptée"
  echo "Fausse mise à jour refusée par Android : seule la nouvelle clé privée peut mettre Orbite à jour"
else
  echo "::warning::Nouvelle clé privée non utilisée (secret ORBITE_KEY_PASS absent) : test de fausse mise à jour non fait"
fi

echo "== Création d'un agenda de test sur le téléphone virtuel"
NOW=$(date +%s)
adb shell "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=test&account_type=LOCAL' --bind account_name:s:test --bind account_type:s:LOCAL --bind name:s:test --bind calendar_displayName:s:AgendaTest --bind calendar_color:i:-14575885 --bind calendar_access_level:i:700 --bind ownerAccount:s:test --bind visible:i:1 --bind sync_events:i:1" || true
CAL_ID=$(adb shell "content query --uri content://com.android.calendar/calendars --projection _id:calendar_displayName" | tr -d '\r' | grep AgendaTest | sed -E 's/.*_id=([0-9]+).*/\1/' | head -n1)
echo "Agenda de test : '${CAL_ID}'"
EXPECT=0
if [ -n "$CAL_ID" ]; then
  T1=$(( (NOW + 86400) * 1000 )); T2=$(( T1 + 3600000 ))
  D1=$(( (NOW / 86400 + 3) * 86400 * 1000 )); D2=$(( D1 + 2 * 86400000 ))
  T3=$(( (NOW + 2 * 86400) * 1000 ))
  adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL_ID --bind title:s:RdvDentiste --bind dtstart:l:$T1 --bind dtend:l:$T2 --bind eventTimezone:s:Europe/Paris --bind eventLocation:s:Cabinet" || true
  adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL_ID --bind title:s:Vacances --bind dtstart:l:$D1 --bind dtend:l:$D2 --bind allDay:i:1 --bind eventTimezone:s:UTC" || true
  adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL_ID --bind title:s:Sport --bind dtstart:l:$T3 --bind duration:s:P3600S --bind rrule:s:'FREQ=WEEKLY;COUNT=3' --bind eventTimezone:s:Europe/Paris" || true
  TITLES=$(adb shell "content query --uri content://com.android.calendar/events --projection title --where calendar_id=$CAL_ID" | tr -d '\r')
  echo "$TITLES"
  echo "$TITLES" | grep -q "title=RdvDentiste" && EXPECT=$((EXPECT + 1))
  echo "$TITLES" | grep -q "title=Vacances" && EXPECT=$((EXPECT + 1))
  echo "$TITLES" | grep -q "title=Sport" && EXPECT=$((EXPECT + 3))
fi
echo "Rendez-vous attendus à l'import : $EXPECT"

echo "== Lancement d'Orbite en mode test"
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity --ez orbite_selftest true || fail "lancement de l'application"
RES=""
for i in $(seq 1 90); do
  RES=$(adb logcat -d -s "$TAG" | tr -d '\r')
  echo "$RES" | grep -q " JS RESULT " && break
  sleep 2
done
echo "$RES"
echo "== Messages de l'interface"
adb logcat -d -s ORBITE_JS | tr -d '\r' | tail -n 40
adb logcat -d | tr -d '\r' | grep -E "FATAL EXCEPTION|AndroidRuntime" -A 12 | tail -n 40

NATIVE_LINE=$(echo "$RES" | grep "NATIVE" | tail -n1)
JS_LINE=$(echo "$RES" | grep " JS RESULT " | tail -n1)
echo "$NATIVE_LINE" | grep -q "NATIVE ok=true" || fail "lecture de l'agenda du téléphone ($NATIVE_LINE)"
[ -n "$JS_LINE" ] || fail "l'interface n'a pas répondu au test"
echo "$JS_LINE" | grep -q '"recur":{"pass":true' || fail "calcul des dates répétées sur le téléphone ($JS_LINE)"
echo "Dates répétées vérifiées sur le téléphone (changement d'heure, fin de mois, 29 février, dates supprimées)"
echo "$JS_LINE" | grep -q '"vault":{"pass":true' || fail "coffre sur le téléphone ($JS_LINE)"
VAULT=$(echo "$JS_LINE" | grep -o '"vault":{[^}]*}')
echo "$VAULT"
echo "$VAULT" | grep -q '"hw":1' || fail "coffre non lié à la puce du téléphone ($VAULT)"
echo "Coffre vérifié sur le téléphone : lié à la puce (copie inutilisable), chiffrement, photos chiffrées à part, sauvegarde chiffrée, attente après 5 codes faux, verrou pendant une saisie sans perte, changement de code"
echo "$JS_LINE" | grep -q '"pass":true' || fail "auto-test de l'interface ($JS_LINE)"
if [ "$EXPECT" -gt 0 ]; then
  ADDED=$(echo "$JS_LINE" | grep -o '"added":[0-9]*' | head -n1 | cut -d: -f2)
  [ "${ADDED:-0}" -ge "$EXPECT" ] || fail "import : ${ADDED:-0} rendez-vous au lieu de $EXPECT"
  echo "Import vérifié : $ADDED rendez-vous (attendus : $EXPECT)"
fi

if [ -n "$CAL_ID" ]; then
  echo "== Une invitation arrive dans l'agenda pendant qu'Orbite est ouvert"
  T4=$(( (NOW + 4 * 86400) * 1000 )); T5=$(( T4 + 1800000 ))
  adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL_ID --bind title:s:InvitationMail --bind dtstart:l:$T4 --bind dtend:l:$T5 --bind eventTimezone:s:Europe/Paris" || fail "création de l'invitation de test"
  L=$(wait_for "JS SYNC .*InvitationMail") || fail "l'invitation n'est pas arrivée dans Orbite en direct"
  echo "$L"

  echo "== Retour dans l'application après être passé sur l'écran d'accueil"
  adb shell input keyevent KEYCODE_HOME; sleep 2
  T6=$(( (NOW + 5 * 86400) * 1000 )); T7=$(( T6 + 1800000 ))
  adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL_ID --bind title:s:PendantAbsence --bind dtstart:l:$T6 --bind dtend:l:$T7 --bind eventTimezone:s:Europe/Paris" || true
  sleep 4
  adb shell am start -n $PKG/.MainActivity >/dev/null
  L=$(wait_for "JS SYNC .*PendantAbsence") || fail "rendez-vous ajouté pendant l'absence non repris"
  echo "$L"
fi

echo "== Invitation reçue par mail, ouverte avec Orbite"
ICS="BEGIN:VCALENDAR
VERSION:2.0
METHOD:REQUEST
BEGIN:VEVENT
UID:test-invite-42@orbite
DTSTART:20261201T130000Z
DTEND:20261201T140000Z
SUMMARY:ReunionICS
LOCATION:Salle 3
END:VEVENT
END:VCALENDAR"
adb shell "am start -a android.intent.action.SEND -t text/calendar -n $PKG/.MainActivity --es android.intent.extra.TEXT '$ICS'" >/dev/null
L=$(wait_for 'JS ICS .*"ok":true.*ReunionICS') || fail "invitation .ics non ajoutée"
echo "$L"
if [ "$TZ_DEV" = "Europe/Paris" ]; then
  echo "$L" | grep -q '"start":"2026-12-01T14:00"' || fail "heure de l'invitation incorrecte ($L)"
  echo "Heure vérifiée : 14:00 à Paris"
else
  echo "$L" | grep -q '"start":"2026-12-01T' || fail "date de l'invitation incorrecte ($L)"
fi

echo "== Annulation reçue par mail"
CANCEL=$(echo "$ICS" | sed -e 's/METHOD:REQUEST/METHOD:CANCEL/' -e 's/SUMMARY:ReunionICS/STATUS:CANCELLED\nSUMMARY:ReunionICS/')
CANCEL=$(printf '%b' "$CANCEL")
adb shell "am start -a android.intent.action.SEND -t text/calendar -n $PKG/.MainActivity --es android.intent.extra.TEXT '$CANCEL'" >/dev/null
L=$(wait_for 'JS ICS .*"action":"annulation"') || fail "annulation non traitée"
echo "$L"
echo "$L" | grep -q '"count":1' || fail "le rendez-vous annulé n'a pas été retiré ($L)"

echo "== Invitation répétée reçue par mail (tous les jeudis, 4 fois)"
REC="BEGIN:VCALENDAR
VERSION:2.0
METHOD:REQUEST
BEGIN:VEVENT
UID:test-serie-7@orbite
DTSTART:20261203T150000Z
DTEND:20261203T160000Z
RRULE:FREQ=WEEKLY;BYDAY=TH;COUNT=4
SUMMARY:SerieICS
END:VEVENT
END:VCALENDAR"
adb shell "am start -a android.intent.action.SEND -t text/calendar -n $PKG/.MainActivity --es android.intent.extra.TEXT '$REC'" >/dev/null
L=$(wait_for 'JS ICS .*SerieICS') || fail "invitation répétée non traitée"
echo "$L"
echo "$L" | grep -q '"rec":"week"' || fail "répétition de l'invitation non reconnue ($L)"
echo "$L" | grep -q '"occ":4' || fail "nombre de dates de l'invitation répétée incorrect ($L)"
echo "Invitation répétée vérifiée : 4 jeudis"

adb logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "l'application s'est arrêtée brutalement"

# Pose le doigt simulé jusqu'à ce que le motif apparaisse dans le journal de test (60 s maximum)
touch_until() {
  local pat="$1" out=""
  for i in $(seq 1 24); do
    out=$(adb logcat -d -s "$TAG" | tr -d '\r' | grep -E "$pat" | tail -n1)
    [ -n "$out" ] && { echo "$out"; return 0; }
    if adb logcat -d -s "$TAG" | grep -q "BIO_PROMPT"; then
      adb emu finger touch 1 >/dev/null 2>&1; sleep 0.5; adb emu finger remove 1 >/dev/null 2>&1
    fi
    sleep 2
  done
  return 1
}

echo "== Empreinte digitale : enregistrement d'un doigt sur le téléphone virtuel"
BIO_ENROLLED=0
python3 ci/empreinte.py && BIO_ENROLLED=1
echo "== Coffre : activation puis ouverture par empreinte"
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity --ez orbite_biotest true >/dev/null || fail "lancement du test d'empreinte"
L=$(wait_for "JS BIO (STATUS|FAIL)") || fail "le test d'empreinte n'a pas démarré"
echo "$L"
if echo "$L" | grep -q '"level":0'; then
  [ "$BIO_ENROLLED" = 1 ] && fail "empreinte enregistrée sur le téléphone mais pas reconnue par Orbite ($L)"
  echo "::warning::Empreinte non testée : le téléphone virtuel n'a pas pu enregistrer de doigt"
else
  L=$(touch_until "JS BIO (ENABLED|FAIL)") || fail "activation de l'empreinte sans réponse"
  echo "$L"
  echo "$L" | grep -q "JS BIO ENABLED" || fail "activation de l'empreinte ($L)"
  echo "$L" | grep -q '"rawStored":false' || fail "clé du coffre enregistrée en clair ($L)"
  L=$(touch_until "JS BIO (UNLOCKED|FAIL)") || fail "ouverture par empreinte sans réponse"
  echo "$L"
  echo "$L" | grep -q '"open":true,"secret":true' || fail "ouverture du coffre par empreinte ($L)"
  echo "$L" | grep -q '"secure":true' || fail "écran non protégé quand le coffre est ouvert ($L)"
  wait_for "JS BIO READY" >/dev/null || fail "test d'absence non prêt"
  echo "== Coffre : courte absence (autre application), il reste ouvert"
  adb shell input keyevent KEYCODE_HOME; sleep 5
  adb shell am start -n $PKG/.MainActivity >/dev/null
  L=$(wait_for "JS BIO SHORT") || fail "retour dans Orbite après une courte absence"
  echo "$L"
  echo "$L" | grep -q '"open":true' || fail "coffre fermé après une absence de 5 s ($L)"
  echo "== Coffre : absence plus longue que le délai choisi, il se ferme"
  adb shell input keyevent KEYCODE_HOME; sleep 16
  adb shell am start -n $PKG/.MainActivity >/dev/null
  L=$(wait_for "JS BIO LONG") || fail "retour dans Orbite après une longue absence"
  echo "$L"
  echo "$L" | grep -q '"open":false' || fail "coffre resté ouvert après le délai ($L)"
  echo "$L" | grep -q '"secure":false' || fail "écran encore protégé une fois le coffre fermé ($L)"
  echo "Empreinte vérifiée sur le téléphone : activation, ouverture, clé protégée par le téléphone, délai de verrouillage"
fi
adb logcat -d -s "$TAG" | tr -d '\r' | grep -E "BIO_" | tail -n 12
adb logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "l'application s'est arrêtée brutalement (empreinte)"

echo "== Coffre : applications choisies puis ouvertes depuis le coffre"
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity --ez orbite_appstest true >/dev/null || fail "lancement du test des applications"
L=$(wait_for "JS APPS (LIST|FAIL)") || fail "le test des applications n'a pas démarré"
echo "$L"
echo "$L" | grep -q '"settings":true' || fail "liste des applications du téléphone ($L)"
echo "$L" | grep -q '"picked":true' || fail "choix d'une application dans le coffre ($L)"
echo "$L" | grep -q '"icon":true' || fail "icône de l'application ($L)"
echo "$L" | grep -q '"kept":true' || fail "application du coffre perdue après verrouillage ($L)"
echo "$L" | grep -q '"self":false' || fail "Orbite apparaît dans sa propre liste ($L)"
echo "$L" | grep -q '"plain":false' || fail "liste des applications du coffre enregistrée en clair ($L)"
L=$(wait_for "JS APPS (SAVE|FAIL)") || fail "sauvegarde du coffre non créée"
echo "$L"
echo "$L" | grep -q '"r":"ok"' || fail "sauvegarde non enregistrée dans Téléchargements ($L)"
SAVED=$(adb shell ls /sdcard/Download/ | tr -d '\r' | grep "Orbite-coffre-test" | head -n1)
[ -n "$SAVED" ] || fail "fichier de sauvegarde absent de Téléchargements"
adb pull "/sdcard/Download/$SAVED" sauvegarde-test.json >/dev/null || fail "lecture de la sauvegarde"
head -c 60 sauvegarde-test.json; echo
grep -q '"format":"orbite-coffre"' sauvegarde-test.json || fail "fichier de sauvegarde illisible"
grep -q -E 'RECUP-1234|SECRET-TEST|Test clé' sauvegarde-test.json && fail "secret lisible en clair dans la sauvegarde"
echo "Sauvegarde vérifiée : $SAVED dans Téléchargements, chiffrée"
L=$(wait_for "JS APPS (TILE|FAIL)") || fail "application absente du coffre"
echo "$L"
echo "$L" | grep -q '"shown":true' || fail "application absente de l'onglet Applications ($L)"
wait_for "JS APPS (LAUNCHED|FAIL)" >/dev/null || fail "ouverture de l'application"
TOP=""
for i in $(seq 1 10); do
  sleep 1
  TOP=$(adb shell dumpsys activity activities | tr -d '\r' | grep -E "topResumedActivity|mResumedActivity|ResumedActivity" | head -n 3)
  echo "$TOP" | grep -q "com.android.settings" && break
done
echo "$TOP"
echo "$TOP" | grep -q "com.android.settings" || fail "l'application choisie ne s'est pas ouverte depuis le coffre ($TOP)"
echo "Applications vérifiées : liste du téléphone, icônes, choix chiffré, ouverture depuis le coffre"
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
adb logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "l'application s'est arrêtée brutalement (applications)"

if [ -s precedente.apk ]; then
  echo "== Mise à jour depuis la version publiée précédente (coffre existant, données conservées)"
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb install precedente.apk || fail "installation de la version précédente"
  adb shell pm grant $PKG android.permission.READ_CALENDAR || true
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --ez orbite_selftest true >/dev/null || fail "lancement de la version précédente"
  OLD=$(wait_for " JS RESULT ") || fail "la version précédente n'a pas fini son auto-test"
  echo "$OLD" | grep -o '"vault":{[^}]*}'
  adb shell am force-stop $PKG
  OUT=$(adb install -r "$APK" 2>&1); echo "$OUT"
  echo "$OUT" | grep -q "Success" || fail "la mise à jour ne s'installe pas par-dessus la version publiée ($OUT)"
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --ez orbite_selftest true >/dev/null || fail "lancement après la mise à jour"
  NEW=$(wait_for " JS RESULT ") || fail "pas de réponse après la mise à jour"
  V=$(echo "$NEW" | grep -o '"vault":{[^}]*}'); echo "$V"
  echo "$V" | grep -q '"pass":true' || fail "coffre après la mise à jour ($V)"
  echo "$V" | grep -q '"existed":true' || fail "coffre de la version précédente perdu à la mise à jour ($V)"
  echo "$V" | grep -q '"hw":1' || fail "coffre de la version précédente non lié à la puce ($V)"
  echo "Mise à jour vérifiée : le coffre de la version publiée s'ouvre avec son code, ses données sont là, il passe sous la protection de la puce"
  adb logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "l'application s'est arrêtée brutalement (mise à jour)"
fi

echo "== Erreurs éventuelles de l'interface"
adb logcat -d -s ORBITE_JS | tr -d '\r' | grep -iE "error|uncaught" | tail -n 20
adb logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "l'application s'est arrêtée brutalement"

echo "== Capture d'écran"
adb shell screencap -p /sdcard/orbite.png && adb pull /sdcard/orbite.png orbite-test.png || true
echo "RÉUSSITE : Orbite fonctionne sur le téléphone virtuel"
