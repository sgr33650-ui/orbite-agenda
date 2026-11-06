# Orbite — agenda Android

Agenda futuriste : vues jour, semaine, mois et année, coffre sécurisé par empreinte digitale, documents et photos dans les événements, partage rapide, thèmes, couleurs, polices et sons personnalisables, import de l'ancien agenda (.ics).

## Installer sur le téléphone

Lien direct vers la dernière version :
**https://github.com/sgr33650-ui/orbite-agenda/releases/latest/download/Orbite.apk**

1. Ouvrez ce lien sur le téléphone Android.
2. Ouvrez le fichier téléchargé, puis autorisez l'installation depuis le navigateur si Android le demande.
3. Pour une mise à jour, refaites la même chose : vos données sont conservées.

## Reprendre l'ancien agenda et suivre les invitations reçues par mail

Dans Orbite, touchez **Importer mon ancien agenda** (ou Paramètres › Importer l’agenda du téléphone) : les rendez-vous de tous les comptes du téléphone (Google, Xiaomi, Outlook…) sont copiés, puis **suivis automatiquement** : nouveaux rendez-vous, modifications et annulations sont repris à chaque ouverture et en direct.

- Gmail et Outlook placent les invitations reçues par mail dans l’agenda du téléphone : elles arrivent donc seules dans Orbite.
- Autre boîte mail (Orange, Free, SFR, Yahoo…) : ouvrez la pièce jointe de l’invitation (fichier .ics) et choisissez Orbite.

## Fonctionnement

Chaque envoi sur la branche `main` fabrique automatiquement un nouvel APK, l’installe et le teste sur un téléphone Android virtuel (onglet **Actions**), puis le publie dans **Releases** seulement si le test réussit.

- `app/src/main/assets/www/index.html` : l'interface de l'agenda
- `app/src/main/java/com/orbite/agenda/MainActivity.java` : la partie Android (empreinte, partage, notifications, fichiers)
- `app/src/main/java/com/orbite/agenda/PhoneCalendar.java` : lecture de l’agenda du téléphone pour l’import
- `ci/test-telephone.sh` : test automatique sur téléphone virtuel
- `keystore/orbite.jks` : clé de signature, toujours la même pour que les mises à jour s'installent par-dessus
