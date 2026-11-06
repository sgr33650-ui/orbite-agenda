#!/usr/bin/env bash
# Test de bout en bout d'Orbite sur un téléphone Android virtuel.
# 1. installe l'APK  2. crée un agenda de test  3. lance Orbite en mode test
# 4. vérifie la lecture native de l'agenda, l'import, le stockage, le chiffrement et l'affichage.
set -u
APK="${1:-Orbite.apk}"
PKG=com.orbite.agenda
TAG=ORBITE_SELFTEST
fail() { echo "::error::ÉCHEC : $1"; exit 1; }

echo "== Téléphone virtuel réglé sur l'heure de Paris"
adb root >/dev/null 2>&1 || true
sleep 3; adb wait-for-device
adb shell setprop persist.sys.timezone Europe/Paris || true
TZ_DEV=$(adb shell getprop persist.sys.timezone | tr -d '\r')
echo "Fuseau du téléphone : $TZ_DEV"

echo "== Installation de l'APK"
adb install -r "$APK" || fail "installation de l'APK"
adb shell pm grant $PKG android.permission.READ_CALENDAR || fail "autorisation agenda"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

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
echo "$JS_LINE" | grep -q '"pass":true' || fail "auto-test de l'interface ($JS_LINE)"
if [ "$EXPECT" -gt 0 ]; then
  ADDED=$(echo "$JS_LINE" | grep -o '"added":[0-9]*' | head -n1 | cut -d: -f2)
  [ "${ADDED:-0}" -ge "$EXPECT" ] || fail "import : ${ADDED:-0} rendez-vous au lieu de $EXPECT"
  echo "Import vérifié : $ADDED rendez-vous (attendus : $EXPECT)"
fi

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

echo "== Erreurs éventuelles de l'interface"
adb logcat -d -s ORBITE_JS | tr -d '\r' | grep -iE "error|uncaught" | tail -n 20
adb logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "l'application s'est arrêtée brutalement"

echo "== Capture d'écran"
adb shell screencap -p /sdcard/orbite.png && adb pull /sdcard/orbite.png orbite-test.png || true
echo "RÉUSSITE : Orbite fonctionne sur le téléphone virtuel"
