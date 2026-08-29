# AGENTS.md — Syekso

Application Android de contrôle d'accès à un immeuble (portes, clés, appels vidéo d'interphone),
construite comme projet vitrine pour un entretien Android senior. Deux applications sortent de ce
dépôt : `:app` (le résident) et `:intercom` (le boîtier de hall, installé sur un second téléphone).

**Lire `README.md` pour ce que fait le produit, l'architecture et les décisions de conception.** Ce
fichier ne couvre que ce qu'un README ne dit pas : comment travailler ici sans marcher sur une mine.

## Règles d'engagement

- **Le propriétaire commite et pousse.** Ne jamais lancer `git commit` ni `git push`. Un `git status`
  chargé est normal et délibéré — l'essentiel du travail de feature reste volontairement non commité.
  Ne pas « ranger ».
- Les spécifications vivent dans `docs/superpowers/specs/`, les plans dans `docs/superpowers/plans/`,
  tous nommés `AAAA-MM-JJ-sujet.md`. Chaque sous-projet suit le cycle spéc → plan → implémentation.

## Commandes

```bash
./gradlew :app:installDebug          # application résident
./gradlew :intercom:installDebug     # application interphone — va sur le SECOND appareil
./gradlew test                       # tests unitaires, tous modules
```

`adb` se trouve à `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe` (absent du PATH).
Appareil de test : téléphone physique MIUI `bqmvkvdisgq469k7`. Il n'y a pas d'émulateur dans ce montage
— le BLE et la caméra sont réels.

## Le backend est un dépôt séparé

`C:/Users/rodol/IdeaProjects/AccessControllerServer`, port **8080**, joint depuis le téléphone à l'IP
LAN de la machine de développement, **`192.168.1.104`** — réservée en DHCP dans la box, donc stable
après redémarrage. **Ne pas proposer de revérifier l'IP ni de rééditer `BASE_URL` :** ce mode de panne
est clos.

Quand l'application affiche « impossible de joindre le serveur », vérifier plutôt, dans cet ordre :

1. Le serveur tourne-t-il vraiment ? `Get-NetTCPConnection -LocalPort 8080 -State Listen`
2. **Le Wi-Fi du téléphone est-il allumé ?** `adb shell settings get global wifi_on` — un `0` signifie
   qu'il est retombé sur les données mobiles et ne peut plus joindre une adresse privée.
   `adb shell svc wifi enable` corrige.

Identifiants de démonstration, pré-remplis dans l'écran de connexion : `rodolphe@example.com` /
`password`. Code d'activation `MONT-2026`. Noms BLE des portes `OSKEY-HALL-01` et `OSKEY-GARAGE-01` —
ce sont ceux qu'annonce le firmware ESP32 de `hardware/esp32-door/`, en renommer un casse la
démonstration matérielle.

## Pièges MIUI — chacun a déjà coûté une session

- **`Log.d` est avalé par le système.** Utiliser `Log.e` pour instrumenter, sinon on conclut à tort que
  le code n'est jamais passé.
- **Installer un *nouveau* package par adb échoue** en `INSTALL_FAILED_USER_RESTRICTED`. Installer un
  nouvel applicationId sur le second appareil.
- Captures d'écran et compteurs d'images sont le moyen fiable de voir ce que fait le téléphone :
  `adb exec-out screencap -p > out.png`, et `adb shell dumpsys gfxinfo <pkg> | grep "Total frames"`
  échantillonné deux fois — un compteur constant signifie que rien ne se dessine.

## Conventions

- Package `dev.rodolphe.syeksodemo` (`….intercom` pour l'application interphone).
- Les modules sont découpés par feature, câblés avec les accesseurs de projets typés
  (`projects.core.network`), Hilt partout. Une nouvelle feature obtient son propre module `feature:`
  plutôt que de faire grossir `:app`.
- Les tests utilisent des **doubles écrits à la main** pour les interfaces (`FakeWebRtcSession`,
  `FakeSignaling`) plutôt que Mockito. JUnit4 + `kotlinx-coroutines-test`.
- Les commentaires expliquent **pourquoi**, pas quoi — en général en nommant le piège évité ou
  l'alternative écartée. S'aligner sur ce ton : c'est le style de la maison, et c'est ce qui rend ce
  dépôt montrable.

## Défaut ouvert connu

Après qu'une Activity a été détruite puis qu'une nouvelle est créée **dans un processus survivant**,
l'horloge de frames de Compose reste en pause pour cette fenêtre : `withFrameNanos` ne revient jamais,
donc plus rien ne recompose et aucune animation ne tourne, tandis que les coroutines `LaunchedEffect`
qui utilisent `delay` continuent. L'application paraît bloquée sur son écran de chargement, avec le
spinner lui-même figé — l'état est correct, c'est l'UI qui ne se redessine plus.

- **Contournement :** appuyer sur accueil puis rouvrir l'application. Un aller-retour de cycle de vie
  relance l'horloge.
- **Reproduire sans matériel :**
  `adb shell am start -n dev.rodolphe.syeksodemo/.MainActivity -f 0x14000000` après avoir mis
  l'application en arrière-plan.
- Ne pas refaire le diagnostic : les causes éliminées (DataStore, thread principal bloqué, le service
  de premier plan, `IncomingCallStore`/EglBase, l'arbre d'UI de l'application elle-même) sont
  consignées dans la mémoire `compose-frozen-by-cleartop`. Le `PendingIntent` de la notification a déjà
  été retiré de `FLAG_ACTIVITY_CLEAR_TOP`, ce qui corrige le cas où l'Activity est encore vivante, mais
  pas celui où elle a d'abord été détruite.

## Ne jamais commiter

Keystores, `google-services.json`, identifiants Atlas, secrets JWT. La clé partagée de l'interphone
(`syekso-demo-intercom-key`) est un défaut de démonstration qui doit rester identique à celle du
backend — la changer d'un seul côté fait silencieusement échouer toute validation de code.
