# XMage Web Bridge Spike

This module tests the riskiest assumption behind an iPad-friendly XMage client:
can a non-Swing Java process connect to an unmodified XMage server and receive
the same typed callbacks as the desktop application?

The module currently:

- uses XMage's existing `SessionImpl` transport;
- identifies itself with the exact XMage build version;
- logs in as a normal player;
- exposes connect, disconnect, session, and lobby data as JSON;
- streams typed XMage callback metadata to browsers using Server-Sent Events;
- includes a responsive, touch-friendly connection and lobby interface;
- stores a searchable deck library locally in the browser;
- accepts pasted lists and uploaded text/XMage deck files;
- imports public Archidekt, Moxfield, and MTGTop8 deck links through allowlisted
  provider adapters;
- resolves card names through XMage's own local card database and reports cards
  that are unavailable in the matching XMage build;
- joins and leaves ordinary match and tournament tables using the selected deck;
- acknowledges XMage's standard game-start handshake and reduces live game
  updates into a browser-safe battlefield, hand, stack, player, and prompt view;
- renders XMage's multi-ability picker as touch-sized choices with validated
  ability IDs and a working cancel action;
- handles constructed sideboarding between games with tap-to-move card copies,
  a countdown, and exact-pool validation before the standard deck submission;
- exposes graveyard, exile, and command-zone cards; floating mana, counters,
  match score, and the player's priority clock; and
- provides a confirmed concede-game action through XMage's normal player-action
  channel;
- includes a web app manifest, service worker, and iPad Home Screen icon; and
- retains `HeadlessProbe` as a command-line compatibility check.

It does not yet create tables from the browser, render card artwork, expose
every unusual game dialog, or support adding basic lands during limited-event
sideboarding. The current interaction slice covers typed boolean prompts,
playable-card, target, and ability UUIDs,
single and validated multi-amount allocations, ordinary text choices, and pile
selection.

## Verified milestone

The probe has been verified against the stock server distribution built from
the same XMage revision. Without changing the server, it successfully:

1. opened the standard XMage `bisocket` transport;
2. logged in as a normal player;
3. received a session ID and main-room ID; and
4. fetched the lobby table list.

That validates the proposed boundary: the bridge can speak XMage's existing
Java protocol toward any version-compatible server while exposing a separate,
browser-safe API toward an iPad.

The browser API has also been exercised end-to-end against that stock server:
tokenless access was rejected when authentication was enabled, the authenticated
client connected, the event stream delivered each connection phase, and the
lobby snapshot reported the active XMage session. A second stock-server smoke
test created an ordinary table using a separate client, then verified that the
bridge could resolve a 60-card deck, join that table, receive the joined-table
callback, and leave successfully.

A full match-start smoke test has also been completed against the unmodified
server. A separate ordinary client hosted and joined the table, the bridge
joined as the second player, the host started the match, and the bridge received
the standard `START_GAME` and initial game-state callbacks with both players,
life totals, libraries, turn, and zone data.

The interaction smoke test continued through the live opening sequence. XMage
asked the bridge player to select a starting player; the bridge rejected an
illegal response type and a stale message ID, accepted an allowed player UUID,
then received the opening hand and Mulligan prompt. This verifies the same typed
response channel used for priority, selections, amounts, and choices.

A best-of-three sideboarding smoke test has also passed against the stock
server. After game one, the bridge received XMage's real `SIDEBOARD` callback,
rejected a forged card-copy ID, submitted the unchanged 60-card main deck and
one-card sideboard through the standard `deckSubmit` call, and advanced toward
the next game without any server modification.

Run the web bridge from the repository root after building `Mage` and
`Mage.Common`:

```sh
mvn -pl Mage.WebBridge -am -DskipTests package
mvn -pl Mage.WebBridge exec:java \
  -Dexec.mainClass=mage.webbridge.BridgeServer
```

Then open `http://127.0.0.1:8080`. The default binding is deliberately local.
To make the bridge reachable from another device, bind it to a network
interface and set an access token:

```sh
XMAGE_BRIDGE_TOKEN="choose-a-long-random-token" mvn -pl Mage.WebBridge exec:java \
  -Dexec.mainClass=mage.webbridge.BridgeServer \
  -Dexec.args="0.0.0.0 8080"
```

Run the original command-line probe with:

```sh
mvn -pl Mage.WebBridge exec:java \
  -Dexec.mainClass=mage.webbridge.HeadlessProbe \
  -Dexec.args="beta.xmage.today webprobe 17171"
```

Recent Java runtimes may also require XMage's standard `--add-opens` options
for legacy JBoss serialization.

The bridge version must match the target server version, just as the desktop
client's version must match it.

## Hosting and iPad installation

The final deployment needs to run this Java bridge as a persistent service
behind an HTTPS address. The iPad opens that address in Safari; Share > Add to
Home Screen installs the included standalone web app shell and icon. HTTPS is
required for service workers and safe remote use.

The hosted bridge then makes a normal outbound XMage connection to the server
address entered in the browser. A friend who already hosts games for remote
XMage players does not install a plugin or use a different client; their server
only sees another version-compatible XMage player. The hosting platform keeps
the bridge process running and supplies HTTPS; an active XMage session remains
connected only while that process stays online.

The repository now includes a production-style container definition and a
Render Blueprint. The container requires `XMAGE_BRIDGE_TOKEN`, listens on the
hosting provider's `PORT`, and stores XMage's generated card database under
`/data`. See [DEPLOYMENT.md](DEPLOYMENT.md) for local and hosted launch steps.

## Browser API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/health` | Bridge process health |
| `POST` | `/api/session/connect` | Connect one player to an XMage server |
| `GET` | `/api/session` | Current connection and lobby snapshot |
| `DELETE` | `/api/session` | Disconnect the player |
| `GET` | `/api/events` | Live bridge/XMage event stream |
| `POST` | `/api/decks/import-url` | Import an allowlisted public deck URL |
| `POST` | `/api/decks/validate` | Resolve deck text against this XMage build |
| `POST` | `/api/tables/join` | Join a lobby table with a resolved deck |
| `POST` | `/api/tables/leave` | Leave the current lobby table |
| `GET` | `/api/game` | Latest browser-safe live game state |
| `POST` | `/api/game/respond` | Send a type-checked response to the current prompt |
| `POST` | `/api/game/action` | Perform an allowlisted match action such as conceding the current game |
| `GET` | `/api/sideboard` | Current between-game sideboarding state |
| `POST` | `/api/sideboard/submit` | Submit an exact-pool-validated sideboard configuration |

When `XMAGE_BRIDGE_TOKEN` is configured, send it as a bearer token. XMage
passwords are passed directly into `SessionImpl` and are not retained in the
bridge state or returned by the API.

Decks saved in the interface remain in that browser's local storage. This makes
the initial library private and account-free. Cloud sync/export can be layered
on later without making it a prerequisite for playing.

The first deck check initializes XMage's local card database and can take about
a minute on a small machine. Later checks reuse that database. The web bridge
depends on `Mage.Sets` so its card names and printings match the protocol version
it reports to the server.

Direct public-link import is verified for Archidekt and MTGTop8. Moxfield's
undocumented export endpoint currently rejects bridge requests, so the interface
gives Moxfield users a concise Export/Copy/Paste fallback while retaining its
isolated adapter for future compatibility updates.

`TableHostProbe` is a disposable development helper that creates a standard
two-player Freeform table for join compatibility tests; it is not part of the
browser's normal workflow. Set `XMAGE_PROBE_SIDEBOARD=true` to create a
best-of-three match and concede game one for the sideboarding smoke test.
