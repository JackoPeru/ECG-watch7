# Release e Update Interno

L'app mobile e l'app watch controllano:

```text
https://api.github.com/repos/JackoPeru/ECG-watch7/releases/latest
```

## Asset attesi
- Mobile: APK con `mobile` nel nome, esempio `Watch7Health-mobile-0.1.1.apk`.
- Watch: APK con `wear` nel nome, esempio `Watch7Health-wear-0.1.1.apk`.
- Tag: `vX.Y.Z`, esempio `v0.1.1`.

## Firma APK
Android installa update solo se il nuovo APK ha lo stesso `applicationId` e la stessa firma dell'app gia' installata.

Per uso personale/dev mode, crea release da questa macchina con:

```powershell
.\scripts\create-github-release.ps1 -Tag v0.1.1 -Notes "Fix updater."
```

Questo usa gli APK debug locali e mantiene compatibilita con app installate da build locale della stessa macchina.

## Flusso Utente
Nell'app:

1. `Check GitHub release`
2. `Download update`
3. `Install update`

Android chiedera' conferma installazione e, al primo uso, permesso per installare APK sconosciuti. Installazione silenziosa non e' disponibile senza store, MDM o root.

## Watch
Il watch ha lo stesso flusso. Wear OS puo' comunque mostrare schermate di permesso/installer diverse dal telefono; se il sistema blocca install da sorgenti sconosciute sul watch, serve una prima abilitazione manuale.

## Migrazione v0.1.1
Wear Data Layer richiede stesso package name e stessa firma tra telefono e orologio. Da `v0.1.1`, anche l'APK watch usa:

```text
com.galaxywatch7.health.mobile
```

Se sul watch e' installata `v0.1.0`, disinstalla prima il vecchio pacchetto:

```powershell
adb -s <WATCH_ID> uninstall com.galaxywatch7.health.wear
```

Poi installa `Watch7Health-wear-0.1.1.apk`. Le versioni successive potranno usare updater interno.
