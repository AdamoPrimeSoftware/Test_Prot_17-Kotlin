# Protocollo17ComposeDesktop

App desktop di **test** (Kotlin, Compose for Desktop, JVM 21, Windows) per terminali POS che parlano il
**Protocollo 17 / ECR17 su TCP** (profili Ingenico e CCV). Reimplementazione da zero del protocollo, con
simulatore di terminale, log traffico hex e journal persistente dei tentativi finanziari. UI in italiano.

**La fonte unica di verità è `docs/DESIGN.md`**: firme Kotlin vincolanti, algoritmi, piano di test e work
breakdown (WP0-WP6). Le analisi da cui deriva sono `docs/ANALISI_REPOSITORY.md` (A§) e
`docs/ANALISI_LARAVEL_ECR17.md` (L§). Leggi la sezione di DESIGN del file che tocchi prima di modificarlo.

## Comandi

```
.\gradlew.bat compileKotlin                       # compile veloce
.\gradlew.bat test                                # unit test (JUnit5 + mockk + turbine + coroutines-test)
.\gradlew.bat run                                 # avvia l'app
.\gradlew.bat packageDistributionForCurrentOS     # installer MSI/EXE (prima: .\gradlew.bat suggestModules)
```

Test di rete o contro terminale reale: `--no-build-cache cleanTest test` (le variabili d'ambiente non sono input
del task). Terminale reale (solo `s`, `T`, `G`, mai comandi finanziari):
`.\gradlew.bat cleanTest test -PrealTerminal --no-build-cache --tests "*RealTerminalTest"` con `P17_HOST`/`P17_PORT`.

Dopo ogni modifica significativa: `compileKotlin` e `test` verdi, con l'esito riportato nel riepilogo.

## Struttura (package radice `it.prime.protocollo17`, un solo modulo Gradle, U12)

- `ecr17/` — protocollo **puro** (estraibile come modulo): `wire` (LRC, frame, splitter, hex, PAN), `model`,
  `error`, `event`, `profile`, `message` (encoder/parser), `transport` (TCP, RxBuffer), `session`, `client`, `sim`
- `domain/` — modelli (settings, journal, traffic, command, simulator), interfacce repository, util pure
- `data/` — settings, journal JSONL, log traffico, controller del simulatore, repository concreti, sistema
- `presentation/` — `state/` (servizi app-scoped senza Compose), `theme/`, `components/`, `util/`, `windows/`,
  `screen/<sezione>/{viewModel,view}` (MVI con ScreenModel Voyager)
- `di/AppModule.kt` — unico modulo Koin; `Main.kt` — sequenza di avvio e chiusura (§6.18)

Dati utente in `~/.protocollo17/`: `settings.json`, `journal/journal.jsonl`, `traffic/yyyy_MM_dd-p17-traffic.txt`,
`logs/app.log` (logback), `app.lock`.

## Regole di dipendenza (§2.10, verificate da `architecture/*Test`)

| Package | Può importare | Non può importare |
|---|---|---|
| `ecr17.**` | `kotlin.*`, `java.*` (JDK), `kotlinx.coroutines.*` (core) | Compose, Koin, Voyager, AWT/Swing, coroutines-swing, serialization, slf4j/logback, `domain`/`data`/`presentation`/`di`; nessun `Dispatchers.Main` |
| `domain.**` | stdlib, serialization, coroutines core, `ecr17.(model\|profile\|error\|event)`, `ecr17.wire.{LrcMode, ControlLrcRule, Hex}`, `ecr17.message.RequestEncoder` | Compose, Koin, Voyager, `data`, `presentation`, il resto di `ecr17` |
| `data.**` | `domain`, tutto `ecr17`, slf4j, serialization | `presentation`, Compose, Voyager |
| `presentation.**` | `domain`, `ecr17.(model\|profile\|error\|event)`, `ecr17.wire.{LrcMode, ControlLrcRule, Hex}`, Compose, Voyager, Koin, `data.local.settings.SettingsRepository`, `data.local.AppDirs` | il resto di `ecr17`, `data.repository.*`, `data.local.(journal\|traffic\|simulator\|system)` |
| `presentation.state.**` | come `presentation`, **senza** Compose | `androidx.*`, `org.jetbrains.compose.*` |
| `di`, `Main.kt` | tutto | — (solo `Main.kt` vede `ShutdownWatchdog`) |

- `ecr17` non logga: la diagnostica esce come `ProtocolEvent` e il data layer la scrive.
- **Dispatcher**: `Ecr17Client` e `Ecr17Session` girano sul dispatcher del chiamante e non lo cambiano mai (niente
  `Dispatchers.IO` in `ecr17/client` e `ecr17/session`); solo `TcpTransport` ha un proprio scope IO. Altrimenti i
  test in tempo virtuale userebbero il tempo reale.

## Invarianti di money-safety (§1.5, da rispettare in ogni modifica)

- **M1** Un comando finanziario non viene mai ripetuto dopo caduta, timeout o annullamento. Solo `s T G E` possono
  essere ripetuti una volta dopo una caduta.
- **M2** Con `IMPLICIT_GO_AHEAD` un frame finanziario si invia una volta se manca l'ACK; reinvii solo su NAK
  esplicito valido (ETX + LRC di una convenzione), fino a `nakRetryCount`. Progress, riga `S` o esito atteso valgono
  come ACK implicito: dopo, nessun reinvio.
- **M3** Journal: `CREATED` prima della connessione, `SENDING` prima di **ogni** scrittura del frame finanziario,
  con fsync. Journal non scrivibile prima del primo invio ⇒ il comando non parte (`NotSent(Internal)`).
- **M4** Un frame diventa esito solo se il codice (pos 10) è tra quelli attesi; il resto riceve ACK ed è orfano
  (un orfano della famiglia `E V e i c v` diventa prova `EVIDENCE` sull'ultimo tentativo non risolto).
- **M5** ACK finale, NAK e drain sono best effort: non trasformano mai un esito decodificato in errore.
- **M6** Esito sconosciuto o "da verificare" ⇒ comandi non ripetibili bloccati finché l'operatore non risolve.
  `G` è sempre un'azione dell'operatore, mai automatica.
- **M7** Un solo comando alla volta: un secondo comando è rifiutato, mai accodato.
- **M8** Nessun timeout esterno attorno a un comando (niente `withTimeout` in UI o repository).
- **M9** PAN mascherato ovunque esca dal parser (log traffico, journal, UI, eventi, scontrini, simulatore).
- **M10** "Scrittura del frame principale tentata" è appiccicoso: dopo, mai `NotSent`.

## Convenzioni di codice (§2.12)

- Identificatori in inglese; stringhe UI e commenti in italiano; KDoc su ogni classe pubblica che spiega il
  **perché** (stile "Prima… ora…" quando c'è una storia). Separatori `// ─── Titolo ───` nei file lunghi.
- Byte senza segno: sempre `b.toInt() and 0xFF`. Mai `String(bytes)` senza charset: ingresso `ISO_8859_1`,
  uscita `US_ASCII` dopo validazione.
- `CancellationException` sempre rilanciata per prima; niente `runCatching` attorno a codice `suspend`.
- Attese con deadline: `withTimeoutOrNull`, mai `withTimeout` dentro un `try/catch (CancellationException)`.
- Ogni file con composable termina con `@Suppress("unused") @Preview @Composable private fun XPreview()` in
  `PreviewTheme`, con gli stati significativi.
- Named arguments per chiamate con più di due parametri; `internal` per i tipi di sessione non esposti.
- Stub di WP0: firme identiche a DESIGN, corpi `TODO("WPn: <file>")`, mai `TODO` in costruttori/`init`/inizializzatori
  (eccezione: `FailureMessages` non lancia). Un WP modifica solo i file che possiede (§8).
