#!/usr/bin/env python3
"""Enregistre une empreinte (doigt simulé n° 1) sur le téléphone virtuel, pour tester
l'ouverture du coffre d'Orbite par empreinte. Un code de verrouillage (1111) est d'abord
réglé : Android l'exige avant toute empreinte. Les écrans des Réglages sont lus avec
uiautomator et les boutons touchés par leur texte. Code de sortie 0 = empreinte enregistrée."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PIN = '1111'
ACTIONS = [
    ['-a', 'android.settings.BIOMETRIC_ENROLL', '--ei', 'android.provider.extra.BIOMETRIC_AUTHENTICATORS_ALLOWED', '15'],
    ['-a', 'android.settings.FINGERPRINT_ENROLL'],
]


def adb(*args, timeout=40):
    try:
        r = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:  # adb bloqué : on continue, l'étape suivante réessaiera
        return f'ERREUR {e}'


def shell(cmd, timeout=40):
    return adb('shell', cmd, timeout=timeout)


def finger():
    adb('emu', 'finger', 'touch', '1')
    time.sleep(0.5)
    adb('emu', 'finger', 'remove', '1')


def enrolled():
    d = shell('dumpsys fingerprint')
    return any(int(x) > 0 for x in re.findall(r'"count"\s*:\s*(\d+)', d))


def screen():
    out = shell('uiautomator dump /sdcard/orbite-ui.xml', timeout=30)
    if 'dumped to' not in out:
        return None, out.strip()
    xml = adb('exec-out', 'cat', '/sdcard/orbite-ui.xml')
    try:
        root = ET.fromstring(xml[xml.index('<?xml'):] if '<?xml' in xml else xml)
    except Exception as e:
        return None, f'xml illisible ({e})'
    nodes = []
    for n in root.iter('node'):
        b = [int(x) for x in re.findall(r'\d+', n.get('bounds') or '')]
        nodes.append({
            'text': (n.get('text') or '').strip(),
            'desc': (n.get('content-desc') or '').strip(),
            'cls': (n.get('class') or '').lower(),
            'enabled': n.get('enabled') == 'true',
            'pw': n.get('password') == 'true',
            'b': b if len(b) == 4 else None,
        })
    return nodes, ''


def tap(n):
    x1, y1, x2, y2 = n['b']
    shell(f'input tap {(x1 + x2) // 2} {(y1 + y2) // 2}')


def find(nodes, *labels):
    for lab in labels:
        for n in nodes:
            if n['b'] and n['enabled'] and lab in (n['text'].lower(), n['desc'].lower()):
                return n
    return None


def main():
    shell('svc power stayon true')
    shell('settings put system screen_off_timeout 2147483647')
    shell('input keyevent KEYCODE_WAKEUP')
    shell('wm dismiss-keyguard')
    print('Code de verrouillage :', shell('locksettings set-pin ' + PIN).strip())
    if enrolled():
        print('EMPREINTE déjà enregistrée')
        return 0
    for a in ACTIONS:
        out = shell('am start ' + ' '.join(a)).strip()
        print('Ouverture des réglages :', out.replace('\n', ' ')[:200])
        if 'Error' not in out:
            break
    time.sleep(4)
    pin_sent = 0
    for step in range(70):
        if enrolled():
            nodes, _ = screen()
            n = find(nodes or [], 'done', 'terminé', 'ok')
            if n:
                tap(n)
            shell('input keyevent KEYCODE_HOME')
            print(f'EMPREINTE enregistrée (étape {step})')
            return 0
        nodes, err = screen()
        if nodes is None:
            finger()
            print(f'  {step}: écran non lisible ({err[:80]}) → doigt posé')
            time.sleep(1.5)
            continue
        texts = ' | '.join(x for n in nodes for x in (n['text'], n['desc']) if x)
        low = texts.lower()
        field = next((n for n in nodes if n['b'] and (n['pw'] or 'edittext' in n['cls'])), None)
        if field and pin_sent < 4 and re.search(r'\bpin\b|password|mot de passe|code', low):
            tap(field)
            time.sleep(0.3)
            shell('input text ' + PIN)
            shell('input keyevent 66')
            pin_sent += 1
            act = 'code saisi'
        else:
            n = find(nodes, 'i agree', 'agree', 'accept', "j'accepte", 'accepter')
            if not n and not re.search(r'fingerprint added|empreinte ajoutée', low):
                n = find(nodes, 'more', 'plus', 'next', 'suivant', 'continue', 'continuer', 'start', 'got it', 'ok')
            if n:
                tap(n)
                act = 'bouton « ' + (n['text'] or n['desc']) + ' »'
            else:
                finger()
                act = 'doigt posé'
        print(f'  {step}: {act} — {texts[:160]}')
        time.sleep(1.5)
    print('EMPREINTE non enregistrée')
    print(shell('dumpsys fingerprint')[:1500])
    shell('screencap -p /sdcard/empreinte.png')
    adb('pull', '/sdcard/empreinte.png', 'empreinte-echec.png')
    shell('input keyevent KEYCODE_HOME')
    return 1


if __name__ == '__main__':
    sys.exit(main())
