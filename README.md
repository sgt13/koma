# Koma — liseuse de manga et comics pour Android

Koma lit les BD rangées dans les dossiers de ton téléphone : `.cbz`, `.zip`, `.cbr`, `.rar` (RAR4), `.pdf`
et les dossiers d'images (un dossier = un chapitre). Les albums sont classés par série, d'après le dossier qui les contient.

- Fold fermé : une page. Fold ouvert : double page, comme un livre.
- Glisse ou tape sur les bords pour tourner. Les touches de volume tournent aussi les pages.
- Double-tape une bulle pour zoomer dessus, puis tape sur les bords pour aller de bulle en bulle.
- Sens Manga ⇄ Comics par album. La progression est retenue.
- « Ouvrir avec Koma » marche aussi depuis un gestionnaire de fichiers.

## Obtenir l'APK sans rien installer (GitHub)

1. Crée un dépôt vide sur github.com (bouton **New**), par exemple `koma`.
2. **Add file → Upload files**, puis glisse le *contenu* de ce dossier (pas le dossier lui-même) et valide.
3. Si le dossier `.github` n'a pas été envoyé (fichiers cachés), crée-le à la main :
   **Add file → Create new file**, nom `.github/workflows/build.yml`, colle le contenu du fichier `build.yml` fourni.
4. Onglet **Actions** : la compilation démarre toute seule (environ 4 min).
5. Onglet **Releases** (à droite sur la page du dépôt) : ouvre-le depuis le Fold et télécharge `Koma.apk`.
6. Installe-le. Android demandera d'autoriser l'installation depuis le navigateur : accepte pour cette fois.

Chaque nouvel envoi de fichiers sur le dépôt produit une nouvelle release qui s'installe par-dessus l'ancienne.

## Avec Android Studio

Ouvre ce dossier, attends la synchronisation Gradle, puis **Run** avec le Fold branché en USB (débogage USB activé).

## Premier lancement

Touche **Dossier** et choisis le dossier où sont tes BD (par exemple `Download` ou `Manga`).
Android ne laisse pas choisir la racine du stockage ni le dossier `Download` entier sur certaines versions :
dans ce cas, range tes BD dans un sous-dossier (`Download/Manga`) et choisis-le.
