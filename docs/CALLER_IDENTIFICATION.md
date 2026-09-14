# Identificazione numeri sconosciuti

Baseline: Rivo Personal `475324a122e0bb1a31ba795a987a6aa469da577c`, già verificata in CI. Branch `codex/caller-identification`; nessuna modifica a package, recorder o firma locale.

## Piano e architettura

Un repository applicativo separato risolve prima la rubrica Android, poi nomi personalizzati locali e risultati per provider. Un evento di `CallService.onCallAdded` avvia la ricerca fuori dal thread UI. Compose osserva lo stato; cronologia e dettagli leggono i risultati senza scansioni online dello storico. Errori di rubrica impediscono l'invio online (fail closed).

Proxy Node con API ufficiali, Bearer token per dispositivo, selezione provider in ogni richiesta, timeout, rate limit, limite concorrenza e deduplicazione per dispositivo/provider/numero. Google richiede corrispondenza del numero internazionale; IPQS produce nomi possibili e indicatori separati. Nessuna azione di blocco.

Rischi: risultati online incompleti, nomi IPQS ambigui, numeri riassegnati, rete lenta, restrizioni di conservazione Places. Google non ha cache persistente: risultati in memoria per la presentazione corrente; IPQS ha TTL indipendente. Disattivazione invalida richieste e risultati del provider. Nomi personalizzati e token non escono dal dispositivo nei backup impostazioni.

## Fonti ufficiali

- https://developers.google.com/maps/documentation/places/web-service/text-search
- https://developers.google.com/maps/documentation/places/web-service/policies
- https://www.ipqualityscore.com/documentation/phone-number-validation-api/overview
- https://www.ipqualityscore.com/documentation/phone-number-validation-api/response-parameters

## Uso e configurazione

Vedere [backend/README.md](../backend/README.md) per chiavi server, token dispositivo, HTTPS e avvio. Nella schermata Impostazioni la sezione identificazione è immediatamente prima del backup/ripristino, che resta in fondo.

La lente accanto alle righe della cronologia e nei dettagli consente ricerca manuale, modifica/rimozione del nome privato e apertura esplicita dell’inserimento nei Contatti Android. I risultati IPQS sono sempre qualificati con “Possibile”; rischio e segnalazioni spam restano separati. Nessun nome viene scritto nel CallLog di sistema.

Il client mantiene una generazione globale e una versione per provider: spegnere Google invalida soltanto le sue risposte e non annulla IPQS. La cancellazione della cache o la sostituzione della connessione invalida tutti i risultati in arrivo. Un osservatore della rubrica aggiorna soltanto dati locali quando cambiano i contatti. Non c’è polling.

## Collaudo sul dispositivo — da verificare

Per ogni caso segnare PASS, FAIL o DA VERIFICARE; nessun test hardware è dichiarato eseguito.

| Caso | Esito iniziale |
| --- | --- |
| Contatto salvato: nome Android immediato e nessuna richiesta proxy | DA VERIFICARE |
| Numero sconosciuto: numero immediato, poi nome senza ritardo dei controlli | DA VERIFICARE |
| Risposta/rifiuto/registrazione mentre il provider è lento o non raggiungibile | DA VERIFICARE |
| Numero privato, anonimo, emergenza o invalido: nessun invio | DA VERIFICARE |
| Google corrispondenza esatta, attribuzione e link Maps visibili | DA VERIFICARE |
| IPQS: possibile nome, rischio distinto da spam, nessun blocco | DA VERIFICARE |
| Google spento prima della chiamata: nessuna richiesta Google nel proxy | DA VERIFICARE |
| Google spento durante richiesta e riacceso: risposta vecchia ignorata | DA VERIFICARE |
| IPQS continua quando Google viene spento o fallisce | DA VERIFICARE |
| Nome privato offline; contatto Android aggiunto successivamente ha precedenza | DA VERIFICARE |
| Cronologia e dettagli aggiornati; registro di sistema invariato | DA VERIFICARE |
| Ricerca manuale, correzione, rimozione e aggiunta esplicita a rubrica | DA VERIFICARE |
| Cache cancellata, scadenza IPQS e risultati Google temporanei | DA VERIFICARE |
| Proxy 401/429/503, modalità aereo, timeout e chiamate consecutive | DA VERIFICARE |
| Chiamata in background, telefono bloccato e schermo spento | DA VERIFICARE |
| Aggiornamento APK solo con certificato compatibile, dati conservati | DA VERIFICARE |

## Limiti di consegna

Le chiavi Places/IPQS e un dominio/server HTTPS non sono forniti: deployment e verifica dei provider con account reali rimangono da completare. Il codice degli adapter usa le API reali, non modalità dimostrative o scraping. I risultati Google non rimangono permanentemente nella cronologia: le condizioni Places non consentono una cache generica dei nomi. IPQS dipende dalla copertura e dai dati abilitati sul relativo piano.

Package e configurazione della firma Android non sono modificati. La chiave debug locale esistente viene riutilizzata. Non si esegue disinstallazione per forzare un aggiornamento con firma differente; un APK firmato da un precedente runner CI effimero potrebbe non essere aggiornabile finché non viene recuperata la sua chiave. I dati installati devono essere preservati.
