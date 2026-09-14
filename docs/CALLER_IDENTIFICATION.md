# Identificazione diretta dei numeri sconosciuti

Baseline stabile: Rivo Personal `1a332b76b8a764c707da5147db07773a4ca02abf`.
Branch: `codex/direct-provider-keys`. Package, firma, registrazioni e motore Shizu invariati.

## Configurazione nell’app

Impostazioni → Identificazione numeri sconosciuti. Aprire la scheda del provider,
inserire la propria chiave, premere **Verifica chiave**, attivare il provider e il
riconoscimento online. Gli interruttori sono indipendenti; una scelta ON senza una
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

### IPQualityScore

1. [Crea un account o accedi](https://www.ipqualityscore.com/login).
2. Apri Dashboard e copia/crea la API key.
3. Incollala nella scheda IPQualityScore e verifica.
4. Controlla abilitazione Phone Validation/Reverse Lookup, crediti e limiti del piano.

La verifica usa la [Credit Usage API ufficiale](https://www.ipqualityscore.com/documentation/account-management/usage),
senza consumare una ricerca telefonica. Un account con crediti non garantisce che
ogni dato di Phone Validation sia incluso nel piano: eventuali errori del lookup
aggiornano lo stato. Il lookup usa l’endpoint ufficiale
`https://ipqualityscore.com/api/json/phone`, chiave nell’header `IPQS-KEY` e numero
nel corpo POST, secondo le [opzioni ufficiali](https://www.ipqualityscore.com/documentation/phone-number-validation-api/advanced-options).
L’endpoint account documentato contiene la chiave nel percorso HTTPS; URL ed
errori del trasporto non vengono mai registrati né propagati alla UI.

Si recuperano valid, formatted, name, carrier, line_type, country, spammer, risky,
recent_abuse e fraud_score. Nomi mancanti o strutture ambigue non diventano nomi
arbitrari; il nome rimane “Possibile”. Spam e rischio sono distinti, senza blocchi.

## Storage, cache e concorrenza

Chiavi personali cifrate AES-256/GCM con IV casuale, autenticazione del provider
tramite AAD e chiave crittografica non esportabile in Android Keystore. Solo
ciphertext in `noBackupFilesDir/provider_keys`, mai SharedPreferences in chiaro,
BuildConfig, risorse, log o saved instance state. Backup Android e trasferimento
device escludono per definizione noBackupFilesDir; il backup impostazioni esistente
legge soltanto le preferenze generali e non legge questa directory. Dopo reinstallazione
senza dati/Keystore occorre reinserire le chiavi. Ogni chiave è inviata via HTTPS
solo al relativo provider per le richieste autorizzate.

Precedenza invariata: rubrica → nome privato → Google esatto → IPQS possibile →
segnali rischio → numero. Un errore nella rubrica impedisce l’invio online. Ricerca
event-driven sullo squillo, nessuna scansione online dello storico. Provider in
parallelo, deadline indipendenti di 3 secondi, I/O fuori dal main thread,
deduplicazione per numero. Disattivazione/cambio chiave invalida la versione del
solo provider e cancella il suo lavoro; risposte tardive non sono accettate.
Fine chiamata può completare una ricerca per cache/cronologia, mentre i collector
Compose e le verifiche di sessione esistenti proteggono la schermata terminata.

Google rimane solo in memoria per 5 minuti, senza cache persistente dei nomi
([policy](https://developers.google.com/maps/documentation/places/web-service/policies)).
IPQS mantiene TTL indipendente di un’ora (default del precedente backend), limite
500 record persistenti. Svuotare cache non elimina nomi privati, contatti o chiavi.

## Verifica e regressioni

Unit test: payload/errori ufficiali sintetici, esatta corrispondenza telefonica,
più risultati/ambiguità, assenza chiavi, verifica ID-only/crediti, timeout paralleli,
fallimenti isolati, deduplicazione e cancellazione, precedenze e risposte obsolete.
Restano eseguiti anche i test preesistenti di recorder, stream e backup impostazioni.

Test strumentali `ProviderSecretsTest`: cifratura reale Keystore, lettura dopo
ricreazione store, rimozione indipendente, tampering, IV casuali e Intent browser.
Usano una directory temporanea isolata e non sovrascrivono le chiavi personali. Da eseguire
su dispositivo/emulatore con `:app:connectedFossDebugAndroidTest`.

| Test hardware/account reale | Stato |
| --- | --- |
| Chiave Google, API disabilitata, billing, quota, restrizioni Android | DA VERIFICARE |
| Chiave IPQS, crediti, accesso Phone Validation e nomi reali | DA VERIFICARE |
| Incolla, mostra/nascondi, rimozione e apertura browser | DA VERIFICARE |
| Keystore e backup sul dispositivo | DA VERIFICARE |
| Chiamata entrante/uscente, risposta/rifiuto con provider lento/offline | DA VERIFICARE |
| Cronologia, contatto Android, nome privato, Google OFF/IPQS ON e viceversa | DA VERIFICARE |
| Registrazione Shizu, schermo bloccato/background, chiamate consecutive | DA VERIFICARE |

Nessuna chiave personale è stata fornita: non si dichiara verificato l’accesso agli
account reali né il collaudo telefonico. Nessuna disinstallazione o migrazione distruttiva.

## Risultati di questa revisione

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
