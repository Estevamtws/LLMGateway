/*
 * LLM Gateway console. Plain JS, no build step, served by the gateway itself.
 * All user and server text is inserted with textContent, never as HTML.
 */
(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const storage = {
    get(area, key) {
      try { return window[area].getItem(key); } catch { return null; }
    },
    set(area, key, value) {
      try {
        if (value == null || value === '') window[area].removeItem(key);
        else window[area].setItem(key, value);
      } catch { /* storage unavailable: keep working without it */ }
    },
  };

  /** Creates an element: h('div', { class: 'x', onclick: fn }, 'text', child). */
  function h(tag, attrs = {}, ...children) {
    const el = document.createElement(tag);
    for (const [name, value] of Object.entries(attrs)) {
      if (value == null || value === false) continue;
      if (name.startsWith('on')) el.addEventListener(name.slice(2), value);
      else if (name === 'style') el.style.cssText = value;
      else el.setAttribute(name, value === true ? '' : value);
    }
    for (const child of children.flat()) {
      if (child == null || child === false) continue;
      el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
  }

  const numberFormat = new Intl.NumberFormat('pt-BR');
  const dateFormat = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'medium', timeStyle: 'short' });
  const formatNumber = (n) => numberFormat.format(n);
  const formatCost = (n) => Number(n).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 8 });
  const formatDate = (iso) => (iso ? dateFormat.format(new Date(iso)) : '');
  const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- API ---------- */

  class ApiError extends Error {
    constructor(status, body) {
      const error = body && body.error ? body.error : {};
      super(error.message || `HTTP ${status}`);
      this.status = status;
      this.code = error.code || 'error';
    }
  }

  async function request(path, { method = 'GET', headers = {}, body } = {}) {
    const init = { method, headers: { Accept: 'application/json', ...headers } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let response;
    try {
      response = await fetch(path, init);
    } catch {
      throw new ApiError(0, { error: { message: 'Não foi possível conectar ao gateway', code: 'network_error' } });
    }
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch { data = null; }
    }
    if (!response.ok) throw new ApiError(response.status, data);
    return data;
  }

  const admin = {
    get token() { return storage.get('sessionStorage', 'gw.adminToken'); },
    set token(value) { storage.set('sessionStorage', 'gw.adminToken', value); },

    async call(path, options = {}) {
      try {
        return await request(path, { ...options, headers: { 'X-Admin-Token': admin.token || '' } });
      } catch (error) {
        if (error.status === 401) {
          admin.token = null;
          renderAdminState();
          toast('Token de administrador inválido ou expirado', 'error');
        }
        throw error;
      }
    },
  };

  /* ---------- Toasts ---------- */

  function toast(message, kind = 'ok') {
    const el = h('div', { class: `toast${kind === 'error' ? ' toast--error' : ''}`, role: 'status' }, message);
    const region = $('[data-toasts]');
    region.append(el);
    // Keep the stack short: drop the oldest toasts beyond three.
    while (region.children.length > 3) region.firstElementChild.remove();
    setTimeout(() => {
      el.classList.add('is-leaving');
      el.addEventListener('transitionend', () => el.remove(), { once: true });
      setTimeout(() => el.remove(), 600);
    }, 3200);
  }

  /* ---------- Theme ---------- */

  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    const button = $('[data-action="toggle-theme"]');
    button.setAttribute('aria-label', theme === 'dark' ? 'Ativar modo claro' : 'Ativar modo escuro');
  }

  $('[data-action="toggle-theme"]').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    storage.set('localStorage', 'gw.theme', next);
    applyTheme(next);
  });
  applyTheme(document.documentElement.dataset.theme || 'light');

  /* ---------- Navigation ---------- */

  const views = ['playground', 'clients', 'usage'];
  const indicator = $('.tabs__indicator');

  function moveIndicator(tab) {
    const navLeft = tab.parentElement.getBoundingClientRect().left;
    const rect = tab.getBoundingClientRect();
    const inset = 12;
    indicator.style.transform =
      `translateX(${rect.left - navLeft + inset}px) scaleX(${Math.max(rect.width - inset * 2, 1)})`;
  }

  function showView(name, { updateHash = true } = {}) {
    if (!views.includes(name)) name = 'playground';
    for (const tab of $$('.tabs__tab')) {
      const selected = tab.dataset.view === name;
      tab.setAttribute('aria-selected', String(selected));
      if (selected) moveIndicator(tab);
    }
    for (const panel of $$('[data-view-panel]')) {
      const active = panel.dataset.viewPanel === name;
      panel.hidden = !active;
      panel.classList.toggle('is-active', active);
    }
    if (updateHash && location.hash !== `#${name}`) history.replaceState(null, '', `#${name}`);
    if (name === 'clients' && admin.token) loadClients();
    if (name === 'usage' && admin.token) loadUsageClients();
  }

  for (const tab of $$('.tabs__tab')) tab.addEventListener('click', () => showView(tab.dataset.view));
  window.addEventListener('hashchange', () => showView(location.hash.slice(1), { updateHash: false }));
  window.addEventListener('resize', () => moveIndicator($('.tabs__tab[aria-selected="true"]')));
  document.fonts?.ready.then(() => moveIndicator($('.tabs__tab[aria-selected="true"]')));

  /* ---------- Admin access ---------- */

  function renderAdminState() {
    const connected = Boolean(admin.token);
    $('[data-admin-status]').hidden = !connected;
    for (const gate of $$('[data-admin-gate]')) {
      gate.replaceChildren();
      if (!connected) gate.append($('[data-template="admin-gate"]').content.cloneNode(true));
    }
    for (const content of $$('[data-admin-content]')) content.hidden = !connected;
  }

  document.addEventListener('submit', async (event) => {
    const form = event.target.closest('[data-form="admin-login"]');
    if (!form) return;
    event.preventDefault();
    const input = $('[data-input="adminToken"]', form);
    const button = $('button', form);
    admin.token = input.value.trim();
    button.disabled = true;
    try {
      await admin.call('/admin/clients');
      renderAdminState();
      toast('Conectado como administrador');
      showView(location.hash.slice(1) || 'clients', { updateHash: false });
    } catch {
      button.disabled = false;
      input.select();
    }
  });

  $('[data-action="disconnect"]').addEventListener('click', () => {
    admin.token = null;
    state.clients = [];
    renderAdminState();
  });

  /* ---------- Playground ---------- */

  const chat = {
    history: [],
    requests: 0,
    tokens: 0,
    busy: false,
  };

  const apiKeyInput = $('[data-input="apiKey"]');
  const modelInput = $('[data-input="model"]');
  const maxTokensInput = $('[data-input="maxTokens"]');
  const temperatureInput = $('[data-input="temperature"]');
  const promptInput = $('[data-input="prompt"]');
  const messagesList = $('[data-messages]');

  apiKeyInput.value = storage.get('sessionStorage', 'gw.apiKey') || '';
  apiKeyInput.addEventListener('input', () => storage.set('sessionStorage', 'gw.apiKey', apiKeyInput.value.trim()));

  $('[data-action="toggle-key"]').addEventListener('click', (event) => {
    const show = apiKeyInput.type === 'password';
    apiKeyInput.type = show ? 'text' : 'password';
    event.currentTarget.setAttribute('aria-label', show ? 'Ocultar chave' : 'Mostrar chave');
  });

  temperatureInput.addEventListener('input', () => {
    $('[data-output="temperature"]').value = Number(temperatureInput.value).toFixed(1);
  });

  function autoGrow() {
    promptInput.style.height = 'auto';
    promptInput.style.height = `${Math.min(promptInput.scrollHeight, 200)}px`;
  }
  promptInput.addEventListener('input', autoGrow);
  promptInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      $('[data-form="chat"]').requestSubmit();
    }
  });

  function appendMessage(role, text, meta) {
    $('[data-chat-empty]').hidden = true;
    const item = h('li', { class: `message message--${role}` },
      h('div', { class: 'message__bubble' }, text),
      meta && meta.length ? h('div', { class: 'message__meta' }, meta.map((m) => h('span', {}, m))) : null);
    messagesList.append(item);
    messagesList.scrollTop = messagesList.scrollHeight;
    return item;
  }

  function updateSessionStats(latency) {
    $('[data-stat="requests"]').textContent = formatNumber(chat.requests);
    $('[data-stat="tokens"]').textContent = formatNumber(chat.tokens);
    if (latency != null) $('[data-stat="latency"]').textContent = `${formatNumber(latency)} ms`;
  }

  $('[data-form="chat"]').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (chat.busy) return;
    const text = promptInput.value.trim();
    const apiKey = apiKeyInput.value.trim();
    const model = modelInput.value.trim();
    if (!text) return;
    if (!apiKey) {
      toast('Informe uma chave do gateway', 'error');
      apiKeyInput.focus();
      return;
    }
    if (!model) {
      toast('Informe um modelo', 'error');
      modelInput.focus();
      return;
    }

    chat.busy = true;
    promptInput.value = '';
    autoGrow();
    appendMessage('user', text);
    chat.history.push({ role: 'user', content: text });
    const typing = h('li', { class: 'message message--assistant' },
      h('div', { class: 'message__bubble typing', 'aria-label': 'Gerando resposta' }, h('span'), h('span'), h('span')));
    messagesList.append(typing);
    messagesList.scrollTop = messagesList.scrollHeight;

    const body = { model, messages: chat.history };
    if (maxTokensInput.value) body.max_tokens = Number(maxTokensInput.value);
    body.temperature = Number(temperatureInput.value);

    const started = performance.now();
    try {
      const response = await request('/v1/chat/completions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}` },
        body,
      });
      const latency = Math.round(performance.now() - started);
      const choice = response.choices[0];
      const usage = response.usage;
      chat.history.push({ role: 'assistant', content: choice.message.content });
      chat.requests += 1;
      chat.tokens += usage.total_tokens;
      typing.remove();
      appendMessage('assistant', choice.message.content || ' ', [
        response.model,
        `${usage.prompt_tokens} entrada`,
        `${usage.completion_tokens} saída`,
        `${formatNumber(latency)} ms`,
        choice.finish_reason !== 'stop' ? choice.finish_reason : null,
      ].filter(Boolean));
      updateSessionStats(latency);
    } catch (error) {
      chat.history.pop();
      typing.remove();
      appendMessage('error', error.message, [error.code, error.status ? `HTTP ${error.status}` : null].filter(Boolean));
      updateSessionStats(null);
    } finally {
      chat.busy = false;
      promptInput.focus();
    }
  });

  $('[data-action="clear-chat"]').addEventListener('click', () => {
    chat.history = [];
    for (const item of $$('.message', messagesList)) item.remove();
    $('[data-chat-empty]').hidden = false;
  });

  /* ---------- Clients ---------- */

  const state = {
    clients: [],
    openClients: new Set(),
    lastIssuedKey: null,
  };

  async function loadClients() {
    try {
      state.clients = await admin.call('/admin/clients');
      renderClients();
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
    }
  }

  function renderClients() {
    const list = $('[data-client-list]');
    const filter = $('[data-input="clientSearch"]').value.trim().toLowerCase();
    const visible = state.clients.filter((c) => c.name.toLowerCase().includes(filter));
    list.replaceChildren();
    if (!visible.length) {
      list.append(h('li', { class: 'empty muted' },
        state.clients.length ? 'Nenhum cliente com esse nome.' : 'Nenhum cliente ainda. Crie o primeiro acima.'));
      return;
    }
    visible.forEach((client, index) => list.append(clientItem(client, index)));
  }

  function clientItem(client, index) {
    const activeKeys = client.keys.filter((k) => k.active).length;
    const open = state.openClients.has(client.id);
    const bodyId = `client-${client.id}`;

    const item = h('li', { class: `client${open ? ' is-open' : ''}`, style: `--i:${Math.min(index, 12)}` });
    const head = h('button', { class: 'client__head', 'aria-expanded': String(open), 'aria-controls': bodyId },
      h('span', { class: 'client__name' }, client.name),
      h('span', { class: 'client__meta' }, `${activeKeys} ${activeKeys === 1 ? 'chave ativa' : 'chaves ativas'}`),
      h('span', { class: `badge ${client.status === 'ACTIVE' ? 'badge--active' : 'badge--off'}` },
        client.status === 'ACTIVE' ? 'Ativo' : 'Suspenso'),
      chevron());
    head.addEventListener('click', () => {
      const nowOpen = !item.classList.contains('is-open');
      item.classList.toggle('is-open', nowOpen);
      head.setAttribute('aria-expanded', String(nowOpen));
      if (nowOpen) state.openClients.add(client.id);
      else state.openClients.delete(client.id);
    });

    const keys = client.keys.length
      ? client.keys.map(keyRow)
      : [h('p', { class: 'muted', style: 'font-size: var(--text-sm)' }, 'Nenhuma chave emitida.')];

    const body = h('div', { class: 'client__body', id: bodyId },
      h('div', {},
        h('div', { class: 'client__keys' },
          h('div', { class: 'client__keys-head' },
            h('span', { class: 'muted', style: 'font-size: var(--text-sm)' }, `Criado em ${formatDate(client.created_at)}`),
            h('button', { class: 'button button--primary button--small', onclick: () => issueKey(client) }, 'Gerar chave')),
          keys)));

    item.append(head, body);
    return item;
  }

  function keyRow(key) {
    return h('div', { class: `key-row${key.active ? '' : ' is-revoked'}` },
      h('span', { class: 'key-row__prefix' }, key.key_prefix),
      h('span', { class: 'key-row__date muted' },
        key.active ? `Criada ${formatDate(key.created_at)}` : `Revogada ${formatDate(key.revoked_at)}`),
      key.active
        ? h('button', { class: 'button button--danger button--small', onclick: (e) => revokeKey(key, e.currentTarget) }, 'Revogar')
        : h('span', { class: 'badge badge--off' }, 'Revogada'));
  }

  function chevron() {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'client__chevron');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'm6 9 6 6 6-6');
    svg.append(path);
    return svg;
  }

  $('[data-form="create-client"]').addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = $('[data-input="clientName"]');
    const button = $('button', event.currentTarget);
    const name = input.value.trim();
    if (!name) return;
    button.disabled = true;
    try {
      const client = await admin.call('/admin/clients', { method: 'POST', body: { name } });
      input.value = '';
      state.openClients.add(client.id);
      toast(`Cliente "${client.name}" criado`);
      await loadClients();
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
    } finally {
      button.disabled = false;
    }
  });

  $('[data-input="clientSearch"]').addEventListener('input', renderClients);

  async function issueKey(client) {
    try {
      const issued = await admin.call(`/admin/clients/${client.id}/keys`, { method: 'POST' });
      state.lastIssuedKey = issued.key;
      state.openClients.add(client.id);
      $('[data-new-key]').textContent = issued.key;
      $('[data-action="copy-key"]').textContent = 'Copiar';
      $('[data-dialog="new-key"]').showModal();
      await loadClients();
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
    }
  }

  async function revokeKey(key, button) {
    if (!window.confirm(`Revogar a chave ${key.key_prefix}...? Aplicações que a usam vão parar de funcionar.`)) return;
    button.disabled = true;
    try {
      await admin.call(`/admin/keys/${key.id}`, { method: 'DELETE' });
      toast('Chave revogada');
      await loadClients();
    } catch (error) {
      button.disabled = false;
      if (error.status !== 401) toast(error.message, 'error');
    }
  }

  const dialog = $('[data-dialog="new-key"]');

  $('[data-action="copy-key"]').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    try {
      await navigator.clipboard.writeText(state.lastIssuedKey);
      button.textContent = 'Copiada';
    } catch {
      const range = document.createRange();
      range.selectNodeContents($('[data-new-key]'));
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      button.textContent = 'Use Ctrl + C';
    }
  });

  $('[data-action="use-key"]').addEventListener('click', () => {
    apiKeyInput.value = state.lastIssuedKey;
    storage.set('sessionStorage', 'gw.apiKey', state.lastIssuedKey);
    dialog.close();
    showView('playground');
    toast('Chave aplicada no Playground');
  });

  // The plaintext key must not linger in the page after the dialog closes.
  dialog.addEventListener('close', () => {
    $('[data-new-key]').textContent = '';
  });

  /* ---------- Usage ---------- */

  const usageClient = $('[data-input="usageClient"]');
  const usageFrom = $('[data-input="usageFrom"]');
  const usageTo = $('[data-input="usageTo"]');
  const displayed = { requests: 0, input: 0, output: 0, cost: 0 };

  async function loadUsageClients() {
    try {
      state.clients = await admin.call('/admin/clients');
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
      return;
    }
    const previous = usageClient.value;
    usageClient.replaceChildren(...state.clients.map((c) => h('option', { value: c.id }, c.name)));
    if (!state.clients.length) {
      usageClient.append(h('option', { value: '' }, 'Nenhum cliente'));
      renderUsage([]);
      return;
    }
    if (state.clients.some((c) => c.id === previous)) usageClient.value = previous;
    loadUsage();
  }

  /** Start of a local calendar day, as an ISO instant. */
  const startOfDay = (value) => new Date(`${value}T00:00:00`).toISOString();
  /** The UI's "to" date is inclusive; the API's is exclusive, so send the next day's start. */
  const startOfNextDay = (value) => {
    const date = new Date(`${value}T00:00:00`);
    date.setDate(date.getDate() + 1);
    return date.toISOString();
  };

  async function loadUsage() {
    if (!usageClient.value) return;
    const params = new URLSearchParams({ clientId: usageClient.value });
    if (usageFrom.value) params.set('from', startOfDay(usageFrom.value));
    if (usageTo.value) params.set('to', startOfNextDay(usageTo.value));
    try {
      const report = await admin.call(`/admin/usage?${params}`);
      renderUsage(report.models);
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
    }
  }

  function renderUsage(models) {
    const totals = models.reduce((acc, m) => ({
      requests: acc.requests + m.requests,
      input: acc.input + m.input_tokens,
      output: acc.output + m.output_tokens,
      cost: acc.cost + Number(m.cost_usd),
    }), { requests: 0, input: 0, output: 0, cost: 0 });

    for (const key of Object.keys(totals)) {
      countTo($(`[data-total="${key}"]`), displayed[key], totals[key], key === 'cost' ? formatCost : formatNumber);
      displayed[key] = totals[key];
    }

    const rows = $('[data-usage-rows]');
    rows.replaceChildren();
    $('[data-usage-empty]').hidden = models.length > 0;
    const bars = [];
    models.forEach((m, index) => {
      const share = totals.cost > 0 ? Number(m.cost_usd) / totals.cost : 0;
      const bar = h('div', { class: 'share__bar' });
      bars.push([bar, share]);
      rows.append(h('tr', { style: `--i:${Math.min(index, 12)}` },
        h('td', {}, m.model),
        h('td', { class: 'num' }, formatNumber(m.requests)),
        h('td', { class: 'num' }, formatNumber(m.input_tokens)),
        h('td', { class: 'num' }, formatNumber(m.output_tokens)),
        h('td', { class: 'num' }, formatCost(m.cost_usd)),
        h('td', {}, h('div', { class: 'share', title: `${Math.round(share * 100)}% do custo` }, bar))));
    });
    // Next frame, so the bars grow from zero instead of appearing at full width.
    requestAnimationFrame(() => requestAnimationFrame(() => {
      for (const [bar, share] of bars) bar.style.transform = `scaleX(${share})`;
    }));
  }

  function countTo(el, from, to, format) {
    if (reducedMotion() || from === to) {
      el.textContent = format(to);
      return;
    }
    const duration = 600;
    const start = performance.now();
    const step = (now) => {
      const t = Math.min((now - start) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      const value = from + (to - from) * eased;
      el.textContent = format(format === formatNumber ? Math.round(value) : value);
      if (t < 1) requestAnimationFrame(step);
      else el.textContent = format(to);
    };
    requestAnimationFrame(step);
  }

  function setActiveChip(chip) {
    for (const c of $$('.chip')) c.classList.toggle('is-active', c === chip);
  }

  const toDateInput = (date) => {
    const offset = date.getTimezoneOffset() * 60000;
    return new Date(date.getTime() - offset).toISOString().slice(0, 10);
  };

  for (const chip of $$('.chip')) {
    chip.addEventListener('click', () => {
      setActiveChip(chip);
      if (chip.dataset.range === 'all') {
        usageFrom.value = '';
        usageTo.value = '';
      } else {
        const today = new Date();
        const from = new Date();
        from.setDate(today.getDate() - Number(chip.dataset.range) + 1);
        usageFrom.value = toDateInput(from);
        usageTo.value = toDateInput(today);
      }
      loadUsage();
    });
  }

  $('[data-form="usage"]').addEventListener('submit', (event) => {
    event.preventDefault();
    loadUsage();
  });
  usageClient.addEventListener('change', loadUsage);
  for (const input of [usageFrom, usageTo]) {
    input.addEventListener('change', () => {
      setActiveChip(null);
      loadUsage();
    });
  }

  /* ---------- Start ---------- */

  setActiveChip($('.chip[data-range="all"]'));
  renderAdminState();
  showView(location.hash.slice(1) || 'playground', { updateHash: false });
})();
