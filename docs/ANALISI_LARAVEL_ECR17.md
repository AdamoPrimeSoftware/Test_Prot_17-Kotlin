# Analisi di `padosoft/laravel-ecr17` e profili terminale Ingenico/CCV

> Documento di analisi tecnica redatto il 2026-10-14. Completa [`ANALISI_REPOSITORY.md`](ANALISI_REPOSITORY.md) (di seguito "doc principale"; `§x` rimanda a una sua sezione) con il terzo port padosoft del Protocollo 17 / ECR17 e con i profili terminale da usare nell'app **Protocollo17ComposeDesktop** (Kotlin / Compose for Desktop, JVM, Windows).
> Repository letti in sola lettura: nessun `composer`, `npm`, `php` né codice dei repo eseguito. Sono stati eseguiti solo script miei che ricalcolano LRC e offset.
>
> **Convenzione delle citazioni** (come nel doc principale, più `laravel:`)
>
> | Prefisso | Sorgente | Radice dei path |
> |---|---|---|
> | `laravel:` | <https://github.com/padosoft/laravel-ecr17> (HEAD `d1beff3`) | radice del repo (es. `src/Session/Ecr17Session.php:258`) |
> | `rust:` | <https://github.com/padosoft/rust-ecr17-protocol> | radice del repo |
> | `rn:` | <https://github.com/padosoft/react-native-ecr17-protocol> | radice del repo |
> | `wpf:` | app C# `D:/Programmazione/Prime/TestVari/Protocollo17WpfTest` | radice del progetto |
> | `lda:` | app `D:/LLM/DesktopApp/LocalDesktopAi` | radice del progetto |
>
> - `demo:` è un'abbreviazione di `laravel:demo/`.
> - **(derivato)** = valore calcolato con uno script indipendente, non presente nei repo.
> - Livelli di evidenza per i profili terminale (§10): **[CAPTURE]** log reale della DLL IAE37 sul terminale Ingenico; **[DLL]** stringhe/export della DLL (analisi statica, nessuna esecuzione); **[REPO]** codice o documentazione dei repo; **[WEB]** pagina pubblica (URL indicato); **[ASSUMPTION]** deduzione non verificata.
> - pos = 1-based (come la spec), off/indice = 0-based (come i test). Hex maiuscolo; i log della DLL sono riportati in minuscolo come compaiono.
> - D1..D19 = differenze della tabella §8 del doc principale; W*n*/S*n* = identificativi dei report di verifica che le hanno originate; L*n* = differenze nuove introdotte da laravel (§9 di questo documento).

---

## Indice

1. [Sintesi](#1-sintesi)
2. [Struttura del repo e tecnologie](#2-struttura-del-repo-e-tecnologie)
3. [Core del protocollo: cosa coincide e cosa differisce](#3-core-del-protocollo-cosa-coincide-e-cosa-differisce)
4. [Sessione e trasporto PHP](#4-sessione-e-trasporto-php)
5. [Risposte: campi, esiti e lacune](#5-risposte-campi-esiti-e-lacune)
6. [Money-safety, idempotenza, recupero con G e journal](#6-money-safety-idempotenza-recupero-con-g-e-journal)
7. [Troubleshooting e runbook: insidie con i terminali reali](#7-troubleshooting-e-runbook-insidie-con-i-terminali-reali)
8. [Demo debug console e idee per la nostra UI](#8-demo-debug-console-e-idee-per-la-nostra-ui)
9. [Posizione di laravel su W1..W12 / S1..S14 e nuove differenze L*](#9-posizione-di-laravel-su-w1w12--s1s14-e-nuove-differenze-l)
10. [Profili terminale Ingenico vs CCV](#10-profili-terminale-ingenico-vs-ccv)
11. [Domande aperte del doc principale: stato dopo laravel](#11-domande-aperte-del-doc-principale-stato-dopo-laravel)
12. [Impatto sul piano di porting Kotlin](#12-impatto-sul-piano-di-porting-kotlin)
13. [Test vector aggiuntivi](#13-test-vector-aggiuntivi)

---

## 1. Sintesi

### 1.1 Cos'è laravel-ecr17

`padosoft/laravel-ecr17` è un pacchetto **PHP 8.3 / Laravel 13** che implementa il Protocollo 17 su TCP. È un **port sincrono, quasi riga per riga, del core C++ di rn**: il repo dichiara rn come "behavioral source of truth" e chiede di allinearsi a rn in caso di dubbio su layout, offset o money-safety (laravel:`AGENTS.md:12-16`; laravel:`docs/LESSON.md:7-9`; laravel:`PROGRESS.md:22-24`).

| Voce | Valore | Fonte |
|---|---|---|
| Storia | 20 commit, dal 2026-05-28 (`ab4f031`, Initial commit) al 2026-07-12 (`d1beff3`, solo documentazione). L'ultimo commit su `src/`/`tests/`/`config/` è `a8038ae` del 2026-05-29 ("Ecr17Client + Laravel glue + PROACTIVE reconnection") | `git log` |
| Dimensione | ~2000 righe in `src/` (27 file); 74 test Pest in 9 file | conteggio |
| Stato dichiarato | "PACKAGE COMPLETE" (fasi 0-5: core, sessione, client, demo, CI) | laravel:`PROGRESS.md:12-20` |
| Terminali | **solo Nexi Group**. Nessuna occorrenza di Ingenico, CCV o altri vendor in codice, test e documentazione | laravel:`composer.json:3`; laravel:`README.md:5,52`; grep |
| Prove su hardware | **nessuna propria**. L'unica evidenza "on device" (sintomo "one yes, one no" sulla chiusura TCP) è ereditata da rn/Nexi; lo screenshot della demo mostra solo un `status` fallito con host vuoto | laravel:`docs/LESSON.md:47-57`; laravel:`resources/screenshoots/ECR17-Debug-Console.png` |

### 1.2 Maturità e valore per noi

- **Sul filo non porta nulla di nuovo.** Byte di controllo, LRC (base `0x7F`, 4 modalità, default `std`), decoder e i 15 builder sono byte-identici a rust/rn (§3, §4.3); i test vector sono un sottoinsieme di quelli già raccolti in §9.
- **È un terzo riscontro indipendente** delle scelte già proposte per Kotlin, e soprattutto delle loro mancanze: anche laravel accoppia l'LRC dei frame di controllo alla modalità applicativa (in `stx` invia `06 03 78`, l'Ingenico usa `06 03 7A`) e ritrasmette i frame finanziari quando manca l'ACK.
- **Il valore aggiunto è operativo**: documentazione di money-safety, runbook e journal (§6, §7), e una demo che apre **un socket per ogni richiesta HTTP** "which mirrors how ECR17 terminals typically close the TCP connection after each transaction" (demo:`README.md:40-42`). È il terzo precedente, dopo la DLL Ingenico, a favore dell'opzione "connessione per comando".

### 1.3 Risultati principali

1. **ACK in uscita sbagliato per l'Ingenico, ACK in ingresso accettato per caso.** `encodeControl` usa la modalità applicativa (laravel:`src/Protocol/PacketCodec.php:40-45`): in `stx` produce `06 03 78` / `15 03 6B`. Lo splitter accetta **qualsiasi** terna che inizi con `06`/`15` senza controllare ETX né LRC (laravel:`src/Session/Ecr17Session.php:258-266`), quindi l'ACK reale `06 03 7A` passa. È il comportamento C++ (D1). Nessuno dei tre port padosoft parla il filo Ingenico in entrambe le direzioni.
2. **Con la configurazione di default non funziona sull'Ingenico**: `lrc_mode=std` (laravel:`config/ecr17.php:15`) fa uscire il `P` della cattura (§9.8 riga [1]) con LRC `2E` invece di `2F` → NAK → `ECR17: NAK after 4 attempts`. Un refuso nel valore ricade **in silenzio** su `std` (laravel:`src/Protocol/LrcMode.php:21-24`, L1).
3. **Nessun implicit go-ahead.** L'handshake è aritmeticamente identico a rust/rn (4 invii in ~8,6 s con i default) e ritrasmette anche `P/X/p/i/c/S/H` (laravel:`src/Session/Ecr17Session.php:94-143`). L'opzione decisa dall'utente non ha riscontro in nessun port padosoft: va scritta da zero sul modello della DLL.
4. **Sessione = C++ su D1-D4, Rust su D5.** ACK finale e drain non sono best effort (un esito approvato può diventare un'eccezione), `U` è inviato sempre; la lettura è *pull* senza reader continuo, quindi in un processo persistente i byte stantii vengono attribuiti al comando successivo.
5. **Il modello di connessione dipende dall'host.** La libreria tiene il socket aperto e fa un probe non distruttivo prima di ogni comando (`stream_select` + `STREAM_PEEK`); con PHP-FPM e nella demo, però, client e socket nascono e muoiono con la richiesta HTTP: di fatto una connessione per comando (L3).
6. **Parser = stesse lacune di rust/rn** (`i` letto come `e`, `c`/`v` e `G` letti come pagamento, nessun `actionCode` nel DTO, `U` dal GT mai letto, VAS multi-messaggio e sentinella di fine scontrino non gestiti). Un `U` dal GT stantio verrebbe letto come **pagamento approvato** (derivato, §5.5).
7. **Documentazione di money-safety utile ma con due insidie**: l'esempio `catch → return sendLastResult()` può attribuire all'ordine l'esito della transazione precedente, e la guida chiede di "confrontare l'importo", che la `E` da 71 B non contiene (§6.4).
8. **Default diversi dagli altri port**: porta **1024** (nessun terminale noto la usa; gli esempi del README usano 10000; l'Ingenico usa 5577) e `auto_reconnect=true` già nella libreria (L2).
9. **CCV: nessuna informazione**, né in laravel né altrove. Il profilo CCV si può solo derivare per ipotesi da quello Ingenico e va validato con una cattura (§10).

### 1.4 Licenza (risposta alla domanda 7 dell'utente)

**Verifica.** Il file `LICENSE` contiene il **testo standard Apache-2.0**, byte-identico a quello di rn (verificato con `cmp`), ed è nel commit iniziale `ab4f031` (tipico della scelta di licenza fatta nella procedura di creazione del repo GitHub). `composer.json` dichiara `"license": "MIT"` (laravel:`composer.json:6`) e il README chiude con "MIT © padosoft" (laravel:`README.md:210`), con il badge MIT che punta proprio a `LICENSE` (laravel:`README.md:13`). Nel repo **non c'è alcun testo MIT**.

> Correzione rispetto ai report intermedi: la riga `Copyright [yyyy] [name of copyright owner]` (laravel:`LICENSE:189`) **non** indica un template "non compilato". Fa parte dell'appendice standard "How to apply the Apache License to your work" che accompagna sempre il testo ufficiale. L'ambiguità reale è un'altra: due dichiarazioni diverse e nessuna nota di copyright padosoft nel file di licenza. È la stessa situazione di rn (§12.9 R9); rust è MIT.

**Differenze tra le due licenze** (sintesi divulgativa, non consulenza legale):

| Aspetto | MIT | Apache-2.0 |
|---|---|---|
| Uso commerciale, modifica, ridistribuzione, app proprietaria (closed source) | sì | sì |
| Obblighi di attribuzione | conservare nota di copyright e testo di permesso in tutte le copie o "porzioni sostanziali" | consegnare una copia della licenza, conservare le note di copyright/brevetto/marchio/attribuzione, **segnalare in modo evidente i file modificati**, propagare le attribuzioni dell'eventuale file `NOTICE` |
| Brevetti | nessuna concessione esplicita | **licenza brevettuale esplicita** dai contributori, che **decade** per chi avvia una causa brevettuale relativa all'opera |
| Marchi | non trattati | nessun diritto sui marchi |
| Contributi | non regolati | i contributi inviati al progetto ricadono sotto Apache-2.0 salvo diverso accordo |
| Lunghezza / complessità | poche righe | testo lungo, obblighi più articolati |

**Raccomandazione per il nostro progetto** (coerente con la scelta dell'utente di rimandare la licenza alla fine): **reimplementare in Kotlin citando le fonti**, senza copiare porzioni sostanziali di codice, come proposto in §12.9 R9. In questo modo l'ambiguità dei repo padosoft non tocca l'app. Se in futuro si copiasse codice da rn o laravel, la scelta prudente è rispettare entrambe le licenze (includere il testo Apache-2.0, la nota "MIT © padosoft" e segnare i file modificati) oppure chiedere chiarimenti a padosoft. La licenza della **nostra** app è una decisione separata: MIT è più semplice, Apache-2.0 aggiunge la protezione brevettuale esplicita.

---

## 2. Struttura del repo e tecnologie

### 2.1 Cartelle

| Percorso | Contenuto | Note |
|---|---|---|
| `src/Protocol/` | `Lrc`, `LrcMode`, `PacketCodec`, `PacketType`, `DecodedPacket`, `Ecr17Protocol` (15 builder + tag di tokenizzazione) | port 1:1 di `package/cpp/{Lcr,PacketCodec,Ecr17Protocol}` di rn |
| `src/Response/` | `Ecr17Response` (parser), DTO `PaymentResponse`, `PreAuthResponse`, `StatusResponse`, `TotalsResponse`, `CloseResponse`, `VasResponse`, `DccInfo`, enum `Outcome` | port 1:1 di `Ecr17Response.cpp` |
| `src/Session/` | `Ecr17Session` (319 righe), `SessionConfig`, `RetryPolicy` | port sincrono di `Ecr17Session.cpp` |
| `src/Transport/` | `TransportInterface`, `SocketTransport` (`stream_socket_client` + `stream_select`), `FakeTransport`, `TransportException` | |
| `src/` | `Ecr17Client` (364 righe), `Ecr17Config`, `Ecr17ServiceProvider`, `Facades/Ecr17`, `TokenizationRequest` | glue Laravel: singleton `'ecr17'`, facade, autodiscovery |
| `config/ecr17.php` | 14 chiavi `ECR17_*` | §4.11 |
| `tests/` | Pest 4 + Orchestra Testbench: 74 test (Protocol 18+15+4, Response 12, Session 15+2, Client 6, Feature 1, Sanity 1) | laravel:`PROGRESS.md:18` (verificato) |
| `demo/` | app Laravel 13 con console React 18 + Tailwind da CDN, senza build | §8 |
| `docs-site/` | sito di documentazione docmd (Node): concetti, architettura (ADR), guide, operations (runbook, troubleshooting, observability), reference | §6, §7 |
| `docs/` | `LESSON.md` (lezioni ereditate da rn), `superpowers/specs` e `superpowers/plans` (spec di design e piano del 2026-05-28) | |
| radice | `AGENTS.md`, `CLAUDE.md`, `PROGRESS.md` (contesto per agenti AI), `.github/` + `tests.yml` (CI Pest matrix + Pint + PHPStan/Larastan) | |

### 2.2 Tecnologie

PHP `^8.3`, `illuminate/support` e `illuminate/contracts` `^13.0`; dev: `orchestra/testbench ^11`, `pestphp/pest ^4`, `laravel/pint`, `larastan/larastan ^3` (laravel:`composer.json:13-23`). Autoload PSR-4 `Padosoft\Ecr17\` → `src/`, provider e alias `Ecr17` registrati per autodiscovery (laravel:`composer.json:34-43`).

### 2.3 Cose dichiarate ma assenti, e piccoli difetti di packaging

- La spec di design prevede classi `Events/`, un helper `ecr17()` e una rotta `POST /ecr17/disconnect` (laravel:`docs/superpowers/specs/2026-05-28-laravel-ecr17-design.md:41,92-100`). **Non esistono**: gli "eventi" sono solo callback (`setOnProgress`, `setOnReceiptLine`, `setOnConnectionStateChange`, laravel:`src/Ecr17Client.php:44-57`).
- `TokenizationService` è un enum dichiarato dentro `TokenizationRequest.php` (laravel:`src/TokenizationRequest.php:7-11`): con PSR-4 si carica solo se `TokenizationRequest.php` è già stato caricato. Irrilevante per il port.
- La documentazione mostra `lrcMode: 'std'` come stringa nel costruttore di `Ecr17Config` (laravel:`docs-site/docs/reference/configuration-api.md:16`), ma il costruttore vuole l'enum `LrcMode`: con `strict_types` sarebbe un `TypeError`.
- Il README parla di log "on screen + file" per la demo (laravel:`README.md:175`), ma il codice scrive solo in Cache (§8.1).

---

## 3. Core del protocollo: cosa coincide e cosa differisce

### 3.1 Tabella di confronto

| Aspetto | laravel | rust | rn (C++) | Nota per Kotlin |
|---|---|---|---|---|
| Caratteri di controllo | `STX 02`, `ETX 03`, `SOH 01`, `EOT 04`, `ACK 06`, `NAK 15`, riempitivo `'0'`, separatore TAG `1B` (laravel:`src/Protocol/PacketCodec.php:16-26`; laravel:`src/Protocol/Ecr17Protocol.php:19-21`) | = | = | = §3.1 |
| Algoritmo LRC | base `0x7F`, XOR, `^STX` se Stx/StxNoext, `^ETX` se Stx/Noext (laravel:`src/Protocol/Lrc.php:22-40`) | = | = | = §3.3; `foldStx`/`foldEtx` |
| Default `lrc_mode` | `std` (laravel:`config/ecr17.php:15`; laravel:`src/Session/SessionConfig.php:12`) | `std` | `std` | profilo Ingenico/CCV: `stx` |
| Parsing della modalità | `tryFrom(strtolower(v)) ?? Std`: **fallback silenzioso** (laravel:`src/Protocol/LrcMode.php:21-24`) | serde esatto, errore | union TS → enum | **L1**: rifiutare valori sconosciuti |
| `encodeApplication` | `02 + p + 03 + Lrc(p)` (laravel:`src/Protocol/PacketCodec.php:31-37`) | = | = | = |
| `encodeControl` | `ctrl + 03 + Lrc(chr(ctrl), mode)` (laravel:`src/Protocol/PacketCodec.php:40-45`) | = | = | regola separata `WIRE` (§3.2) |
| Decode di un frame | ACK/NAK dal **solo primo byte** (`validLrc=true`); `01…04` → Progress; `02…03 LRC` → Application anche con LRC errato (`validLrc=false`); altro → Unknown (laravel:`src/Protocol/PacketCodec.php:47-94`) | = | = | = (decoder permissivo, severità nello splitter: §11 punto 14) |
| Splitter, lead `06`/`15` | prende **qualsiasi** terna, nessun controllo di ETX/LRC, nessuna risincronizzazione (laravel:`src/Session/Ecr17Session.php:258-266`) | richiede ETX e LRC = `mode.compute([ctrl])`, altrimenti scarta 1 byte | come laravel | **D1**: ETX obbligatorio, LRC di entrambe le convenzioni, log del mismatch |
| Splitter, `02` / `01` / altro | `02…primo 03 + 1 byte`; `01…primo 04`; altro → scarta 1 byte **senza log** (laravel:`src/Session/Ecr17Session.php:268-291`) | = | = | = ma con log dei byte scartati |
| 15 builder | **byte-identici** a §4.3 (verificato dai test e da ricalcolo indipendente) | = | = | = |
| Testo scontrino | allineato a **destra**, riempito di spazi (laravel:`src/Protocol/Ecr17Protocol.php:206,220`) | = | = | = §11 punto 13 |
| Tipo pagamento nel builder | `string` concatenata **senza controlli né di valore né di lunghezza**: `'12'` produce 168 byte (laravel:`src/Protocol/Ecr17Protocol.php:195-208`) | `'0'..'3'` o `InvalidPaymentType` | `char` senza controlli | **L4**: enum + larghezza esatta |
| Tipo pagamento nel client | `debit/credit/other` → `1/2/3`, **qualsiasi altra stringa → `'0'` (auto) in silenzio**, comprese `'DEBIT'`, `'debito'` e le cifre `'1'..'3'` (laravel:`src/Ecr17Client.php:355-363`) | enum serde | enum Nitro | rifiutare |
| Contenuto `U` e contratto | solo lunghezze 1..100 e 1..18 (laravel:`src/Protocol/Ecr17Protocol.php:157-160,182-185`) | vieta `1B`, contratto alfanumerico | come laravel | = D8 (regole Rust + divieto byte di controllo) |
| Importo | `int` 64 bit; `<0` errore, `>99 999 999` overflow, 0 accettato (laravel:`src/Protocol/Ecr17Protocol.php:262-269`) | = | `static_cast<int>(double)` | = D7 |
| Codifica | stringhe PHP = byte: offset esatti in ingresso, nessuna validazione ASCII in uscita | UTF-8 lossy in ingresso (D6) | byte grezzi | `ByteArray`/`ISO_8859_1`, ASCII in uscita |
| Override cash register | `closeSession`, `totals`, `sendLastResult` (e i finanziari) accettano un `?cashRegisterId`; `vas` usa sempre la config (laravel:`src/Ecr17Client.php:201-235,255-262`) | nessun override per C/T/G | — | **L8** (bassa): facoltativo |
| Testi d'errore | identici al C++ (`attempts` senza "(s)") | `attempt(s)` | stringhe | prefissi `ECR17: …` nel log, sealed class |

### 3.2 Frame di controllo: cosa produce laravel e cosa passa sul filo Ingenico

| Modalità | ACK laravel | NAK laravel | Nota |
|---|---|---|---|
| `std` (default) | `06 03 79` | `15 03 6A` | |
| `stx` | **`06 03 78`** | `15 03 6B` | modalità applicativa corretta per l'Ingenico, ACK errato |
| `noext` | `06 03 7A` | `15 03 69` | ACK corretto per caso, LRC applicativo errato |
| `stx_noext` | `06 03 7B` | `15 03 68` | |
| **Filo Ingenico (`0x7F ^ ctrl ^ ETX`)** | **`06 03 7A`** [CAPTURE] | `15 03 69` (derivato, mai osservato) | 13 ACK su 13 (wpf:`bin/Debug/2026_03_18-iaedll-log.txt:2,6,8,12,14,18,20,24,26,30,32,36,38`) |

Valori ricalcolati con uno script indipendente; coincidono con §3.3/§9.2. **Regola unificata osservata** sull'Ingenico: *LRC = `0x7F` XOR tutti i byte che lo precedono sul filo*. Per i frame applicativi equivale a `stx`, per i frame di controllo a `0x7F^ctrl^03`. Nessuna `LrcMode` dei tre port la riproduce per entrambi i tipi di frame.

### 3.3 Cosa succederebbe usando laravel contro l'Ingenico reale

| `lrc_mode` | Esito previsto |
|---|---|
| `std` (default) | Il `P` della cattura (§9.8 riga [1]) esce con LRC `2E` invece di `2F` → il terminale lo NAKa o lo ignora → 3 ritrasmissioni → `ECR17: NAK after 4 attempts` (o `no ACK after 4 attempts` dopo ~8,6 s). **Non funziona.** |
| `stx` | Frame applicativi corretti; l'ACK `06 03 7A` del terminale viene accettato perché lo splitter non guarda l'LRC. L'ECR però ACKa l'esito con `06 03 78`: non sappiamo se l'Ingenico lo accetti (§13.1 Q2). Se lo rifiutasse e ritrasmettesse l'esito: nella demo il frame muore con il socket della richiesta; in un worker persistente resta nel buffer del SO e viene **restituito come esito del comando successivo** (D5). |
| `noext` / `stx_noext` | LRC applicativo errato (`P` → `2D` / `2C`) → NAK, come in `std`. |

### 3.4 Conseguenze dello splitter permissivo (D1, lato C++)

- **(a)** Accetta l'ACK reale Ingenico in qualsiasi modalità: funziona per caso.
- **(b)** Se un terminale inviasse un ACK "nudo" di 1 byte seguito subito dall'esito, la terna `[06, 02, p0]` verrebbe presa come ACK e il resto dell'esito scartato byte per byte come spazzatura → `no application response before timeout`, salvo ritrasmissione del terminale. Poiché `rxBuffer` accumula tra una `read` e l'altra, il caso vale anche se `06` e STX arrivano in segmenti TCP diversi.
- **(c)** Una terna con LRC sbagliato come `06 03 00` è accettata come ACK; Rust la scarta. Laravel non ha test di risincronizzazione (mancano gli equivalenti di rust:`crates/ecr17-protocol/src/session.rs:465,481`).

---

## 4. Sessione e trasporto PHP

### 4.1 Modello di esecuzione

- **Sincrono e pull**: la sessione "pompa" `Transport::read()` solo dentro `waitForFrame` (laravel:`src/Session/Ecr17Session.php:228-247`); non c'è un reader in background. Orologio monotono `hrtime(true)/1e6`, pause con `usleep` (laravel:`src/Session/Ecr17Session.php:308-318`).
- API di sessione (laravel:`src/Session/Ecr17Session.php:58-84`):

| Metodo | Sequenza |
|---|---|
| `exchange(p)` | reset → handshake(p) → waitForResult |
| `exchangeWithAdditionalData(p, u)` | reset → handshake(p) → **handshake(u) sempre** → waitForResult |
| `sendAckOnly(p)` | reset → handshake(p) (per `E`, `R`) |

- `resetForNewTransaction()` azzera solo `rxBuffer` e `pendingResult` in memoria (laravel:`src/Session/Ecr17Session.php:88-92`): **non** legge né scarta i byte già nel buffer del SO.

### 4.2 Handshake (laravel:`src/Session/Ecr17Session.php:94-143`)

```
frame = encodeApplication(p); send(frame); attempts = 1; deadline = now + ackTimeout
loop:
  if now >= deadline:
     if attempts > retryCount: throw RuntimeException("ECR17: no ACK after {attempts} attempts")
     usleep(retryDelay); send(frame); attempts++; deadline = now + ackTimeout; continue
  pkt = waitForFrame(remaining); if null: continue
  Ack         -> return
  Nak         -> if attempts > retryCount: throw RuntimeException("ECR17: NAK after {attempts} attempts")
                 else usleep(retryDelay); send(frame); attempts++; deadline rinnovata
  Application -> pendingResult = pkt; return             (LRC non verificato qui)
  Progress / Unknown -> ignorati (il progress NON arriva al callback; Unknown NON riceve NAK)
```

Identico a §5.3: **ritrasmette i frame finanziari** sul timeout dell'ACK. Caso peggiore con i default: 4×2000 + 3×200 = **8600 ms**; con la tokenizzazione si aggiunge un secondo handshake (+8600 ms).

### 4.3 Attesa dell'esito (laravel:`src/Session/Ecr17Session.php:145-193`)

Deadline **unica** `now + responseTimeoutMs`, non estesa da progress o scontrini.

| Pacchetto | Azione |
|---|---|
| `pendingResult` presente | consumato per primo, stesso `switch` |
| Progress | callback `onProgress(payload)` con i 20 caratteri grezzi; nessun ACK |
| Application con LRC errato | NAK, si continua |
| Application valido con `payload[9]=='S'` (len ≥ 10) | ACK, `onReceiptLine(payloadIntero)` con l'intestazione; si continua |
| Application valido di altro tipo | ACK → `drainReceipts()` → **return**, senza verificare che il codice sia quello atteso (es. `E` per `P`) |
| Ack / Nak | ignorati |
| Unknown | NAK (codice difensivo: lo splitter estrae solo frame ben formati) |
| timeout | `RuntimeException('ECR17: no application response before timeout')` |

### 4.4 Drain degli scontrini (laravel:`src/Session/Ecr17Session.php:195-225`)

Attivo solo se `receiptDrainMs > 0` (default 0). Ogni frame riarma la finestra; nessun limite complessivo. Application valido → ACK (+ callback se è `S`; un frame non `S`, per esempio un `U` dal GT o un secondo `K`, viene ACKato e **scartato in silenzio**); LRC errato → NAK; Progress → callback. Un'eccezione di trasporto **non è catturata**: l'esito, già ACKato, si perde (D3).

### 4.5 ACK/NAK dell'ECR

`sendControl` = `transport->send(encodeControl(c))` senza protezione (laravel:`src/Session/Ecr17Session.php:297-300`). Se il socket è morto (`fwrite` fallisce e `feof`), `SocketTransport::send` lancia `TransportException` (laravel:`src/Transport/SocketTransport.php:99-102`) e l'esito già decodificato si perde (D2). In pratica serve un RST o un errore locale: dopo un FIN del peer una scrittura di 3 byte di solito riesce.

### 4.6 Messaggio `U` (tokenizzazione, D4)

Il secondo handshake parte **sempre** (laravel:`src/Session/Ecr17Session.php:70-77`):
- se l'esito è già stato messo da parte durante il primo handshake, `U` viene inviato comunque;
- se l'handshake di `U` fallisce, l'esito messo da parte si perde;
- un frame applicativo ricevuto durante l'handshake di `U` **sovrascrive** `pendingResult` (laravel:`src/Session/Ecr17Session.php:137`).

Il caso "ACK ed esito nello stesso chunk" non è dannoso: l'esito resta in `rxBuffer`, soddisfa l'handshake di `U` e viene restituito (come §8.1 per il C++). Con la lettura pull vale anche se l'esito è già nel buffer del SO.

### 4.7 Byte stantii tra le transazioni (D5)

Il socket si legge solo durante uno scambio. Ciò che il terminale invia dopo (righe `S` tardive, `U` dal GT, un esito ritrasmesso) resta nel buffer del SO; lo scambio successivo lo legge **dopo** il reset e un frame applicativo vecchio soddisfa l'handshake e viene restituito come esito. Inoltre `isAlive()` vede quei byte e considera il socket vivo, nascondendo un eventuale FIN successivo. I byte scartati dallo splitter non sono registrati da nessuna parte. Il rischio esiste solo in un processo persistente (queue worker, Octane): con FPM e nella demo il socket muore a fine richiesta (L3).

### 4.8 Trasporto `SocketTransport` (laravel:`src/Transport/SocketTransport.php`)

| Operazione | Comportamento | Righe |
|---|---|---|
| `connect` | chiama prima `disconnect()` (non idempotente); host vuoto → `ECR17: host is empty`; `stream_socket_client("tcp://h:p", …, max(0.1, ms/1000), STREAM_CLIENT_CONNECT)`; poi `stream_set_blocking(false)`. Nessun `TCP_NODELAY` (L7), nessun `SO_KEEPALIVE`. Porta non validata | `:23-49` |
| `disconnect` | `fclose` se è una risorsa | `:51-57` |
| `isConnected` | `is_resource && !feof` | `:59-62` |
| `isAlive` | `feof` → morto; `stream_select(0,0)`: `false` → morto, `0` → vivo; se leggibile `stream_socket_recvfrom(1, STREAM_PEEK)`: `''`/`false` → morto, un byte → vivo. **Non distruttivo**, equivalente al `poll_peek` di Rust (D17) | `:64-87` |
| `send` | ciclo di `fwrite`; su `false/0` con `feof` → `transport disconnected during send`, altrimenti `usleep(1000)` e riprova **senza deadline** (L9) | `:89-110` |
| `read(timeoutMs)` | `stream_select`; `false` → `stream_select failed`; `0` → `''`; poi `fread(4096)`: `false` o `''`+`feof` → `transport disconnected during exchange` | `:112-138` |

### 4.9 Ciclo di vita della connessione

- **Libreria**: client persistente e "pigro". `ensureConnected()` gira prima di **ogni** comando: se `isConnected() && isAlive()` riusa il socket, altrimenti emette `connecting` → `connect()` → `connected` (o `disconnected` e rilancia) (laravel:`src/Ecr17Client.php:270-289`). Il client non chiude mai il socket dopo una transazione. Motivazione: "ECR17 terminals often close the TCP after each transaction" (laravel:`src/Ecr17Client.php:272-277`; laravel:`docs/LESSON.md:47-57`).
- **Uso reale**:
  - con PHP-FPM il singleton `'ecr17'` (laravel:`src/Ecr17ServiceProvider.php:17-24`) vive per **una richiesta HTTP**; il socket si chiude quando PHP libera la risorsa (non c'è `__destruct`);
  - la demo crea client e `SocketTransport` **nuovi a ogni richiesta** e non chiama mai `disconnect()` (demo:`app/Http/Controllers/Ecr17DemoController.php:68-81`): una connessione per comando, chiusa implicitamente;
  - la documentazione di produzione raccomanda però queue worker o Octane (laravel:`docs-site/docs/best-practices/production.md:8-14`; laravel:`README.md:192-198`), dove il socket è persistente e torna il problema D5.
- **Eventi**: stringhe `'connecting'|'connected'|'disconnected'`, emesse solo da `ensureConnected()` e `disconnect()` (quest'ultimo anche se già disconnesso). Una caduta durante lo scambio non emette eventi.

### 4.10 Replay dopo una caduta (laravel:`src/Ecr17Client.php:291-341`; laravel:`src/Session/RetryPolicy.php:21-24`)

```
try doExchange()
catch original:
  dropped = !transport.isConnected()            // feof, NON isAlive (L5)
  if autoReconnect && dropped: try ensureConnected() catch -> throw original
  if autoReconnect && dropped && safeToRetry: return doExchange()      // UNA volta
  throw original
```

`safeToRetry=true` solo per `status`, `totals`, `sendLastResult`, `enableEcrPrinting` = {`s`,`T`,`G`,`E`}; tutti gli altri, compresi `closeSession`, `reprint`, `vas` e `verifyCard`, non vengono ripetuti. Un timeout su un socket vivo non viene mai ripetuto. Identico a §5.3.

Come nei repo, i parametri vengono validati **dopo** `ensureConnected()`: un `InvalidArgumentException` del builder arriva a socket già aperto (laravel:`src/Ecr17Client.php:91-103`).

### 4.11 Default e timeout

| Parametro | Default laravel | Fonte | Confronto con §5.2 |
|---|---|---|---|
| `ack_timeout_ms` | 2000 (per tentativo) | laravel:`config/ecr17.php:24`; laravel:`src/Session/SessionConfig.php:13` | = |
| `response_timeout_ms` | 60000 (deadline unica) | laravel:`config/ecr17.php:23` | = |
| `retry_count` / `retry_delay_ms` | 3 / 200 | laravel:`config/ecr17.php:27-28` | = |
| `receipt_drain_ms` | 0 | laravel:`config/ecr17.php:32` | = |
| `connection_timeout_ms` | 5000 (minimo effettivo 100 ms) | laravel:`config/ecr17.php:22`; laravel:`src/Transport/SocketTransport.php:37` | = rn; copertura del DNS **ambigua** (probabilmente esclusa) |
| `port` | **1024** | laravel:`config/ecr17.php:8`; laravel:`src/Ecr17Config.php:15` | ≠ 10000 (rust Tauri/rn); Ingenico 5577; il README usa 10000 (laravel:`README.md:113`) |
| `auto_reconnect` | **true nella libreria** | laravel:`config/ecr17.php:19` | ≠ rust/rn (`false` nella libreria, `true` nelle UI) |
| `keep_alive`, `debug` | true / false, **mai letti** | laravel:`src/Ecr17Config.php:19,27` | = (non usati in nessun port) |
| `terminal_id`, `cash_register_id` | `''` → padding a `00000000` | laravel:`src/Ecr17Config.php:16-17`; laravel:`config/ecr17.php:11-12` | rust/rn: obbligatori |
| timeout di scrittura | nessuno | laravel:`src/Transport/SocketTransport.php:97-109` | = |

### 4.12 Errori (laravel)

| Tipo | Messaggio | Origine |
|---|---|---|
| `InvalidArgumentException` | `ECR17: value '{v}' exceeds fixed field width of {n} bytes` | laravel:`src/Protocol/Ecr17Protocol.php:242-244,254-256` |
| `InvalidArgumentException` | `ECR17: amount must be non-negative`; `ECR17: VAS request exceeds 1024 bytes`; `ECR17: additional TAG content must be 1..100 chars`; `ECR17: tokenization contract code must be 1..18 chars` | laravel:`src/Protocol/Ecr17Protocol.php:265,140,159,184` |
| `RuntimeException` | `ECR17: no ACK after {N} attempts`; `ECR17: NAK after {N} attempts`; `ECR17: no application response before timeout` | laravel:`src/Session/Ecr17Session.php:106,125,156` |
| `TransportException` (⊂ `RuntimeException`) | `ECR17: transport disconnected during exchange`; `… during send`; `… is not connected`; `ECR17: stream_select failed`; `ECR17: host is empty`; `ECR17: connect to {h}:{p} failed: {errstr} ({errno})` | laravel:`src/Transport/SocketTransport.php:28,42-44,92,101,115,126,134` |

AckTimeout, Nak e ResponseTimeout non sono tipi distinti: si riconoscono solo dal testo. Un KO del terminale non è un'eccezione ma `Outcome::Ko`. Non esiste un flag "esito sconosciuto".

### 4.13 `FakeTransport` (laravel:`src/Transport/FakeTransport.php`)

Ibrido (D19): parte **disconnesso** e `send()` non fallisce mai (come C++); la risposta scriptata viene accodata dentro `send()` per ogni frame STX e consegnata dalla `read()` successiva (pull, come Rust); `connect()` azzera i flag di drop (come Rust); `read()` lancia se `dropped` o `peerClosed`, anche con byte pendenti. Aggiunge `simulatePeerClosed()` (socket half-open: `isConnected` vero, `isAlive` falso) e `applicationRequestCount()` (laravel:`src/Transport/FakeTransport.php:101-136`). Una `read()` senza dati dorme davvero per tutto il timeout.

---

## 5. Risposte: campi, esiti e lacune

### 5.1 Helper (semantica da replicare in Kotlin)

| Helper | Comportamento | Fonte |
|---|---|---|
| `at(p, pos1, len)` | 1-based; `''` se `pos1 == 0` o `pos1 > strlen`; altrimenti `substr(p, pos1-1, len)`, **troncato** al disponibile; lavora su byte | laravel:`src/Response/Ecr17Response.php:137-145` |
| `trimRight` | `rtrim(s, ' ')`, solo spazi | laravel:`src/Response/Ecr17Response.php:147-150` |
| `xmlValue(xml, key)` | prima occorrenza di `"KEY">`, fino al `<` successivo, poi `trim()` PHP (`" \t\n\r\0\x0B"`, byte) | laravel:`src/Response/Ecr17Response.php:152-166` |

I DTO tengono tutti i campi numerici, di data e di importo come **stringhe grezze**. Le uniche conversioni sono: esito → enum `Outcome`, cifra di stato → `int` (o `-1`), flag DCC `applied` e VAS `moreMessages` → bool. Nessun DTO conserva il **codice messaggio**, il **TID** (tranne lo status) o il **payload grezzo**.

### 5.2 Comando → parser

| Metodo | Cod. | Sessione | Parser | `safeToRetry` | Fonte (laravel:`src/Ecr17Client.php`) |
|---|---|---|---|---|---|
| `status()` | `s` | `exchange` | `parseStatus` | **sì** | `:79-89` |
| `pay(…)` / `payExtended(…)` | `P` / `X` | `exchange` o `+U` | `parsePayment` (X perde 72-92) | no | `:91-123` |
| `reverse(?stan, ?crid)` | `S` | `exchange`, STAN `?? '000000'` | `parsePayment` | no | `:125-136` |
| `preAuth(…)` | `p` | `exchange` o `+U` | `parsePreAuth` | no | `:138-153` |
| `incrementalAuth(…)` | `i` | `exchange` | `parsePreAuth` ✗ | no | `:155-169` |
| `preAuthClosure(…)` | `c` | `exchange` | `parsePayment` ✗ | no | `:171-185` |
| `verifyCard(…)` | `H` | `exchange` o `+U` | `parsePayment` | no | `:187-199` |
| `closeSession(?crid)` | `C` | `exchange` | `parseClose` | no | `:201-211` |
| `totals(?crid)` | `T` | `exchange` | `parseTotals` | **sì** | `:213-223` |
| `sendLastResult(?crid)` | `G` | `exchange` | `parsePayment` ✗ (sempre) | **sì** | `:225-235` |
| `enableEcrPrinting(bool)` | `E` | `sendAckOnly` | — | **sì** | `:237-244` |
| `reprint(bool toEcr)` | `R` | `sendAckOnly`, tipo ticket fisso `'0'` | — | no | `:246-253` |
| `vas(xml)` | `K` | `exchange`, cash register della config | `parseVas` (solo il 1° messaggio) | no | `:255-266` |

### 5.3 Codici di esito (laravel:`src/Response/Outcome.php:7-24`)

`00` → `Ok` (`ok`), `01` → `Ko` (`ko`), `05` → `CardNotPresent` (`cardNotPresent`), `09` → `UnknownTag` (`unknownTag`), altro (anche `''`) → `Unknown` (`unknown`). Identico a §4.6, compresi i valori JSON.

### 5.4 Tabelle dei campi

**`parsePayment`** (laravel:`src/Response/Ecr17Response.php:15-50`), usato per E/V e anche per S, H, c, G:

| pos | len | Campo | Ramo | Decodifica |
|---|---|---|---|---|
| 10 | 1 | codice | — | solo `=== 'V'` abilita il DCC; non salvato |
| 11 | 2 | `resultCode` → `outcome` | tutti | grezzo |
| 13 | 24 | `errorDescription` | solo `01` | rtrim |
| 13 | 19 | `pan` | ≠ `01` (00/05/09/altro) | grezzo |
| 32 / 35 / 41 | 3 / 6 / 7 | `transactionType` / `authCode` / `hostDateTime` | ≠ `01` | rtrim / rtrim / grezzo |
| 48 / 49 / 60 / 66 | 1 / 11 / 6 / 6 | `cardType` / `acquirerId` / `stan` / `onlineId` | tutti | grezzo / rtrim / grezzo / grezzo |
| 72 / 75 | 3 / 8 | action code / importo (V, c, S-84, X-92) | — | **non letti; il DTO non ha i campi** |
| 83 / 84 / 92 / 95 / 107 | 1 / 8 / 3 / 12 / 1 | DCC `applied` / `rate` (4 decimali impliciti, grezzo) / `currencyCode` / `amount` / `precision` | solo `V` | bool / grezzo / rtrim / grezzo / grezzo |

**`parsePreAuth`** (laravel:`src/Response/Ecr17Response.php:91-120`): OK = pan 13/19, txType 32/3, auth 35/6, `preAuthorizedAmount` 41/8, `preAuthCode` 49/9, `actionCode` 58/3, `hostDateTime` 61/7; KO = descrizione 13/24, `actionCode` 37/3, `cardType` 48/1; comuni = acquirer 72/11, STAN 83/6, online 89/6. Sul ramo OK la pos 48 (ultima cifra dell'importo) non è letta come tipo carta, come rn.

**`parseStatus`** (`:52-62`): TID 1/8; `dateTimeRaw` 21/10 `DDMMYYhhmm` **senza conversione**; `status` 31/1 → int se cifra, altrimenti `-1`; release da 32 a fine, rtrim.

**`parseTotals`** (`:64-72`): esito 11/2; `posTotal` 13/16 con **qualsiasi** esito. **`parseClose`** (`:74-89`): layout positivo **solo** per `00` (pos 13/16, host 29/16), altrimenti descrizione 13/19 e action code 32/3. Le asimmetrie di ramo sono quelle di §4.5.7.

**`parseVas`** (`:122-133`): `moreMessages` 15/1, `idMessage` 16/3, `rawXml` da 27 a fine **ignorando la lunghezza 23/4**; `responseId`, `responseMessage`, `orderId` da `RESPID`/`RESPMSG`/`ORDER_ID`.

**Riga `S`**: nessun parser; la sessione la riconosce da `payload[9]=='S'` e passa al callback il payload con l'intestazione. Niente traduzione di `0x7D`, niente sentinella, niente concatenazione.

### 5.5 Confronto con le lacune segnalate nel doc principale

| Lacuna (doc) | laravel | Conseguenza (derivato dove indicato) |
|---|---|---|
| `i` 75 B, layout proprio (§4.5.4) | `parsePreAuth` (laravel:`src/Ecr17Client.php:168`) | **(derivato, §13.6)** `authCode="ACQ"`, `preAuthorizedAmount="     AUT"`, `preAuthCode="H01000042"`, `hostDateTime="0992111"`, `acquirerId="0000"`, `stan=""`, `onlineId=""`. Corretti solo esito, PAN e tipo transazione |
| `c` 74 B / `v` 109 B (§4.5.5) | `parsePayment`; DCC solo con `'V'` maiuscola | **(derivato)** `v` → `Ok`, campi 1-71 corretti, blocco DCC **perso senza segnalazione**, action code scartato |
| Risposta a `G` (§4.2) | sempre `parsePayment` (`:234`) | **(derivato)** G dopo `p` (`e` 106 B) → `hostDateTime="0005000"`, `cardType="0"`, `acquirerId="00000012300"`, `stan="021115"`, `onlineId="200000"`: **campi di riconciliazione errati** proprio nel percorso di recupero |
| `U` dal GT (§4.5.10) | mai letto; con drain 0 resta nel socket, con drain > 0 è ACKato e scartato | **(derivato, ⚠️ layout)** se le pos 11-16 riservate valgono `'0'`, un `U` stantio letto come esito del comando successivo dà `resultCode="00"` → **`Outcome::Ok`**, `pan="0000005HELLO0000000"`: un pagamento nuovo risulterebbe approvato |
| VAS multi-messaggio (§4.5.8) | `moreMessages` esposto nel DTO, ma nessuna lettura dei messaggi successivi | i `K` successivi restano nel socket (drain 0) o vengono scartati (drain > 0) |
| Fine scontrino `0x7D`×6+`0x1B` (§4.5.9) | non gestita | — |
| `E` non persistente (§4.3.6) | comando autonomo ACK-only; la documentazione non lo dice | va inviato prima di ogni procedura (nexi) |
| Action code S/H/X/c/V (§4.5.2) | **assente dal DTO** | **(derivato)** su S-84 `authCode` legge il riservato 35-40 (`"000000"`) |
| Importo host di X-92 (75/8) | non letto | si perde l'unico importo confrontabile di una `E` |
| Codice di risposta atteso | non verificato: qualsiasi frame valido non `S` è l'esito (laravel:`src/Session/Ecr17Session.php:170-184`) | `status()` farebbe `parseStatus` di una `E` stantia |

### 5.6 Cosa salva laravel rispetto agli altri port

- **D6 non si presenta**: parsing su byte, offset esatti anche con byte ≥ `0x80`.
- **D10/D11 non si presentano**: nessuna conversione numerica o di data; il tasso DCC resta una stringa di 8 cifre (niente bug di scala di rust).

Modello da seguire in Kotlin: **valore grezzo sempre conservato + vista tipizzata calcolata** (BigDecimal, enum), con `null` se il grezzo è malformato.

---

## 6. Money-safety, idempotenza, recupero con G e journal

### 6.1 Cosa stabilisce laravel

- **Invariante**: "a financial command is never blindly re-sent after the connection drops". Elenco esplicito dei comandi non ripetibili: `pay`, `payExtended`, `reverse`, `preAuth`, `incrementalAuth`, `preAuthClosure`, `verifyCard`, `closeSession`, `reprint`, `vas` (laravel:`docs-site/docs/concetti-teoria/money-safety.md:8-12`).
- **Sicuri dopo riconnessione**: `status`, `totals`, `sendLastResult`, `enableEcrPrinting` (laravel:`docs-site/docs/concetti-teoria/money-safety.md:14-21`); regola `auto && dropped && safe` come ADR 002 (laravel:`docs-site/docs/architettura/adr.md:22-34`).
- **Riconnessione proattiva** prima di ogni comando con probe non distruttivo (§4.9), così un comando finanziario non parte su un socket half-open e non produce il falso errore "one yes, one no" (laravel:`docs/LESSON.md:47-57`).
- **Niente retry generici** attorno ai comandi finanziari: "Generic HTTP or queue retry policies can create duplicate charges" (laravel:`docs-site/docs/guides/recovery.md:34-36`); "Disable automatic re-execution of the payment body" (laravel:`docs-site/docs/concetti-teoria/money-safety.md:37-39`).
- Il parser "cannot validate that a payload belongs to the order you intended": vanno persistiti e confrontati i campi di correlazione (laravel:`docs-site/docs/architettura/modello-dati-contratto.md:40-42`). Importi sempre interi in centesimi (`:18-26`).

### 6.2 Procedura di recupero e runbook

Recupero (laravel:`docs-site/docs/guides/recovery.md:25-32`): 1) catturare l'eccezione; 2) marcare l'ordine "richiede riconciliazione ECR17"; 3) riconnettere se serve; 4) `sendLastResult()`; 5) confrontare importo, STAN, codice autorizzazione e dati del terminale con l'ordine; 6) salvare **un solo** esito finale.

Runbook (laravel:`docs-site/docs/operations/runbook.md:8-19`): 1) identificare TID, IP del POS, ordine e comando; 2) **stabilire se il guasto è avvenuto prima dell'ACK, dopo l'ACK o durante l'attesa dell'esito**; 3) in caso di incertezza finanziaria eseguire `sendLastResult()` **una volta**, da un'azione controllata dell'operatore o da un job di recupero; 4) confrontare importo, STAN, auth ed esito; 5) salvare lo stato risolto e impedire tentativi di pagamento duplicati; 6) escalation con timestamp e conteggi di NAK e timeout. Callout: "Expose recovery as a deliberate action with audit logging. Avoid buttons that simply re-run the original payment."

### 6.3 Journal minimo secondo laravel

Per ogni tentativo: id ordine, importo in centesimi, tipo comando, terminal id, STAN/codice autorizzazione, errore grezzo (laravel:`docs-site/docs/best-practices/production.md:16-27`). Il contract code della tokenizzazione è metadato durevole del mandato (laravel:`docs-site/docs/guides/tokenization.md:28-30`).

### 6.4 Critica (verificata sul codice)

1. **"Confrontare l'importo" non è possibile** sull'esito di `P`: la `E` da 71 B non contiene l'importo, e `PaymentResponse` non ha il campo. L'importo c'è solo in `V` (75/8), X-92 (75/8) ed `e` (41/8), e laravel legge solo l'ultimo. La riconciliazione di `P` deve basarsi su STAN, online id, codice autorizzazione, `hostDateTime` (`DDDHHMM`) e tempi.
2. **L'esempio `catch → return sendLastResult()`** (laravel:`docs-site/docs/concetti-teoria/money-safety.md:27-35`; laravel:`docs-site/docs/guides/payments.md:35-43`) restituisce come esito del nuovo ordine anche quello della transazione **precedente** quando il nuovo comando non ha mai raggiunto il terminale (connect fallito, NAK esauriti, nessuna elaborazione). Il discriminante è proprio il passo 2 del runbook, ma il codice non lo espone: le eccezioni non dicono la fase, salvo dedurla dal testo.
3. **Lo STAN di un KO non è univoco** (§4.5.1; nella cattura il KO [10] e l'OK [22] hanno lo stesso STAN `000004` e online `000055`).
4. **G letto sempre come pagamento**: dopo `p`/`i`/`c` i campi di riconciliazione sono sbagliati (§5.5).
5. **Esiti stantii** (`U` dal GT, esito ritrasmesso) possono diventare l'esito del comando successivo in un processo persistente (§4.7).
6. **Retransmit senza implicit go-ahead**: se l'ACK si perde, il `P` ritrasmesso è una fonte potenziale di doppio addebito (nessuna fonte dice se il terminale deduplica).
7. **Timeout esterni** (L6): un watchdog più corto della sessione (fino a ~77 s con `U`) produce un esito sconosciuto; con i retry delle code può rieseguire `pay()`.

### 6.5 Linee guida per l'app Kotlin

1. **Journal per fasi** (`journal.jsonl`, scritto **prima** dell'invio e aggiornato a ogni transizione):

   | Fase | Quando | Esito noto? |
   |---|---|---|
   | `NON_INVIATO` | validazione ok, prima del connect | sì: nessun effetto sul terminale |
   | `CONNESSIONE_FALLITA` | connect fallito | sì: nessun effetto |
   | `INVIATO_SENZA_ACK` | frame scritto, ACK non ancora ricevuto (con `IMPLICIT_GO_AHEAD` si resta qui fino all'esito) | **no** |
   | `RIFIUTATO_NAK` | NAK oltre il limite | sì: frame rifiutato (salvo NAK spurio) |
   | `ACK_RICEVUTO` | ACK del terminale | **no** finché non arriva l'esito |
   | `ESITO_RICEVUTO` | esito decodificato (anche se l'ACK finale fallisce) | sì |
   | `SCONOSCIUTO` | timeout esito, caduta, annullamento dopo l'invio | **no** → proporre `G` |
   | `RICONCILIATO` | `G` eseguito e confrontato | sì |

   Campi: id tentativo, istante, comando, importo in centesimi, TID/ECR, profilo terminale, `ackTimeoutPolicy` e `connectionMode` effettivi, numero di invii, fase, esito, STAN, online id, auth, `hostDateTime`, payload grezzo dell'esito (PAN mascherato), errore grezzo.
2. **`outcomeUnknown` deriva dalla fase**, non dal tipo di eccezione: un comando finanziario in `INVIATO_SENZA_ACK`, `ACK_RICEVUTO` o `SCONOSCIUTO` è a esito sconosciuto; in `NON_INVIATO`, `CONNESSIONE_FALLITA` o `RIFIUTATO_NAK` no (niente `G`, niente banner).
3. **`G` è un'azione deliberata** ("Ultimo esito (G)"), mai automatica e mai un pulsante "riprova pagamento"; si propone una volta per tentativo aperto e viene registrata nel journal (laravel runbook, passo 3).
4. **Confronto del risultato di `G`** con l'ultimo esito già noto del journal: stesso STAN **e** stesso online id **e** stesso `hostDateTime` = "non è la nuova transazione". Su un KO lo STAN da solo non basta.
5. **Smistamento della risposta a `G` per codice** (pos 10) con parser dedicati e sealed class (`Payment | PreAuth | Incremental | Closure`), come §12.4.
6. **Un tentativo aperto blocca o avvisa?** In un'app di test basta un avviso evidente con conferma esplicita per avviare un nuovo comando finanziario (da confermare con l'utente, §11).
7. **Nessun timeout esterno più corto della sessione**: niente `withTimeout` o timer UI attorno a un comando finanziario; il caso peggiore si calcola come `(R+1)·A + R·D` (raddoppiato con `U`) + `responseTimeout` + connect. Pulsanti disabilitati durante un comando e debounce contro il doppio clic (L6).
8. **Con `IMPLICIT_GO_AHEAD`** la mancanza di ACK porta all'attesa dell'esito; se l'esito non arriva entro `responseTimeout`, la fase è `SCONOSCIUTO` e si propone `G`. Con `RETRANSMIT` il journal registra quanti invii sono partiti: un esito dopo più invii è da verificare con `G` prima di un nuovo addebito.
9. **Ricerca di esiti persi a inizio transazione** (modalità persistente): i byte residui vengono registrati come orfani e, se contengono un frame di esito, si emette l'avviso "possibile esito perso: usa G" (§12.4 punto 1).

---

## 7. Troubleshooting e runbook: insidie con i terminali reali

### 7.1 Tabella di laravel (laravel:`docs-site/docs/operations/troubleshooting.md:10-14`)

| Sintomo | Causa probabile | Azione |
|---|---|---|
| Nessuna connessione | IP/porta errati, VLAN, firewall | test TCP dall'host |
| NAK ripetuti | **`lrc_mode`** errata o identificativi malformati | controllare `ECR17_LRC_MODE`, TID, ECR id ("Different terminal fleets may expect different LRC scopes", laravel:`docs-site/docs/concetti-teoria/teoria.md:34-36`) |
| Timeout del pagamento | interazione del titolare oltre il timeout | alzare `response_timeout_ms`, eseguire in un worker |
| Eccezione dopo l'addebito | caduta TCP prima della risposta | `sendLastResult()` e riconciliazione |
| Righe di scontrino mancanti | `receipt_drain_ms` a 0 o troppo basso | abilitare una piccola finestra di drain |

Segnali da monitorare (laravel:`docs-site/docs/operations/observability.md:16-28`): tentativi di connessione, timeout ACK, timeout esito, chiamate di recupero ("payment uncertainty rate"), conteggio NAK ("protocol configuration drift"); payload redatti; niente PAN completi né dati di traccia nei log e debug disattivato in produzione (laravel:`docs-site/docs/best-practices/security.md:12-25`).

### 7.2 Insidie consolidate (laravel + cattura Ingenico) e diagnosi nell'app Kotlin

| # | Sintomo | Causa | Come la riconosce l'app Kotlin | Azione |
|---|---|---|---|---|
| T1 | `s` funziona ma `P` riceve NAK ×4 | LRC applicativo errato (`std` invece di `stx`) | log wire: LRC TX vs atteso nelle 4 modalità; sonda LRC (§13.4) | profilo Ingenico/CCV → `stx` |
| T2 | ACK del terminale "mai ricevuto", `P` ritrasmesso | splitter rigido su LRC di controllo (il caso rust con `06 03 7A`) | log `ACK con LRC 7A (atteso 78)`, accettato in modalità tollerante | splitter con ETX obbligatorio e LRC di entrambe le convenzioni |
| T3 | Esito ricevuto due volte o esito "fantasma" al comando dopo | il terminale non accetta il nostro ACK finale e ritrasmette | drain post-esito che vede un secondo frame identico → warning "esito ritrasmesso: ACK non accettato?" | provare `WIRE` vs `SAME_AS_APP` (§13.5 S-ACK) |
| T4 | Comando finanziario fallito con "disconnected" subito dopo uno riuscito | socket chiuso dal terminale tra le transazioni (Nexi) | modalità persistente: stato derivato dal reader | `PER_COMMAND` (default) o riconnessione prima dell'invio |
| T5 | `Connect Fail` dopo ~2 s | porta sbagliata (8000 sull'Ingenico) | errore `ConnectFailed` con host:porta | porta del profilo (5577); avviso su 20002/20007/20011 (ZVT/OPI, §10.7) |
| T6 | Esito oltre 60 s → timeout su un pagamento poi approvato | finestra del titolare/host lunga (osservati 48,6 s) | fase `SCONOSCIUTO` | `responseTimeout` 120 s, `G` |
| T7 | Nessun progress | opzione terminale "Messaggio di stato" disattivata | — | i progress non estendono mai la deadline e non sono obbligatori |
| T8 | `s` senza risposta | parametri ECR del terminale non configurati (§4.5.6) | timeout esito su `s` | controllare il menu ECR del terminale |
| T9 | Refuso in configurazione → comportamento di default | fallback silenzioso (L1) | parsing rigoroso con errore visibile | — |
| T10 | Pagamento eseguito come "auto" invece di "debito" | fallback silenzioso del tipo pagamento (L4) | enum nella UI | — |
| T11 | Esito approvato segnalato come errore | ACK finale o drain che lanciano su socket chiuso (D2/D3) | ACK e drain best effort con log | esito comunque restituito |
| T12 | Doppio addebito sospetto | ritrasmissione di un finanziario senza ACK, retry esterni, doppio clic | journal (numero di invii), mutex per comando | `IMPLICIT_GO_AHEAD` (default proposto), `G` |
| T13 | ACK applicativo lento (fino a ~200 ms) | Nagle + delayed ACK (L7) | log temporizzato del wire | `tcpNoDelay = true`, una sola `write` per frame |

### 7.3 Tempi di riferimento sull'Ingenico [CAPTURE]

ACK del terminale 0-174 ms (P) e 45 ms (R); un solo progress `"OPERAZIONE IN CORSO "` 0,36-1,26 s dopo l'ACK; esito 8,9-48,6 s dopo l'invio; ACK della DLL 17-28 ms dopo l'esito; read successiva in `ERR_TIMEOUT` dopo 6-8 ms; chiusura del socket ~190-262 ms dopo (wpf:`bin/Debug/2026_03_18-iaedll-log.txt:1-38`; wpf:`bin/Debug/2026_03_18-iaedll-debug.txt:56-125`). Utili per tarare gli allarmi "lento" nel log.

---

## 8. Demo debug console e idee per la nostra UI

### 8.1 Architettura della demo

- Pagina Blade con React 18 UMD, Babel standalone e Tailwind da CDN, senza build (demo:`resources/views/ecr17.blade.php:8-12`).
- Endpoint JSON: `GET /`, `POST /ecr17/connect`, `POST /ecr17/command/{key}`, `GET /ecr17/logs`, `POST /ecr17/clear-logs` (demo:`routes/web.php:6-13`). **Nessuna rotta di disconnessione.**
- Ogni richiesta costruisce un client nuovo dalla config inviata dalla UI (demo:`app/Http/Controllers/Ecr17DemoController.php:68-81`): connessione per comando, esecuzione sincrona. Il pulsante di connessione è solo un test di raggiungibilità.
- Log: ring buffer di **500** voci in `Cache::forever('ecr17.demo.log')`, voce `{id, ts, level, label, detail}` (demo:`app/Support/DemoLog.php:15-34`); polling ogni **1200 ms** (demo:`resources/views/ecr17.blade.php:77-86`). Con il server di sviluppo a worker singolo il polling resta probabilmente in coda dietro al comando in corso, quindi progress e scontrini comparirebbero solo a comando concluso (inferenza: la demo non è stata eseguita).

### 8.2 Layout (screenshot laravel:`resources/screenshoots/ECR17-Debug-Console.png`)

Colonna singola, tema scuro (palette di rn):
1. **Header**: titolo "ECR17 Debug Console" e pulsante blu **"Ping (status)"**.
2. **Configurazione** richiudibile, griglia a 2 colonne: Host (placeholder `192.168.1.50`), Port `1024`, Terminal ID, Cash register ID, LRC mode (select a 4 valori), Auto reconnect (checkbox), Response timeout `60000`, ACK timeout `2000`. `connection_timeout_ms`, `retry_count`, `retry_delay_ms`, `receipt_drain_ms` vengono inviati ma **non sono modificabili**. Persistenza in `localStorage['ecr17.config']` (demo:`resources/views/ecr17.blade.php:47-56,118-135`).
3. **Commands**: chip con badge della lettera; **bordo rosso** per `P X S p i c C` (demo:`resources/views/ecr17.blade.php:24-39`). I comandi senza parametri partono **subito, senza conferma**, compresa la chiusura contabile `C`.
4. **Log** dal più recente: ora, livello colorato, label, dettaglio JSON. Livelli: `info` slate, `sent` blu, `recv` (**definito ma mai emesso**), `progress` e `ko` ambra, `receipt` viola, `ok` verde, `error` rosso (demo:`resources/views/ecr17.blade.php:41-45`).
5. **Dialog parametri** a bottom sheet: importo in euro (`Math.round(parseFloat(x.replace(',','.'))*100)||0`), tipo carta, carta già presente, testo scontrino, "STAN (blank = last)", codice preautorizzazione, enabled, to ECR, XML; pulsante rosso "Run (financial)" per i danger.
6. **Feedback**: overlay "Working…" senza Annulla e senza ultimo progress; toast 2500 ms "OK" / "KO (outcome)" / "Error — see log".

### 8.3 Difetti e limiti osservati

- Nessun log del wire (hex) né livello `recv` effettivo; `sent` viene registrato **prima** della connessione (nello screenshot `sent status` precede `connection: connecting`), quindi significa "richiesto", non "trasmesso".
- PAN mascherato solo sulla chiave `pan` del log (demo:`app/Support/DemoLog.php:52-75`); righe `receipt`/`progress` e il **JSON del risultato restituito al browser** restano in chiaro (demo:`app/Http/Controllers/Ecr17DemoController.php:60,76-77`).
- Esito OK/KO incoerente: il controller considera un VAS KO se `responseId !== '0'`, il toast guarda solo `outcome` → un VAS KO appare "OK" nel toast e "ko" nel log (demo:`app/Http/Controllers/Ecr17DemoController.php:117-130`).
- Importo non numerico → `NaN` → `0` centesimi inviati con il pulsante ancora abilitato.
- Byte non UTF-8 (es. una lettera accentata Latin-1 in un esito) fanno fallire la risposta JSON di Laravel: un comando completato diventa un errore della demo.
- `H` (verifica carta) non è marcato danger, pur non essendo ripetibile.
- Nessun Annulla, nessun export del log, nessun journal, nessuna gestione dell'esito sconosciuto.

### 8.4 Idee per la UI di Protocollo17ComposeDesktop

Le richieste dell'utente sono: opzioni attivabili per *implicit go-ahead* e *connessione per comando* (punti 2 e 3), **più finestre** (opzioni, log, …, punto 3) e possibilmente il **layout di LocalDesktopAi** (punto 5). Proposta che le combina:

- **Struttura alla LocalDesktopAi**: `NavigationRail` a sinistra con voci raggruppate (lda: `docs/ARCHITETTURA.md:44-58`; `AppNavigationRail.kt`), una schermata per voce, Voyager + MVI come in lda. Voci proposte:
  1. **Console**: barra di connessione (profilo, host:porta, stato, TID reale dall'ultimo `s`), comandi raggruppati come le tab WPF (Pagamento: `P X S`; Preautorizzazione: `p i c`; Terminale: `s H C T G E R K`), pannello **Ultimo esito** (chiave/valore, valori grezzi, PAN mascherato, action code, codice ricevuto vs atteso).
  2. **Opzioni**: profilo Ingenico/CCV e campi di connessione; **switch** "Implicit go-ahead: non ritrasmettere i comandi finanziari se manca l'ACK" e "Connessione per comando (chiudi il TCP dopo ogni operazione)"; sezione *Avanzate* (LRC applicativo, regola LRC di controllo `WIRE`/come app, tolleranza LRC in ricezione, timeout, retry, drain, stampa su ECR, log wire). Ogni scostamento dal profilo è evidenziato ("modificato rispetto al profilo").
  3. **Log** (eventi: livelli `info/tx/rx/progress/receipt/ok/ko/error/warn/orphan`, filtro, ricerca, export).
  4. **Wire** (hex TX/RX nel formato della DLL `dd/MM/yyyy-HH:mm:ss-SSS: Data Tx:02 30 …`, LRC verificato nelle 4 modalità, PAN mascherato), per confrontare direttamente con `<data>-iaedll-log.txt`.
  5. **Journal** (tentativi finanziari per fase, azione "Ultimo esito (G)" su un tentativo aperto).
  6. **Diagnostica**: sonda LRC con `s`, test della variante ACK, test di persistenza (§10.6 fase 2).
- **Finestre staccabili**: Log, Wire e Ultimo esito apribili anche come `Window` separate di Compose Desktop (pulsante "Apri in finestra"), con geometria persistita come in lda; la finestra principale resta utilizzabile.
- Da riprendere dalla demo laravel: pulsante rapido **Ping (status)** nella barra; chip con lettera e bordo rosso per i danger; pulsante "Esegui (finanziario)" rosso; log dal più recente con ora/livello/label/dettaglio; toast di 2,5 s.
- Da migliorare rispetto alla demo: overlay con ultimo progress e **Annulla** (esito → `SCONOSCIUTO`); conferma per `C`; tutti i campi modificabili; importo con `BigDecimal` (2 decimali, > 0); mascheramento del PAN ovunque (UI, log, wire, scontrini); esecuzione su `Dispatchers.IO` con eventi via `SharedFlow`, così progress e scontrini sono davvero in tempo reale; distinzione "richiesto" vs "TX".

---

## 9. Posizione di laravel su W1..W12 / S1..S14 e nuove differenze L*

### 9.1 Differenze del doc principale

Colonna "Verdetto" = esito della seconda lettura indipendente del codice laravel (confermata / parziale con correzione).

| ID | D | Tema | laravel segue | Comportamento laravel | Verdetto | Ref |
|---|---|---|---|---|---|---|
| W1 | D1 | ACK/NAK nello splitter | **C++** | qualsiasi terna `06`/`15`, nessun controllo ETX/LRC, nessuna risincronizzazione; accetta `06 03 7A` in ogni modalità; un `06` nudo + STX consuma `[06,02,p0]` anche tra `read` diverse | confermato | laravel:`src/Session/Ecr17Session.php:253-266`; laravel:`src/Protocol/PacketCodec.php:55-61` |
| S1 | D1 | LRC/ETX dei frame di controllo | **C++** | come W1; in uscita `06 03 78` in `stx` | confermato | laravel:`src/Protocol/PacketCodec.php:40-45` |
| W2 | D2 | errore nell'invio dell'ACK/NAK | **C++** | `sendControl` non protetto: l'esito decodificato si perde; anche NAK e ACK del drain | confermato (+ `send` senza deadline se `fwrite` dà 0 senza `feof`) | laravel:`src/Session/Ecr17Session.php:172,175,189,208,213,297-300` |
| S4 | D2 | idem (best effort vs eccezione) | **C++** | idem; in pratica richiede un RST | confermato | laravel:`src/Transport/SocketTransport.php:97-106` |
| W3 | D3 | caduta durante il drain | **C++** | `TransportException` non catturata dopo l'ACK dell'esito | confermato | laravel:`src/Session/Ecr17Session.php:175,182,195-225,242` |
| S3 | D3 | idem | **C++** | idem | confermato | — |
| W4 | D4 | invio di `U` | **C++** | sempre; `pendingResult` sovrascrivibile; esito perso se l'handshake di `U` fallisce | confermato | laravel:`src/Session/Ecr17Session.php:70-77,106,125,137` |
| S2 | D4 | idem | **C++** | idem; con lettura pull anche un esito già nel buffer del SO soddisfa l'handshake di `U` | confermato (+ nota) | laravel:`src/Session/Ecr17Session.php:116,134-139` |
| W5 | D5 | byte stantii | **Rust** | pull; reset solo in memoria; `isAlive` dice "vivo" con byte pendenti; nessun controllo del codice atteso. Mitigato da FPM/demo (L3) | confermato | laravel:`src/Session/Ecr17Session.php:88-92,228-247`; laravel:`src/Transport/SocketTransport.php:64-87` |
| S5 | D5 | idem | **Rust** | idem | confermato | — |
| W6 | D6 | codifica in ingresso | **C++** | byte grezzi, offset esatti, callback senza conversioni | confermato (la demo, non la libreria, fallisce su JSON non UTF-8) | laravel:`src/Response/Ecr17Response.php:138-150` |
| S12 | D6 | idem | **C++** | idem | confermato | — |
| W7 | D7 | importo | **Rust** | `int`, `<0` errore, `>99 999 999` overflow, 0 ammesso; un chiamante non `strict_types` che passa `6.99` ottiene 6 con deprecation | confermato | laravel:`src/Protocol/Ecr17Protocol.php:239-248,262-269` |
| W8 | D8 | validazione `U` e contratto | **C++** | solo lunghezze; testi d'errore identici al C++ | confermato | laravel:`src/Protocol/Ecr17Protocol.php:155-173,180-190` |
| W9 | D9 | tipo pagamento nei builder | variante propria | stringa senza controlli di valore **e lunghezza**; client con fallback su `'0'` (anche per `'1'..'3'`); il problema di lunghezza riguarda solo le chiamate dirette ai builder | confermato (+ nota) | laravel:`src/Protocol/Ecr17Protocol.php:195-208`; laravel:`src/Ecr17Client.php:355-363` |
| W10 | D10 | conversioni numeriche | variante propria | nessuna conversione numerica salvo la cifra di stato | **parziale**: oltre alla cifra di stato ci sono conversioni non numeriche (esito → enum `Outcome`, `applied`/`moreMessages` → bool); tutto il resto è grezzo | laravel:`src/Response/Ecr17Response.php:22,41,57-58,125` |
| W11 | D11 | data `DDMMYYhhmm` | variante propria | solo `dateTimeRaw` grezzo | confermato | laravel:`src/Response/Ecr17Response.php:56` |
| W12 | D12 | trim XML VAS | variante propria | `trim()` PHP: insieme C++ più NUL e VT, su byte | confermato | laravel:`src/Response/Ecr17Response.php:153-166` |
| S6 | D13 | rilevamento delle cadute | **Rust** | nessun flag; EOF → eccezione; `isConnected` = `!feof` resta falso | **parziale**: una `send()` diretta dopo un FIN **non** fallisce subito (la prima scrittura di solito riesce e i byte si perdono); lancia solo dopo un RST. Il client comunque riconnette prima, perché `isConnected()` è falso | laravel:`src/Transport/SocketTransport.php:59-62,89-110,132-135` |
| S7 | D14 | annullamento | **Rust** (nessuna API) | nessun `cancel`; PHP sincrono; il processo può essere ucciso dall'host (L6) | confermato | laravel:`src/Ecr17Client.php:66-70` |
| S8 | D15 | modello degli errori | **C++** | stringhe + `TransportException`; nessun `outcomeUnknown` | confermato | §4.12 |
| S9 | D16 | concorrenza | variante propria | nessun lock tra richieste o worker: due sessioni TCP simultanee verso lo stesso terminale sono possibili | confermato (in C++ il lock è a rn:`package/cpp/Ecr17Client/HybridEcr17Client.cpp:355,395`) | laravel:`src/Ecr17ServiceProvider.php:17-24` |
| S10 | D17 | probe di liveness | **Rust** | `feof` + `stream_select` + `STREAM_PEEK`, non distruttivo | confermato | laravel:`src/Transport/SocketTransport.php:64-87` |
| S11 | D18 | host, porta, timeout | variante propria | config Laravel; porta 1024; timeout minimo 100 ms; porta non validata; DNS ambiguo | confermato | laravel:`src/Ecr17Config.php:14-21,34-41` |
| S13 | D10+D11 | conversioni numeriche e date | variante propria | stringhe grezze | **parziale**: unica mappatura a enum = esito → `Outcome`; bool per DCC `applied` e VAS `moreMessages` | laravel:`src/Response/Ecr17Response.php:15-62` |
| S14 | D19 | `FakeTransport` | variante propria | ibrido | **parziale**: la consegna "accodata in `send()` e restituita dalla `read()` successiva" è il modello **Rust** (pull), non C++ (che consegna in modo sincrono tramite callback dentro `send()`); `read()` lancia su drop anche con byte pendenti | laravel:`src/Transport/FakeTransport.php:17-122` |

**Sintesi**: laravel segue il **C++** su D1, D2, D3, D4, D6, D8, D15; il **Rust** su D5, D7, D13, D14, D17; ha varianti proprie su D9-D12, D16, D18, D19. Per tutte le raccomandazioni Kotlin di §8 la posizione di laravel non cambia nulla: dove segue il C++ conferma il problema, dove segue Rust conferma la soluzione o il buco (D5).

### 9.2 Nuove differenze L* (laravel vs rust/C++)

| ID | Tema | laravel | rust / C++ | Impatto | Raccomandazione Kotlin | Verdetto |
|---|---|---|---|---|---|---|
| L1 | Parsing di `lrc_mode` | `tryFrom(strtolower(v)) ?? Std`: `'STX'` accettato, `'stx '`, `'sxt'`, `'stx-noext'` → `std` in silenzio (laravel:`src/Protocol/LrcMode.php:21-24`) | serde esatto (errore); TS union | sull'Ingenico un refuso dà NAK ×4 senza indizi | parsing rigoroso dei 4 nomi (trim e case-insensitive ammessi), errore visibile; log della modalità effettiva e dei byte ACK/NAK calcolati alla connessione | confermato |
| L2 | Default della libreria | porta 1024, `autoReconnect=true`, TID/ECR `''` → `00000000` (laravel:`src/Ecr17Config.php:14-20`) | porta assente/10000, `autoReconnect=false`, ID obbligatori | 1024 non corrisponde a nessun terminale noto; replay di `s/T/G/E` senza scelta esplicita | porta dal profilo (Ingenico 5577, CCV obbligatoria); `autoReconnect` come opzione visibile; mostrare il valore effettivo `00000000` | confermato |
| L3 | Ciclo di vita effettivo | per richiesta HTTP con chiusura implicita (FPM/demo); persistente nei worker (demo:`app/Http/Controllers/Ecr17DemoController.php:68-81`; laravel:`src/Ecr17ServiceProvider.php:17-24`) | persistente e pigro; DLL: per operazione con chiusura esplicita | per richiesta elimina D5 e l'half-open, ma senza drain e con chiusura incontrollata | `PER_COMMAND` esplicito: connect → scambio → drain breve → **close esplicito con log**; mai affidarsi a GC/finalizer | confermato (sotto FPM una richiesta può contenere più comandi, es. `pay` + `G` nel `catch`) |
| L4 | Tipo pagamento/ticket come stringa libera | nessun controllo di valore e lunghezza; fallback su `'0'` nel client | `char` validato (rust) / `char` (C++) | frame malformato con chiamata diretta; refuso → pagamento "auto" | enum `PaymentType`/`TicketType`; builder che verificano la larghezza esatta di ogni campo | confermato |
| L5 | Verifica "dropped" dopo un errore | `!isConnected()` (`feof`), non `isAlive()` (laravel:`src/Ecr17Client.php:278,308,328`) | stessa funzione prima e dopo | basso: `feof` su socket PHP fa già un peek di liveness quando il buffer PHP è vuoto | un'unica funzione di stato derivata dal reader | **parziale** (effetto pratico piccolo; dettaglio interno di PHP) |
| L6 | Terminazione imposta dall'host | sessione fino a ~77 s (+5 s connect) dentro richiesta/job; `request_terminate_timeout`, proxy e `queue:work --timeout` possono ucciderla; con `retry_after` 90 s della demo e `--timeout` > 90 e `tries` > 1 il job può rieseguire `pay()` | nessun watchdog esterno | esito sconosciuto o doppio addebito per cause esterne al protocollo | nessun `withTimeout`/timer UI più corto della sessione; su annullamento → `SCONOSCIUTO` + `G`; debounce | **parziale**: `max_execution_time` su Linux conta solo CPU e non scatta durante `stream_select`; il replay richiede anche `--timeout` > `retry_after` e `tries` > 1; il job può ricevere `failed()` |
| L7 | `TCP_NODELAY` | non impostato (laravel:`src/Transport/SocketTransport.php:33-39`) | rust e rn Android sì; rn iOS no | ritardo fino a ~200 ms sugli invii piccoli (ACK, `U`) | `socket.tcpNoDelay = true`, una `write`+`flush` per frame | confermato |
| L8 | Override del cash register su C/T/G | `?cashRegisterId` su `closeSession`, `totals`, `sendLastResult` | rust: dalla config | basso | facoltativo nel dialog parametri | rilevato dai lettori, non sottoposto alla seconda verifica |
| L9 | `send()` senza deadline | se `fwrite` restituisce `false/0` senza `feof`, ciclo con `usleep(1000)` senza limite (laravel:`src/Transport/SocketTransport.php:97-106`) | scrittura bloccante senza timeout | basso | timeout di scrittura o socket con `soTimeout` + chiusura su stallo | rilevato in verifica di W2 come addendum |

---

## 10. Profili terminale Ingenico vs CCV

### 10.1 Evidenza disponibile

- **Ingenico**: filo osservato direttamente su un terminale reale (TID `02575733`, `192.168.1.131:5577`) con IAE37.dll 5.45 (wpf:`bin/Debug/2026_03_18-iaedll-log.txt`, `…-iaedll-debug.txt`).
- **CCV: nessuna fonte specifica.**
  - I tre repo nominano solo Nexi.
  - La DLL non contiene la stringa `CCV`, non ha selettori di vendor, modello, LRC o variante, non legge file `.ini` né il registro; le uniche stringhe di vendor sono "Ingenico Italia S.p.A.", "Ingenico Amount Exchange", "Ingenico IAEx7" [DLL].
  - Il WPF ha solo IP e porta (`TETHParametersAX`: `IPAddress char[16]`, `Port char[6]`, wpf:`IAE37Interop.cs:8-16`); `IAE37AX_SetTimeout` è dichiarata ma non esportata e mai chiamata (wpf:`IAE37Interop.cs:295-296`).
  - L'unico legame CCV ↔ ECR17 è testuale: `Title="Protocollo 17 - Test IAE37 DLL (Ingenico/CCV)"` (wpf:`MainWindow.xaml:4`) e la descrizione dell'assembly (wpf:`Properties/AssemblyInfo.cs:6`).
  - Sul web non c'è documentazione CCV per ECR17; le pagine pubbliche CCV citano solo **ZVT** e **O.P.I.** (porte 20011/20007 ZVT, 20002 OPI) [WEB <https://help.unzer.com/en/support/solutions/articles/79000147666-ccv-setting-up-your-pos-integration>].
- **Conseguenza logica**: la DLL fissa da sola LRC, ACK, modello di connessione e policy di ritrasmissione e non distingue il vendor. **Se** il WPF funziona davvero con il CCV, il CCV accetta esattamente il filo della DLL. Restano specifici del terminale solo la porta, le opzioni ECR lato terminale, i tempi e l'eventuale tolleranza in ricezione.

### 10.2 Regola LRC unificata

Tutti i 13 frame applicativi catturati (7 Tx P/R, 6 Rx E) tornano **solo** in `stx`; tutti i 13 ACK (7 dal terminale, 6 dalla DLL) sono `06 03 7A` [CAPTURE]. Entrambi soddisfano *LRC = `0x7F` XOR ogni byte che precede l'LRC sul filo*. Nel modello Kotlin la si esprime come `appLrc = STX` + `controlLrcTx = WIRE` (`0x7F ^ ctrl ^ 0x03`).

### 10.3 Tabella dei profili proposti

| Parametro | Ingenico | CCV |
|---|---|---|
| Ruolo TCP | POS server, ECR client [CAPTURE wpf:`bin/Debug/2026_03_18-iaedll-debug.txt:56-58`] | idem [ASSUMPTION]; coerente con [WEB Nexi integration-options] |
| Porta di default | **5577** [CAPTURE debug.txt:48,58]; 8000 → `Connect Fail` [CAPTURE debug.txt:16-19,28-31] | **nessuna: campo obbligatorio** [ASSUMPTION]. Visti altrove: 2000 (PAX) [WEB bman], 10000 [REPO rust/rn], 1024 [REPO laravel]; "porta > 1024" sugli EFTPOS Linux [WEB Nexi]. 20002/20007/20011 indicano OPI/ZVT, non P17 [WEB unzer] |
| LRC frame applicativi (TX) | **`stx`**, 7/7 [CAPTURE log:1,7,13,19,25,31,37] | `stx` [ASSUMPTION: stesso filo della DLL] |
| LRC frame applicativi (RX) | 6/6 validi solo in `stx` [CAPTURE log:4,10,16,22,28,34]. Proposta: accettare con warning se l'LRC torna in un'altra modalità; NAK solo se non torna in nessuna | sconosciuto; stessa politica tollerante [ASSUMPTION] |
| LRC frame di controllo (TX) | **`WIRE`**: ACK `06 03 7A`, inviato 6 volte dalla DLL [CAPTURE log:6,12,18,24,30,36]; accettazione indiretta (pagamenti successivi regolari). NAK `15 03 69` derivato, mai osservato | `WIRE` [ASSUMPTION]; alternativa `SAME_AS_APP` (`06 03 78`, come i repo) selezionabile |
| LRC frame di controllo (RX) | il terminale invia `06 03 7A` 7/7 [CAPTURE log:2,8,14,20,26,32,38]. Proposta: ETX obbligatorio in [1], `7A` atteso, `78/79/7B` accettati con warning | tollerante come sopra [ASSUMPTION] |
| Modello di connessione (default; **switch UI**) | **per comando**, come la DLL: 7 connessioni consecutive [CAPTURE debug.txt:56→125, 142→214, 246→315, 332→399, 436→504, 516→583, 619→654]. Persistente mai provato | per comando [ASSUMPTION]; laravel/demo e la nota Nexi "close the TCP after each transaction" sono un precedente [REPO], ma riguardano Nexi |
| Chi chiude il TCP | la DLL, ~190-262 ms dopo il proprio ACK; la read dopo l'ACK termina in `ERR_TIMEOUT` (non EOF), indizio debole che il terminale non chiude subito [CAPTURE debug.txt:98→125 ecc.] | ? (Wireshark, §10.6) |
| Policy su ACK mancante, comandi finanziari (default; **switch UI**) | **implicit go-ahead**: `AE_17_TxMessage_ETH_ImplicitACK`, stringa `ACK NOT RECEIVED - IMPLICIT GO AHEAD` [DLL @0x37408, @0x37438]; usata per P [CAPTURE debug.txt:86,88]; osservato solo il ramo "ACK RECEIVED"; X/p/i/c/S/H non catturati | stesso valore [ASSUMPTION: la DLL non distingue il vendor]; i repo (e laravel) ritrasmettono [REPO laravel:`src/Session/Ecr17Session.php:102-133`] |
| Ritrasmissione su NAK (finanziari) | mai osservata; proposta: fino a 3 ritrasmissioni (NAK = frame rifiutato) [ASSUMPTION; WEB Nexi "up to three"]; la DLL ha `ERR_NAK` [DLL @0x373f0] | idem [ASSUMPTION] |
| Comandi non finanziari (`s T G C E R K`) | `AE_17_TxMessage_ETH` (senza ImplicitACK) per `R` [CAPTURE debug.txt:646-648]; proposta retry 3 × 200 ms [REPO] | idem |
| `connectTimeout` | `Connect OK` in 12-19 ms in LAN [CAPTURE]; proposta 5000 ms [REPO laravel:`config/ecr17.php:22`] | 5000 ms [ASSUMPTION] |
| `ackTimeout` | ACK in 0-174 ms (P), 45 ms (R) [CAPTURE]; timeout DLL ignoto; proposta 2000 ms [REPO] | 2000 ms [ASSUMPTION] |
| `responseTimeout` | esiti in 8,9-48,6 s [CAPTURE]; proposta **120 000 ms** (60 s dei repo troppo vicino al massimo osservato) | 120 000 ms [ASSUMPTION] |
| Progress (SOH) | **uno solo**, `"OPERAZIONE IN CORSO "` (20 caratteri), 0,36-1,26 s dopo l'ACK, mai ACKato [CAPTURE log:3,9,15,21,27,33] | sconosciuto; può essere disattivato dall'opzione terminale "Messaggio di stato" [WEB bman]. Mai usarlo per estendere timeout o dedurre stati |
| Drain dopo l'esito | la DLL legge ancora 6-8 ms dopo l'ACK [CAPTURE]; proposta 100 ms senza scontrino su ECR, 1500 ms con scontrino su ECR [REPO rn:`docs-site/docs/configuration.md:13-24`] | idem [ASSUMPTION] |
| Svuotamento byte residui prima dell'invio | non serve in per-comando [CAPTURE]; in persistente: drain del reader con log "orfani" (raccomandazione D5 del doc principale) | idem |
| Scontrino su ECR | mai osservato (`E` non inviato, `Ticket2Ecr` non impostato) [CAPTURE, per assenza]; la DLL lo supporta (`Ricezione Ticket su cassa abilitata`) [DLL @0x36758]; `E1` a ogni procedura, fine con 6×`7D`+`1B` [WEB Nexi] | sconosciuto; stesse regole della spec [ASSUMPTION] |
| TID / ECR id | `00000000` / `00000000` accettati; il terminale risponde con il proprio TID `02575733` [CAPTURE log:1,4] | `00000000` [ASSUMPTION]; possibile opzione terminale "Check ID terminale" [WEB bman] |
| PAN | già mascherato dal terminale (`000558278******4768`) [CAPTURE log:22] | sconosciuto: mascherare **sempre** nell'app e nel wire |
| Esito `E` | 71 B, sia OK sia KO [CAPTURE] | ? |
| Ristampa `R`, pos 11 | la DLL invia `0x00` (`Ticket2Ecr` non impostato), ACK in 45 ms [CAPTURE log:37-38]; Kotlin invierà `'0'`/`'1'` come da spec [ASSUMPTION] | idem |
| Charset | ASCII (`Encoding.ASCII`, wpf:`IAE37Wrapper.cs:37-54`) [REPO wpf] | ASCII |
| `tcpNoDelay` | consigliato (L7) [REPO rust/rn Android] | idem |
| Seriale | supportata dalla DLL (`AE_17_*_RS232`) [DLL]; fuori perimetro | fuori perimetro |

Legenda URL: [WEB Nexi] = <https://developer.nexigroup.com/traditionalpos/en-EU/docs/communication-protocol/> e <https://developer.nexigroup.com/traditionalpos/en-EU/docs/integration-options-this-section-provides-an-overview-of-lan-integration-and-other-basic-integration-options/>; [WEB bman] = <https://wiki.bman.it/soluzioni-pratiche/configurazione-pos-pax-d210-e-ethernet/>.

### 10.4 Opzioni UI e valori di profilo

| Livello | Voci |
|---|---|
| Scelta principale | profilo (`Ingenico` / `CCV`), host, porta (precompilata solo per Ingenico) |
| **Switch decisi dall'utente** | `financialAckPolicy`: *Implicit go-ahead* (default) / *Ritrasmetti*; `connectionMode`: *Per comando* (default) / *Persistente* |
| Avanzate | LRC applicativo; regola LRC di controllo `WIRE` / `SAME_AS_APP`; tolleranza LRC in ricezione (rigida / tollerante); ack/response/connect timeout; retry e delay; drain post-esito e drain scontrini; stampa su ECR (`E1` automatico); `autoReconnect` (solo modalità persistente); log wire |

Ogni valore diverso da quello del profilo va segnato nella UI e scritto nel journal/log all'inizio di ogni comando.

### 10.5 Bozza del modello Kotlin

```kotlin
enum class TerminalVendor { INGENICO, CCV }
enum class ControlLrcRule { WIRE /* 0x7F^ctrl^ETX → 06 03 7A */, SAME_AS_APP /* repo: in STX → 06 03 78 */ }
enum class RxLrcPolicy { STRICT_NAK, ACCEPT_ANY_MODE_WARN, IGNORE_WARN }
enum class ConnectionMode { PER_COMMAND, PERSISTENT }
enum class FinancialAckPolicy { IMPLICIT_GO_AHEAD, RETRANSMIT }

data class TerminalProfile(
    val vendor: TerminalVendor,
    val defaultPort: Int?,                                   // INGENICO 5577, CCV null (obbligatoria)
    val appLrc: LrcMode = LrcMode.STX,
    val controlLrcTx: ControlLrcRule = ControlLrcRule.WIRE,
    val controlLrcRx: RxLrcPolicy = RxLrcPolicy.IGNORE_WARN,  // ETX obbligatorio in [1]
    val appLrcRx: RxLrcPolicy = RxLrcPolicy.ACCEPT_ANY_MODE_WARN,
    val connectionMode: ConnectionMode = ConnectionMode.PER_COMMAND,
    val financialAckPolicy: FinancialAckPolicy = FinancialAckPolicy.IMPLICIT_GO_AHEAD,
    val connectTimeoutMs: Int = 5_000, val ackTimeoutMs: Int = 2_000,
    val retryCount: Int = 3, val retryDelayMs: Int = 200,
    val responseTimeoutMs: Int = 120_000,
    val postResultDrainMs: Int = 100, val receiptDrainMs: Int = 1_500,
    val terminalId: String = "00000000", val ecrId: String = "00000000",
)
object Profiles {
    val INGENICO = TerminalProfile(TerminalVendor.INGENICO, defaultPort = 5577)
    val CCV = TerminalProfile(TerminalVendor.CCV, defaultPort = null)    // valori di filo = Ingenico [ASSUMPTION]
}
```

`FinancialAckPolicy` si applica a `P X p i c S H`; `C` e `K` (non ripetibili per laravel) seguono la stessa policy dei finanziari per prudenza. Un valore `"ccv"` (o qualunque profilo) letto dal file impostazioni che non corrisponde a un enum va **rifiutato**, non sostituito (L1).

### 10.6 Come colmare le lacune CCV

L'esecuzione spetta all'utente; qui non è stato eseguito nulla.

1. **Sul terminale CCV** (menu tecnico/installatore → configurazione ECR/cassa): verificare che il protocollo sia "17" su Ethernet; annotare porta, IP, modello, firmware, applicazione bancaria; annotare le opzioni ECR (messaggio di stato, controllo TID, conferma importo, stampa su ECR); fotografare la configurazione. Se esistono solo ZVT/OPI, **il terminale non parla P17** e il profilo CCV non ha senso così com'è.
2. **Raggiungibilità**: `Test-NetConnection <ip> -Port <porta>`.
3. **Wireshark** (o `pktmon`) con filtro `tcp.port == <porta>` per vedere chi invia FIN/RST, tempi e ritrasmissioni.
4. **App WPF** (`bin/Debug`) con IP e porta del CCV: Status, Stato POS, Ultimo risultato, Terminal Balance; Payment da 0,01 € **annullato** e poi approvato; Ristampa (tipo 0); Verifica carta; Chiusura solo se accettabile.
5. **Raccolta**: `bin/Debug/<yyyy_MM_dd>-iaedll-log.txt` e `-iaedll-debug.txt` (la DLL li scrive senza `SetDebugPath`) più il pcap.
6. **Analisi** con lo stesso script LRC: modalità LRC dei frame Tx/Rx, byte LRC dell'ACK del CCV (`7A`? `78`?), tempi di ACK/progress/esito, numero di progress, lunghezza dell'esito, chiusura del TCP lato terminale.
7. Ripetere sull'**Ingenico** per i comandi mai catturati (`s G T C X H p i c S`).
8. **Fase 2, con l'app Kotlin**, solo comandi sicuri:
   - *sonda LRC*: `s` con TID `00000000` nelle 4 modalità (LRC `3C/3D/3F/3E`, §13.4); ACK = modalità giusta;
   - *variante ACK*: rispondere all'esito di `s` o `T` con `06 03 78` invece di `7A` e restare in ascolto 5 s; un esito ritrasmesso indica che il terminale valida l'LRC dell'ACK;
   - *persistenza*: due `s` sulla stessa socket a 5 s e a 60 s di distanza.

   Mai usare `P` per le sonde.

### 10.7 Rischio principale sul CCV

I CCV documentati pubblicamente (Mobile Premium, Base Next) espongono ZVT e OPI di default. Prima di scrivere codice specifico conviene confermare con il punto 1 di §10.6 che il CCV dell'utente abbia davvero un'applicazione con Protocollo 17 attivo. Se la porta configurata è 20002/20007/20011, l'app deve avvisare: "porta tipica ZVT/OPI, non Protocollo 17".

---

## 11. Domande aperte del doc principale: stato dopo laravel

Stato: **risolta** (da decisione utente o da questo documento), **ristretta** (resta aperta ma con meno alternative), **invariata** (laravel non aggiunge nulla).

| # §13 | Tema | Stato | Note |
|---|---|---|---|
| Q1 | Modello di terminale / profili | ristretta | decisione utente: **Ingenico + CCV**, gestione a profili; per CCV mancano tutti i dati (§10) |
| Q2 | LRC dei frame di controllo; forma del NAK | invariata | laravel invia `78` in `stx` come i repo e non ha prove su hardware; si conferma `WIRE` come default e la sonda "variante ACK" (§10.6). Indizio debole: rn in `stx` (`06 03 78`) ha funzionato con un Nexi (§9.9), ma con chiusura TCP tra transazioni un esito ritrasmesso non sarebbe stato visibile |
| Q3 | Ritrasmissione dei finanziari senza ACK | risolta come opzione | decisione utente: **switch UI**; laravel (come rust/rn) ritrasmette sempre; default proposto implicit go-ahead (DLL). Il comportamento lato terminale resta ignoto |
| Q4 | Connessione per comando o persistente | risolta come opzione | decisione utente: **switch UI**; laravel/demo = terzo precedente per comando; timeout di inattività e chi invia il FIN restano da misurare |
| Q5 | Timeout esito 120 s | ristretta | laravel 60 s ma "must cover the real cardholder interaction window"; nulla contro 120 s |
| Q6 | Comandi solo DLL (Refund, VoidR, …) | rimandata | laravel non li ha; il punto 4 della risposta utente ("potrebbero servire, in un secondo momento") è interpretato come riferito a questi comandi |
| Q7 | Campo `Contract` = testo scontrino? | invariata | laravel conferma solo il layout (pos 32/128, a destra) |
| Q8 | Storno `S` a 26 B sull'Ingenico | invariata | laravel identico ai repo, nessuna prova |
| Q9 | Status / StatoPos, tabella 0..6 vs 0..9 | invariata | laravel segue Nexi 0..6 |
| Q10 | Righe `S` prima/dopo l'esito, sentinella | invariata | laravel testa entrambi gli ordini solo con dati sintetici; non gestisce la sentinella |
| Q11 | Charset | invariata | laravel non valida l'ASCII; proposta invariata (solo ASCII + traslitterazione) |
| Q12 | `U` dal GT e tokenizzazione | ristretta | nuovo rischio derivato: un `U` stantio può essere letto come pagamento **OK** (§5.5). Proposta: tokenizzazione **fuori perimetro** nella prima versione, oppure gestione esplicita del `U` dal GT (ACK + log) e controllo del codice atteso |
| Q13 | Specifica completa | invariata | — |
| Q14 | Seriale RS-232 | invariata | — |
| Q15 | Annulla; `G` automatico o proposto | ristretta | laravel: `G` come azione deliberata e registrata, una volta; proposta: **proposto, non automatico** |
| Q16 | Conferma per comandi danger senza parametri | ristretta | anche la demo laravel esegue `C` senza conferma; proposta: conferma per `C` |
| Q17 | Layout | risolta | utente: layout LocalDesktopAi e più finestre (opzioni, log, …); proposta §8.4 |
| Q18 | Lingua e tema | ristretta | UI in italiano (lda); tema da decidere |
| Q19 | Configurazione avanzata | ristretta | switch decisi (§10.4); resto in "Avanzate" |
| Q20 | Journal | ristretta | linee guida §6.5; da decidere se un tentativo aperto blocca o solo avvisa |
| Q21 | Log del wire | ristretta | proposta: formato compatibile con la DLL, file giornaliero con rotazione |
| Q22 | Naming | ristretta | utente: il nome dell'app è quello della cartella, **Protocollo17ComposeDesktop**; package da decidere |
| Q23 | Struttura dei moduli | risolta | utente: **un solo modulo** finché il protocollo non è collaudato; poi estrazione della comunicazione in un secondo modulo |
| Q24 | Distribuzione | invariata | — |
| Q25 | Licenza dei repo di riferimento | ristretta | laravel ha la stessa ambiguità di rn (§1.4); l'utente rimanda la licenza alla fine; si reimplementa citando le fonti |
| Q26 | Analizzare laravel-ecr17 | risolta | questo documento |

---

## 12. Impatto sul piano di porting Kotlin

### 12.1 Decisioni utente e loro effetto

| Decisione | Effetto sul piano di §12 |
|---|---|
| Terminali Ingenico + CCV (come il WPF) | §12.6: i profili "Ingenico (osservato)" / "Nexi (repo)" diventano **Ingenico** / **CCV** (§10.3). Nessun profilo Nexi; i valori stile repo restano raggiungibili dalle opzioni avanzate (`std`, `SAME_AS_APP`, `RETRANSMIT`, `PERSISTENT`) |
| Implicit go-ahead come opzione | `AckTimeoutPolicy` di §12.4 diventa `FinancialAckPolicy`, campo del profilo e switch della UI; default `IMPLICIT_GO_AHEAD` |
| Connessione per comando come opzione | `ConnectionMode` resta, diventa switch della UI; default `PER_COMMAND` per entrambi i profili |
| Più finestre, layout LocalDesktopAi | §12.6: una schermata unica a due colonne diventa rail + sezioni + finestre staccabili (§8.4) |
| Un solo modulo Gradle | §12.1/§12.2: niente `:ecr17-protocol` separato per ora (§12.2 qui sotto) |
| Comandi solo DLL più avanti | fuori dalla prima versione |
| Licenza alla fine | reimplementazione con citazione delle fonti (§1.4) |

### 12.2 Struttura: modulo unico

```
Protocollo17ComposeDesktop/                 rootProject.name = "Protocollo17ComposeDesktop"
├── settings.gradle.kts / build.gradle.kts  un solo modulo: kotlin.jvm, kotlin.serialization, kotlin.compose, org.jetbrains.compose
├── gradle/libs.versions.toml               versioni allineate a LocalDesktopAi (§12.2 del doc principale)
├── docs/                                   ANALISI_REPOSITORY.md, ANALISI_LARAVEL_ECR17.md
└── src/
    ├── main/kotlin/<pkg>/
    │   ├── ecr17/        wire/ message/ transport/ session/ client/ model/ error/ log/ profile/
    │   │                 (NESSUN import di Compose, Koin, Voyager: sarà il futuro secondo modulo)
    │   ├── di/AppModule.kt
    │   ├── domain/       CommandDef, PosTerminalRepository, ResultClassifier, PanMasking
    │   ├── data/         PosTerminalRepositoryImpl, SettingsStore, TransactionJournal, LogStore, WireLogFile
    │   └── presentation/ screen/{console,options,log,wire,journal,diagnostics}/{viewModel,view}, theme/
    └── test/kotlin/<pkg>/
        ├── ecr17/        vettori di §9 + §13 di questo documento, FakeTransport, FakeTerminalServer
        └── architecture/ test che scansiona gli import di <pkg>.ecr17 (vietati androidx.compose, org.koin, cafe.adriel)
```

Il package `ecr17` resta "puro" in modo verificabile, così l'estrazione nel secondo modulo (decisione 6 dell'utente) sarà uno spostamento di cartella senza refactoring.

### 12.3 Modifiche al design di §12.4

| Elemento di §12.4 | Modifica |
|---|---|
| `ControlLrc { APPLICATION_MODE, WIRE_BYTES }` | rinominare in `ControlLrcRule { SAME_AS_APP, WIRE }` (stessa semantica), campo del profilo |
| Splitter | invariato e confermato da laravel come "cosa non fare": ETX obbligatorio, LRC di entrambe le convenzioni con warning, `06`/`15` isolato seguito da `02`/`01` = controllo nudo; **ogni byte scartato va nel log** |
| Handshake | `FinancialAckPolicy` per i comandi finanziari (+ `C`, `K`); su NAK si ritrasmette sempre; il numero di invii va nell'esito/journal |
| `U` | regola di §12.4 confermata (laravel/C++ la violano) |
| Attesa esito | **codice atteso per comando** obbligatorio (laravel ne dimostra il rischio: `U` stantio = OK); codici inattesi = orfani |
| ACK finale e drain | best effort (laravel e C++ perdono l'esito); in `PER_COMMAND` il drain post-esito (100 ms) serve anche a rilevare un esito **ritrasmesso** (ACK non accettato) |
| Transport | `tcpNoDelay = true` (L7); timeout anche in scrittura (L9); in `PER_COMMAND` chiusura esplicita con log (L3) |
| Client | validazione **prima** del connect; parsing rigoroso di enum e profilo (L1, L4); `autoReconnect` significativo solo in `PERSISTENT` |
| Errori | sealed class con prefissi `ECR17: …` compatibili con i testi laravel/C++; `outcomeUnknown` derivato dalla **fase** del journal (§6.5) |
| Journal | fasi esplicite (§6.5); `G` proposto, mai automatico |
| Log wire | formato DLL opzionale; PAN mascherato |

### 12.4 Piano incrementale (modulo unico)

| Fase | Contenuto | Criterio di uscita |
|---|---|---|
| 0 | Scaffold Gradle (modulo unico, versioni lda), finestra vuota con rail, `SettingsStore` | build e avvio |
| 1 | `wire/` + `message/` (LRC, `ControlLrcRule`, codec, splitter, 15 builder, parser con smistamento) | tutti i vettori di §9 e §13 verdi |
| 2 | `transport/` (`Socket` + reader daemon, `RxBuffer`) e `session/` con `FakeTransport` stile Rust | scenari di §9.6 + §13.5 verdi, compresi implicit go-ahead e drain |
| 3 | `FakeTerminalServer` che riproduce la cattura Ingenico (§9.8) su `ServerSocket` locale | flusso P → ACK `7A` → progress → E → ACK `7A` end-to-end in `PER_COMMAND` |
| 4 | `client/` + journal + profili; UI Console, Opzioni (switch), Log, Wire | comandi sicuri dalla UI contro il fake server |
| 5 | Prove su Ingenico reale: prima `s` (sonda LRC), poi `T`, `G`, poi `P` da 0,01 € | wire identico ai log DLL |
| 6 | Cattura CCV (§10.6) e taratura del profilo CCV | profilo CCV promosso da [ASSUMPTION] a [CAPTURE] |
| 7 | Journal UI, finestre staccabili, export; poi estrazione del modulo di comunicazione | — |

### 12.5 Cosa non copiare da laravel

Fallback silenziosi (`lrc_mode`, tipo pagamento); splitter che accetta qualsiasi terna `06`/`15`; ACK finale e drain non best effort; `U` sempre inviato; nessun controllo del codice atteso; lettura pull senza reader in modalità persistente; validazione dopo il connect; porta 1024; `keepAlive`/`debug` inutilizzati; `G` sempre letto come pagamento; esempio `catch → return sendLastResult()`.

### 12.6 Cosa riprendere

Probe concettuale "verifica lo stato prima di inviare" (in Kotlin: stato derivato dal reader); tabella `safe` = {`s`,`T`,`G`,`E`} e regola `auto && dropped && safe` (un solo replay); `simulatePeerClosed()` nel fake; runbook con la **fase** del guasto; journal per tentativo; divieto di retry esterni sui finanziari; valori grezzi nei DTO; pulsante "Ping (status)".

### 12.7 Rischi aggiornati (in aggiunta a §12.9)

| # | Rischio | Mitigazione |
|---|---|---|
| R11 | Il CCV dell'utente non parla P17 (solo ZVT/OPI) | verifica di §10.6 punto 1 prima di sviluppare il profilo CCV; avviso sulle porte 20002/20007/20011 |
| R12 | Il CCV usa un filo diverso dall'Ingenico (LRC, ACK) | opzioni avanzate già presenti; sonda LRC e variante ACK in Diagnostica |
| R13 | `IMPLICIT_GO_AHEAD` attende inutilmente 120 s se il frame si è perso davvero | fase `INVIATO_SENZA_ACK` visibile, Annulla con esito `SCONOSCIUTO`, `G` |
| R14 | Esito ritrasmesso dal terminale perché il nostro ACK non è accettato | drain post-esito che lo rileva e lo segnala; test "variante ACK" |

---

## 13. Test vector aggiuntivi

### 13.1 Copertura dei test laravel rispetto a §9

I test laravel sono un **sottoinsieme** di §9.1-§9.7 (stessi payload, stessi attesi, stessa config rapida `Std, ack 40, response 40, retry 2, delay 1, drain 0`, laravel:`tests/Unit/Session/Ecr17SessionTest.php:12-23`). Mancano rispetto a rust: tolleranza del lead nel decoder (`[06 00]`, `[06 03 FF]`, `[15 99 42]`), `decode(encodeControl(...))`, ACK spurio `06 FF`, ACK con LRC errato `06 03 00` (che laravel **accetterebbe**), `U` saltato, contenuto `U` con `0x1B`, contratti `'AB|CD'`/`'ab cd'`, `pre_auth_negative (94)`, builder `s` e `S` (nessun test). **Non ci sono vettori di wire nuovi**: i vettori da usare restano quelli di §9. Le sezioni seguenti aggiungono solo ciò che §9 non contiene.

### 13.2 Scenari client di laravel (verbatim, laravel:`tests/Unit/Ecr17ClientTest.php`)

Config: `host '127.0.0.1', terminalId '12345678', cashRegisterId '87654321', autoReconnect true, ackTimeoutMs 40, responseTimeoutMs 40, retryCount 2, retryDelayMs 1` (`:14-26`); `reply(p) = encodeControl(ACK) . encodeApplication(p)` in `Std` (`:29-34`).

| Test | Scenario | Atteso | Righe |
|---|---|---|---|
| status | `connect()`; risposta `"123456780s000000000001022515302V1.2.3"` | terminalId `'12345678'`, status 2, release `'V1.2.3'` | `:36-48` |
| pay | `pay(650, 'credit')`; risposta `"123456780E000000000004111111111ICCABC12321115202ACQ        000042000099"` | `Ok`, stan `'000042'`; 1 richiesta applicativa | `:50-63` |
| MONEY-SAFETY | `disconnectOnNextRequest()`, poi `verifyCard('auto')` | `RuntimeException`; **1** sola richiesta (nessun replay) | `:65-75` |
| replay sicuro | `disconnectOnNextRequest()`; risposta `"123456780T000000000000123456000000"` consegnata al secondo invio; `totals()` | `Ok`, posTotal `'0000000000123456'`, **2** richieste | `:77-90` |
| **socket half-open** | `connect()`, poi `simulatePeerClosed()`, poi `pay(650)` con la risposta di pagamento | `Ok`; gli stati contengono `'connecting'`; **1** richiesta (riconnessione **prima** dell'invio) | `:92-115` |
| **eventi di stato** | `connect()` | stati = `['connecting', 'connected']` | `:117-127` |

Adattamento Kotlin degli ultimi due: in `PERSISTENT`, *peer chiuso tra due comandi* (EOF letto dal reader) → `pay` deve produrre la sequenza `Connecting, Connected` e **un solo** frame STX trasmesso; in `PER_COMMAND` ogni comando produce `Connecting, Connected, …, Disconnected(intenzionale)`.

### 13.3 Frame di controllo per regola e attese dello splitter Kotlin

| Caso (lrc applicativo `STX`) | Byte | `WIRE` | `SAME_AS_APP` |
|---|---|---|---|
| ACK in uscita | — | `06 03 7A` | `06 03 78` |
| NAK in uscita | — | `15 03 69` | `15 03 6B` |

| Input allo splitter (`STX`, tollerante) | Atteso Kotlin | laravel / rust |
|---|---|---|
| `06 03 7A` | ACK, nessun warning con `WIRE`; warning "LRC controllo 7A, atteso 78" con `SAME_AS_APP` | laravel: ACK; rust (`stx`): scartato |
| `06 03 78` | ACK (warning con `WIRE`) | laravel: ACK; rust: ACK |
| `06 03 00` | ACK con warning "LRC non riconosciuto" in tollerante; scartato (1 byte) in rigido | laravel: ACK; rust: scartato |
| `06 FF 06 03 7A` + esito | `06`, `FF` scartati e loggati; poi ACK; poi esito | laravel: `06 FF 06` = "ACK", `03 7A` scartati (funziona per caso) |
| `06` + `02 …esito… 03 LRC` (ACK nudo) | ACK nudo con warning, poi esito **integro** | laravel/C++: `[06,02,p0]` = ACK, esito perso |
| `06 03` (2 byte) | attesa del terzo byte | laravel: attesa |

### 13.4 Sonde LRC con `s` (TID `00000000`)

Payload `"000000000s"` (10 byte). Frame: `02 30 30 30 30 30 30 30 30 30 73 03 LRC`.

| Modalità | LRC (derivato) |
|---|---|
| `std` | `3C` |
| `stx` | **`3D`** (atteso dall'Ingenico) |
| `noext` | `3F` |
| `stx_noext` | `3E` |

### 13.5 Scenari di sessione Kotlin con i byte reali Ingenico

Proposti per `FakeTransport`/`FakeTerminalServer`; byte da §9.8. Config rapida suggerita: profilo Ingenico con `ack 40`, `response 200`, `retry 3`, `delay 1`, `postResultDrain 20`. `ACKW` = `06 03 7A`; `P1` = frame [1] di §9.8 (167 B, LRC `stx` `2F`); `E4` = `02 "025757330E01OPERAZIONE ANNULLATA    " + 35×"0" 03 46` (71 B; LRC `stx` `46`, ricalcolato); `E22` = esito approvato [22] (LRC `stx` `21`, ricalcolato); `PRG` = `01 "OPERAZIONE IN CORSO " 04`.

| Nome | Risposte del terminale | Atteso |
|---|---|---|
| I-1 flusso reale | dopo `P1`: `ACKW`, `PRG`, `E4` | esito Ko `"OPERAZIONE ANNULLATA"`; 1 frame STX trasmesso; TX finale `06 03 7A`; evento progress `"OPERAZIONE IN CORSO "` |
| I-2 approvato | dopo `P1`: `ACKW`, `PRG`, `E22` | Ok, pan `"000558278******4768"`, auth `"R48432"`, stan `"000004"`, online `"000055"` (§9.8) |
| I-3 implicit go-ahead | dopo `P1`: **nessun ACK**, poi (oltre `ack`) `E4` | `IMPLICIT_GO_AHEAD`: **1** solo `P1` trasmesso, esito restituito, fase passata da `INVIATO_SENZA_ACK` a `ESITO_RICEVUTO` |
| I-4 retransmit | come I-3 ma con `RETRANSMIT` e `E4` mai inviato | 4 `P1` trasmessi, poi `AckTimeout(4)`; fase `SCONOSCIUTO` (comando finanziario) |
| I-5 NAK con implicit go-ahead | dopo `P1`: `15 03 69`; dopo il secondo `P1`: `ACKW`, `E4` | 2 `P1` trasmessi (il NAK ritrasmette sempre); esito restituito |
| I-6 nessun ACK e nessun esito | silenzio | `IMPLICIT_GO_AHEAD`: 1 invio, `ResponseTimeout` dopo `response`; `outcomeUnknown = true`; proposta `G` |
| I-7 esito ritrasmesso (ACK non accettato) | dopo `P1`: `ACKW`, `E4`; dopo il nostro ACK: di nuovo `E4` entro il drain | esito restituito una sola volta; warning "esito ritrasmesso dal terminale: ACK non accettato?"; secondo ACK inviato |
| I-8 codice inatteso | dopo `s`: `ACKW`, poi `E4` | `E4` registrato come orfano; `ResponseTimeout` (non deve essere restituito come status) |
| I-9 ristampa | `R` reale [37] (`… 52 00 30 … 03 2C`, LRC `stx` `2C`): `ACKW` | ACK-only riuscito (in Kotlin la pos 11 sarà `'0'`/`'1'`, LRC `stx` `1C`/`1D` da §9.8) |

### 13.6 Vettori derivati di smistamento (payload sintetici dai layout ⚠️ di §4.5)

Servono come **test negativi** (cosa fa laravel/rust/rn) e positivi (cosa deve fare il dispatcher Kotlin). I layout `i`, `v`, S-84 e `U` dal GT non sono verificati sul campo.

```
i75 (75 B) — integrazione, layout §4.5.4            LRC std 05, stx 04 (derivato)
"123456780i000000000004111111111CLIACQ        AUTH01000042000099211152000000"
  laravel (parsePreAuth): authCode "ACQ", preAuthorizedAmount "     AUT", preAuthCode "H01000042",
                          hostDateTime "0992111", acquirerId "0000", stan "", onlineId ""
  Kotlin atteso:          Ok, pan "0000000004111111111", txType "CLI", acquirer "ACQ", auth "AUTH01",
                          stan "000042", onlineId "000099", hostDateTime "2111520", actionCode "000"

e106 (106 B) — risposta a p completa; anche "G dopo p"   LRC std 3C, stx 3D (derivato)
"123456780e000000000004111111111CLIAUTH01000500000000001230002111520" + "000" + "0" + "ACQ        " + "000042" + "000099" + "000000000000"
  laravel parsePreAuth:  corretto (importo "00050000", codice "000000123", acquirer "ACQ", stan "000042", online "000099")
  laravel G (parsePayment): hostDateTime "0005000", cardType "0", acquirerId "00000012300", stan "021115", onlineId "200000"
  Kotlin atteso per G:   smistamento su 'e' → LastResult.PreAuth con gli stessi valori di parsePreAuth

v109 (109 B) — chiusura preautorizzazione con DCC, layout §4.5.5   LRC std 1D, stx 1C (derivato)
"123456780v000000000004111111111ICCABC12321115202ACQ        000042000099" + "000" + "1" + "00012345" + "USD" + "000000000650" + "2" + "0000000000"
  laravel (parsePayment): Ok, campi 1-71 corretti, NESSUN DCC, actionCode assente
  Kotlin atteso:          actionCode "000", dcc applied, rate "00012345" (1,2345), currency "USD", amount "000000000650", precision "2"

U34 (34 B) — U dal GT stantio, layout §4.5.10 con riservato a '0'   LRC std 65, stx 64 (derivato)
"123456780U000000005HELLO0000000000"
  laravel (parsePayment come esito del comando successivo): resultCode "00" → Ok, pan "0000005HELLO0000000"
  Kotlin atteso:          mai un esito: codice 'U' inatteso → orfano (o UGt(data="HELLO") se gestito), ACK + log

S84 (84 B) — risposta OK allo storno, layout §4.5.2   LRC std 1D, stx 1C (derivato)
"123456780E000000000004111111111ICC0000002111520" + "2" + "ACQ        " + "000042" + "000099" + "000" + "0000000000"
  laravel (parsePayment): authCode "000000" (riservato letto come auth), actionCode assente
  Kotlin atteso:          ReversalResult Ok, authCode assente/null, actionCode "000", stan "000042", onlineId "000099"
```

### 13.7 Vettori di configurazione (comportamento laravel da **non** replicare)

| Input | laravel | Kotlin atteso |
|---|---|---|
| `LrcMode::fromConfig('STX')` | `Stx` | `STX` (case-insensitive ammesso) |
| `LrcMode::fromConfig('stx ')`, `('sxt')`, `('stx-noext')` | `Std` in silenzio (laravel:`src/Protocol/LrcMode.php:21-24`) | errore di configurazione visibile (trim facoltativo per `'stx '`) |
| `mapPaymentType('debito')`, `('DEBIT')`, `('1')` | `'0'` (auto) in silenzio (laravel:`src/Ecr17Client.php:355-363`) | rifiuto: il tipo è un enum |
| builder `P` con tipo `'12'` | payload di 168 B | `FieldOverflow`/`InvalidPaymentType` |
| default config | `port 1024`, `lrc_mode 'std'`, `response_timeout_ms 60000` (laravel:`tests/Feature/ServiceProviderTest.php:5-9`) | profilo Ingenico: porta 5577, `stx`, 120 000 ms; CCV: porta obbligatoria |
