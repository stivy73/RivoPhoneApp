# Identificazione diretta dei numeri sconosciuti

Baseline stabile: Rivo Personal `1a332b76b8a764c707da5147db07773a4ca02abf`.
Branch: `codex/direct-provider-keys`. Package, firma, registrazioni e motore Shizu invariati.

## Configurazione nell’app

Impostazioni → Identificazione numeri sconosciuti. Aprire la scheda del provider,
inserire la propria chiave, premere **Verifica chiave**, attivare il provider e il
riconoscimento online. Google Places è l’unico provider online; una scelta ON senza una
chiave verificata non effettua ricerche. La verifica esplicita è disponibile anche
con riconoscimento OFF e non abilita automaticamente un interruttore lasciato OFF.
Mostra/Nascondi controlla il campo password; Rimuovi chiave richiede conferma,
disabilita solo quel provider e conserva i nomi personalizzati.

Non servono server, URL backend o token dispositivo. I vecchi campi proxy/token
vengono rimossi dalle preferenze al primo avvio. Il codice `backend/` è storico,
non è utilizzato dall’app e non va configurato per questa versione.

### Google Places

1. [Crea o seleziona un progetto Google Cloud](https://developers.google.com/maps/documentation/places/web-service/get-api-key?hl=it).
2. Attiva la fatturazione.
3. Abilita **Places API (New)**.
4. Crea una chiave personale, limita le API consentite a Places API (New).
5. Configura la restrizione Android per `it.stivy.rivo.personal.debug` e SHA-1
   della firma dell’APK effettivamente installato (non quello di un runner CI diverso).
6. Incolla e verifica nell’app. Imposta quote e avvisi di spesa nel progetto Cloud.

Lo [SDK Android supporta Text Search](https://developers.google.com/maps/documentation/places/android-sdk/text-search),
ma aggiungerebbe una libreria proprietaria Google alla distribuzione FOSS e un
client SDK globale da reinizializzare per le chiavi personali. Si usa quindi la
[Text Search HTTPS ufficiale](https://developers.google.com/maps/documentation/places/web-service/text-search),
compatibile col trasporto già presente e senza nuove dipendenze.

La richiesta include `X-Android-Package` e `X-Android-Cert`, ricavati dal package e
dal certificato installato. Come prescritto dalla
[guida Google alle chiamate mobili dirette](https://developers.google.com/maps/api-security-best-practices#secure_client-side_web_service_calls),
il titolare deve verificare che l’endpoint rifiuti identificatori Android errati
prima di considerare effettive le restrizioni. Questo controllo con chiave reale
non è stato eseguito. Non rimuovere indiscriminatamente le restrizioni per aggirare
un errore. La chiave resta accessibile al processo autorizzato: Keystore protegge
il salvataggio, non rende un client mobile un server fidato.

La verifica invia una Text Search `Google`, massimo un risultato, field mask
`places.id` (nessun numero personale). Verifica l’accesso all’API; una limitazione
specifica del piano o dello SKU telefonico può emergere solo al lookup. I lookup
richiedono ID, nome, telefoni internazionale/nazionale, tipo primario, attribuzioni
e link Maps: i campi telefonici possono comportare addebiti Enterprise. Si accetta
un nome soltanto con corrispondenza E.164; nomi diversi sullo stesso centralino non
vengono selezionati arbitrariamente. Nessuno scraping e nessuna ricerca HTML.

## Storage, cache e concorrenza

Chiavi personali cifrate AES-256/GCM con IV casuale, autenticazione del provider
tramite AAD e chiave crittografica non esportabile in Android Keystore. Solo
ciphertext in `noBackupFilesDir/provider_keys`, mai SharedPreferences in chiaro,
BuildConfig, risorse, log o saved instance state. Backup Android e trasferimento
device escludono per definizione noBackupFilesDir; il backup impostazioni esistente
legge soltanto le preferenze generali e non legge questa directory. Dopo reinstallazione
senza dati/Keystore occorre reinserire le chiavi. Ogni chiave è inviata via HTTPS
solo al relativo provider per le richieste autorizzate.

Precedenza: rubrica → nome privato → Google esatto → numero. Un errore nella rubrica impedisce l’invio online. Ricerca
event-driven sullo squillo, nessuna scansione online dello storico. Lookup Google con
deadline di 3 secondi, I/O fuori dal main thread,
deduplicazione per numero. Disattivazione/cambio chiave invalida la versione del
solo provider e cancella il suo lavoro; risposte tardive non sono accettate.
Fine chiamata può completare una ricerca per cache/cronologia, mentre i collector
Compose e le verifiche di sessione esistenti proteggono la schermata terminata.

Google rimane solo in memoria per 5 minuti, senza cache persistente dei nomi
([policy](https://developers.google.com/maps/documentation/places/web-service/policies)).
Svuotare cache non elimina nomi privati, contatti o chiavi.

## Verifica e regressioni

Unit test: payload/errori ufficiali sintetici, esatta corrispondenza telefonica,
più risultati/ambiguità, assenza chiavi, verifica ID-only, timeout,
fallimenti, deduplicazione e cancellazione, precedenze e risposte obsolete.
Restano eseguiti anche i test preesistenti di recorder, stream e backup impostazioni.

Test strumentali `ProviderSecretsTest`: cifratura reale Keystore, lettura dopo
ricreazione store, rimozione e pulizia del provider ritirato, tampering, IV casuali e Intent browser.
Usano una directory temporanea isolata e non sovrascrivono le chiavi personali. Da eseguire
su dispositivo/emulatore con `:app:connectedFossDebugAndroidTest`.

| Test hardware/account reale | Stato |
| --- | --- |
| Chiave Google, API disabilitata, billing, quota, restrizioni Android | DA VERIFICARE |
| Incolla, mostra/nascondi, rimozione e apertura browser | DA VERIFICARE |
| Keystore e backup sul dispositivo | DA VERIFICARE |
| Chiamata entrante/uscente, risposta/rifiuto con provider lento/offline | DA VERIFICARE |
| Cronologia, contatto Android, nome privato, Google ON/OFF | DA VERIFICARE |
| Registrazione Shizu, schermo bloccato/background, chiamate consecutive | DA VERIFICARE |

La ricerca Google reale è stata verificata durante la diagnosi del formato +39.
Il collaudo delle chiamate sul telefono rimane separato. Nessuna disinstallazione o migrazione distruttiva.

## Risultati della baseline d3d220b (prima del fix query +39)

- `:app:testFossDebugUnitTest`: PASS, 91 test, zero fallimenti (incluse regressioni).
- `:app:lintFossDebug`: PASS, zero errori; warning preesistenti non disabilitati.
- `:app:assembleFossDebug`: PASS.
- `:app:assembleFossDebugAndroidTest`: PASS; 7 test strumentali compilati, non eseguiti.
- Traduzioni italiano/inglese: 1219/1219, XML/placeholder/plurali validi.
- Backend storico: 16 test PASS; nessuna dipendenza runtime.
- Controllo statico storage/log/build e scansione APK: PASS; nessuna chiave personale fornita.
- Firma locale SHA-256 invariata: `c29bd078c3d8dce14aef44687e42a662b2b69f5118caeb99e0de6cccb2492004`.
- SHA-1 per restrizione Google della build locale: `55:7F:E5:5E:4E:F0:BA:67:85:57:EB:72:88:8C:13:F5:CF:F0:40:0D`.

L’APK del runner CI usa il certificato debug del runner, quindi non sostituisce
necessariamente la build locale installata. Per aggiornare preservando la firma
locale usare l’APK prodotto sul Mac, senza disinstallazioni.


## Fix query +39

La diagnosi reale ha confermato che la richiesta E.164 compatta può restituire
zero risultati, mentre il prefisso separato e `regionCode: IT` producono un match
telefonico esatto. Il fix applica questo formato alle ricerche +39, conservando
lo zero dei fissi. Non forza la regione Italia sui numeri con altri prefissi.
Il numero canonico usato per confronto e cache rimane invariato; anche la
verifica ID-only della chiave resta invariata. Non vengono aggiunte richieste o retry.

Riferimento: [Text Search, parametro textQuery](https://developers.google.com/maps/documentation/places/web-service/text-search#textquery).

Aggiunte regressioni sintetiche per fisso, mobile, numero estero e numero
restituito differente, più il controllo della query di verifica.

## Rimozione IPQS

Google Places resta l’unico provider online. Rimossi trasporto, parser, impostazioni,
link e segnalazioni spam/rischio di IPQS. Al primo avvio vengono eliminati la sua
chiave cifrata, il flag di verifica, le preferenze e i risultati persistenti.
La pulizia è idempotente e preserva chiave Google e nomi personalizzati.
Il backend storico non è utilizzato dall’app.

## Verifica build Google-only (14 settembre 2026)

- Unit test FOSS: PASS, 75 test, zero errori/fallimenti/skipped; include recorder e backup.
- Lint FOSS: PASS; warning non disabilitati.
- assembleFossDebug e assembleFossDebugAndroidTest: PASS.
- Test strumentali compilati, non eseguiti sul telefono.
- Traduzione: 1211/1211 (100%); controllo sicurezza sorgenti/APK: PASS.
- Package: `it.stivy.rivo.personal.debug`; certificato locale invariato rispetto alla baseline.
- APK: `app/build/outputs/apk/foss/debug/RivoPhone-2.1-foss.apk`.
- SHA256 APK: `199c371901ad0c6d6439925a409dd7bf3a10cffdb8c1ba2393b2907752a8a9bf`.

## Feedback ricerca manuale

I numeri già salvati nei Contatti Android non vengono inviati a Google, anche
premendo la lente. Il dialog mostra il motivo e disabilita la ricerca online
per contatti e nomi personalizzati. I lookup riportano esiti distinti: nessuna
corrispondenza telefonica, attività trovata, provider disattivato, chiave da
verificare, permesso Contatti mancante o errore specifico Google. Il messaggio
generico “ricerca terminata” non viene più usato per una ricerca saltata.

Verifica del feedback: 75 unit test PASS, lintFossDebug PASS e assembleFossDebug
PASS. Traduzione 1218/1218; firma locale invariata. APK SHA256:
`f68c3b09bab7ca20561061bea9a4e8fa00c6e12a2e47b31030775cffdc39d92b`.
UI sul telefono ancora da collaudare.
