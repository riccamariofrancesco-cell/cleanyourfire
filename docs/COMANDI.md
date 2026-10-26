# Comandi manuali (ADB)

Servono solo se l'app **Pulizia Fire TV** non riesce a collegarsi da sola al debug ADB della stick. Fanno la stessa
cosa dell'app e degli script `tools/cleanyourfire.sh` (Linux/macOS) e `tools/cleanyourfire.ps1` (Windows), che sono
il modo più comodo se hai un PC.

Tutto viene **disattivato** (`pm disable-user`), mai disinstallato, e si annulla con `pm enable`.

## 0. Preparazione

Sulla Fire TV:

1. **Impostazioni → La mia Fire TV → Informazioni**: premi OK 7 volte sul nome del dispositivo, finché compare
   "Sei uno sviluppatore".
2. **La mia Fire TV → Opzioni sviluppatore → Debug ADB: ON** (e **App di origine sconosciuta** per Downloader).
3. Annota l'IP: **La mia Fire TV → Informazioni → Rete**.

Dal PC (serve [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)):

```sh
adb connect 192.168.1.50:5555      # usa l'IP della tua Fire TV; sulla TV scegli "Consenti sempre"
adb shell                          # da qui in poi i comandi girano sulla Fire TV
```

Da un telefono Android puoi usare un'app ADB, per esempio **Bugjaeger** o **Easy Fire Tools**: collegati all'IP della
Fire TV, porta 5555, e incolla i comandi nella sua shell.

## 1. Backup

```sh
pm list packages > /sdcard/Download/cleanyourfire-backup-tutte.txt
pm list packages -d > /sdcard/Download/cleanyourfire-backup-disattivate.txt
```

## 2. Pulizia, per categoria

Incolla solo le categorie che vuoi. Un pacchetto assente su questa stick risponde "Unknown package": è normale.

### Telemetria, metriche e log (`telemetry`)

```sh
for p in com.amazon.client.metrics.api com.amazon.neo.minerva com.amazon.neo.minerva.logcat com.amazon.tv.fw.metrics com.amazon.wirelessmetrics.service com.amazon.perfcollection com.amazon.perfc com.amazon.dp.logger com.amazon.ceviche com.amazon.tv.developer.dataservice com.amazon.livedeviceservice com.amazon.privacypassservice com.amazon.wifilocker com.amazon.tv.acr; do pm disable-user --user 0 $p; done
```

### Pubblicità, consigli, promozioni e shopping (`ads`)

```sh
for p in com.amazon.kso.blackbird com.amazon.sneakpeek com.amazon.hybridadidservice com.amazon.media.recommendations com.amazon.providers.contentsupport com.amazon.tv.easyupgrade com.amazon.shoptv.client com.amazon.shoptv.firetv.client com.amazon.hedwig com.amazon.tv.livetv com.amazon.franktvinput com.amazon.gamehub com.amazon.systemnotices com.amazon.tv.releasenotes com.amazon.uxnotification; do pm disable-user --user 0 $p; done
```

### App Amazon preinstallate e configurazione (`apps`)

```sh
for p in com.amazon.tahoe com.amazon.bueller.music com.amazon.bueller.photos com.amazon.cloud9 com.amazon.tv.website_launcher com.amazon.tmm.tutorial com.amazon.storm.lightning.tutorial com.amazon.naatyam com.amazon.whisperjoin.middleware.np com.amazon.tv.ffsprovisioneeclient com.amazon.ods.kindleconnect com.amazon.tv.mattercohost; do pm disable-user --user 0 $p; done
```

### Extra di Alexa (la ricerca vocale col telecomando resta) (`alexa`)

```sh
for p in com.amazon.tv.alexaalerts com.amazon.tv.alexanotifications com.amazon.alexa.externalmediaplayer.fireos com.amazon.alexamediaplayer.runtime.ftv com.amazon.aiondec com.amazon.audiohome com.amazon.whad com.amazon.whasettings com.amazon.wha.mediabrowserservice com.amazon.aca; do pm disable-user --user 0 $p; done
```

Dopo un riavvio controlla con `pm list packages -d`: qualche extra di Alexa può tornare attivo; in quel caso ripeti la
sua riga.

## 3. Launcher pulito (dopo aver installato CleanYourFire.apk)

Fire OS non lascia cambiare la Home: il servizio di accessibilità dell'app riporta al launcher pulito ogni volta che
compare la Home Amazon.

```sh
pm grant it.cleanyourfire.home android.permission.WRITE_SECURE_SETTINGS
pm grant it.cleanyourfire.home android.permission.ACCESS_FINE_LOCATION
settings get global alarm_manager_constants      # annota il valore per poterlo ripristinare
settings put global alarm_manager_constants min_futurity=0
settings get secure enabled_accessibility_services   # se non è "null", aggiungi il servizio dopo un ":"
settings put secure enabled_accessibility_services it.cleanyourfire.home/com.gathin.tvhome.HomeKeyService
settings put secure accessibility_enabled 1
am start -n it.cleanyourfire.home/com.gathin.tvhome.MainActivity
```

Per tornare alla Home Amazon:

```sh
settings delete secure enabled_accessibility_services
settings put secure accessibility_enabled 0
settings delete global alarm_manager_constants   # oppure rimetti il valore annotato prima
```

## 4. Extra facoltativi

```sh
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5
pm trim-caches 999G      # libera la cache delle app
```

Privacy (solo dai menu, perché si sincronizza con l'account Amazon): **Impostazioni → Preferenze → Impostazioni
privacy**: disattiva *Dati sull'utilizzo del dispositivo*, *Raccogli dati sull'utilizzo delle app* e *Pubblicità
basata sugli interessi*. **Preferenze → Contenuti in evidenza**: disattiva la riproduzione automatica di video e audio.

## 5. Ripristino

Se hai usato l'app o lo script, riattiva esattamente ciò che hanno spento:

```sh
for p in $(cat /sdcard/Download/cleanyourfire-ripristino.txt); do pm enable $p; done
```

Se hai usato i comandi manuali qui sopra, riattiva l'intera lista:

```sh
for p in com.amazon.client.metrics.api com.amazon.neo.minerva com.amazon.neo.minerva.logcat com.amazon.tv.fw.metrics com.amazon.wirelessmetrics.service com.amazon.perfcollection com.amazon.perfc com.amazon.dp.logger com.amazon.ceviche com.amazon.tv.developer.dataservice com.amazon.livedeviceservice com.amazon.privacypassservice com.amazon.wifilocker com.amazon.tv.acr com.amazon.kso.blackbird com.amazon.sneakpeek com.amazon.hybridadidservice com.amazon.media.recommendations com.amazon.providers.contentsupport com.amazon.tv.easyupgrade com.amazon.shoptv.client com.amazon.shoptv.firetv.client com.amazon.hedwig com.amazon.tv.livetv com.amazon.franktvinput com.amazon.gamehub com.amazon.systemnotices com.amazon.tv.releasenotes com.amazon.uxnotification com.amazon.tahoe com.amazon.bueller.music com.amazon.bueller.photos com.amazon.cloud9 com.amazon.tv.website_launcher com.amazon.tmm.tutorial com.amazon.storm.lightning.tutorial com.amazon.naatyam com.amazon.whisperjoin.middleware.np com.amazon.tv.ffsprovisioneeclient com.amazon.ods.kindleconnect com.amazon.tv.mattercohost com.amazon.tv.alexaalerts com.amazon.tv.alexanotifications com.amazon.alexa.externalmediaplayer.fireos com.amazon.alexamediaplayer.runtime.ftv com.amazon.aiondec com.amazon.audiohome com.amazon.whad com.amazon.whasettings com.amazon.wha.mediabrowserservice com.amazon.aca; do pm enable $p; done
```

## 6. Fine

```sh
settings put global adb_enabled 0    # spegne il debug ADB (chiude la porta 5555)
```
