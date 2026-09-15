# Backup registrazioni Google Drive

## Architettura e distribuzione

Le nuove registrazioni sono finalizzate in `files/Rivo Recordings`, nella sandbox di Rivo,
fuori da MediaStore. I file precedenti restano nelle cartelle originali e sono ancora
letti dal player; non vengono spostati o eliminati automaticamente. Il backup include la nuova cartella privata e le precedenti cartelle denominate
`Rivo Recordings`, quando accessibili. Non importa le cartelle di registratori OEM. La disinstallazione elimina le copie private locali.

Dopo chiusura muxer e file descriptor, rinomina del file `.pending` e aggiornamento del
player, viene accodato un WorkManager. Il recorder non attende la rete. Il backup è
facoltativo e inizialmente disattivato. L'attivazione include tutti i file già presenti
nelle cartelle locali Rivo accessibili. Sono previsti backup manuale e ripristino con conferma.

Per mantenere una distribuzione FOSS vera sono presenti tre varianti:

- `fossDebug`: nessuna libreria Google OAuth, backup Drive non disponibile;
- `cloudDebug`: nessuna pubblicità, Google AuthorizationClient e backup Drive;
- `play`: mantiene la distribuzione originale con pubblicità e include OAuth.

Cloud e FOSS condividono applicationId e firma debug: Cloud aggiorna l'installazione
FOSS esistente preservandone i dati. Cloud **non è una distribuzione interamente FOSS**:
include Google Play services Auth 22.0.0. Nessuna dipendenza Ads è inclusa in Cloud.
La firma release non viene creata o sostituita da questo lavoro.

## Configurazione Google Cloud (una sola volta per package e firma)

1. Nel proprio progetto Google Cloud abilitare **Google Drive API**.
2. Configurare Google Auth Platform / OAuth consent screen con il nome Rivo Personal.
3. Se il progetto è in modalità Testing, aggiungere l'account Google utilizzato sul telefono
   agli utenti di test. I consensi/token in modalità test possono richiedere nuova autorizzazione.
4. Creare un client OAuth di tipo **Android**, package `it.stivy.rivo.personal.debug`
   per gli APK debug. Inserire lo SHA-1 del certificato che firma l'APK installato.
5. Ricavare lo SHA-1 con `./gradlew :app:signingReport`, oppure con
   `apksigner verify --print-certs <apk>` (non condividere keystore o password).
6. Configurare lo scope `https://www.googleapis.com/auth/drive.file`.
7. Installare Cloud Debug come aggiornamento; aprire Impostazioni registrazione chiamate
   → Backup registrazioni → Collega Google Drive e completare il consenso Google.
8. Abilitare il backup automatico o premere Backup adesso. La cartella
   **Rivo Personal Recordings** viene creata nell'account autorizzato.

Non servono API key, client secret, token copiati manualmente, redirect web o backend.
L'identità del client Android è determinata da package e certificato registrati.
Per una firma differente (ad esempio artifact CI o futura release) registrare un altro
client Android con il suo SHA-1. Un APK CI con firma debug differente non può aggiornare
l'installazione locale senza perdita dati: usare l'APK prodotto con la firma originale.

### Certificato della build locale verificata

Package debug: `it.stivy.rivo.personal.debug`.
SHA-1: `55:7F:E5:5E:4E:F0:BA:67:85:57:EB:72:88:8C:13:F5:CF:F0:40:0D`.
Questa è un'impronta pubblica del certificato, non una chiave privata.

## Autorizzazione e sicurezza

AuthorizationClient ottiene i token OAuth; Rivo li conserva solo in memoria per le
richieste. Google Play services gestisce la cache e il rinnovo. Lo scope `drive.file`
limita l'accesso ai file creati/autorizzati per questa app. Non viene richiesta la rubrica,
né lo storico o l'intero Drive. La cartella è gestita da Rivo; non si usa il selettore SAF.

Il worker vincola ogni richiesta all'account e alla generazione corrente della sessione.
Un consenso mancante/revocato ferma il lavoro e richiede il ricollegamento nelle impostazioni;
non apre finestre durante la chiamata. Scollega backup disabilita localmente la coda,
invalidando anche i callback tardivi; non revoca automaticamente il consenso Google.
Per revocarlo usare Account Google → Connessioni con app e servizi di terze parti.

I log non contengono token, account, nomi, numeri o payload. Le preferenze Drive sono
escluse da Android backup/device transfer e dal backup impostazioni Rivo (che usa
`rivo_prefs`). Anche le registrazioni private sono escluse dal backup Android automatico:
la seconda copia cloud viene creata solo tramite la funzione autorizzata.

## Trasferimento e ripristino

- Upload resumable Drive v3 a blocchi di 1 MiB, con timeout di connessione, lettura,
  scrittura e chiamata; coroutine cancellabili e coda seriale.
- Gli upload incompleti sono riavviati in una nuova sessione dopo errore. Non viene
  conservato su disco l'URL sensibile della sessione resumable.
- SHA256 in `appProperties`, insieme al nome originale, per deduplicazione, ID Drive pregenerato persistente per
  evitare copie duplicate se la risposta finale si perde. Conferma tramite dimensione
  e MD5 restituiti da Drive (MD5 serve solo come checksum di trasporto).
- `appProperties` contiene versione del formato, SHA256 e timestamp locale. Il nome
  originale conserva l'associazione già usata da Rivo con contatto/chiamata.
- Ripristino paginato, file temporaneo privato, controllo dimensione/MD5/SHA256 prima
  della rinomina. I file identici vengono saltati. Un conflitto conserva entrambe le
  copie con suffisso derivato dall'hash, preservando il timestamp riconosciuto da Rivo.
- Se la destinazione viene cestinata o spostata, il backup non viene confermato;
  ricollegare Google Drive e riprovare per ricreare la copia.
- Nessuna cancellazione automatica locale o remota, nessuna sincronizzazione delle
  eliminazioni. Le copie già presenti in cloud rimangono anche eliminando il file locale.
- Opzione reti senza consumo (`UNMETERED`, normalmente Wi-Fi). È Android a classificare
  la rete: una Wi-Fi a consumo non soddisfa il vincolo.
- Retry esponenziale per rete/quota e riconciliazione ogni 12 ore quando automatico è
  attivo; Android/ColorOS possono rinviare i job. Non è una garanzia di upload immediato.
- Operazioni lunghe continuano tramite retry entro il limite di esecuzione dei worker.
- Stato globale, numero di file da elaborare, ultima operazione riuscita e stato per file.

## Verifiche

Eseguire:

```sh
./gradlew :app:testFossDebugUnitTest :app:testCloudDebugUnitTest \
  :app:lintFossDebug :app:lintCloudDebug \
  :app:assembleFossDebug :app:assembleCloudDebug
python3 scripts/check-translations.py
```

I test HTTP usano MockWebServer e dati sintetici, senza audio telefonico o credenziali
reali. Coprono upload multiparte resumable, hash, idempotenza ID, paginazione, restore
troncato, revoca/sessione vecchia, errori distinti e rifiuto di URL verso host diversi.

### Collaudo OPPO / account reale (DA VERIFICARE fino alla configurazione OAuth)

- Consenso Google, annullamento, Play services assenti, package/SHA-1 errati.
- Chiamata registrata: file locale riproducibile prima dell'upload e copia Drive integra.
- Due chiamate consecutive, arresto manuale, app chiusa, schermo spento.
- Assenza rete e ritorno rete, Wi-Fi a consumo, reboot e retry.
- Revoca consenso, scollegamento durante upload, ricollegamento allo stesso account.
- Backup ripetuto senza duplicati; file remoto eliminato o cartella cestinata.
- Ripristino dopo reinstallazione con lo stesso progetto OAuth: player, contatto, durata.
- File locale omonimo differente, spazio insufficiente, quota Drive esaurita.
- Le registrazioni non appaiono nel catalogo multimediale Android.
- Nessuna regressione nelle due voci, nei controlli chiamata e nel recorder Shizu.

Fonti ufficiali:
- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://developers.google.com/workspace/drive/api/guides/manage-uploads
