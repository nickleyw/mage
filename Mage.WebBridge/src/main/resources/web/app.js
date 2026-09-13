const form = document.querySelector('#connect-form');
const connectButton = document.querySelector('#connect-button');
const disconnectButton = document.querySelector('#disconnect-button');
const refreshButton = document.querySelector('#refresh-button');
const clearButton = document.querySelector('#clear-button');
const message = document.querySelector('#form-message');
const statusPill = document.querySelector('#status-pill');
const statusLabel = document.querySelector('#status-label');
const lobbyEmpty = document.querySelector('#lobby-empty');
const tableList = document.querySelector('#table-list');
const activityList = document.querySelector('#activity-list');
const deckDialog = document.querySelector('#deck-dialog');
const deckForm = document.querySelector('#deck-form');
const deckList = document.querySelector('#deck-list');
const deckEmpty = document.querySelector('#deck-empty');
const deckText = document.querySelector('#deck-text');
const deckName = document.querySelector('#deck-name');
const deckUrl = document.querySelector('#deck-url');
const deckPreview = document.querySelector('#deck-preview');
const deckSearch = document.querySelector('#deck-search');
const deleteDeckButton = document.querySelector('#delete-deck-button');
const selectedDeckLabel = document.querySelector('#selected-deck-label');
const validateDeckButton = document.querySelector('#validate-deck-button');
const deckReadiness = document.querySelector('#deck-readiness');
const practicePlayerDeck = document.querySelector('#practice-player-deck');
const practiceOpponent = document.querySelector('#practice-opponent');
const practiceFormat = document.querySelector('#practice-format');
const practiceStartButton = document.querySelector('#practice-start-button');
const practiceMessage = document.querySelector('#practice-message');
const lobbyMessage = document.querySelector('#lobby-message');
const gamePanel = document.querySelector('#game-panel');
const gameTurn = document.querySelector('#game-turn');
const gameStep = document.querySelector('#game-step');
const gameStatus = document.querySelector('#game-status');
const gameStatusTitle = document.querySelector('#game-status-title');
const gameStatusDetail = document.querySelector('#game-status-detail');
const gamePrompt = document.querySelector('#game-prompt');
const playerBoards = document.querySelector('#player-boards');
const handCards = document.querySelector('#hand-cards');
const handCount = document.querySelector('#hand-count');
const stackCards = document.querySelector('#stack-cards');
const stackCount = document.querySelector('#stack-count');
const sideboardPanel = document.querySelector('#sideboard-panel');
const sideboardTime = document.querySelector('#sideboard-time');
const sideboardMessage = document.querySelector('#sideboard-message');
const sideboardMain = document.querySelector('#sideboard-main');
const sideboardSide = document.querySelector('#sideboard-side');
const sideboardMainCount = document.querySelector('#sideboard-main-count');
const sideboardSideCount = document.querySelector('#sideboard-side-count');
const sideboardChanges = document.querySelector('#sideboard-changes');
const sideboardSubmitButton = document.querySelector('#sideboard-submit-button');
const concedeButton = document.querySelector('#concede-button');
const showLobbyButton = document.querySelector('#show-lobby-button');
const resumeGameButton = document.querySelector('#resume-game-button');
const cardDialog = document.querySelector('#card-dialog');
const cardDialogName = document.querySelector('#card-dialog-name');
const cardDialogType = document.querySelector('#card-dialog-type');
const cardDialogAction = document.querySelector('#card-dialog-action');
const accessTokenInput = document.querySelector('#access-token');
const rememberTokenInput = document.querySelector('#remember-token');
let eventAbort;
let editingDeckId = null;
let currentSnapshot = {connected: false, tables: [], joinedTableId: null};
let tableActionBusy = false;
let practiceBusy = false;
let gameResponseBusy = false;
let gameViewSuppressed = false;
let gameWasActive = false;
let conceding = false;
let sideboardDraft = null;
let sideboardDraftKey = null;
let sideboardSubmitBusy = false;
let importedSource = 'Pasted text';
let importedSourceUrl = '';
const DECK_LIBRARY_KEY = 'xmage-web-bridge.decks.v1';
const SELECTED_DECK_KEY = 'xmage-web-bridge.selected-deck.v1';
const ACCESS_TOKEN_KEY = 'xmage-web-bridge.access-token.v1';
let decks = loadDecks();

accessTokenInput.value = localStorage.getItem(ACCESS_TOKEN_KEY) || '';

const token = () => document.querySelector('#access-token').value.trim();
const headers = (json = false) => ({
  ...(json ? {'Content-Type': 'application/json'} : {}),
  ...(token() ? {'Authorization': `Bearer ${token()}`} : {})
});

async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {...headers(Boolean(options.body)), ...options.headers}});
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
  return body;
}

function loadDecks() {
  try {
    const parsed = JSON.parse(localStorage.getItem(DECK_LIBRARY_KEY) || '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

function saveDecks() {
  localStorage.setItem(DECK_LIBRARY_KEY, JSON.stringify(decks));
}

function parseDeckText(text) {
  const main = new Map();
  const sideboard = new Map();
  const warnings = [];
  let section = 'main';
  const headings = new Set(['deck', 'main', 'mainboard', 'decklist']);
  const sideHeadings = new Set(['sideboard', 'side board', 'commander', 'commanders', 'companion']);
  const ignoredHeadings = new Set(['maybeboard', 'maybe board', 'considering', 'tokens']);

  text.split(/\r?\n/).forEach((original, index) => {
    let line = original.trim();
    if (!line || line.startsWith('//') || line.startsWith('#')) return;
    const heading = line.replace(/:$/, '').trim().toLowerCase();
    if (headings.has(heading)) { section = 'main'; return; }
    if (sideHeadings.has(heading)) { section = 'sideboard'; return; }
    if (ignoredHeadings.has(heading)) { section = 'ignore'; return; }

    const sidePrefix = /^SB:\s*/i.test(line);
    line = line.replace(/^SB:\s*/i, '');
    const match = line.match(/^(\d+)\s*x?\s+(.+)$/i);
    if (!match) {
      warnings.push(`Line ${index + 1}: ${original.trim()}`);
      return;
    }
    const quantity = Number(match[1]);
    let name = match[2]
      .replace(/\s+\([A-Z0-9_]+\)\s+[A-Z0-9-]+(?:\s+\*F\*)?$/i, '')
      .replace(/\s+\*CMDR\*$/i, '')
      .replace(/\s+\*F\*$/i, '')
      .trim();
    if (!name || quantity < 1) return;
    if (section === 'ignore') return;
    const target = sidePrefix || section === 'sideboard' ? sideboard : main;
    target.set(name, (target.get(name) || 0) + quantity);
  });

  const entries = map => [...map.entries()].map(([name, quantity]) => ({name, quantity}));
  return {main: entries(main), sideboard: entries(sideboard), warnings};
}

function deckCount(cards) {
  return cards.reduce((sum, card) => sum + card.quantity, 0);
}

function updateDeckPreview() {
  const parsed = parseDeckText(deckText.value);
  const mainCount = deckCount(parsed.main);
  const sideCount = deckCount(parsed.sideboard);
  deckPreview.className = `deck-preview ${mainCount ? 'valid' : 'invalid'}`;
  if (!deckText.value.trim()) {
    deckPreview.className = 'deck-preview';
    deckPreview.textContent = 'Paste a list to preview its card counts.';
  } else if (!mainCount && !sideCount) {
    deckPreview.textContent = 'No recognizable card lines yet. Use “4 Card Name” formatting.';
  } else {
    deckPreview.textContent = `${mainCount} main · ${sideCount} sideboard${parsed.warnings.length ? ` · ${parsed.warnings.length} line${parsed.warnings.length === 1 ? '' : 's'} skipped` : ''}`;
  }
  return parsed;
}

function openDeckDialog(deck = null) {
  editingDeckId = deck?.id || null;
  importedSource = deck?.source || 'Pasted text';
  importedSourceUrl = deck?.sourceUrl || '';
  deckName.value = deck?.name || '';
  deckText.value = deck?.text || '';
  deckUrl.value = deck?.sourceUrl || '';
  document.querySelector('#deck-dialog-title').textContent = deck ? 'Edit deck' : 'Import a deck';
  deleteDeckButton.hidden = !deck;
  updateDeckPreview();
  deckDialog.showModal();
  setTimeout(() => (deck ? deckName : deckUrl).focus(), 0);
}

function closeDeckDialog() {
  deckDialog.close();
  editingDeckId = null;
}

function selectDeck(id) {
  localStorage.setItem(SELECTED_DECK_KEY, id);
  deckReadiness.textContent = '';
  deckReadiness.className = 'deck-readiness';
  renderDecks();
  renderLobby(currentSnapshot);
}

function selectedDeck() {
  const selectedId = localStorage.getItem(SELECTED_DECK_KEY);
  return decks.find(deck => deck.id === selectedId) || null;
}

function renderPractice(snapshot = currentSnapshot) {
  const playerDeck = selectedDeck();
  const previousOpponent = practiceOpponent.value;
  practicePlayerDeck.textContent = playerDeck?.name || 'Select a deck above';
  practiceOpponent.replaceChildren();

  if (!decks.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'Import a deck first';
    practiceOpponent.append(option);
  } else {
    [...decks].sort((a, b) => a.name.localeCompare(b.name)).forEach(deck => {
      const option = document.createElement('option');
      option.value = deck.id;
      option.textContent = deck.name;
      practiceOpponent.append(option);
    });
    const preferred = decks.some(deck => deck.id === previousOpponent)
      ? previousOpponent
      : (playerDeck?.id || decks[0].id);
    practiceOpponent.value = preferred;
  }

  const connected = Boolean(snapshot?.connected);
  const alreadySeated = Boolean(snapshot?.joinedTableId);
  practiceOpponent.disabled = practiceBusy || !decks.length;
  practiceFormat.disabled = practiceBusy;
  practiceStartButton.disabled = practiceBusy || !connected || !playerDeck || !decks.length || alreadySeated;
  practiceStartButton.textContent = practiceBusy ? 'Starting…' : 'Start practice game';

  if (!practiceBusy && !connected) {
    practiceMessage.className = 'lobby-message';
    practiceMessage.textContent = 'Connect to an XMage server and select a deck to begin.';
  } else if (!practiceBusy && alreadySeated) {
    practiceMessage.className = 'lobby-message';
    practiceMessage.textContent = 'Leave your current table before starting a practice game.';
  } else if (!practiceBusy && playerDeck) {
    practiceMessage.className = 'lobby-message';
    practiceMessage.textContent = 'Freeform is recommended while testing interactions; choose a sanctioned format when you also want legality checks.';
  }
}

function renderDecks() {
  const query = deckSearch.value.trim().toLowerCase();
  const selectedId = localStorage.getItem(SELECTED_DECK_KEY);
  const visible = decks
    .filter(deck => !query || `${deck.name} ${deck.source}`.toLowerCase().includes(query))
    .sort((a, b) => a.name.localeCompare(b.name));
  deckList.replaceChildren();
  deckEmpty.hidden = decks.length > 0;
  const selected = decks.find(deck => deck.id === selectedId);
  selectedDeckLabel.textContent = selected ? `Selected: ${selected.name}` : 'No deck selected';
  validateDeckButton.disabled = !selected;
  renderPractice(currentSnapshot);

  visible.forEach(deck => {
    const parsed = parseDeckText(deck.text);
    const card = document.createElement('article');
    card.className = `deck-card${deck.id === selectedId ? ' selected' : ''}`;
    const top = document.createElement('div');
    top.className = 'deck-card-top';
    const title = document.createElement('h3');
    title.textContent = deck.name;
    const source = document.createElement('span');
    source.className = 'source-badge';
    source.textContent = deck.source;
    top.append(title, source);
    const counts = document.createElement('p');
    counts.className = 'deck-counts';
    counts.textContent = `${deckCount(parsed.main)} main · ${deckCount(parsed.sideboard)} sideboard`;
    const actions = document.createElement('div');
    actions.className = 'deck-actions';
    const select = document.createElement('button');
    select.type = 'button';
    select.className = 'button quiet select-deck';
    select.textContent = deck.id === selectedId ? 'Selected' : 'Use deck';
    select.addEventListener('click', () => selectDeck(deck.id));
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'button quiet';
    edit.textContent = 'Edit';
    edit.addEventListener('click', () => openDeckDialog(deck));
    const copy = document.createElement('button');
    copy.type = 'button';
    copy.className = 'button quiet';
    copy.textContent = 'Copy';
    copy.addEventListener('click', async () => {
      await navigator.clipboard.writeText(deck.text);
      copy.textContent = 'Copied';
      setTimeout(() => { copy.textContent = 'Copy'; }, 1200);
    });
    actions.append(select, edit, copy);
    card.append(top, counts, actions);
    deckList.append(card);
  });
}

function setStatus(state) {
  const labels = {connected: 'Connected', connecting: 'Connecting…', error: 'Connection error', disconnected: 'Disconnected'};
  statusPill.dataset.state = state;
  statusLabel.textContent = labels[state] || state;
  const connected = state === 'connected';
  disconnectButton.hidden = !connected;
  connectButton.textContent = state === 'connecting' ? 'Connecting…' : 'Connect';
  connectButton.disabled = state === 'connecting' || connected;
  [...form.elements].forEach(control => {
    if (control.id !== 'access-token' && control !== connectButton) control.disabled = connected || state === 'connecting';
  });
}

function renderLobby(snapshot) {
  currentSnapshot = snapshot;
  renderGame(snapshot.game);
  renderSideboard(snapshot.sideboard);
  const tables = snapshot.tables || [];
  tableList.replaceChildren();
  lobbyEmpty.hidden = tables.length > 0;
  if (!snapshot.connected) {
    lobbyEmpty.querySelector('h3').textContent = 'Connect to see tables';
    lobbyEmpty.querySelector('p').textContent = 'Available games on the XMage server will appear here.';
  } else if (!tables.length) {
    lobbyEmpty.querySelector('h3').textContent = 'The lobby is quiet';
    lobbyEmpty.querySelector('p').textContent = 'Connected successfully. No tables are open right now.';
  }
  tables.forEach(table => {
    const card = document.createElement('article');
    card.className = `table-card${table.joined || snapshot.joinedTableId === table.id ? ' joined' : ''}`;
    const topline = document.createElement('div');
    topline.className = 'table-topline';
    const title = document.createElement('h3');
    title.textContent = table.name || table.gameType;
    const state = document.createElement('span');
    state.className = 'table-state';
    state.textContent = table.stateText || table.state;
    const detail = document.createElement('p');
    const minimumRating = Number(table.minimumRating || 0);
    const maximumQuitRatio = Number(table.maximumQuitRatio);
    detail.textContent = [table.gameType, table.deckType,
      table.requiresDeck === false ? 'Deck supplied by event' : 'Bring your deck',
      `${table.seats} seats`,
      minimumRating > 0 ? `Minimum rating ${minimumRating}` : '',
      Number.isFinite(maximumQuitRatio) && maximumQuitRatio < 100 ? `Maximum quit ratio ${maximumQuitRatio}%` : '',
      table.passworded ? 'Password required' : '',
      table.controller]
      .filter(Boolean).join(' · ');
    topline.append(title, state);
    const actions = document.createElement('div');
    actions.className = 'table-actions';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = table.joined ? 'button quiet' : 'button primary compact';
    button.textContent = table.joined ? 'Leave table' : 'Join table';
    button.disabled = tableActionBusy || (!table.joined &&
      (!table.joinable || (table.requiresDeck !== false && !selectedDeck()) || Boolean(snapshot.joinedTableId)));
    button.addEventListener('click', () => table.joined ? leaveTable(table) : joinTable(table));
    actions.append(button);
    card.append(topline, detail, actions);
    tableList.append(card);
  });
}

function groupSideboardCards(cards) {
  const groups = new Map();
  cards.forEach(card => {
    const key = `${card.name}\u0000${card.setCode}\u0000${card.cardNumber}`;
    if (!groups.has(key)) groups.set(key, {...card, ids: []});
    groups.get(key).ids.push(card.id);
  });
  return [...groups.values()].sort((a, b) => a.name.localeCompare(b.name));
}

function renderSideboardList(container, cards, destination) {
  container.replaceChildren();
  const groups = groupSideboardCards(cards);
  if (!groups.length) {
    const empty = document.createElement('p');
    empty.className = 'zone-empty';
    empty.textContent = destination === 'sideboard' ? 'No cards in your main deck' : 'No cards in your sideboard';
    container.append(empty);
    return;
  }
  groups.forEach(group => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'sideboard-card';
    row.disabled = sideboardSubmitBusy;
    const quantity = document.createElement('strong');
    quantity.textContent = `${group.ids.length}×`;
    const identity = document.createElement('span');
    const name = document.createElement('b');
    name.textContent = group.name;
    const printing = document.createElement('small');
    printing.textContent = `${group.setCode} ${group.cardNumber}`;
    identity.append(name, printing);
    const move = document.createElement('span');
    move.className = 'sideboard-move';
    move.textContent = destination === 'sideboard' ? '→ Side' : '→ Main';
    row.append(quantity, identity, move);
    row.addEventListener('click', () => moveSideboardCard(group.ids[0], destination));
    container.append(row);
  });
}

function moveSideboardCard(id, destination) {
  if (!sideboardDraft || sideboardSubmitBusy) return;
  const source = destination === 'sideboard' ? sideboardDraft.main : sideboardDraft.sideboard;
  const target = destination === 'sideboard' ? sideboardDraft.sideboard : sideboardDraft.main;
  const index = source.findIndex(card => card.id === id);
  if (index < 0) return;
  target.push(source.splice(index, 1)[0]);
  renderSideboard(currentSnapshot.sideboard);
}

function updateSideboardClock() {
  if (!sideboardDraft?.deadlineEpochMs) return;
  const seconds = Math.max(0, Math.ceil((sideboardDraft.deadlineEpochMs - Date.now()) / 1000));
  sideboardTime.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function renderSideboard(state) {
  if (!state?.active) {
    sideboardPanel.hidden = true;
    sideboardDraft = null;
    sideboardDraftKey = null;
    return;
  }
  const key = `${state.tableId}:${state.deadlineEpochMs}`;
  if (sideboardDraftKey !== key) {
    sideboardDraftKey = key;
    sideboardDraft = {...state, main: [...(state.main || [])], sideboard: [...(state.sideboard || [])]};
  }
  sideboardPanel.hidden = false;
  sideboardMessage.className = 'lobby-message';
  const originalMain = new Set((state.main || []).map(card => card.id));
  const movedOut = sideboardDraft.sideboard.filter(card => originalMain.has(card.id)).length;
  const movedIn = sideboardDraft.main.filter(card => !originalMain.has(card.id)).length;
  sideboardMessage.textContent = state.limited
    ? 'Tap a card to move one copy. Adding new basic lands during limited sideboarding is not supported yet.'
    : 'Tap a card to move one copy between your main deck and sideboard.';
  sideboardChanges.textContent = movedOut || movedIn
    ? `${movedIn} into main · ${movedOut} into sideboard`
    : 'No changes yet; submitting the same deck is allowed.';
  sideboardMainCount.textContent = `${sideboardDraft.main.length} cards`;
  sideboardSideCount.textContent = `${sideboardDraft.sideboard.length} cards`;
  sideboardSubmitButton.disabled = sideboardSubmitBusy;
  sideboardSubmitButton.textContent = sideboardSubmitBusy ? 'Submitting…' : 'Submit deck';
  renderSideboardList(sideboardMain, sideboardDraft.main, 'sideboard');
  renderSideboardList(sideboardSide, sideboardDraft.sideboard, 'main');
  updateSideboardClock();
}

async function submitSideboard() {
  if (!sideboardDraft || sideboardSubmitBusy) return;
  sideboardSubmitBusy = true;
  renderSideboard(currentSnapshot.sideboard);
  try {
    const result = await api('/api/sideboard/submit', {
      method: 'POST',
      body: JSON.stringify({
        mainIds: sideboardDraft.main.map(card => card.id),
        sideboardIds: sideboardDraft.sideboard.map(card => card.id)
      })
    });
    logActivity(`Sideboard submitted · ${result.mainCount} main · ${result.sideboardCount} sideboard`);
    currentSnapshot = {...currentSnapshot, sideboard: null};
    renderSideboard(null);
  } catch (error) {
    sideboardMessage.textContent = error.message;
    sideboardMessage.className = 'lobby-message error';
  } finally {
    sideboardSubmitBusy = false;
    renderSideboard(currentSnapshot.sideboard);
  }
}

function cardElement(card, game) {
  const element = document.createElement('article');
  const selectable = isCardSelectable(card, game);
  element.className = `game-card${card.tapped ? ' tapped' : ''}${selectable ? ' selectable' : ''}${card.selected ? ' selected' : ''}`;
  element.dataset.cardId = card.id;
  const name = document.createElement('span');
  name.className = 'game-card-name';
  name.textContent = card.faceDown ? 'Face-down card' : (card.name || 'Unknown card');
  const cost = document.createElement('span');
  cost.className = 'game-card-cost';
  cost.textContent = card.faceDown ? '' : (card.manaCost || '');
  const type = document.createElement('span');
  type.className = 'game-card-type';
  type.textContent = card.faceDown ? '' : (card.type || (card.token ? 'Token' : ''));
  element.append(name, cost, type);
  const combat = [card.power && card.toughness ? `${card.power}/${card.toughness}` : '', card.loyalty ? `${card.loyalty} loyalty` : '', card.damage ? `${card.damage} damage` : ''].filter(Boolean).join(' · ');
  if (combat) {
    const combatLine = document.createElement('span');
    combatLine.className = 'game-card-combat';
    combatLine.textContent = combat;
    element.append(combatLine);
  }
  if (selectable) {
    element.tabIndex = 0;
    element.setAttribute('role', 'button');
    const action = document.createElement('span');
    action.className = 'game-card-action';
    action.textContent = cardActionLabel(card, game);
    element.prepend(action);
    element.setAttribute('aria-label', `${action.textContent} ${name.textContent}`);
    const choose = () => sendGameResponse('uuid', card.id);
    element.addEventListener('click', choose);
    element.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        choose();
      }
    });
  } else {
    element.tabIndex = 0;
    element.setAttribute('role', 'button');
    element.setAttribute('aria-label', `View ${name.textContent}`);
    const inspect = () => showCardDetails(card, game);
    element.addEventListener('click', inspect);
    element.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        inspect();
      }
    });
  }
  return element;
}

function cardActionLabel(card, game) {
  if (game?.prompt?.type === 'GAME_TARGET' || (game?.prompt?.targets || []).includes(card.id)) return 'Target';
  if (card.playable) return 'Play';
  return 'Choose';
}

function showCardDetails(card, game) {
  cardDialogName.textContent = card.faceDown ? 'Face-down card' : (card.name || 'Unknown card');
  cardDialogType.textContent = [card.manaCost, card.type, card.power && card.toughness ? `${card.power}/${card.toughness}` : ''].filter(Boolean).join(' · ') || 'No public card details.';
  if (!game?.active) {
    cardDialogAction.textContent = 'This game has ended; no actions are available.';
  } else if (game?.prompt) {
    cardDialogAction.textContent = 'This card is not one of XMage’s available choices for the current action. Cards you can use have a bright Play, Choose, or Target badge.';
  } else {
    cardDialogAction.textContent = 'No action is available for this card right now. The banner at the top will change when XMage needs your response.';
  }
  cardDialog.showModal();
}

function isCardSelectable(card, game) {
  const prompt = game?.prompt;
  const targets = prompt?.targets || [];
  const possibleTargets = prompt?.possibleTargets || [];
  const hasExplicitTargets = targets.length > 0 || possibleTargets.length > 0;
  const target = targets.includes(card.id) || possibleTargets.includes(card.id);
  const offeredByPrompt = !hasExplicitTargets &&
    [...(prompt?.cards || []), ...(prompt?.otherCards || [])]
      .some(candidate => candidate?.id === card.id);
  const selectionPrompt = prompt?.type === 'GAME_SELECT' || prompt?.type === 'GAME_PLAY_MANA';
  const objectPrompt = prompt?.type === 'GAME_TARGET' || selectionPrompt;
  return Boolean(prompt) && objectPrompt &&
    (offeredByPrompt || target || card.choosable || (selectionPrompt && card.playable));
}

function renderCards(container, cards, emptyText, game) {
  container.replaceChildren();
  if (!cards?.length) {
    const empty = document.createElement('p');
    empty.className = 'zone-empty';
    empty.textContent = emptyText;
    container.append(empty);
    return;
  }
  cards.forEach(card => container.append(cardElement(card, game)));
}

function responseButton(label, action, value, style = 'quiet') {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = `button ${style}`;
  button.textContent = label;
  button.disabled = gameResponseBusy;
  button.addEventListener('click', () => sendGameResponse(action, value === null ? null : String(value)));
  return button;
}

function renderPrompt(prompt, game) {
  gamePrompt.hidden = !prompt;
  gamePrompt.replaceChildren();
  if (!prompt) return;

  const label = document.createElement('strong');
  const promptLabels = {
    GAME_ASK: 'Choose an answer',
    GAME_SELECT: 'Your priority',
    GAME_TARGET: 'Choose a target',
    GAME_CHOOSE_PILE: 'Choose a pile',
    GAME_PLAY_MANA: 'Pay mana',
    GAME_PLAY_XMANA: 'Choose X',
    GAME_GET_AMOUNT: 'Choose an amount',
    GAME_GET_MULTI_AMOUNT: 'Divide an amount',
    GAME_CHOOSE_CHOICE: 'Choose one',
    GAME_CHOOSE_ABILITY: 'Choose an ability'
  };
  label.textContent = promptLabels[prompt.type] || 'Your action';
  const detail = document.createElement('span');
  detail.textContent = prompt.message || 'XMage is waiting for your response.';
  gamePrompt.append(label, detail);
  if (prompt.subMessage) {
    const subMessage = document.createElement('small');
    subMessage.textContent = prompt.subMessage;
    gamePrompt.append(subMessage);
  }

  if (prompt.cards?.length || prompt.otherCards?.length) {
    const piles = document.createElement('div');
    piles.className = 'prompt-piles';
    [prompt.cards || [], prompt.otherCards || []].filter(cards => cards.length).forEach((cards, index) => {
      const pile = document.createElement('div');
      const pileLabel = document.createElement('span');
      pileLabel.className = 'prompt-pile-label';
      pileLabel.textContent = prompt.type === 'GAME_CHOOSE_PILE' ? `Pile ${index + 1}` : 'Available cards';
      const cardList = document.createElement('div');
      cardList.className = 'game-cards prompt-cards';
      renderCards(cardList, cards, 'No cards', game);
      pile.append(pileLabel, cardList);
      piles.append(pile);
    });
    gamePrompt.append(piles);
  }

  const actions = document.createElement('div');
  actions.className = 'prompt-actions';
  const leftLabel = prompt.choices?.['UI.left.btn.text'];
  const rightLabel = prompt.choices?.['UI.right.btn.text'];
  switch (prompt.type) {
    case 'GAME_ASK': {
      const mulliganPrompt = /mulligan/i.test(prompt.message || '');
      actions.append(
        responseButton(leftLabel || (mulliganPrompt ? 'Mulligan' : 'Yes'), 'boolean', true, 'primary'),
        responseButton(rightLabel || (mulliganPrompt ? 'Keep' : 'No'), 'boolean', false)
      );
      break;
    }
    case 'GAME_SELECT':
      actions.append(responseButton(rightLabel || 'Pass priority / Done', 'boolean', false, 'primary'));
      break;
    case 'GAME_TARGET':
      if (!prompt.required) actions.append(responseButton(rightLabel || 'Cancel', 'boolean', false));
      break;
    case 'GAME_CHOOSE_PILE':
      actions.append(
        responseButton('Choose pile 1', 'boolean', true, 'primary'),
        responseButton('Choose pile 2', 'boolean', false)
      );
      break;
    case 'GAME_PLAY_MANA':
      actions.append(responseButton(rightLabel || 'Cancel', 'boolean', false));
      break;
    case 'GAME_PLAY_XMANA':
      actions.append(
        responseButton(leftLabel || 'OK', 'boolean', true, 'primary'),
        responseButton(rightLabel || 'Cancel', 'boolean', false)
      );
      break;
    case 'GAME_GET_AMOUNT': {
      const amount = document.createElement('input');
      amount.type = 'number';
      amount.inputMode = 'numeric';
      amount.min = prompt.min;
      amount.max = prompt.max;
      amount.value = prompt.min;
      amount.setAttribute('aria-label', 'Amount');
      const submit = document.createElement('button');
      submit.type = 'button';
      submit.className = 'button primary';
      submit.textContent = 'Choose amount';
      submit.addEventListener('click', () => sendGameResponse('integer', amount.value));
      actions.append(amount, submit, responseButton('Cancel', 'boolean', false));
      break;
    }
    case 'GAME_GET_MULTI_AMOUNT': {
      const allocation = document.createElement('div');
      allocation.className = 'amount-allocation';
      const inputs = (prompt.amountItems || []).map(item => {
        const row = document.createElement('label');
        const text = document.createElement('span');
        text.textContent = item.label || 'Amount';
        const input = document.createElement('input');
        input.type = 'number';
        input.inputMode = 'numeric';
        input.min = item.min;
        input.max = item.max;
        input.value = item.value;
        row.append(text, input);
        allocation.append(row);
        return input;
      });
      const submit = document.createElement('button');
      submit.type = 'button';
      submit.className = 'button primary';
      submit.textContent = 'Confirm allocation';
      submit.addEventListener('click', () => sendGameResponse('string', inputs.map(input => input.value).join(' ')));
      actions.append(allocation, submit);
      if (prompt.choices?.canCancel) actions.append(responseButton('Cancel', 'boolean', false));
      break;
    }
    case 'GAME_CHOOSE_CHOICE':
      (prompt.choiceItems || []).forEach((choice, index) => {
        actions.append(responseButton(choice.label || choice.value || 'Choose', 'string', choice.value, index === 0 ? 'primary' : 'quiet'));
      });
      if (!prompt.required) actions.append(responseButton('Cancel', 'string', null));
      break;
    case 'GAME_CHOOSE_ABILITY':
      actions.classList.add('ability-actions');
      (prompt.abilityItems || []).forEach((choice, index) => {
        actions.append(responseButton(choice.label || 'Choose ability', 'uuid', choice.value, index === 0 ? 'primary' : 'quiet'));
      });
      actions.append(responseButton('Cancel', 'boolean', false));
      break;
    default:
      break;
  }
  if (actions.children.length) gamePrompt.append(actions);
}

async function sendGameResponse(action, value) {
  const game = currentSnapshot.game;
  const prompt = game?.prompt;
  if (!prompt || gameResponseBusy) return;
  gameResponseBusy = true;
  renderGame(game);
  try {
    await api('/api/game/respond', {
      method: 'POST',
      body: JSON.stringify({messageId: prompt.messageId, action, value})
    });
    const latest = await api('/api/game');
    currentSnapshot = {...currentSnapshot, game: latest};
    renderGame(latest);
  } catch (error) {
    logActivity(`Game response failed · ${error.message}`);
    const latest = await api('/api/game').catch(() => null);
    if (latest) {
      currentSnapshot = {...currentSnapshot, game: latest};
      renderGame(latest);
    }
  } finally {
    gameResponseBusy = false;
    renderGame(currentSnapshot.game);
  }
}

function renderGame(game) {
  if (!game) {
    gamePanel.hidden = true;
    document.body.classList.remove('game-active');
    resumeGameButton.hidden = true;
    gameWasActive = false;
    conceding = false;
    return;
  }
  const becameActive = Boolean(game.active) && !gameWasActive;
  if (becameActive) gameViewSuppressed = false;
  gamePanel.hidden = gameViewSuppressed;
  document.body.classList.toggle('game-active', !gameViewSuppressed);
  resumeGameButton.hidden = !gameViewSuppressed;
  resumeGameButton.textContent = game.active ? 'Return to game' : 'Review finished game';
  gameWasActive = Boolean(game.active);
  gameTurn.textContent = game.turn ? `Turn ${game.turn} · ${game.activePlayer || 'Active player'}` : 'Game starting…';
  gameStep.textContent = [game.phase, game.step, game.priorityPlayer ? `Priority: ${game.priorityPlayer}` : ''].filter(Boolean).join(' · ');
  renderGameStatus(game);
  renderPrompt(game.prompt, game);

  playerBoards.replaceChildren();
  (game.players || []).forEach(player => {
    const board = document.createElement('article');
    board.className = `player-board${player.controlled ? ' mine' : ''}${player.active ? ' active-player' : ''}${player.priority ? ' has-priority' : ''}`;
    const summary = document.createElement('div');
    summary.className = 'player-summary';
    const identity = document.createElement('div');
    identity.className = 'player-name';
    if (game.prompt && (game.prompt.targets || []).includes(player.id)) {
      identity.classList.add('selectable-player');
      identity.tabIndex = 0;
      identity.setAttribute('role', 'button');
      identity.addEventListener('click', () => sendGameResponse('uuid', player.id));
    }
    const name = document.createElement('h3');
    name.textContent = player.controlled ? `${player.name} · You` : player.name;
    identity.append(name);
    if (player.priority) {
      const priority = document.createElement('span');
      priority.className = 'priority-mark';
      priority.textContent = 'Priority';
      identity.append(priority);
    }
    const stats = document.createElement('div');
    stats.className = 'player-stats';
    [['Life', player.life], ['Hand', player.handCount], ['Library', player.libraryCount], ['Graveyard', player.graveyardCount], ['Games', `${player.wins}/${player.winsNeeded}`]].forEach(([label, value]) => {
      const stat = document.createElement('span');
      stat.textContent = `${label} `;
      const amount = document.createElement('strong');
      amount.textContent = value;
      stat.append(amount);
      stats.append(stat);
    });
    const mana = Object.entries(player.mana || {}).filter(([, value]) => value > 0);
    mana.forEach(([color, value]) => {
      const symbol = document.createElement('span');
      symbol.className = `mana mana-${color.toLowerCase()}`;
      symbol.textContent = `${color}:${value}`;
      stats.append(symbol);
    });
    (player.counters || []).forEach(counter => {
      const item = document.createElement('span');
      item.textContent = `${counter.name} `;
      const amount = document.createElement('strong');
      amount.textContent = counter.count;
      item.append(amount);
      stats.append(item);
    });
    if (player.controlled && player.priority && Number.isFinite(player.priorityTime)) {
      const timer = document.createElement('span');
      timer.className = 'priority-timer';
      timer.textContent = `Clock ${player.priorityTime}s${player.bufferTime ? ` +${player.bufferTime}s` : ''}`;
      stats.append(timer);
    }
    summary.append(identity, stats);
    const battlefieldLabel = document.createElement('p');
    battlefieldLabel.className = 'battlefield-label';
    battlefieldLabel.textContent = 'Battlefield';
    const battlefield = document.createElement('div');
    battlefield.className = 'game-cards';
    renderCards(battlefield, player.battlefield, 'No permanents', game);
    const otherZones = document.createElement('details');
    otherZones.className = 'secondary-zones';
    const zoneSummary = document.createElement('summary');
    zoneSummary.textContent = `Other zones · Graveyard ${player.graveyardCount || 0} · Exile ${player.exileCount || 0} · Command ${(player.command || []).length}`;
    otherZones.append(zoneSummary);
    const zoneGrid = document.createElement('div');
    zoneGrid.className = 'secondary-zone-grid';
    [['Graveyard', player.graveyard || []], ['Exile', player.exile || []], ['Command', player.command || []]].forEach(([label, cards]) => {
      if (!cards.length) return;
      const zone = document.createElement('section');
      zone.className = 'mini-zone';
      const heading = document.createElement('h4');
      heading.textContent = label;
      const list = document.createElement('div');
      list.className = 'game-cards';
      renderCards(list, cards, `No cards in ${label.toLowerCase()}`, game);
      zone.append(heading, list);
      zoneGrid.append(zone);
      if (cards.some(card => isCardSelectable(card, game))) otherZones.open = true;
    });
    otherZones.append(zoneGrid);
    board.append(summary, battlefieldLabel, battlefield, otherZones);
    playerBoards.append(board);
  });
  renderCards(handCards, game.hand || [], 'Your hand is empty', game);
  handCount.textContent = `${(game.hand || []).length} card${(game.hand || []).length === 1 ? '' : 's'}`;
  renderCards(stackCards, game.stack || [], 'The stack is empty', game);
  stackCount.textContent = (game.stack || []).length ? `${game.stack.length} object${game.stack.length === 1 ? '' : 's'}` : 'Empty';
  concedeButton.disabled = !game.active || conceding;
  concedeButton.textContent = conceding ? 'Conceding…' : 'Concede this game';
  if (becameActive) requestAnimationFrame(() => gamePanel.scrollIntoView({block: 'start'}));
}

function renderGameStatus(game) {
  const me = (game.players || []).find(player => player.controlled);
  const promptMessage = game.prompt?.message || game.prompt?.subMessage;
  if (!game.active) {
    gameStatus.dataset.state = 'ended';
    gameStatus.querySelector('.game-status-mark').textContent = '■';
    gameStatusTitle.textContent = 'Game ended';
    gameStatusDetail.textContent = cleanDisplayText(game.message) || 'XMage has closed this game. You can return to the lobby or wait for sideboarding.';
    conceding = false;
    return;
  }
  if (conceding) {
    gameStatus.dataset.state = 'waiting';
    gameStatus.querySelector('.game-status-mark').textContent = '…';
    gameStatusTitle.textContent = 'Concession sent';
    gameStatusDetail.textContent = 'Waiting for XMage to finish the game and report the result.';
    return;
  }
  if (game.prompt) {
    gameStatus.dataset.state = 'action';
    gameStatus.querySelector('.game-status-mark').textContent = '!';
    gameStatusTitle.textContent = 'Your action is required';
    gameStatusDetail.textContent = promptMessage || 'Use the highlighted cards or the response buttons below.';
    return;
  }
  if (me?.priority || (game.priorityPlayer && game.priorityPlayer === me?.name)) {
    gameStatus.dataset.state = 'action';
    gameStatus.querySelector('.game-status-mark').textContent = '!';
    gameStatusTitle.textContent = 'You have priority';
    gameStatusDetail.textContent = 'XMage is preparing your available actions. Playable cards will be highlighted.';
    return;
  }
  gameStatus.dataset.state = 'waiting';
  gameStatus.querySelector('.game-status-mark').textContent = '…';
  if (game.priorityPlayer) {
    gameStatusTitle.textContent = `Waiting for ${game.priorityPlayer}`;
    gameStatusDetail.textContent = `${game.activePlayer || game.priorityPlayer} is the active player. You do not need to respond yet.`;
  } else if (game.activePlayer === me?.name) {
    gameStatusTitle.textContent = 'Your turn';
    gameStatusDetail.textContent = 'Waiting for XMage to request your next action.';
  } else {
    gameStatusTitle.textContent = game.activePlayer ? `${game.activePlayer}’s turn` : 'Game in progress';
    gameStatusDetail.textContent = 'No response is needed from you right now.';
  }
}

function cleanDisplayText(value) {
  if (!value) return '';
  const text = document.createElement('textarea');
  text.innerHTML = String(value).replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '');
  return text.value;
}

async function concedeGame() {
  if (!currentSnapshot.game?.active || !confirm('Concede this game? Your match will continue if another game remains.')) return;
  concedeButton.disabled = true;
  conceding = true;
  renderGame(currentSnapshot.game);
  try {
    await api('/api/game/action', {method: 'POST', body: JSON.stringify({action: 'concede'})});
    logActivity('Conceded the current game');
  } catch (error) {
    conceding = false;
    logActivity(`Concede failed · ${error.message}`);
  } finally {
    concedeButton.disabled = false;
  }
}

function formatDeckCheck(result) {
  const unresolved = Object.entries(result.unresolved || {});
  if (unresolved.length) {
    const names = unresolved.slice(0, 4).map(([name, count]) => `${count}× ${name}`).join(', ');
    return {ok: false, text: `${result.mainCount} main · ${result.sideboardCount} sideboard. Not found in this XMage build: ${names}${unresolved.length > 4 ? ` and ${unresolved.length - 4} more` : ''}.`};
  }
  if (!result.canJoin) return {ok: false, text: 'No playable cards were resolved from this decklist.'};
  const corrections = Object.entries(result.correctedNames || {});
  const warnings = result.warnings || [];
  const details = [];
  if (corrections.length) {
    details.push(`name fixes: ${corrections.slice(0, 3).map(([from, to]) => `${from} → ${to}`).join(', ')}${corrections.length > 3 ? ` and ${corrections.length - 3} more` : ''}`);
  }
  if (warnings.length) details.push(warnings.join(' '));
  return {
    ok: true,
    warning: warnings.length > 0,
    text: `Prepared for XMage: ${result.mainCount} main · ${result.sideboardCount} sideboard${details.length ? ` · ${details.join(' · ')}` : ''}.`
  };
}

async function validateSelectedDeck() {
  const deck = selectedDeck();
  if (!deck) throw new Error('Select a deck first.');
  deckReadiness.className = 'deck-readiness';
  deckReadiness.textContent = 'Preparing XMage card data and checking every card… The first check can take a minute.';
  const result = await api('/api/decks/validate', {
    method: 'POST',
    body: JSON.stringify({deckName: deck.name, deckText: deck.text})
  });
  const formatted = formatDeckCheck(result);
  deckReadiness.className = `deck-readiness ${formatted.ok && !formatted.warning ? 'success' : 'error'}`;
  deckReadiness.textContent = formatted.text;
  if (!formatted.ok) throw new Error('Fix the selected deck before joining.');
  return deck;
}

async function startPractice() {
  const playerDeck = selectedDeck();
  const opponentDeck = decks.find(deck => deck.id === practiceOpponent.value);
  if (!playerDeck || !opponentDeck) {
    practiceMessage.className = 'lobby-message error';
    practiceMessage.textContent = 'Choose both your deck and an AI opponent deck.';
    return;
  }

  practiceBusy = true;
  renderPractice(currentSnapshot);
  practiceMessage.className = 'lobby-message';
  practiceMessage.textContent = 'Resolving both decks, creating a private table, and seating XMage’s AI…';
  try {
    await api('/api/practice/start', {
      method: 'POST',
      body: JSON.stringify({
        deckName: playerDeck.name,
        deckText: playerDeck.text,
        opponentDeckName: opponentDeck.name,
        opponentDeckText: opponentDeck.text,
        format: practiceFormat.value
      })
    });
    practiceMessage.className = 'lobby-message success';
    practiceMessage.textContent = `Practice started: ${playerDeck.name} vs Practice Bot using ${opponentDeck.name}.`;
    logActivity(`Practice game started · ${playerDeck.name} vs ${opponentDeck.name}`);
    await refresh();
  } catch (error) {
    practiceMessage.className = 'lobby-message error';
    practiceMessage.textContent = error.message;
  } finally {
    practiceBusy = false;
    renderPractice(currentSnapshot);
  }
}

async function joinTable(table) {
  tableActionBusy = true;
  renderLobby(currentSnapshot);
  lobbyMessage.className = 'lobby-message';
  lobbyMessage.textContent = table.requiresDeck === false
    ? `Joining “${table.name || table.gameType}”; XMage will supply the limited card pool…`
    : `Checking your selected deck for “${table.name || table.gameType}”…`;
  try {
    const deck = table.requiresDeck === false ? null : await validateSelectedDeck();
    let password = '';
    if (table.passworded) {
      password = prompt('Enter the password for this XMage table:');
      if (password === null) {
        lobbyMessage.textContent = 'Join cancelled.';
        return;
      }
    }
    lobbyMessage.textContent = 'Sending a standard XMage join request…';
    await api('/api/tables/join', {
      method: 'POST',
      body: JSON.stringify({
        tableId: table.id,
        deckName: deck?.name || '',
        deckText: deck?.text || '',
        password
      })
    });
    lobbyMessage.className = 'lobby-message success';
    lobbyMessage.textContent = deck
      ? `Joined “${table.name || table.gameType}” with ${deck.name}.`
      : `Joined “${table.name || table.gameType}”; XMage will supply your limited card pool.`;
    logActivity(`Joined table · ${table.name || table.gameType}`);
    await refresh();
  } catch (error) {
    lobbyMessage.className = 'lobby-message error';
    lobbyMessage.textContent = error.message;
  } finally {
    tableActionBusy = false;
    renderLobby(currentSnapshot);
  }
}

async function leaveTable(table) {
  tableActionBusy = true;
  renderLobby(currentSnapshot);
  try {
    await api('/api/tables/leave', {method: 'POST', body: JSON.stringify({tableId: table.id})});
    lobbyMessage.className = 'lobby-message';
    lobbyMessage.textContent = `Left “${table.name || table.gameType}”.`;
    logActivity(`Left table · ${table.name || table.gameType}`);
    await refresh();
  } catch (error) {
    lobbyMessage.className = 'lobby-message error';
    lobbyMessage.textContent = error.message;
  } finally {
    tableActionBusy = false;
    renderLobby(currentSnapshot);
  }
}

function logActivity(text, timestamp = new Date()) {
  const row = document.createElement('li');
  const time = document.createElement('time');
  time.textContent = new Date(timestamp).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit', second: '2-digit'});
  const detail = document.createElement('span');
  detail.textContent = text;
  row.append(time, detail);
  activityList.prepend(row);
  while (activityList.children.length > 60) activityList.lastElementChild.remove();
}

function describeEvent(event) {
  const payload = event.payload || {};
  if (event.type === 'chat.message') {
    const speaker = payload.username ? `${payload.username}: ` : '';
    return `${speaker}${payload.message || 'Chat message'}`;
  }
  if (event.type === 'xmage.user-message') {
    return `${payload.title ? `${payload.title}: ` : ''}${payload.message || 'XMage message'}`;
  }
  if (event.type === 'xmage.callback') return `${payload.method || 'XMage callback'}${payload.dataType ? ` · ${payload.dataType.split('.').pop()}` : ''}`;
  if (payload.message) return payload.message;
  return event.type.replaceAll('.', ' ');
}

function shouldShowTechnicalEvent(event) {
  return event.type !== 'xmage.callback';
}

async function streamEvents() {
  eventAbort?.abort();
  const controller = new AbortController();
  eventAbort = controller;
  let announcedOffline = false;
  while (!controller.signal.aborted) {
    try {
      const response = await fetch('/api/events', {headers: headers(), signal: controller.signal});
      if (!response.ok || !response.body) throw new Error('Live event stream unavailable.');
      if (announcedOffline) logActivity('Live activity reconnected');
      announcedOffline = false;
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const {value, done} = await reader.read();
        if (done) throw new Error('Live event stream ended.');
        buffer += decoder.decode(value, {stream: true});
        const chunks = buffer.split('\n\n');
        buffer = chunks.pop();
        chunks.forEach(chunk => {
          const data = chunk.split('\n').find(line => line.startsWith('data: '));
          if (!data) return;
          const event = JSON.parse(data.slice(6));
          if (shouldShowTechnicalEvent(event)) logActivity(describeEvent(event), event.timestamp);
          if (event.type === 'session.connected') setStatus('connected');
          if (event.type === 'session.disconnected') setStatus('disconnected');
          if (event.type === 'session.error') setStatus('error');
          if (event.type === 'xmage.user-message' && /join table|submit deck|load failed/i.test(event.payload?.title || '')) {
            lobbyMessage.className = 'lobby-message error';
            lobbyMessage.textContent = event.payload?.message || event.payload?.title;
          }
          if (event.type === 'table.joined' || event.type === 'table.left' || event.type === 'practice.started') refresh();
          if (event.type === 'sideboard.started') {
            currentSnapshot = {...currentSnapshot, sideboard: event.payload};
            renderSideboard(event.payload);
          }
          if (event.type === 'sideboard.submitted') {
            currentSnapshot = {...currentSnapshot, sideboard: null};
            renderSideboard(null);
          }
          if (event.type === 'game.started' || event.type === 'game.state' || event.type === 'game.over') {
            currentSnapshot = {...currentSnapshot, game: event.payload};
            renderGame(event.payload);
          }
        });
      }
    } catch (error) {
      if (error.name === 'AbortError' || controller.signal.aborted) return;
      if (!announcedOffline) logActivity('Live activity interrupted; reconnecting…');
      announcedOffline = true;
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
  }
}

async function refresh() {
  try {
    const snapshot = await api('/api/session');
    setStatus(snapshot.state);
    renderLobby(snapshot);
    return snapshot;
  } catch (error) {
    message.textContent = error.message;
    message.className = 'error';
    setStatus('error');
  }
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  // Capture the values before setStatus('connecting') disables the form.
  // Disabled controls are intentionally omitted by FormData.
  const data = new FormData(form);
  setStatus('connecting');
  message.className = '';
  message.textContent = 'Opening the standard XMage connection…';
  streamEvents();
  try {
    const snapshot = await api('/api/session/connect', {
      method: 'POST',
      body: JSON.stringify({
        host: data.get('host'),
        port: Number(data.get('port')),
        username: data.get('username'),
        password: data.get('password')
      })
    });
    setStatus('connected');
    renderLobby(snapshot);
    message.textContent = `Connected to ${snapshot.host}:${snapshot.port} as ${snapshot.username}.`;
    logActivity(`Lobby loaded · ${snapshot.tableCount} table${snapshot.tableCount === 1 ? '' : 's'}`);
  } catch (error) {
    setStatus('error');
    message.textContent = error.message;
    message.className = 'error';
  }
});

disconnectButton.addEventListener('click', async () => {
  try {
    const snapshot = await api('/api/session', {method: 'DELETE'});
    eventAbort?.abort();
    setStatus('disconnected');
    renderLobby(snapshot);
    message.textContent = 'Disconnected. Your friend’s XMage session is unaffected.';
  } catch (error) {
    message.textContent = error.message;
    message.className = 'error';
  }
});

refreshButton.addEventListener('click', refresh);
practiceStartButton.addEventListener('click', startPractice);
clearButton.addEventListener('click', () => activityList.replaceChildren());
sideboardSubmitButton.addEventListener('click', submitSideboard);
concedeButton.addEventListener('click', concedeGame);
showLobbyButton.addEventListener('click', () => {
  gameViewSuppressed = true;
  renderGame(currentSnapshot.game);
  document.querySelector('.lobby-panel').scrollIntoView({block: 'start'});
});
resumeGameButton.addEventListener('click', () => {
  gameViewSuppressed = false;
  renderGame(currentSnapshot.game);
  requestAnimationFrame(() => gamePanel.scrollIntoView({block: 'start'}));
});
document.querySelector('#close-card-dialog').addEventListener('click', () => cardDialog.close());
cardDialog.addEventListener('click', event => {
  if (event.target === cardDialog) cardDialog.close();
});
accessTokenInput.addEventListener('change', () => {
  if (rememberTokenInput.checked && accessTokenInput.value.trim()) {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessTokenInput.value.trim());
  } else {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
  }
  streamEvents();
  refresh();
});
rememberTokenInput.addEventListener('change', () => {
  if (rememberTokenInput.checked && accessTokenInput.value.trim()) {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessTokenInput.value.trim());
  } else {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
  }
});

document.querySelector('#add-deck-button').addEventListener('click', () => openDeckDialog());
document.querySelector('#close-deck-dialog').addEventListener('click', closeDeckDialog);
document.querySelector('#cancel-deck-button').addEventListener('click', closeDeckDialog);
deckText.addEventListener('input', updateDeckPreview);
deckSearch.addEventListener('input', renderDecks);
validateDeckButton.addEventListener('click', async () => {
  validateDeckButton.disabled = true;
  try {
    await validateSelectedDeck();
  } catch (error) {
    if (!deckReadiness.textContent) {
      deckReadiness.className = 'deck-readiness error';
      deckReadiness.textContent = error.message;
    }
  } finally {
    validateDeckButton.disabled = !selectedDeck();
  }
});

document.querySelector('#deck-file').addEventListener('change', async event => {
  const file = event.target.files[0];
  if (!file) return;
  deckText.value = await file.text();
  if (!deckName.value) deckName.value = file.name.replace(/\.[^.]+$/, '');
  importedSource = 'File';
  importedSourceUrl = '';
  updateDeckPreview();
  event.target.value = '';
});

document.querySelector('#fetch-deck-button').addEventListener('click', async event => {
  const button = event.currentTarget;
  if (!deckUrl.value.trim()) {
    deckUrl.focus();
    return;
  }
  button.disabled = true;
  button.textContent = 'Importing…';
  deckPreview.className = 'deck-preview';
  deckPreview.textContent = 'Requesting the public deck from its source…';
  try {
    const imported = await api('/api/decks/import-url', {
      method: 'POST',
      body: JSON.stringify({url: deckUrl.value.trim()})
    });
    deckName.value = imported.name || '';
    deckText.value = imported.text || '';
    importedSource = imported.source || 'Imported link';
    importedSourceUrl = imported.sourceUrl || deckUrl.value.trim();
    updateDeckPreview();
  } catch (error) {
    deckPreview.className = 'deck-preview invalid';
    deckPreview.textContent = `${error.message} You can still paste the exported list below.`;
  } finally {
    button.disabled = false;
    button.textContent = 'Import link';
  }
});

deckForm.addEventListener('submit', event => {
  event.preventDefault();
  const parsed = updateDeckPreview();
  if (!deckCount(parsed.main) && !deckCount(parsed.sideboard)) {
    deckText.focus();
    return;
  }
  const existing = decks.find(deck => deck.id === editingDeckId);
  const saved = {
    id: editingDeckId || (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`),
    name: deckName.value.trim(),
    text: deckText.value.trim(),
    source: importedSource,
    sourceUrl: importedSourceUrl,
    createdAt: existing?.createdAt || new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };
  decks = existing ? decks.map(deck => deck.id === saved.id ? saved : deck) : [...decks, saved];
  saveDecks();
  if (!localStorage.getItem(SELECTED_DECK_KEY)) localStorage.setItem(SELECTED_DECK_KEY, saved.id);
  closeDeckDialog();
  renderDecks();
});

deleteDeckButton.addEventListener('click', () => {
  if (!editingDeckId) return;
  const deck = decks.find(item => item.id === editingDeckId);
  if (!deck || !confirm(`Delete “${deck.name}” from this browser?`)) return;
  decks = decks.filter(item => item.id !== editingDeckId);
  if (localStorage.getItem(SELECTED_DECK_KEY) === editingDeckId) localStorage.removeItem(SELECTED_DECK_KEY);
  saveDecks();
  closeDeckDialog();
  renderDecks();
});

streamEvents();
refresh();
renderDecks();
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/service-worker.js')
    .catch(error => logActivity(`Home Screen support unavailable · ${error.message}`)));
}
setInterval(() => {
  if (statusPill.dataset.state === 'connected') refresh();
}, 5000);
setInterval(updateSideboardClock, 1000);
