# Collaudo OPPO Find N6 — Rivo Personal

**Stato complessivo: DA VERIFICARE.** Nessun telefono era collegato via ADB; nessuna chiamata reale è stata effettuata. La funzionalità di ShizuCallRecorder 1.3.3 sul telefono è un dato fornito dall’utente, non un test di questo APK.

## Preparazione

1. Annotare modello, versione ColorOS/Android, build APK, SHA256 e versione Shizuku. Installare Rivo Personal accanto a Rivo originale e scegliere Personal come app Telefono predefinita.
2. Avviare Shizuku e concedere a **Rivo Personal** la sua autorizzazione. L’app ShizuCallRecorder non è necessaria.
3. Selezionare Italiano nelle lingue dell’app e completare i permessi telefonia/contatti/notifiche necessari.
4. Copiare manualmente sorgente/codec/bitrate della configurazione Shizu 1.3.3 realmente funzionante. Non presumere che sia quella predefinita. Annotarla qui: sorgente ___ codec ___ bitrate ___ cartella ___.
5. Svolgere chiamate di prova concordate con l’interlocutore. Non caricare nel repository audio, numeri, nomi, log integrali o altre informazioni personali.
6. Ripetere i test audio per Opus e AAC e per i percorsi realmente utilizzati. Per ciascun caso scegliere **PASS / FAIL / DA VERIFICARE**, indicando osservazioni e configurazione. Non segnare PASS sulla sola esistenza di un file.

| # | Test | Risultato atteso | Esito | Osservazioni |
|---|---|---|---|---|
| 1 | Chiamata uscente | Chiamata gestita da Rivo Personal, file finalizzato | DA VERIFICARE | — |
| 2 | Chiamata entrante | Risposta e fine chiamata normali, file finalizzato | DA VERIFICARE | — |
| 3 | Capsula auricolare | Entrambe le voci udibili | DA VERIFICARE | — |
| 4 | Vivavoce | Entrambe le voci udibili, nessun cambiamento automatico della sorgente | DA VERIFICARE | — |
| 5 | Bluetooth | Ripetere con il dispositivo Bluetooth realmente usato; entrambe le voci udibili | DA VERIFICARE | — |
| 5a | Scelta dispositivo Bluetooth | Con almeno due dispositivi collegati, Uscita audio mostra ogni nome e instrada la chiamata sul dispositivo selezionato | DA VERIFICARE | — |
| 6 | Schermo acceso | Controlli e stato coerenti | DA VERIFICARE | — |
| 7 | Schermo spento | Cattura continua senza interruzioni | DA VERIFICARE | — |
| 8 | Telefono bloccato | Cattura continua e fine chiamata gestita | DA VERIFICARE | — |
| 9 | App in background | Notifica coerente; nessun arresto inatteso | DA VERIFICARE | — |
| 10 | Avvio manuale | STARTING poi RECORDING soltanto dopo audio valido | DA VERIFICARE | — |
| 11 | Stop manuale | STOPPING poi IDLE; file riproducibile | DA VERIFICARE | — |
| 12 | Registrazione automatica | Rispetta preferenza e filtri Rivo | DA VERIFICARE | — |
| 13 | Fine chiamata | Stop e finalizzazione senza notifica persistente | DA VERIFICARE | — |
| 14 | Chiamate consecutive | Timer azzerato, file distinti, nessun callback della chiamata precedente | DA VERIFICARE | — |
| 15 | Shizuku spento | Errore chiaro; nessun file presentato come riuscito | DA VERIFICARE | — |
| 16 | Revoca autorizzazione Shizuku | Errore e cleanup; nuova registrazione richiede autorizzazione | DA VERIFICARE | — |
| 17 | Terminazione Shizuku durante la chiamata | Errore e cleanup, nessuna falsa registrazione microfono | DA VERIFICARE | — |
| 18 | Spazio insufficiente | Errore chiaro; nessun file vuoto presentato come riuscito | DA VERIFICARE | — |
| 19 | Entrambe le voci presenti | Ascoltare parlato alternato e simultaneo dei due interlocutori | DA VERIFICARE | — |
| 20 | Volume delle due voci | Annotare bilanciamento, distorsione, clipping e silenzi | DA VERIFICARE | — |
| 21 | Player Rivo | Riproduzione e durata coerenti per Ogg/Opus e M4A/AAC | DA VERIFICARE | — |
| 22 | Condivisione | File ricevuto con estensione, MIME e contenitore corretti | DA VERIFICARE | — |
| 23 | Eliminazione | Scompare solo il file selezionato; lista aggiornata | DA VERIFICARE | — |
| 24 | Associazione al contatto | Contatto corretto in elenco e scheda contatto | DA VERIFICARE | — |
| 25 | UI completamente italiana | Onboarding, telefono, cronologia, impostazioni, errori, notifiche e accessibilità | DA VERIFICARE | — |

## Prove aggiuntive di robustezza

- Stop immediato durante STARTING; doppio tap su registra e doppio stop: nessuna sessione duplicata, timer fermo, nessun file descriptor/processo residuo.
- Nuova chiamata mentre la precedente termina il salvataggio: l’avvio automatico attende il cleanup e ricontrolla che la chiamata sia ancora attiva.
- Revoca autorizzazione durante STARTING e durante RECORDING; successiva nuova autorizzazione e chiamata.
- Chiudere/aprire il player e cambiare file più volte; file danneggiato deve produrre un errore visibile.
- Verificare anche registrazioni precedenti già accessibili nelle cartelle condivise. Il nuovo package non può leggere automaticamente la sandbox privata del vecchio Rivo.
- Prima della prova spazio insufficiente scegliere una destinazione di test; non riempire indiscriminatamente la memoria del telefono e non cancellare dati personali.
- Con ADB autorizzato, confrontare processi scrcpy/ElevatedShellService e descrittori prima/dopo le prove. Annotare soltanto conteggi e codici d’errore redatti. I test JVM non certificano questa parte Android.

## Registrazione risultati

Data ___ · firmware ___ · APK/SHA ___ · Shizuku ___ · percorso audio ___ · sorgente/codec/bitrate ___

Esito complessivo: **DA VERIFICARE**. La compatibilità completa può essere dichiarata soltanto dopo ascolto di entrambe le voci e completamento delle prove applicabili.
