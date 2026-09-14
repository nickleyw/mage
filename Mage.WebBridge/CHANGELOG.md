# XMage for iPad changelog

This file tracks the fork-specific `Mage.WebBridge` application. It does not duplicate upstream XMage's release history.

## Unreleased — 2026-09-13

### Added

- Solo Table self-play using two ordinary human XMage sessions, two decks, and switchable Player 1/Player 2 viewpoints.
- Per-player Solo Table status showing which side has a prompt, sideboard decision, or lost connection.
- Sticky in-game response banner and clearer current-turn/priority/prompt presentation.
- Touch-accessible card details and on-demand Scryfall card previews without bundling card art.
- Player-facing match start, reconnect, concede, leave, and end-state notices.
- More complete prompt/action support for boolean choices, modes, targets, cards, abilities, mana, amounts, combat, rollback, and sideboarding.
- Browser-side default for `beta.xmage.today:17171`.
- Custom XMage web-app icons and favicon.

### Changed

- Joined games are promoted into the main play view instead of appearing as another section at the bottom of the lobby.
- Raw callback names and chat callbacks are treated as troubleshooting detail instead of primary instructions.
- Table cards explain unsupported limited/draft workflows and visible rating requirements before joining.
- Deck readiness messages distinguish normalization/preparation from final server legality.
- Removed the non-working AI practice panel. Public servers generally do not expose the server-hosted AI workflow used by a locally hosted desktop XMage server.

### Fixed

- Corrected table-join argument handling and surfaced server-provided rejection messages when available.
- Corrected callback message ordering and stale-prompt protection.
- Fixed target selection to submit XMage's allowed object IDs, including library searches such as fetch lands.
- Improved imported double-faced-card normalization to XMage's front-face naming.
- Corrected multiple build and type-compatibility issues found during Render deployment.

## Initial hosted prototype — 2026-09-12

### Added

- Java bridge module using XMage's native, version-matched client protocol.
- Responsive browser shell, PWA manifest, service worker, and iPad Home Screen installation.
- Render free-tier Docker Blueprint with health check, generated access token, and reduced-memory JVM settings.
- Normal XMage connection, lobby listing, constructed-table joining, and event streaming.
- Local browser deck library with search, edit, copy, selection, paste/file import, and validation.
- Public deck URL import for Archidekt and MTGTop8, plus a guided Moxfield export/copy/paste fallback.
- Initial game-state reduction for players, battlefield, hand, stack, zones, and prompts.

## Compatibility note

This branch is an active prototype. A successful compile verifies API compatibility with this source tree; complete confidence still requires live interaction tests against a matching XMage server.
