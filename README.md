# CleanYourFire

[CleanYourTV](https://github.com/Gathin23/cleanyourtv) adattato alla **Fire TV Stick** con **Fire OS 7.7.1.7**
(build PS7717/5741, Android 9). È pensato e configurato per una Fire TV Stick HD di 1ª generazione.

Un solo APK da installare con Downloader. Fa due cose:

1. **Pulizia Fire TV**: disattiva (non disinstalla) telemetria, pubblicità, consigli sponsorizzati, app Amazon
   preinstallate ed extra di Alexa. Fa un backup prima di toccare qualcosa e annulla tutto con un tasto.
2. **Launcher pulito**: la Home essenziale di CleanYourTV (dock, griglia app, orologio). Fire OS non permette di
   cambiare la Home, quindi un servizio di accessibilità rimanda subito al launcher pulito ogni volta che compare la
   Home Amazon. La Home Amazon resta installata e attiva e si torna indietro con un tasto.

Non serve root e non serve un PC: l'app usa il debug ADB della stick collegandosi a se stessa. Se il collegamento non
riesce, ci sono gli script per PC e i [comandi manuali](docs/COMANDI.md).

## Installazione con Downloader

1. Sulla Fire TV: **Impostazioni → La mia Fire TV → Informazioni**, premi OK **7 volte** sul nome del dispositivo
   finché compare "Sei uno sviluppatore".
2. **La mia Fire TV → Opzioni sviluppatore**:
   - **Debug ADB: ON**
   - **Installa app sconosciute → Downloader: ON** (installa prima Downloader dall'Appstore, se non c'è)
3. Apri **Downloader** e inserisci questo indirizzo:

   ```
   https://github.com/riccamariofrancesco-cell/cleanyourfire/raw/main/dist/CleanYourFire.apk
   ```

   Poi premi **Installa**. SHA-256: vedi [`dist/CleanYourFire.apk.sha256`](dist/CleanYourFire.apk.sha256).

In alternativa, da un PC: `adb connect <IP>:5555`, poi `adb install -r dist/CleanYourFire.apk`.

## Uso

Apri **Pulizia Fire TV** da **Le tue app e canali** e segui i passi:

| Passo | Cosa fa |
|---|---|
| 1. Connetti | Si collega al debug ADB della stick. **Sulla TV compare "Consentire debug USB?": scegli "Consenti sempre da questo computer"**. |
| 2. Backup | Salva l'elenco delle app e le impostazioni in `/sdcard/Download/cleanyourfire-backup-*.txt`. |
| 3. Cosa disattivare | Quattro categorie, tutte attive: telemetria, pubblicità e consigli, app Amazon (Music, Photos, Silk, Kids, tutorial), extra di Alexa. Premi OK per escluderne una. |
| 4. Applica | `pm disable-user` su ogni pacchetto presente e attivo. Annota cosa ha spento in `/sdcard/Download/cleanyourfire-ripristino.txt`. |
| 5. Launcher pulito | Concede i permessi e attiva il servizio Home. Ripremi per tornare alla Home Amazon. |
| 6. Extra | Animazioni a 0,5× e svuotamento della cache: utile con meno di 1 GB libero. |
| 7. Privacy | Apre le Impostazioni. Questi interruttori vanno cambiati a mano perché si sincronizzano con l'account Amazon: **Preferenze → Impostazioni privacy** (tutto OFF) e **Preferenze → Contenuti in evidenza** (autoplay OFF). |
| Ripristina tutto | Riattiva solo le app spente da CleanYourFire e spegne il launcher pulito. |
| Fine | Spegne il debug ADB. Per riapplicare o ripristinare dovrai riaccenderlo. |

Le impostazioni del launcher (colonne, animazioni, sfondo, riordino) e la voce **Pulizia Fire TV** si trovano nel
launcher: tieni premuto OK sul pulsante Impostazioni in alto.

### Cosa resta acceso apposta

Home Amazon (`com.amazon.tv.launcher`, protetta da Fire OS), Impostazioni, Appstore, tastiera, telecomando e
Bluetooth, aggiornamenti di sistema, **ricerca vocale col telecomando**, Prime Video, casting, profili e il programma
di prima configurazione (serve per il ripristino di fabbrica). Cinque servizi di telemetria sono protetti da Fire OS e
non si possono disattivare. L'elenco completo, con il motivo di ogni riga, è in
[`debloat-fire-os7.txt`](launcher/app/src/main/assets/debloat-fire-os7.txt).

## Se l'app non riesce a connettersi

Un fork di CleanYourTV ha osservato che su qualche stick il debug ADB rifiuta le connessioni che partono dalla stick
stessa. In quel caso fai tutto da un PC o da un telefono sulla stessa rete Wi-Fi.

- **Linux / macOS**: `tools/cleanyourfire.sh <IP-Fire-TV> --install`
- **Windows**: `powershell -ExecutionPolicy Bypass -File tools\cleanyourfire.ps1 <IP-Fire-TV> -Install`
- **Telefono** (Bugjaeger, Easy Fire Tools…) o `adb shell`: i comandi in [docs/COMANDI.md](docs/COMANDI.md)

Gli script accettano `--skip <categoria>` / `-Skip`, `--no-launcher` / `-NoLauncher` e `--restore` / `-Restore`. Il
backup e il registro di ripristino vengono salvati in `backup/<IP>/`.

## Limiti noti

- **La Home Amazon può comparire per un attimo** prima del rimando, soprattutto all'accensione o uscendo da alcune
  app. Il launcher non può impedirlo: Fire OS non cede la Home.
- Con Alexa attiva, alcuni **extra di Alexa possono riattivarsi dopo un riavvio**. Riapri l'app e premi di nuovo
  *Applica* (serve il debug ADB acceso).
- La riga dei contenuti in evidenza della Home Amazon arriva dal launcher Amazon e non si toglie disattivando
  pacchetti. Con il launcher pulito non la vedi più.
- Un aggiornamento di Fire OS può riattivare pacchetti o cambiare i nomi: dopo un aggiornamento, riapplica.
- Il servizio di accessibilità usa un po' di RAM in più: la Home Amazon resta in memoria.

## Compilare da sé

Servono JDK 17+ e l'Android SDK (platform 34, build-tools 34.0.0):

```sh
cd launcher
./gradlew testReleaseUnitTest lintRelease assembleRelease
# APK: launcher/app/build/outputs/apk/release/app-release.apk
```

L'APK è firmato con `launcher/app/cleanyourfire.jks` (password `cleanyourfire`), che sta nel repository così ogni nuova
versione si installa sopra la precedente. **Non è una chiave segreta**: chiunque abbia il repository può firmare con
essa, quindi installa solo APK presi da qui.

## Crediti e licenze

- Launcher e lista di pulizia da [CleanYourTV](https://github.com/Gathin23/cleanyourtv) di Gathin (MIT, vedi
  [`launcher/LICENSE-CleanYourTV.txt`](launcher/LICENSE-CleanYourTV.txt)), tramite il fork Fire TV
  [bearylabs/cleanyourtv `firetv`](https://github.com/bearylabs/cleanyourtv/tree/firetv) (commit `6b60114`).
- Il rimando della Home su Fire OS (`FireHomeRedirect`) si basa su dumbtv (MIT, vedi
  [`launcher/DUMBTV-LICENSE.txt`](launcher/DUMBTV-LICENSE.txt)).
- Font Outfit: SIL OFL ([`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt)).
- Client ADB: [dadb](https://github.com/mobile-dev-inc/dadb) (Apache-2.0).
- Il resto del repository è sotto GPL-3.0 ([LICENSE](LICENSE)).
