# XMage Desktop UX

This branch explores a more approachable XMage desktop client while keeping the
XMage rules engine, server protocol, and ordinary public/private servers
unchanged.

## Why this is a client fork, not only a skin

XMage's current themes are compiled Nimbus color and image bundles. They can
change appearance, but they cannot simplify deck management, connection setup,
table discovery, prompts, or game actions. A separate launcher could improve
setup and file management, but the experience would fall back to the existing UI
as soon as play begins.

The working direction is therefore a deliberately thin fork of `Mage.Client`.
It should remain wire-compatible with the matching XMage server and concentrate
changes in presentation and local workflows. Rules and authoritative game state
stay upstream.

## Experience structure

1. **Home**
   - Continue to the last server
   - Clear connection status and version compatibility
   - Recent decks and recent games
2. **Decks**
   - Searchable local library rather than repeated file-picker navigation
   - Import from public URL, pasted list, or file
   - Source URL, last used, format, main/sideboard counts, and validation state
   - Explicit Save a copy / Replace actions
3. **Play**
   - Server profiles and friendlier connection errors
   - Lobby organized around useful table status and eligibility
   - Creating or joining a game always begins with a visible selected deck
4. **Game**
   - One unambiguous “XMage is waiting for…” area
   - Strong turn, priority, stack, combat, and legal-action hierarchy
   - Secondary logs and zones remain available without competing with the next action

## First slice

The desktop deck editor now offers **From deck URL** as the first import option.

- Archidekt public deck URLs import directly.
- MTGTop8 deck URLs import directly.
- Moxfield is attempted directly and gives a guided Export → clipboard fallback
  when Moxfield blocks automated access.
- Downloads are host-allowlisted, size-limited, and performed away from the Swing
  event thread.
- Imported text enters XMage's existing deck loader and legality workflow, so this
  feature does not require the web bridge or a modified server.

The provider behavior was adapted from the proven `Mage.WebBridge` work, but is
implemented locally in `Mage.Client`.

## Implementation boundaries

- Do not modify game rules to solve presentation problems.
- Do not require a hosted bridge for desktop-only improvements.
- Keep client/server version matching visible and understandable.
- Avoid OS-level window automation or click interception.
- Prefer small, reviewable client changes that can be rebased on upstream XMage.
- Treat a visual theme as a supporting layer after workflow hierarchy is sound.

## Next slice

Build the local Deck Library surface and make it the normal route into the deck
editor and table-join flow. Start by indexing the user's existing `.dck` files
without moving or rewriting them, then add search, recent-use metadata, source
URLs, and a clear selected-deck state.
