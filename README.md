# Orbite — agenda Android

Agenda futuriste : vues jour, semaine, mois et année, coffre sécurisé par empreinte digitale, documents et photos dans les événements, partage rapide, thèmes, couleurs, polices et sons personnalisables, import de l'ancien agenda (.ics).

## Installer sur le téléphone (le plus simple)

Ouvrez **https://sgr33650-ui.github.io/orbite-agenda/** dans Chrome, puis touchez **Installer**. Aucun fichier à ouvrir, aucun compte.

## Installer avec le fichier APK

Lien direct vers la dernière version :
**https://github.com/sgr33650-ui/orbite-agenda/releases/latest/download/Orbite.apk**

1. Ouvrez ce lien sur le téléphone Android.
2. Ouvrez le fichier téléchargé, puis autorisez l'installation depuis le navigateur si Android le demande.
3. Pour une mise à jour, refaites la même chose : vos données sont conservées.

## Fonctionnement

Chaque envoi sur la branche `main` fabrique automatiquement un nouvel APK (onglet **Actions**) et le publie dans **Releases**.

- `app/src/main/assets/www/index.html` : l'interface de l'agenda
- `app/src/main/java/com/orbite/agenda/MainActivity.java` : la partie Android (empreinte, partage, notifications, fichiers)
- `keystore/orbite.jks` : clé de signature, toujours la même pour que les mises à jour s'installent par-dessus
