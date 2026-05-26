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

Da ora lo script blocca la release se manca:

```text
wear/libs/samsung-health-sensor-api.aar
```

Usare `-AllowNoSensorSdk` solo per release UI/debug senza ECG reale.

## Flusso Utente
Nell'app:

1. `Check GitHub release`
2. `Download update`
3. `Install update`

Android chiedera' conferma installazione e, al primo uso, permesso per installare APK sconosciuti. Installazione silenziosa non e' disponibile senza store, MDM o root.

## Watch
Il watch ha lo stesso flusso. Wear OS puo' comunque mostrare schermate di permesso/installer diverse dal telefono; se il sistema blocca install da sorgenti sconosciute sul watch, serve una prima abilitazione manuale.

## Health Platform Developer Mode
Se sul watch compare `SDK_POLICY_ERROR`, non basta Android `Developer options`. Serve la developer mode di **Health Platform** sul watch:

1. Watch: apri `Settings`.
2. Vai su `Apps`.
3. Scorri e apri `Health Platform`.
4. Tocca rapidamente circa 10 volte la parte del titolo `Health Platform`.
5. Deve apparire `[Dev mode]` accanto/al posto del titolo.

Questa modalita' e' solo per test/debug. Per distribuzione reale Samsung richiede partner approval con package name e firma registrati.

Da `v0.1.8`, il watch non salva piu' sessioni ECG da 0 campioni quando Samsung blocca il tracker. L'app mostra `ECG request sent` finche' arrivano veri campioni raw. Per BP research, se Samsung blocca `HEART_RATE_CONTINUOUS`, viene provato il fallback Android pubblico `TYPE_HEART_RATE`; questo non fornisce ECG raw o PPG raw.

Da `v0.1.9`, il watch ha il bottone `Policy info`: manda al telefono package name, SHA-256 firma APK, versione Health Platform, tracker Samsung disponibili e sensori Android pubblici visibili. Questi dati servono per capire se resta solo il blocco policy Samsung o se il firmware espone un fallback sensori pubblico.

Da `v0.2.0`, il watch usa il sensore Android vendor pubblico rilevato nei log (`AFE4510 ECG`, type `69669`, stringType `com.samsung.sensor.ecg`) prima del Samsung Health Sensor SDK. Il fallback registra il sensore con `SensorManager`, salva `SensorEvent.values[0]`, logga il primo frame dati e stima il sample rate dalla durata reale.

Da `v0.2.1`, il telefono puo' scaricare anche l'APK watch e inviarlo all'orologio via Wear Data Layer (`Download watch update` -> `Send watch update`). Il watch salva l'APK ricevuto e usa il normale bottone `Install`. Questo evita il download GitHub diretto dal watch, spesso lento.

Da `v0.2.2`, il fallback ECG pubblico prova piu' sampling period con handler esplicito e logga permesso richiesto, min/max delay, reporting mode e wake status del sensore. Serve per distinguere listener rifiutato per permesso/signature da listener rifiutato per configurazione sampling.

Da `v0.2.3`, il watch logga tutti i permessi sensore rilevanti, richiede quelli runtime prima di avviare ECG e dichiara `HIGH_SAMPLING_RATE_SENSORS`. Il bottone `Permissions` forza richiesta/log permessi.

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
