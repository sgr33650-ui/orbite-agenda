# Orbite — agenda Android

Agenda futuriste : vues jour, semaine, mois et année, coffre sécurisé par empreinte digitale (événements privés, clés de récupération et mots de passe), documents et photos dans les événements, partage rapide, thèmes, couleurs, polices et sons personnalisables, import de l'ancien agenda (.ics).

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

## Répéter un événement

Dans l’éditeur, le champ **Répétition** propose : tous les jours, du lundi au vendredi, toutes les semaines (jours au choix), tous les mois (le 17, le 3e mardi ou le dernier vendredi), tous les ans, avec un intervalle (toutes les 2 semaines…) et une fin (jamais, à une date, après X fois). Pour modifier ou supprimer une date d’une série : « Cette date seulement », « Cette date et les suivantes » ou « Toutes les dates de la série ». Les invitations répétées reçues par mail (.ics) sont reprises avec toutes leurs dates.

## Coffre sécurisé

Touchez le cadenas en haut de l’écran. À la création, choisissez un code de 4 à 8 chiffres : l’empreinte est proposée tout de suite, et le coffre s’ouvre ensuite en posant le doigt (le code reste valable).

- **Événements privés** : créez-les dans le coffre, ou cochez « Ranger dans le coffre » dans un événement ; si le coffre est fermé, il s’ouvre sur place sans perdre la saisie.
- **Clés et mots de passe** : clés de récupération des applications (codes de secours, plusieurs lignes) et mots de passe, avec Afficher, Copier (effacé du presse-papiers après 1 minute), Générer, Rechercher, Modifier, Supprimer. On peut y joindre des **photos (JPEG…) et des fichiers (PDF…)**, jusqu’à 25 Mo chacun, chiffrés un par un.
- **Applications** : choisissez des applications du téléphone (banque, messagerie…) pour les ouvrir depuis le coffre, après l’empreinte. Elles restent aussi sur l’écran d’accueil ; pour les protéger elles aussi par empreinte sur un Xiaomi, cherchez « Verrouillage des applications » dans les Paramètres.
- **Verrouillage** : après le délai choisi sans utilisation (1, 2, 5 ou 15 min), même si vous êtes passé sur une autre application. S’il se ferme pendant une saisie, celle-ci est conservée : déverrouillez et continuez.
- Tout est chiffré sur le téléphone (AES-256). La clé du coffre est protégée par votre code **mêlé à une clé de la puce de sécurité du téléphone** (Android Keystore) : une copie des fichiers d’Orbite est inutilisable sur un autre appareil. L’empreinte passe aussi par la puce. Si les empreintes du téléphone changent, ouvrez avec votre code : l’empreinte vous sera reproposée.
- **Code** : 6 à 8 chiffres tapés sur le clavier intégré d’Orbite (un clavier espion ne voit rien), ou un mot de passe avec des lettres. Après 5 codes faux, l’attente s’allonge (30 s, 1 min, 5 min, 15 min, 1 h, 3 h), même si l’application est relancée. Changer le code demande le code actuel.
- **Écran protégé** : quand le coffre est ouvert, ni capture d’écran ni enregistrement, et l’aperçu est masqué dans les applications récentes.
- **Sauvegarde chiffrée** (Coffre › Sécurité › Exporter le coffre) : un fichier `Orbite-coffre-AAAA-MM-JJ.json` dans Téléchargements, protégé par son propre mot de passe (10 caractères minimum). C’est le seul moyen de retrouver le coffre sur un autre téléphone (Importer une sauvegarde).
- **Signature** : Orbite est signée avec une clé privée (secret GitHub `ORBITE_KEY_PASS`) ; une fausse mise à jour signée avec l’ancienne clé est refusée par Android.

## Fonctionnement

Chaque envoi sur la branche `main` fabrique automatiquement un nouvel APK, l’installe et le teste sur un téléphone Android virtuel (onglet **Actions**), puis le publie dans **Releases** seulement si le test réussit.

- `app/src/main/assets/www/index.html` : l'interface de l'agenda
- `app/src/main/java/com/orbite/agenda/MainActivity.java` : la partie Android (partage, notifications, fichiers, presse-papiers)
- `app/src/main/java/com/orbite/agenda/VaultBio.java` : ouverture du coffre par empreinte (Android Keystore)
- `app/src/main/java/com/orbite/agenda/PhoneCalendar.java` : lecture de l’agenda du téléphone pour l’import
- `ci/test-telephone.sh` : test automatique sur téléphone virtuel (agenda, invitations, répétitions, coffre, empreinte)
- `ci/empreinte.py` : enregistre un doigt simulé sur le téléphone virtuel pour tester l’empreinte
- `ci/signer.sh` : signature avec la clé privée et preuve de rotation depuis l’ancienne clé
- `app/src/main/java/com/orbite/agenda/VaultHw.java` : clé de la puce de sécurité mêlée au code du coffre
- `keystore/orbite.jks` : clé de signature, toujours la même pour que les mises à jour s'installent par-dessus
