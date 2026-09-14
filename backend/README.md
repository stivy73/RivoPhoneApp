# Proxy identificazione Rivo Personal

Node.js 22 o successivo, senza dipendenze npm. Le richieste reali usano Places API (New) `places:searchText` e IPQS Phone Number Validation. I test sostituiscono solo le risposte upstream con fixture sintetiche; autenticazione, HTTP, rate limit e deduplicazione vengono eseguiti realmente su localhost. Non sono stati interrogati provider a pagamento senza chiavi.

## Configurazione

1. Copiare `.env.example` in `.env` e proteggere il file (`chmod 600 .env`). Generare un token diverso per ciascun dispositivo con `openssl rand -hex 32`; inserirli separati da virgola in `RIVO_DEVICE_TOKENS`.
2. Configurare `GOOGLE_PLACES_API_KEY` (Places API New abilitata, billing e restrizioni API/IP server) e/o `IPQS_API_KEY`. Nessuna chiave va nell’APK o nel token dispositivo. Un provider senza chiave non è annunciato da `/v1/status`.
3. Avviare: `node --env-file=.env server.mjs`. Ascolta solo su localhost. Usare un reverse proxy TLS con certificato valido, per esempio Caddy:

```
caller.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Sostituire il dominio con quello sotto il proprio controllo. Non abilitare logging di header Authorization o corpi richiesta/risposta; il server non registra numeri, token o URL upstream. In Docker pubblicare la porta solo su localhost (`-p 127.0.0.1:8080:8080`), passando le variabili con `--env-file .env`.

4. In Rivo: Impostazioni → Identificazione numeri sconosciuti → URL `https://caller.example.com`, token dispositivo → Salva e verifica connessione. Attivare i provider configurati e il riconoscimento online. Ogni provider può essere spento indipendentemente senza ricompilazione.

Il collegamento è esclusivamente HTTPS e i redirect non sono seguiti. Il token è privato dell’app ed escluso da backup cloud, trasferimento Android e backup impostazioni. Per revocarlo, rimuoverlo dal server e riavviare. Non riutilizzare lo stesso token per utenti diversi.

## Contratto

Autenticazione: `Authorization: Bearer <token dispositivo>`.

- `GET /v1/status`: `{"providers":["google","ipqs"]}`.
- `POST /v1/identify`: `{"number":"+390212345678","provider":"ipqs","enabled":true,"refresh":false}`.
- Una richiesta contiene **un solo provider**, mai la rubrica o la cronologia. Nessun fallback implicito, retry, polling o fanout verso provider non richiesti. L’app interroga i provider selezionati in coroutine indipendenti.
- Risposta: numero e provider, `name` nullable, `verified`, `ttlSeconds`, ed eventualmente `risk` e `spamReported`. Google comprende attribuzioni originali e link Maps. IPQS non viene presentato come nome verificato, indipendentemente dal rischio.
- Errori: 401 autenticazione; 400 input; 413 corpo troppo grande; 429 limite; 503 provider non configurato; 502 errore upstream. Non vengono esposti errori grezzi o segreti.

## Limiti e conservazione

30 richieste/minuto per token (incluso status), massimo 16 lookup simultanei per processo, 4 KiB per richiesta e timeout upstream 3,5 s. IPQS ha cache separata per dispositivo/numero, TTL configurabile da 0 a 86400 secondi (predefinito 3600), massimo 1000 risultati nel proxy; refresh manuale bypassa il TTL. La cache è in RAM e si svuota al riavvio. Android conserva al massimo 500 risposte IPQS private con la stessa scadenza. Per più istanze serve un rate limiter condiviso: questa versione è progettata per un singolo processo personale.

Google non viene memorizzato nel proxy né su disco Android. Il risultato rimane nello stato di presentazione dell’app per massimo cinque minuti, poi scompare; una nuova chiamata o ricerca manuale richiede un risultato fresco. Di conseguenza, la cronologia non ha nomi Google permanenti. I nomi personalizzati inseriti dall’utente rimangono offline e non vengono inviati al proxy.

Disabilitare Google impedisce nuove richieste client e quindi nuove chiamate del proxy a Google; le richieste già spedite non possono essere ritirate dal provider, ma i loro risultati vengono ignorati. Il proxy non avvia richieste successive, retry o altri provider dopo la prima. IPQS continua in modo indipendente. La cache locale può essere cancellata dalle impostazioni senza eliminare i nomi personalizzati.

## Google e IPQS

Prima di mettere il servizio a disposizione di altri utenti, pubblicare termini e informativa privacy appropriati che includano i rinvii richiesti da Google. Rispettare le condizioni Places, incluse quelle applicabili all’account SEE, e il contratto IPQS relativo a uso e conservazione. Non presentare il servizio come identificazione garantita. La verifica Google riguarda la corrispondenza del numero restituito, non la prova dell’identità di chi chiama.

Fonti ufficiali: [Places Text Search](https://developers.google.com/maps/documentation/places/web-service/text-search), [Places policies](https://developers.google.com/maps/documentation/places/web-service/policies), [IPQS API](https://www.ipqualityscore.com/documentation/phone-number-validation-api/overview), [IPQS response fields](https://www.ipqualityscore.com/documentation/phone-number-validation-api/response-parameters).

## Test

`node --test backend/test/*.test.mjs` dalla radice della repository. Tutti i numeri sono sintetici. Deployment, chiavi reali, latenza dei provider e chiamata reale sull’OPPO sono verifiche separate ancora da eseguire.
