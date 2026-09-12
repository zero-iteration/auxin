/* auxin UI -- vanilla ES2020, no framework, no build step, no CDN, no network
 * except the auxin read-only JSON API on the same origin.
 *
 * Design rules this file is written to obey, in priority order:
 *
 *  1. NEVER render a zero or an empty graph as if it were data. Every route
 *     result carries {ok,status}. A missing route, a missing field and a
 *     genuinely-zero measurement render as three DIFFERENT things.
 *  2. Edge counts are `sampledObservations`, never `calls`, and never appear
 *     without their sample rate.
 *  3. Runtime and static edges are drawn differently and never merged.
 *  4. "never ran" (UNKNOWN / probe bit unset) and "could never be observed"
 *     (NOT_DYNAMICALLY_OBSERVABLE / NO_PROBE_INSTALLED) are visually distinct.
 *     Conflating them is bug #18.
 *  5. Every route and field consumed is listed in API-USED.md and echoed in
 *     the "data sources" tab at runtime, so a server change is traceable.
 */
'use strict';

/* ==================================================================== */
/* config                                                               */
/* ==================================================================== */

// Same-origin by default (the server will mount this directory). `?api=` lets
// dev-serve.sh point at a collector on another port when a proxy is in front.
const QS = new URLSearchParams(location.search);
const API_BASE = (QS.get('api') || '').replace(/\/$/, '');

const LIMITS = {
  classEnum: 20000,   // instrumentation-gaps?limit=  (class enumeration)
  hotMethods: 5000,   // hot-methods?limit=
  hotPaths: 1500,     // hot-paths?limit=       (runtime edges, ranked top-N)
  deadCandidates: 1000,
  edgeDetail: 200,    // callers/callees?limit=
  staticFetch: 400,   // max blast-radius calls for the static-edge overlay
  autoCollapseOver: 900, // methods above this => start collapsed, and say so
};
const SINCE_DAYS = 3650; // hot-methods?sinceDays= : we want everything stored

const STATUSES = ['LIVE', 'DEAD_CANDIDATE', 'UNKNOWN', 'NOT_DYNAMICALLY_OBSERVABLE'];
const SILENT_ELIGIBILITY = new Set(['NO_PROBE_INSTALLED', 'DE_INSTRUMENTED']);

/* layout constants (user units) */
const PAD = 12, GAP = 8, HDR = 24, MW_MIN = 132, MW_MAX = 300, MH_BASE = 34;

/* ==================================================================== */
/* state                                                                */
/* ==================================================================== */

const S = {
  build: null,
  builds: [],
  verdicts: [],            // flat list of verdict objects (as served)
  byKey: new Map(),        // "cls#idx" -> verdict
  tier2: new Map(),        // "cls#idx" -> hot-methods row
  runtimeEdges: [],        // {from:{cls,idx,method}, to:{...}, sampledObservations, edgesSampleRate, ...}
  staticEdges: [],         // {from:{cls,name,desc}, to:{cls,idx}, resolution, semantics}
  staticLoaded: false,
  staticProgress: null,
  tree: null,
  collapsed: new Set(),
  selected: null,          // node id
  filter: '',
  hideNonMatching: false,
  statusOn: new Set(STATUSES),
  view: { x: 40, y: 40, k: 1 },
  api: {                   // raw route results, kept for the "data sources" tab
    builds: null, summary: null, gaps: null, verdicts: [], hotMethods: null,
    hotPaths: null, coverage: null, dead: null, health: null, proposals: null, efp: null,
  },
  sources: [],             // [{route, status, ok, note}]
  nodesById: new Map(),
  methodNodes: [],
  hiddenEdgeCount: 0,
  detailCache: new Map(),
};

/* ==================================================================== */
/* tiny helpers                                                         */
/* ==================================================================== */

const $ = (s) => document.querySelector(s);
const el = (t, a, kids) => {
  const n = document.createElement(t);
  if (a) for (const k in a) { if (k === 'class') n.className = a[k]; else if (k === 'html') n.innerHTML = a[k]; else n.setAttribute(k, a[k]); }
  if (kids) for (const c of [].concat(kids)) n.append(c);
  return n;
};
const svg = (t, a) => {
  const n = document.createElementNS('http://www.w3.org/2000/svg', t);
  if (a) for (const k in a) n.setAttribute(k, a[k]);
  return n;
};
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const num = (n) => (typeof n === 'number' && isFinite(n)) ? n.toLocaleString('en-US') : null;

/** "not available" is a first-class rendering, distinct from 0. */
const NA = (why) => `<span class="na" title="${esc(why || 'field absent from the API response')}">not available</span>`;

/** Render a value that MIGHT legitimately be zero -- but only if `hasData`
 *  is true. When hasData is false a 0 is "we could not look", not "none". */
function count(v, hasData, why) {
  if (v == null) return NA(why);
  if (!hasData) return `<span class="na" title="${esc(why || 'no data was reported for this measurement')}">no data</span>`;
  return esc(num(v));
}

function tri(v, labels) {
  // Tri-state booleans (staticReachable, probeInstalled, runtimeReachable):
  // null is "unknown" and must NEVER be rendered as false.
  if (v === true) return `<b>${esc(labels.t)}</b>`;
  if (v === false) return `<b>${esc(labels.f)}</b>`;
  return `<span class="na" title="null in the API response: nothing is known either way; never read as false">${esc(labels.n)}</span>`;
}

/** Durations: the API does NOT declare a unit for tier-2 percentiles. The
 *  agent records System.nanoTime() deltas, so these are nanoseconds -- but
 *  because the API is silent we always keep the raw number reachable. */
function dur(v) {
  if (v == null) return NA('percentile absent');
  if (v < 1000) return `${v}ns`;
  if (v < 1e6) return `${(v / 1e3).toFixed(v < 1e4 ? 2 : 1)}µs`;
  return `${(v / 1e6).toFixed(2)}ms`;
}

const shortDesc = (d) => {
  if (!d) return '';
  const m = /^\((.*)\)(.*)$/.exec(d);
  if (!m) return d;
  const args = m[1] ? m[1].replace(/L([^;]*);/g, (_, c) => c.split('/').pop()).replace(/([BCDFIJSZ])/g, '$1') : '';
  return '(' + (m[1] ? args : '') + ')';
};
const mkey = (cls, idx) => cls + '#' + idx;
const pkgOf = (cls) => { const i = cls.lastIndexOf('.'); return i < 0 ? '' : cls.slice(0, i); };
const simpleName = (cls) => cls.slice(cls.lastIndexOf('.') + 1);

/* ==================================================================== */
/* API client -- every call reports ok/status, nothing throws            */
/* ==================================================================== */

/* Concurrency gate. `ax-server` is a stdlib ThreadingHTTPServer, and
 * socketserver's default `request_queue_size` is 5 -- a burst of 9 parallel
 * GETs gets connections REFUSED at the listen backlog, which then shows up as
 * a spurious "not available". A UI that invents missing data because it was
 * impatient is exactly the failure mode this project keeps finding, so the
 * fan-out is capped instead. */
const GATE = { max: 4, active: 0, waiting: [] };
function acquire() {
  if (GATE.active < GATE.max) { GATE.active++; return Promise.resolve(); }
  return new Promise((res) => GATE.waiting.push(res));
}
function release() {
  const next = GATE.waiting.shift();
  if (next) next(); else GATE.active--;
}

async function api(route, opts) {
  await acquire();
  try { return await api_(route, opts); } finally { release(); }
}

async function api_(route, opts) {
  const url = API_BASE + route;
  let r, body = null, err = null;
  try {
    r = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (e) {
    const res = { ok: false, status: 0, data: null, error: 'network error: ' + e.message, route };
    if (!(opts && opts.quiet)) S.sources.push({ route, status: 0, ok: false, note: res.error });
    return res;
  }
  const text = await r.text();
  if (text) { try { body = JSON.parse(text); } catch (e) { err = 'response was not JSON (' + text.slice(0, 120) + ')'; } }
  const ok = r.ok && !err;
  const res = {
    ok, status: r.status, data: ok ? body : null, route,
    error: ok ? null : (err || (body && body.error) || ('HTTP ' + r.status)),
  };
  if (!(opts && opts.quiet)) {
    S.sources.push({
      route, status: r.status, ok,
      note: ok ? describeSize(body) : res.error,
    });
  }
  return res;
}

function describeSize(b) {
  if (Array.isArray(b)) return b.length + ' item(s)';
  if (b && typeof b === 'object') return Object.keys(b).length + ' field(s)';
  return typeof b;
}

/* ==================================================================== */
/* load                                                                 */
/* ==================================================================== */

async function loadBuilds() {
  const r = await api('/v1/builds');
  S.api.builds = r;
  if (!r.ok) { fatal(`<b>/v1/builds failed (${esc(r.error)}).</b> The UI has no data at all. This is not an empty codebase &mdash; it is a failed request. Check that the collector is running and that this page is served from the same origin (or started via <code>dev-serve.sh</code>).`); return false; }
  const list = Array.isArray(r.data) ? r.data : [];
  S.builds = list;
  const sel = $('#build-select');
  sel.innerHTML = '';
  if (!list.length) {
    sel.append(el('option', { value: '' }, '(no builds)'));
    fatal('<b>/v1/builds returned an empty list.</b> No JVM has reported an observation window yet, so there is nothing to draw. An empty canvas here means <b>no data ingested</b>, never "no dead code" and never "no code".');
    return false;
  }
  for (const b of list) sel.append(el('option', { value: b }, b));
  S.build = QS.get('build') && list.includes(QS.get('build')) ? QS.get('build') : list[list.length - 1];
  sel.value = S.build;
  return true;
}

async function loadBuild() {
  const b = encodeURIComponent(S.build);
  S.sources = S.sources.filter((s) => s.route === '/v1/builds');
  clearFatal();
  note('loading ' + S.build + '…');

  const [summary, gaps, hotMethods, hotPaths, coverage, dead, health, exceptions, suppressions] = await Promise.all([
    api(`/v1/builds/${b}/summary`),
    api(`/v1/builds/${b}/instrumentation-gaps?limit=${LIMITS.classEnum}`),
    api(`/v1/builds/${b}/hot-methods?limit=${LIMITS.hotMethods}&sinceDays=${SINCE_DAYS}`),
    api(`/v1/builds/${b}/hot-paths?limit=${LIMITS.hotPaths}`),
    api(`/v1/builds/${b}/coverage-windows`),
    api(`/v1/builds/${b}/dead-candidates?limit=${LIMITS.deadCandidates}`),
    api(`/v1/builds/${b}/agent-health`),
    // BUG #24 and BUG #28. Both routes are newer than some deployed servers,
    // so a 404 here is an OLD SERVER, not an absence of exceptions or of
    // suppression rules -- and it is reported as exactly that.
    api(`/v1/builds/${b}/exception-classes?limit=50&sinceDays=${SINCE_DAYS}`),
    api('/v1/suppressions'),
  ]);
  const [proposals, efp] = await Promise.all([
    api(`/v1/builds/${b}/proposals`),
    api('/v1/effective-false-positives'),
  ]);
  Object.assign(S.api, { summary, gaps, hotMethods, hotPaths, coverage, dead, health, exceptions, suppressions, proposals, efp });

  /* ---- class enumeration ------------------------------------------ *
   * There is no "list all classes" route. `instrumentation-gaps` is the
   * only bulk route that names every class in the build, so that is the
   * enumerator -- and it is CAPPED (`classesListed` vs `classes`), so a
   * truncation is reported rather than silently drawn as a small build. */
  let classNames = [];
  let enumTruncated = 0;
  if (gaps.ok && Array.isArray(gaps.data.byClass)) {
    classNames = gaps.data.byClass.map((r) => r['class']).filter(Boolean);
    const total = gaps.data.classes;
    if (typeof total === 'number' && total > classNames.length) enumTruncated = total - classNames.length;
  }

  /* ---- verdicts ---------------------------------------------------- *
   * `verdicts` requires class= | package= | file=. We query the minimal
   * set of package prefixes that covers every enumerated class, then
   * check the result actually covers them. */
  S.verdicts = []; S.api.verdicts = [];
  const prefixes = coveringPackages(classNames);
  const bare = classNames.filter((c) => !c.includes('.'));
  const reqs = prefixes.map((p) => api(`/v1/builds/${b}/verdicts?package=${encodeURIComponent(p)}`))
    .concat(bare.map((c) => api(`/v1/builds/${b}/verdicts?class=${encodeURIComponent(c)}`)));
  const results = await Promise.all(reqs);
  S.api.verdicts = results;
  const seen = new Set();
  for (const r of results) {
    if (!r.ok || !Array.isArray(r.data)) continue;
    for (const v of r.data) {
      const k = v['class'] + '#' + v.method + v.desc;
      if (seen.has(k)) continue;
      seen.add(k); S.verdicts.push(v);
    }
  }
  S.byKey = new Map();
  for (const v of S.verdicts) if (v.probeIdx != null) S.byKey.set(mkey(v['class'], v.probeIdx), v);

  /* did the package queries actually cover the enumerated classes? */
  const gotClasses = new Set(S.verdicts.map((v) => v['class']));
  const missing = classNames.filter((c) => !gotClasses.has(c));

  /* ---- tier-2 ------------------------------------------------------ */
  S.tier2 = new Map();
  if (hotMethods.ok && Array.isArray(hotMethods.data)) {
    for (const row of hotMethods.data) S.tier2.set(mkey(row['class'], row.idx), row);
  }

  /* ---- runtime edges ----------------------------------------------- */
  S.runtimeEdges = [];
  if (hotPaths.ok && Array.isArray(hotPaths.data.paths)) {
    for (const p of hotPaths.data.paths) {
      if (!p.caller || !p.callee) continue;
      S.runtimeEdges.push({
        from: p.caller, to: p.callee,
        sampledObservations: p.sampledObservations,
        edgesSampleRate: p.edgesSampleRate,
        estimatedCalls: p.estimatedCalls,
        windows: p.windows, firstSeen: p.firstSeen, lastSeen: p.lastSeen,
      });
    }
  }
  S.staticEdges = []; S.staticLoaded = false; S.staticProgress = null;

  /* ---- report what we could not get -------------------------------- */
  const problems = [];
  if (!summary.ok) problems.push(`<code>summary</code> ${esc(summary.error)}`);
  if (!gaps.ok) problems.push(`<code>instrumentation-gaps</code> ${esc(gaps.error)} &mdash; this is the class enumerator, so the graph may be missing classes entirely`);
  if (!hotMethods.ok) problems.push(`<code>hot-methods</code> ${esc(hotMethods.error)} &mdash; no calls / error rate / percentiles anywhere`);
  if (!hotPaths.ok) problems.push(`<code>hot-paths</code> ${esc(hotPaths.error)} &mdash; <b>no runtime edges will be drawn; that is a failed request, not an absence of calls</b>`);
  if (!coverage.ok) problems.push(`<code>coverage-windows</code> ${esc(coverage.error)}`);
  if (!dead.ok) problems.push(`<code>dead-candidates</code> ${esc(dead.error)}`);
  if (!health.ok) problems.push(`<code>agent-health</code> ${esc(health.error)}`);
  if (!exceptions.ok) problems.push(`<code>exception-classes</code> ${esc(exceptions.error)} &mdash; exception <b>type names</b> are unavailable build-wide (error <i>counts</i> are unaffected). Not "no exceptions".`);
  if (!suppressions.ok) problems.push(`<code>/v1/suppressions</code> ${esc(suppressions.error)} &mdash; the suppression list <b>cannot be audited</b>, so a method missing from the candidates cannot be distinguished from one a pattern hid.`);
  const failedVerdicts = results.filter((r) => !r.ok);
  if (failedVerdicts.length) problems.push(`${failedVerdicts.length} of ${results.length} <code>verdicts</code> queries failed (${esc(failedVerdicts[0].error)})`);
  if (enumTruncated) problems.push(`the class list from <code>instrumentation-gaps</code> was truncated: ${enumTruncated} class(es) beyond <code>limit=${LIMITS.classEnum}</code> are <b>not drawn</b>`);
  if (missing.length) problems.push(`${missing.length} enumerated class(es) returned no verdict rows (e.g. <code>${esc(missing.slice(0, 3).join(', '))}</code>) &mdash; they are <b>absent from the graph</b>`);
  if (hotPaths.ok && Array.isArray(hotPaths.data.paths) && hotPaths.data.paths.length >= LIMITS.hotPaths) {
    problems.push(`<code>hot-paths</code> returned exactly <code>limit=${LIMITS.hotPaths}</code> rows: it is a <b>ranked top-N</b>, so edges below the cut are <b>not drawn</b> and their absence means nothing`);
  }
  S.problems = problems;

  if (!S.verdicts.length) {
    fatal('<b>No verdict rows were returned for this build.</b> ' +
      (gaps.ok ? 'The class enumerator worked, so this is either an empty manifest or a failing <code>verdicts</code> query &mdash; see the <b>data sources</b> tab. ' : '') +
      'The canvas is empty because the request produced nothing, <b>not</b> because the build has no code.');
  }

  buildTree();
  if (S.methodNodes.length > LIMITS.autoCollapseOver) {
    for (const n of S.nodesById.values()) if (n.kind === 'class') S.collapsed.add(n.id);
    problems.push(`${S.methodNodes.length} methods: every class starts <b>collapsed</b> for legibility. Expand a class to see its methods.`);
  }
  render();
  fitToView();
  renderCaveats();
  renderDead();
  renderEvidence();
  renderSources();
  renderDetail();
  clearNote();
}

/** Minimal set of `package=` prefixes covering every class name.
 *  Grouped by first segment, then the longest common segment-wise prefix of
 *  each group -- so a build spanning com.x and io.y costs two requests, not
 *  one per package. */
function coveringPackages(classNames) {
  const groups = new Map();
  for (const c of classNames) {
    if (!c.includes('.')) continue;              // default package -> class= query
    const segs = c.split('.'); segs.pop();       // drop the class name
    const g = segs[0];
    if (!groups.has(g)) groups.set(g, segs.slice());
    else {
      const cur = groups.get(g);
      let i = 0; while (i < cur.length && i < segs.length && cur[i] === segs[i]) i++;
      groups.set(g, cur.slice(0, Math.max(1, i)));
    }
  }
  return [...groups.values()].map((s) => s.join('.'));
}

/* ==================================================================== */
/* tree: package -> class -> method                                     */
/* ==================================================================== */

function buildTree() {
  const root = { id: '@root', kind: 'pkg', label: '(build)', full: '', children: [], parent: null };
  const pkgIndex = new Map([['', root]]);
  S.nodesById = new Map([[root.id, root]]);

  const pkgNode = (full) => {
    if (pkgIndex.has(full)) return pkgIndex.get(full);
    const i = full.lastIndexOf('.');
    const parent = pkgNode(i < 0 ? '' : full.slice(0, i));
    const n = { id: 'p:' + full, kind: 'pkg', label: i < 0 ? full : full.slice(i + 1), full, children: [], parent };
    parent.children.push(n); pkgIndex.set(full, n); S.nodesById.set(n.id, n);
    return n;
  };

  const classes = new Map();
  for (const v of S.verdicts) {
    const cn = v['class'];
    let cnode = classes.get(cn);
    if (!cnode) {
      const parent = pkgNode(pkgOf(cn));
      cnode = { id: 'c:' + cn, kind: 'class', label: simpleName(cn), full: cn, children: [], parent };
      parent.children.push(cnode); classes.set(cn, cnode); S.nodesById.set(cnode.id, cnode);
    }
    const id = 'm:' + cn + '#' + v.method + v.desc;
    const t2 = v.probeIdx != null ? S.tier2.get(mkey(cn, v.probeIdx)) : undefined;
    const m = {
      id, kind: 'method', label: v.method + shortDesc(v.desc), full: cn + '#' + v.method + v.desc,
      parent: cnode, children: [], verdict: v, tier2: t2 || null, cls: cn, idx: v.probeIdx,
      status: STATUSES.includes(v.status) ? v.status : 'UNKNOWN',
      silence: SILENT_ELIGIBILITY.has(v.eligibility),
    };
    cnode.children.push(m); S.nodesById.set(id, m);
  }

  /* compress package chains that have exactly one child and no classes:
     io -> auxin -> demo becomes one box labelled io.auxin.demo */
  const compress = (n) => {
    while (n.kind === 'pkg' && n.children.length === 1 && n.children[0].kind === 'pkg') {
      const only = n.children[0];
      S.nodesById.delete(only.id);
      n.children = only.children;
      for (const c of n.children) c.parent = n;
      n.label = (n.full ? n.label + '.' : '') + only.label;
      n.full = only.full;
      n.id = 'p:' + only.full;
      S.nodesById.set(n.id, n);
    }
    for (const c of n.children) if (c.kind !== 'method') compress(c);
  };
  compress(root);

  const sortKids = (n) => {
    n.children.sort((a, b) => {
      if (a.kind !== b.kind) return a.kind === 'pkg' ? -1 : a.kind === 'class' ? -1 : 1;
      return a.label.localeCompare(b.label);
    });
    for (const c of n.children) sortKids(c);
  };
  sortKids(root);

  S.tree = root;
  S.methodNodes = [...S.nodesById.values()].filter((n) => n.kind === 'method');
  for (const n of S.nodesById.values()) if (n.kind !== 'method') n.agg = aggregate(n);
}

function aggregate(n) {
  const a = { methods: 0, byStatus: {}, silence: 0, calls: 0, callsKnown: 0, errors: 0, inObs: 0, inEdges: 0, rates: new Set() };
  const walk = (x) => {
    if (x.kind === 'method') {
      a.methods++;
      a.byStatus[x.status] = (a.byStatus[x.status] || 0) + 1;
      if (x.silence) a.silence++;
      if (x.tier2) { a.callsKnown++; a.calls += x.tier2.calls || 0; a.errors += x.tier2.errors || 0; }
      const v = x.verdict;
      a.inEdges += v.runtimeInboundEdges || 0;
      a.inObs += v.runtimeInboundSampledObservations || 0;
      if (v.edgesSampleRate != null) a.rates.add(v.edgesSampleRate);
      return;
    }
    for (const c of x.children) walk(c);
  };
  walk(n);
  return a;
}

/* ==================================================================== */
/* filtering                                                            */
/* ==================================================================== */

function matches(n) {
  if (!S.filter) return false;
  const f = S.filter.toLowerCase();
  if (n.kind === 'method') return (n.cls + '#' + n.verdict.method + n.verdict.desc).toLowerCase().includes(f);
  return String(n.full || n.label).toLowerCase().includes(f);
}
function statusVisible(n) { return S.statusOn.has(n.status); }

/** A method is rendered when its status is enabled and (if "hide
 *  non-matching" is on) it or an ancestor matches the search. */
function methodVisible(n) {
  if (!statusVisible(n)) return false;
  if (S.hideNonMatching && S.filter) {
    if (matches(n)) return true;
    for (let p = n.parent; p; p = p.parent) if (matches(p)) return true;
    return false;
  }
  return true;
}
function visibleChildren(n) {
  return n.children.filter((c) => c.kind === 'method' ? methodVisible(c) : hasVisibleMethod(c));
}
function hasVisibleMethod(n) {
  if (n.kind === 'method') return methodVisible(n);
  return n.children.some(hasVisibleMethod);
}

/* ==================================================================== */
/* layout (hand-rolled nested box packing; no graph library)            */
/* ==================================================================== */

/* Character widths for the two mono fonts used in a box. Measured once so
   `measure()` and the renderer agree -- a box sized from one estimate and
   filled from another is how text ends up outside its rectangle. */
const CW_NAME = 6.7;   // .m-name  / .box-label : 11-12px mono, 600 weight
const CW_META = 5.75;  // .m-meta  / .box-sub   : 9.5-10px mono

/** The exact lines a method box will contain. Single source of truth. */
function methodLines(n) {
  const v = n.verdict, t2 = n.tier2;
  const meta = [];
  meta.push(shortStatus(n.status) + (n.silence ? ' · ' + v.eligibility : '') +
    (v.suppressed ? ' · suppressed' : '') + (v.publicApi ? ' · public-api' : ''));
  if (t2) {
    const p = t2.percentiles || {};
    const errRate = t2.calls ? ((t2.errors / t2.calls) * 100).toFixed(2) + '% err' : 'err n/a';
    meta.push(`${num(t2.calls)} calls · ${errRate}`);
    meta.push(`p50 ${durPlain(p.p50)} p90 ${durPlain(p.p90)} p99 ${durPlain(p.p99)}`);
  } else {
    meta.push('calls / latency: not available');
  }
  meta.push(v.runtimeEdgesReported
    ? `in ${v.runtimeInboundEdges} out ${v.runtimeOutboundEdges} · ${num(v.runtimeInboundSampledObservations)} obs${v.edgesSampleRate != null ? ' @1-in-' + v.edgesSampleRate : ''}`
    : 'edges: no data (tier never reported)');
  return { name: n.label, meta };
}

function methodBox(n) {
  const L = methodLines(n);
  /* +14, not +12: the clip budget below is (w - 12) and clip() floors, so a
     box sized to exactly the text width truncates its own last character. */
  const need = Math.max(L.name.length * CW_NAME, ...L.meta.map((m) => m.length * CW_META)) + 14;
  return {
    w: Math.max(MW_MIN, Math.min(MW_MAX, need)),
    h: MH_BASE + 11 * L.meta.length,
    lines: L,
  };
}

/** The two aggregate lines a COLLAPSED class box shows. */
function collapsedLines(n) {
  const a = n.agg;
  return [
    `${a.methods} methods · ` + STATUSES.filter((s) => a.byStatus[s]).map((s) => `${a.byStatus[s]} ${shortStatus(s)}`).join(' · '),
    (a.callsKnown
      ? `${num(a.calls)} calls (${a.callsKnown} tier-2)`
      : 'calls: not available (no tier-2 method)') +
    ` · ${num(a.inObs)} inbound obs${a.rates.size === 1 ? ' @1-in-' + [...a.rates][0] : (a.rates.size ? ' @MIXED' : '')}`,
  ];
}

/** The right-aligned summary on an EXPANDED container header. */
function containerRight(n) {
  const a = n.agg;
  return `${a.methods}m · ` + STATUSES.filter((s) => a.byStatus[s]).map((s) => `${a.byStatus[s]}${shortStatus(s)[0]}`).join(' ') +
    (a.silence ? ` · ${a.silence} silent` : '');
}
function containerLabel(n) {
  return n.kind === 'pkg' ? (n.full || '(default package)') : n.label;
}

function measure(n) {
  if (n.kind === 'method') { const b = methodBox(n); n.w = b.w; n.h = b.h; n.lines = b.lines; return; }
  const kids = visibleChildren(n);
  n.vkids = kids;
  /* header width: chevron + label, then a gap, then the right-aligned summary */
  const headW = 26 + containerLabel(n).length * CW_NAME + 14 + containerRight(n).length * CW_META + 10;
  if (n.kind === 'class' && S.collapsed.has(n.id)) {
    n.sub = collapsedLines(n);
    n.w = Math.max(200, Math.min(440, Math.max(26 + containerLabel(n).length * CW_NAME + 10,
      ...n.sub.map((s) => 27 + s.length * CW_META))));
    n.h = HDR + 12 + 11 * n.sub.length;
    n.rows = null;
    return;
  }
  if (!kids.length) {
    n.w = Math.max(190, headW); n.h = HDR + 26; n.rows = [];
    return;
  }
  kids.forEach(measure);
  const area = kids.reduce((s, k) => s + (k.w + GAP) * (k.h + GAP), 0);
  const maxW = Math.max(...kids.map((k) => k.w));
  const target = Math.max(maxW, Math.sqrt(area * 1.9));
  const rows = [];
  let cur = [], curW = 0;
  for (const k of kids) {
    if (cur.length && curW + k.w + GAP > target) { rows.push(cur); cur = []; curW = 0; }
    cur.push(k); curW += k.w + GAP;
  }
  if (cur.length) rows.push(cur);
  n.rows = rows;
  const rowW = rows.map((r) => r.reduce((s, k) => s + k.w, 0) + GAP * (r.length - 1));
  const rowH = rows.map((r) => Math.max(...r.map((k) => k.h)));
  n.w = Math.max(Math.max(...rowW) + PAD * 2, headW);
  n.h = HDR + rowH.reduce((s, h) => s + h + GAP, 0) - GAP + PAD;
}

function place(n, x, y) {
  n.x = x; n.y = y;
  if (n.kind === 'method' || !n.rows || !n.rows.length) return;
  let cy = y + HDR;
  for (const row of n.rows) {
    let cx = x + PAD;
    const h = Math.max(...row.map((k) => k.h));
    for (const k of row) { place(k, cx, cy); cx += k.w + GAP; }
    cy += h + GAP;
  }
}

function layout() {
  measure(S.tree);
  place(S.tree, 0, 0);
}

/* ==================================================================== */
/* edge resolution                                                      */
/* ==================================================================== */

/** The box that VISUALLY CONTAINS a (class, idx) on screen, or null.
 *
 *  Only two things can stand in for a method: the method's own box, or the
 *  collapsed class box its methods were rolled up into. A method hidden by a
 *  filter is deliberately NOT attributed to an ancestor -- drawing an edge to
 *  a package box whose visible contents do not include the real endpoint
 *  would invent a relationship. Those edges are counted as unresolved and
 *  reported in the caveat strip instead. */
function renderedNodeFor(cls, idx) {
  const v = S.byKey.get(mkey(cls, idx));
  if (!v) return null;                    // endpoint not in this build's manifest
  const m = S.nodesById.get('m:' + cls + '#' + v.method + v.desc);
  return (m && m.proxy) || null;
}

function markRendered() {
  for (const n of S.nodesById.values()) { n.rendered = false; n.proxy = null; }
  const walk = (n) => {
    n.rendered = true;
    if (n.kind === 'method') { n.proxy = n; return; }
    if (n.kind === 'class' && S.collapsed.has(n.id)) {
      for (const c of n.children) if (methodVisible(c)) c.proxy = n; // rolled up
      return;
    }
    for (const c of (n.vkids || [])) walk(c);
  };
  walk(S.tree);
}

function resolveEdges(list, kind) {
  const out = new Map(); // "a->b" -> aggregate
  let hidden = 0, unresolved = 0;
  for (const e of list) {
    const a = renderedNodeFor(e.from.class || e.from.cls, e.from.idx);
    const b = renderedNodeFor(e.to.class || e.to.cls, e.to.idx);
    if (!a || !b) { unresolved++; continue; }
    if (a === b) { hidden++; continue; }
    const k = a.id + ' ' + b.id;
    let agg = out.get(k);
    if (!agg) {
      agg = { a, b, kind, n: 0, obs: 0, obsKnown: false, rates: new Set(), resolutions: new Set(), raw: [] };
      out.set(k, agg);
    }
    agg.n++; agg.raw.push(e);
    if (kind === 'rt') {
      if (typeof e.sampledObservations === 'number') { agg.obs += e.sampledObservations; agg.obsKnown = true; }
      if (e.edgesSampleRate != null) agg.rates.add(e.edgesSampleRate);
    } else if (e.resolution) agg.resolutions.add(e.resolution);
  }
  return { edges: [...out.values()], hidden, unresolved };
}

/* ---- geometry: clip a centre-to-centre line at each box border ------ */
function anchor(node, tx, ty) {
  const cx = node.x + node.w / 2, cy = node.y + node.h / 2;
  let dx = tx - cx, dy = ty - cy;
  if (!dx && !dy) return { x: cx, y: cy };
  const sx = dx ? (node.w / 2) / Math.abs(dx) : Infinity;
  const sy = dy ? (node.h / 2) / Math.abs(dy) : Infinity;
  const s = Math.min(sx, sy);
  return { x: cx + dx * s, y: cy + dy * s };
}
function edgePath(a, b, spread) {
  const ac = { x: a.x + a.w / 2, y: a.y + a.h / 2 }, bc = { x: b.x + b.w / 2, y: b.y + b.h / 2 };
  const p1 = anchor(a, bc.x, bc.y), p2 = anchor(b, ac.x, ac.y);
  const mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
  const dx = p2.x - p1.x, dy = p2.y - p1.y, len = Math.hypot(dx, dy) || 1;
  const off = (spread || 0) + Math.min(60, len * 0.12);
  const qx = mx - (dy / len) * off, qy = my + (dx / len) * off;
  return { d: `M ${p1.x.toFixed(1)} ${p1.y.toFixed(1)} Q ${qx.toFixed(1)} ${qy.toFixed(1)} ${p2.x.toFixed(1)} ${p2.y.toFixed(1)}`, mid: { x: (mx + qx) / 2, y: (my + qy) / 2 } };
}

/* ==================================================================== */
/* render                                                               */
/* ==================================================================== */

function render() {
  if (!S.tree) return;
  layout();
  markRendered();

  const Lp = $('#layer-pkg'), Lc = $('#layer-class'), Le = $('#layer-edges'), Lm = $('#layer-method'), Lh = $('#layer-highlight');
  for (const L of [Lp, Lc, Le, Lm, Lh]) L.textContent = '';

  const anyMethod = S.methodNodes.some(methodVisible);
  const emptyBox = $('#canvas-empty');
  if (!anyMethod) {
    emptyBox.hidden = false;
    emptyBox.innerHTML = `<div>${S.methodNodes.length
      ? `<b>Nothing matches the current filter.</b><br>${S.methodNodes.length} method(s) are loaded; the status filter and/or the search box are hiding all of them. This is a <b>filter</b>, not an absence of data.`
      : `<b>No methods were loaded.</b> See the <b>data sources</b> tab for the routes that were called and what each returned. An empty canvas here means the requests produced nothing, not that the build is empty.`}</div>`;
  } else emptyBox.hidden = true;

  /* boxes */
  const drawBox = (n) => {
    const layer = n.kind === 'pkg' ? Lp : n.kind === 'class' ? Lc : Lm;
    if (n.kind === 'method') { layer.append(methodG(n)); return; }
    layer.append(containerG(n));
    if (!(n.kind === 'class' && S.collapsed.has(n.id))) for (const c of (n.vkids || [])) drawBox(c);
  };
  drawBox(S.tree);

  /* edges */
  const rt = resolveEdges(S.runtimeEdges, 'rt');
  const st = S.staticLoaded ? resolveEdges(S.staticEdges, 'st') : { edges: [], hidden: 0, unresolved: 0 };
  S.edgeStats = { rt, st };

  const seenPair = new Map();
  const all = st.edges.concat(rt.edges); // static under runtime
  for (const e of all) {
    const pk = [e.a.id, e.b.id].sort().join(' ');
    const idx = seenPair.get(pk) || 0; seenPair.set(pk, idx + 1);
    const spread = (idx % 2 ? -1 : 1) * Math.ceil(idx / 2) * 22;
    const { d, mid } = edgePath(e.a, e.b, spread);
    e._d = d; e._mid = mid;
    const p = svg('path', { d, class: e.kind === 'rt' ? 'e-rt' : 'e-st' });
    p.setAttribute('stroke-width', e.kind === 'rt' ? strokeFor(e) : 1.2);
    const t = svg('title');
    t.textContent = edgeTitle(e);
    p.append(t);
    Le.append(p);
  }
  /* label only the heaviest runtime edges: a label on every edge is noise,
     and an unlabelled edge must never look like an exact call count. */
  const labelled = rt.edges.slice().sort((a, b) => b.obs - a.obs).slice(0, 14);
  for (const e of labelled) {
    if (!e.obsKnown) continue;
    const txt = `${num(e.obs)} obs @1-in-${e.rates.size === 1 ? [...e.rates][0] : '?'}`;
    Le.append(edgeLabel(e._mid, txt, 'e-label'));
  }

  highlightSelection();
  applyZoom();
  updateEdgeCaveat();
}

function strokeFor(e) {
  if (!e.obsKnown || !e.obs) return 1.2;
  const max = Math.max(...S.runtimeEdges.map((x) => x.sampledObservations || 0), 1);
  return 1.1 + 3.2 * Math.sqrt(e.obs / max);
}

function edgeTitle(e) {
  if (e.kind === 'st') {
    return `STATIC edge (manifest callEdges)\n${e.a.full} -> ${e.b.full}\n` +
      `resolution: ${[...e.resolutions].join(', ') || 'unreported'}\n` +
      'Build-time whole-program scan. Over-approximates (a call site need never run) AND ~61% unsound (A5). Not evidence that the call happened.';
  }
  const rate = e.rates.size === 1 ? '1-in-' + [...e.rates][0] : (e.rates.size ? 'MIXED rates: ' + [...e.rates].join(', ') : 'undeclared');
  return `RUNTIME edge (sampled)\n${e.a.full} -> ${e.b.full}\n` +
    `sampledObservations: ${e.obsKnown ? num(e.obs) : 'not available'}  (NOT calls)\n` +
    `edgesSampleRate: ${rate}\n` +
    (e.n > 1 ? `aggregated from ${e.n} method-level edges\n` : '') +
    'Presence proves the call happened. Absence of an edge proves nothing.';
}

function edgeLabel(mid, text, cls) {
  const g = svg('g', { class: 'edge-label-g' });
  const w = text.length * 5.45 + 8;
  g.append(svg('rect', { x: mid.x - w / 2, y: mid.y - 7.5, width: w, height: 13, rx: 2, class: 'e-label-bg' }));
  const t = svg('text', { x: mid.x, y: mid.y + 2, class: cls, 'text-anchor': 'middle' });
  t.textContent = text;
  g.append(t);
  return g;
}

function containerG(n) {
  const g = svg('g', {
    class: 'box ' + (n.kind === 'pkg' ? 'pkg' : 'cls') + (S.selected === n.id ? ' selected' : '') +
      (S.filter && matches(n) ? ' match' : '') + (S.filter && !S.hideNonMatching && !subtreeMatches(n) ? ' dim' : ''),
    'data-id': n.id, role: 'button', tabindex: '0',
  });
  g.append(svg('rect', { x: n.x, y: n.y, width: n.w, height: n.h, rx: 5, class: 'box-rect' }));
  const collapsed = n.kind === 'class' && S.collapsed.has(n.id);
  const chev = svg('text', { x: n.x + 8, y: n.y + 16, class: 'box-chev' });
  chev.textContent = collapsed ? '▸' : '▾';
  g.append(chev);
  const lab = svg('text', { x: n.x + 20, y: n.y + 16, class: 'box-label' });
  lab.textContent = clip(containerLabel(n), n.w - 28, CW_NAME);
  g.append(lab);

  const a = n.agg;
  if (collapsed) {
    let y = n.y + 33;
    for (const s of (n.sub || collapsedLines(n))) {
      const t = svg('text', { x: n.x + 20, y, class: 'box-sub' });
      t.textContent = clip(s, n.w - 24, CW_META);
      g.append(t); y += 11;
    }
  } else {
    const t = svg('text', { x: n.x + n.w - 8, y: n.y + 16, class: 'box-sub', 'text-anchor': 'end' });
    t.textContent = containerRight(n);
    g.append(t);
  }
  const ttl = svg('title');
  ttl.textContent = (n.kind === 'pkg' ? 'package ' : 'class ') + n.full + '\n' +
    `${a.methods} method(s): ` + STATUSES.filter((s) => a.byStatus[s]).map((s) => `${a.byStatus[s]} ${s}`).join(', ') +
    (a.silence ? `\n${a.silence} method(s) whose zero probe bit is SILENCE, not an observation (bug #18 / C4)` : '') +
    '\nclick to ' + (collapsed ? 'expand' : (n.kind === 'class' ? 'collapse' : 'collapse its classes'));
  g.append(ttl);
  g.setAttribute('aria-label', `${n.kind === 'pkg' ? 'package' : 'class'} ${n.full} ${a.methods} methods ${collapsed ? 'collapsed' : 'expanded'}`);
  return g;
}

function shortStatus(s) {
  return s === 'LIVE' ? 'LIVE' : s === 'DEAD_CANDIDATE' ? 'DEAD' : s === 'UNKNOWN' ? 'UNK' : 'NDO';
}
function subtreeMatches(n) {
  if (matches(n)) return true;
  if (n.kind === 'method') return false;
  return n.children.some(subtreeMatches);
}

function methodG(n) {
  const v = n.verdict, t2 = n.tier2;
  const g = svg('g', {
    class: 'box m-' + n.status + (n.silence ? ' m-silence' : '') + (S.selected === n.id ? ' selected' : '') +
      (S.filter && matches(n) ? ' match' : '') + (S.filter && !S.hideNonMatching && !matches(n) ? ' dim' : ''),
    'data-id': n.id, role: 'button', tabindex: '0',
  });
  g.append(svg('rect', { x: n.x, y: n.y, width: n.w, height: n.h, rx: 4, class: 'm-rect' }));
  if (n.silence) g.append(svg('rect', { x: n.x + 1, y: n.y + 1, width: n.w - 2, height: n.h - 2, rx: 3, class: 'm-hatch' }));

  const L = n.lines || methodLines(n);
  let y = n.y + 14;
  const name = svg('text', { x: n.x + 6, y, class: 'm-name' });
  name.textContent = clip(L.name, n.w - 12, CW_NAME);
  g.append(name); y += 12;
  for (const m of L.meta) {
    const t = svg('text', { x: n.x + 6, y, class: 'm-meta' + (/not available|no data/.test(m) ? ' m-na' : '') });
    t.textContent = clip(m, n.w - 12, CW_META);
    g.append(t); y += 11;
  }

  const ttl = svg('title');
  ttl.textContent = methodTooltip(n);
  g.append(ttl);
  g.setAttribute('aria-label', `method ${n.cls}#${v.method}${v.desc} status ${v.status} eligibility ${v.eligibility}`);
  return g;
}

function durPlain(v) {
  if (v == null) return 'n/a';
  if (v < 1000) return v + 'ns';
  if (v < 1e6) return (v / 1e3).toFixed(1) + 'µs';
  return (v / 1e6).toFixed(2) + 'ms';
}
function clip(s, px, per) {
  const max = Math.max(4, Math.floor(px / per));
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

function methodTooltip(n) {
  const v = n.verdict, t2 = n.tier2;
  const L = [`${n.cls}#${v.method}${v.desc}`, `status: ${v.status}`, `eligibility: ${v.eligibility}`];
  if (n.silence) L.push(v.eligibility === 'NO_PROBE_INSTALLED'
    ? 'NO PROBE WAS EVER INSTALLED at this index. Its zero bit is SILENCE -- nothing could write it. This is a configuration fact, not "never ran" (bug #18).'
    : 'Probes were STRIPPED by our own tier-1b optimisation (C4). Its zero bit is not an observation.');
  if (v.status === 'UNKNOWN') L.push('UNKNOWN = not seen running AND not provably dead. Different from NOT_DYNAMICALLY_OBSERVABLE, which could never have been seen.');
  if (v.status === 'NOT_DYNAMICALLY_OBSERVABLE') L.push('Could never be observed dynamically (C51): no distinct frame exists. "Unobserved" carries no information here.');
  L.push(`windowDays: ${v.windowDays}`);
  if (v.phasesMissing && v.phasesMissing.length) L.push('phasesMissing: ' + v.phasesMissing.join(', ') + ' -- while this is non-empty a DEAD_CANDIDATE cannot be produced at all.');
  L.push(t2 ? `calls ${num(t2.calls)} errors ${num(t2.errors)} p50 ${durPlain((t2.percentiles || {}).p50)}` : 'calls/latency: not available (not a tier-2 method)');
  L.push(v.runtimeEdgesReported
    ? `observed inbound edges ${v.runtimeInboundEdges} / ${num(v.runtimeInboundSampledObservations)} sampledObservations${v.edgesSampleRate != null ? ' at 1-in-' + v.edgesSampleRate : ''} -- NOT calls`
    : 'runtime edge tier never reported for this build: the zeroes are "no data", not "no callers"');
  L.push('click for the full reason list');
  return L.join('\n');
}

/* ---- selection highlight ------------------------------------------- */
function highlightSelection() {
  const Lh = $('#layer-highlight');
  Lh.textContent = '';
  if (!S.selected || !S.edgeStats) return;
  const sel = S.nodesById.get(S.selected);
  if (!sel) return;
  const touch = (e) => e.a === sel || e.b === sel ||
    isAncestor(sel, e.a) || isAncestor(sel, e.b);
  const hit = S.edgeStats.st.edges.concat(S.edgeStats.rt.edges).filter((e) => touch(e) && e._d);
  for (const e of hit) {
    const p = svg('path', { d: e._d, class: e.kind === 'rt' ? 'e-hi' : 'e-hi-st' });
    p.setAttribute('stroke-width', e.kind === 'rt' ? Math.max(2, strokeFor(e)) : 1.6);
    Lh.append(p);
  }
  /* Label only the heaviest few: labelling all of a hub's edges buries the
     boxes under text, and the tooltip on every path carries the full story. */
  const labelled = hit.filter((e) => e.kind === 'st' || e.obsKnown)
    .sort((a, b) => (b.obs || 0) - (a.obs || 0)).slice(0, 8);
  for (const e of labelled) {
    Lh.append(e.kind === 'rt'
      ? edgeLabel(e._mid, `${num(e.obs)} obs @1-in-${e.rates.size === 1 ? [...e.rates][0] : '?'}`, 'e-label')
      : edgeLabel(e._mid, `static · ${[...e.resolutions].join('/') || 'unreported'}`, 'e-label e-label-st'));
  }
  if (hit.length > labelled.length) {
    Lh.append(edgeLabel({ x: sel.x + sel.w / 2, y: sel.y - 9 },
      `${hit.length} edges touch this box · ${labelled.length} labelled · hover an edge for its count and rate`,
      'e-label'));
  }
}
function isAncestor(a, n) { for (let p = n; p; p = p.parent) if (p === a) return true; return false; }

/* ==================================================================== */
/* pan / zoom                                                           */
/* ==================================================================== */

function applyZoom() {
  $('#viewport').setAttribute('transform', `translate(${S.view.x.toFixed(2)} ${S.view.y.toFixed(2)}) scale(${S.view.k.toFixed(4)})`);
  $('#zoom-label').textContent = Math.round(S.view.k * 100) + '%';
  $('#layer-method').classList.toggle('tiny', S.view.k < 0.42);
  $('#layer-edges').classList.toggle('tiny', S.view.k < 0.35);
}
function fitToView() {
  if (!S.tree || S.tree.w == null) return;
  const wrap = $('#canvas-wrap');
  const W = wrap.clientWidth, H = wrap.clientHeight;
  const k = Math.max(0.06, Math.min(1.6, Math.min((W - 60) / (S.tree.w || 1), (H - 60) / (S.tree.h || 1))));
  S.view.k = k;
  S.view.x = (W - S.tree.w * k) / 2;
  S.view.y = (H - S.tree.h * k) / 2;
  applyZoom();
}
function zoomBy(f, cx, cy) {
  const wrap = $('#canvas-wrap');
  const px = cx == null ? wrap.clientWidth / 2 : cx;
  const py = cy == null ? wrap.clientHeight / 2 : cy;
  const k2 = Math.max(0.04, Math.min(6, S.view.k * f));
  S.view.x = px - (px - S.view.x) * (k2 / S.view.k);
  S.view.y = py - (py - S.view.y) * (k2 / S.view.k);
  S.view.k = k2;
  applyZoom();
}

/* ==================================================================== */
/* caveat strip                                                         */
/* ==================================================================== */

function renderCaveats() {
  const sum = S.api.summary && S.api.summary.ok ? S.api.summary.data : null;
  const cov = S.api.coverage && S.api.coverage.ok ? S.api.coverage.data : null;
  const gaps = S.api.gaps && S.api.gaps.ok ? S.api.gaps.data : null;

  /* --- edges / sample rate --- */
  const ce = $('#cav-edges');
  const re = sum && sum.runtimeEdges;
  if (!re) {
    ce.className = 'cav alarm';
    ce.innerHTML = 'runtime edges &mdash; <b>unknown</b>: <code>summary.runtimeEdges</code> not available, so nothing can be said about the sample rate';
  } else if (!re.reported) {
    ce.className = 'cav alarm';
    ce.innerHTML = 'runtime edges &mdash; <b>tier never reported</b> for this build (<code>edgesEnabled</code> defaults to false). Every edge count is <b>no data</b>, not zero calls.';
  } else {
    const rates = Object.keys(re.edgesSampleRates || {});
    ce.className = 'cav warn';
    ce.innerHTML = `runtime edges &mdash; <b>sampled ${rates.length === 1 ? '1-in-' + esc(rates[0]) : 'at MIXED rates ' + esc(rates.join(', ') || '?')}</b>; counts are <code>sampledObservations</code>, <b>not calls</b>. Presence proves a call; <b>absence proves nothing</b>.`;
  }

  /* --- phases --- */
  const cp = $('#cav-phases');
  const missing = (cov && cov.phasesMissing) || (sum && sum.phasesMissing) || null;
  if (!missing) { cp.className = 'cav alarm'; cp.innerHTML = 'phase coverage &mdash; ' + NA('phasesMissing absent from both summary and coverage-windows'); }
  else if (missing.length) {
    cp.className = 'cav alarm';
    cp.innerHTML = `phases missing (<b>${missing.length}</b>): <b>${esc(missing.join(', '))}</b> &mdash; while non-empty, <b>a DEAD_CANDIDATE cannot exist</b>`;
  } else { cp.className = 'cav ok'; cp.innerHTML = 'phase coverage &mdash; <b>complete</b>: ' + esc(((cov && cov.phasesCovered) || []).join(', ') || '(none required)'); }

  /* --- windows / non-production ---
   * `summary.windows` (BUG #22b) is the authoritative breakdown when the
   * server serves it; otherwise we count the coverage-windows rows. */
  const cw = $('#cav-windows');
  const sw = sum && sum.windows && typeof sum.windows === 'object' ? sum.windows : null;
  if (!sw && !cov) { cw.className = 'cav alarm'; cw.innerHTML = 'windows &mdash; ' + NA('neither summary.windows nor coverage-windows is available'); }
  else {
    const u = sw ? sw.usableAsDeathEvidence : (cov.usable || []).length;
    const x = sw ? sw.excluded : (cov.excluded || []).length;
    const nonProd = sw ? sw.nonProduction : (cov ? (cov.usable || []).concat(cov.excluded || []).filter((w) => w.production === false).length : null);
    const degraded = sw ? sw.degraded : (cov ? (cov.usable || []).concat(cov.excluded || []).filter((w) => w.degraded).length : null);
    const labels = sw && sw.nonProductionEnvironments ? Object.entries(sw.nonProductionEnvironments).map(([k, v]) => `${k}×${v}`).join(', ') : '';
    cw.className = (x || nonProd) ? 'cav warn' : 'cav';
    cw.innerHTML = `windows &mdash; <b>${esc(u)}</b> usable as death evidence, <b>${esc(x)}</b> excluded` +
      (nonProd ? `, <b>${esc(nonProd)}</b> <b>non-production</b>${labels ? ' (' + esc(labels) + ')' : ''} &mdash; <code>livenessEvidence:false</code>, <b>excluded from liveness and from death</b>` : '') +
      (degraded ? `, <b>${esc(degraded)}</b> degraded (never death evidence)` : '') +
      ` &middot; windowDays <b>${cov && cov.windowDays != null ? esc(cov.windowDays) : (sum && sum.windowDays != null ? esc(sum.windowDays) : '?')}</b>`;
  }

  /* --- probes / bug #18 --- */
  const cpr = $('#cav-probes');
  const inst = (sum && sum.instrumentation) || null;
  if (!inst) { cpr.className = 'cav alarm'; cpr.innerHTML = 'probe install mask &mdash; ' + NA('summary.instrumentation absent'); }
  else if (!inst.maskSupportedByStore) { cpr.className = 'cav alarm'; cpr.innerHTML = 'probe install mask &mdash; <b>not supported by this store</b>: an uninstrumented index is still indistinguishable from an unexecuted one (bug #18 gate is OFF)'; }
  else if (!inst.maskReported) { cpr.className = 'cav alarm'; cpr.innerHTML = 'probe install mask &mdash; <b>never reported</b> (pre-#18 agent): verdicts were computed without the gate, so a JVM with tier-1 disabled would read as dead code'; }
  else if (inst.observableButNeverInstrumented) {
    cpr.className = 'cav alarm';
    cpr.innerHTML = `probe install mask &mdash; <b>${esc(inst.observableButNeverInstrumented)}</b> observable method(s) <b>never instrumented</b>: SILENCE, never death evidence (bug #18)`;
  } else if (gaps && gaps.observableButNeverInstrumented === 0) {
    cpr.className = 'cav ok';
    cpr.innerHTML = 'probe install mask &mdash; <b>complete</b>: every observable method had a probe installed, so an unset bit is a real observation';
  } else { cpr.className = 'cav'; cpr.innerHTML = 'probe install mask &mdash; reported'; }

  updateEdgeCaveat();
}

function updateEdgeCaveat() {
  const holder = $('#cav-edges');
  if (!holder || !S.edgeStats) return;
  const { rt, st } = S.edgeStats;
  const extra = [];
  if (rt.hidden) extra.push(`${rt.hidden} runtime edge(s) are <b>inside collapsed boxes</b> and not drawn`);
  if (rt.unresolved) extra.push(`${rt.unresolved} runtime edge(s) have an endpoint that is <b>hidden by the filter or absent from the manifest</b> and are not drawn`);
  if (st.hidden) extra.push(`${st.hidden} static edge(s) inside collapsed boxes`);
  let tail = holder.querySelector('.edge-extra');
  if (!tail) { tail = document.createElement('span'); tail.className = 'edge-extra'; holder.append(tail); }
  tail.innerHTML = extra.length ? ' &middot; ' + extra.join(' &middot; ') : '';
}

/* ==================================================================== */
/* rail: detail                                                         */
/* ==================================================================== */

function renderDetail() {
  const pane = $('#tab-detail');
  const n = S.selected ? S.nodesById.get(S.selected) : null;
  if (!n) {
    pane.innerHTML = `<p class="hint">Click a <b>method</b> box for its verdict reasons, phases, callers and callees.<br>
      Click a <b>class</b> or <b>package</b> box to collapse or expand it.</p>
      <div class="note warn"><b>Two things this UI refuses to conflate.</b><br>
      <b>UNKNOWN</b> = a probe existed and never fired: the code was never seen running, and that is still not proof it is dead.<br>
      <b>NOT_DYNAMICALLY_OBSERVABLE</b> / <b>NO_PROBE_INSTALLED</b> (hatched) = nothing could ever have observed it. Its silence carries no information at all. Treating these as "never ran" is bug #18.</div>
      <div class="note"><b>Edge counts are <code>sampledObservations</code>, not calls.</b> Every count in this UI is printed with the sample rate that produced it. An edge that is missing is not evidence of anything.</div>`;
    return;
  }
  if (n.kind !== 'method') { renderContainerDetail(pane, n); return; }
  renderMethodDetail(pane, n);
}

function renderContainerDetail(pane, n) {
  const a = n.agg;
  const rows = STATUSES.map((s) => `<tr><td>${esc(s)}</td><td class="v">${a.byStatus[s] || 0}</td></tr>`).join('');
  pane.innerHTML = `
    <h2 class="rail-h">${esc(n.full || '(default package)')}</h2>
    <p class="hint">${n.kind === 'pkg' ? 'package' : 'class'} &middot; ${a.methods} method(s) &middot; ${S.collapsed.has(n.id) ? 'collapsed' : 'expanded'}</p>
    <h3 class="sec">status breakdown</h3>
    <table class="kv">${rows}</table>
    <h3 class="sec">aggregate runtime data</h3>
    <table class="kv">
      <tr><td>tier-2 methods (timed)</td><td class="v">${a.callsKnown}</td></tr>
      <tr><td>calls (tier-2 only)</td><td class="v">${a.callsKnown ? esc(num(a.calls)) : NA('no method in this subtree is a tier-2 (timed) method, so no call count exists')}</td></tr>
      <tr><td>errors (tier-2 only)</td><td class="v">${a.callsKnown ? esc(num(a.errors)) : NA('no tier-2 method here')}</td></tr>
      <tr><td>observed inbound edges</td><td class="v">${esc(num(a.inEdges))}</td></tr>
      <tr><td>inbound sampledObservations</td><td class="v">${esc(num(a.inObs))} <span class="rate">${a.rates.size === 1 ? '@1-in-' + [...a.rates][0] : (a.rates.size ? '@MIXED rates ' + [...a.rates].join(',') : '(rate not declared)')}</span></td></tr>
      <tr><td>methods whose silence is not evidence</td><td class="v">${a.silence}</td></tr>
    </table>
    <div class="note">Aggregates are sums of what was <b>reported</b>. <code>calls</code> exists only for tier-2 (boundary / <code>--tier2</code>) methods; everything else has no call count at all and is shown as <i>not available</i>, never as 0.</div>
    <div class="note warn"><code>sampledObservations</code> are observations in <b>sampled</b> traces, not calls. Summing across methods recorded at different sample rates produces a number with no single scale &mdash; the rate is printed above for exactly that reason.</div>`;
}

async function renderMethodDetail(pane, n) {
  const v = n.verdict, t2 = n.tier2;
  const reasons = (v.reasons || []).map((r, i) => {
    const c = r.indexOf(':');
    const rule = c > 0 ? r.slice(0, c) : r;
    const rest = c > 0 ? r.slice(c + 1) : '';
    return `<li><code>${esc(rule)}</code>${esc(rest)}</li>`;
  }).join('');

  const errRate = t2 && t2.calls ? ((t2.errors / t2.calls) * 100).toFixed(3) + '%' : null;
  const p = (t2 && t2.percentiles) || {};

  /* BUG #24: hot-methods carries the per-method breakdown as `errorClasses`
     (name -> count) plus `errorsAttributed` / `errorsUnattributed` /
     `errorTypesAvailable` / `errorTypesSource` / `errorReading`. Older
     servers omit the whole group -- which is "we have the count and not the
     names", never "zero exception types". */
  const ebc = t2 ? (t2.errorClasses || null) : null;
  let excHtml;
  if (!t2) {
    excHtml = `<div class="note">Exception classes: ${NA('this method is not a tier-2 (timed) method, so no error data exists for it at all')}</div>`;
  } else if (ebc && Object.keys(ebc).length) {
    excHtml = `<ul class="plain">` + Object.entries(ebc).map(([k, c]) =>
      `<li class="edgerow"><span class="who">${esc(k)}</span><span class="cnt">${esc(num(Number(c)))}</span></li>`).join('') +
      (t2.errorsUnattributed ? `<li class="edgerow"><span class="who na">unattributed &mdash; no name to give</span><span class="cnt">${esc(num(t2.errorsUnattributed))}</span></li>` : '') +
      `</ul>
      <table class="kv">
        <tr><td>attributed</td><td class="v">${count(t2.errorsAttributed, true)}</td></tr>
        <tr><td>unattributed</td><td class="v">${count(t2.errorsUnattributed, true)}</td></tr>
        <tr><td>errors (unconditional total)</td><td class="v">${esc(num(t2.errors))}</td></tr>
        <tr><td>source</td><td class="v">${t2.errorTypesSource ? esc(t2.errorTypesSource) : NA('errorTypesSource absent')}</td></tr>
      </table>
      <div class="note">${esc(t2.errorReading || '')}</div>
      <div class="note warn">The sum of <code>errorClasses</code> may be <b>less</b> than <code>errors</code>: the agent's id table holds 254 classes with id 255 as an overflow bucket, and <code>errors</code> is counted unconditionally. The shortfall is reported as <b>unattributed</b> and is <b>never</b> closed by inventing a class.</div>`;
  } else if (t2.errorTypesAvailable === false && t2.errors) {
    excHtml = `<div class="note warn"><b>${esc(num(t2.errors))} error(s) counted, exception types unavailable.</b> The agent sent no <code>errorsByClass</code> breakdown for them (an older agent, or its id table was unavailable). ${t2.errorReading ? esc(t2.errorReading) : ''} <b>This is not "zero exception types" &mdash; it is "we have the count and not the names".</b></div>`;
  } else if (t2.errorTypesAvailable === false) {
    excHtml = `<div class="note"><b>No error was recorded for this method</b> in the queried window range, so there is nothing to attribute to a class. ${t2.errorReading ? esc(t2.errorReading) : ''}</div>`;
  } else {
    excHtml = `<div class="note warn"><b>Exception classes: not available from this API.</b> ${t2.errors ? esc(num(t2.errors)) + ' error(s) were counted' : 'No error was counted'}, but this <code>hot-methods</code> response carries none of the BUG&nbsp;#24 fields (<code>errorClasses</code>, <code>errorTypesAvailable</code>). Either this server predates that change or the store does not implement <code>Tier2ErrorStore</code>. <b>Never read this as "zero exception types".</b></div>`;
  }

  pane.innerHTML = `
    <h2 class="rail-h">${esc(n.cls)}<br>#${esc(v.method)}${esc(v.desc)}</h2>
    <p><span class="pill s-${esc(v.status)}">${esc(v.status)}</span>
       <span class="pill ${SILENT_ELIGIBILITY.has(v.eligibility) ? 'alarm' : 'neutral'}">${esc(v.eligibility)}</span>
       ${v.suppressed ? '<span class="pill neutral">suppressed</span>' : ''}
       ${v.publicApi ? '<span class="pill neutral">public API</span>' : ''}
       <span class="pill neutral">probeIdx ${v.probeIdx == null ? '?' : esc(v.probeIdx)}</span></p>

    ${statusExplainer(v)}

    <h3 class="sec">verdict reasons &mdash; every rule that fired, in order</h3>
    ${reasons ? `<ol class="reasons">${reasons}</ol>` : `<div class="note alarm"><b>No reasons were returned.</b> A verdict without an audit trail is not usable evidence &mdash; ${NA('reasons[] empty or absent')}</div>`}

    <h3 class="sec">evidence window</h3>
    <table class="kv">
      <tr><td>windowDays</td><td class="v">${v.windowDays == null ? NA('windowDays absent') : esc(v.windowDays)}</td></tr>
      <tr><td>phasesCovered</td><td class="v">${(v.phasesCovered && v.phasesCovered.length) ? esc(v.phasesCovered.join(', ')) : '<span class="na">none</span>'}</td></tr>
      <tr><td>phasesMissing</td><td class="v">${(v.phasesMissing && v.phasesMissing.length) ? '<b>' + esc(v.phasesMissing.join(', ')) + '</b>' : '<span class="na">none</span>'}</td></tr>
      <tr><td>lastSeen</td><td class="v">${v.lastSeen ? esc(v.lastSeen) : NA('never observed in an accepted window')}</td></tr>
      <tr><td>firstProposedAt</td><td class="v">${v.firstProposedAt ? esc(v.firstProposedAt) : NA('never proposed')}</td></tr>
      <tr><td>revokedReason</td><td class="v">${v.revokedReason ? esc(v.revokedReason) : NA('not revoked')}</td></tr>
      <tr><td>entryPointKind</td><td class="v">${esc(v.entryPointKind || 'none')}</td></tr>
    </table>
    ${(v.phasesMissing && v.phasesMissing.length) ? `<div class="note alarm"><b>${v.phasesMissing.length} business phase(s) have not been spanned yet</b> (${esc(v.phasesMissing.join(', '))}). A window that missed these is not evidence about code that only runs in them (C45) &mdash; and while this list is non-empty <b>no method in this build can become a DEAD_CANDIDATE at all</b>.</div>` : ''}

    <h3 class="sec">reachability &mdash; two graphs, never merged</h3>
    <table class="kv">
      <tr><td>staticReachable <span class="rate">(over-approximate, ~61% unsound)</span></td>
          <td class="v">${tri(v.staticReachable, { t: 'true', f: 'false', n: 'unresolved' })}</td></tr>
      <tr><td>runtimeReachable <span class="rate">(sampled; no <code>false</code> exists)</span></td>
          <td class="v">${tri(v.runtimeReachable, { t: 'true — a call really happened', f: 'false', n: 'no observed edge — not evidence' })}</td></tr>
      <tr><td>runtimeEdgesReported</td><td class="v">${v.runtimeEdgesReported ? '<b>true</b>' : '<b>false</b> — the zeroes below are <i>no data</i>'}</td></tr>
      <tr><td>observed inbound edges</td><td class="v">${count(v.runtimeInboundEdges, !!v.runtimeEdgesReported, 'the edge tier never reported for this build')}</td></tr>
      <tr><td>observed outbound edges</td><td class="v">${count(v.runtimeOutboundEdges, !!v.runtimeEdgesReported, 'the edge tier never reported for this build')}</td></tr>
      <tr><td>inbound <code>sampledObservations</code></td>
          <td class="v">${count(v.runtimeInboundSampledObservations, !!v.runtimeEdgesReported, 'the edge tier never reported for this build')}
              <span class="rate">${v.edgesSampleRate != null ? '@ 1-in-' + esc(v.edgesSampleRate) : '(rate not declared)'}</span></td></tr>
      <tr><td>probeInstalled <span class="rate">(bug #18)</span></td>
          <td class="v">${tri(v.probeInstalled, { t: 'true — an unset bit would be a real observation', f: 'false — SILENCE: nothing could ever write this bit', n: 'no mask reported' })}</td></tr>
      <tr><td>probeInstallMaskReported</td><td class="v">${v.probeInstallMaskReported ? 'true' : 'false'}</td></tr>
    </table>

    <h3 class="sec">tier-2 measurements</h3>
    <table class="kv">
      <tr><td>calls</td><td class="v">${t2 ? esc(num(t2.calls)) : NA('not a tier-2 (timed) method — no call counter exists, and 0 would be a lie')}</td></tr>
      <tr><td>errors</td><td class="v">${t2 ? esc(num(t2.errors)) : NA('not a tier-2 method')}</td></tr>
      <tr><td>error rate</td><td class="v">${errRate ? esc(errRate) : NA(t2 ? 'calls is 0, so a rate is undefined' : 'not a tier-2 method')}</td></tr>
      <tr><td>p50</td><td class="v">${p.p50 == null ? NA('percentile absent') : esc(dur(p.p50)) + ' <span class="rate">(' + esc(num(p.p50)) + ' raw)</span>'}</td></tr>
      <tr><td>p90</td><td class="v">${p.p90 == null ? NA('percentile absent') : esc(dur(p.p90)) + ' <span class="rate">(' + esc(num(p.p90)) + ' raw)</span>'}</td></tr>
      <tr><td>p99</td><td class="v">${p.p99 == null ? NA('percentile absent') : esc(dur(p.p99)) + ' <span class="rate">(' + esc(num(p.p99)) + ' raw)</span>'}</td></tr>
      <tr><td>bucketScheme</td><td class="v">${t2 ? esc(t2.bucketScheme || '') : NA('not a tier-2 method')}</td></tr>
    </table>
    ${t2 ? `<div class="note">Percentiles are the <b>upper bound of the bucket the rank fell into</b> under <code>${esc(t2.bucketScheme || '?')}</code>, computed server-side (the agent never sends a percentile, C31). <b>The API does not declare a unit</b>; the agent records <code>System.nanoTime()</code> deltas, so these are read as nanoseconds &mdash; the raw value is shown alongside so you can check.</div>` : ''}

    <h3 class="sec">exception classes</h3>
    ${excHtml}

    <h3 class="sec">observed callers / callees <span class="rate">(live query)</span></h3>
    <div id="edge-detail" class="loadbar">loading&hellip;</div>

    <h3 class="sec">static callers <span class="rate">(manifest callEdges)</span></h3>
    <div id="static-detail" class="loadbar">loading&hellip;</div>
  `;

  loadMethodEdges(n);
}

function statusExplainer(v) {
  if (v.eligibility === 'NO_PROBE_INSTALLED') {
    return `<div class="note alarm big"><b>No JVM ever installed a probe at this index.</b> Its bit is permanently zero because <b>nothing can write it</b> &mdash; <code>ax.tier1.enabled=false</code>, a per-method <code>frameEmissionUnsupported</code> decision for this bytecode shape, or a class-file fallback. This is <b>silence, not "never ran"</b>, and it is excluded from the death argument. Conflating the two is bug #18.</div>`;
  }
  if (v.eligibility === 'DE_INSTRUMENTED') {
    return `<div class="note warn big"><b>Probes were stripped by auxin's own tier-1b optimisation (C4).</b> It was observed, then de-instrumented. Its later silence is our optimisation talking, not the code.</div>`;
  }
  if (v.status === 'NOT_DYNAMICALLY_OBSERVABLE') {
    return `<div class="note big"><b>This method could never be observed dynamically (C51).</b> Constant-returning, compile-time-folded or single-instruction bodies have no distinct frame, so "unobserved" carries no information. <b>This is not "never ran".</b></div>`;
  }
  if (v.status === 'UNKNOWN') {
    return `<div class="note warn big"><b>UNKNOWN: not seen running, and not provably dead.</b> A probe existed here and never fired, but at least one clause of the death rule did not pass &mdash; read the reasons below to see which. UNKNOWN is the default everywhere and is <b>not</b> a weak DEAD_CANDIDATE.</div>`;
  }
  if (v.status === 'DEAD_CANDIDATE') {
    return `<div class="note alarm big"><b>Proposal for human review. Nothing is auto-deleted.</b> Posture is <b>false-negative-biased</b> and <b>no precision number is claimed</b>; the closest published analogue measured roughly <b>1 in 3 flagged items genuinely deletable</b>, and ~15% of static+dynamic-approved removals still broke on unseen executions (JShrink). Read the reasons, the phases and the callers before deleting.</div>`;
  }
  if (v.status === 'LIVE') {
    return `<div class="note ok big"><b>LIVE &mdash; a probe bit was set: this method really ran</b> in an accepted window. Presence is positive evidence and does not depend on sampling.</div>`;
  }
  return '';
}

async function loadMethodEdges(n) {
  const b = encodeURIComponent(S.build);
  const cls = encodeURIComponent(n.cls);
  const m = encodeURIComponent(n.verdict.method + n.verdict.desc);
  const box = $('#edge-detail'), sbox = $('#static-detail');
  if (!box) return;

  const [callers, callees, blast] = await Promise.all([
    api(`/v1/builds/${b}/callers?class=${cls}&method=${m}&limit=${LIMITS.edgeDetail}`, { quiet: true }),
    api(`/v1/builds/${b}/callees?class=${cls}&method=${m}&limit=${LIMITS.edgeDetail}`, { quiet: true }),
    api(`/v1/builds/${b}/blast-radius?class=${cls}&method=${m}`, { quiet: true }),
  ]);
  if (S.selected !== n.id) return; // selection moved on

  const side = (r, key, label) => {
    if (!r.ok) return `<div class="note alarm"><b>${label}: request failed</b> &mdash; ${esc(r.error)}. This is a failed request, <b>not</b> an absence of ${label}.</div>`;
    const d = r.data, list = d[key] || [];
    const rates = d.edgesSampleRates || {};
    const rateTxt = Object.keys(rates).length === 1 ? '1-in-' + Object.keys(rates)[0] : (Object.keys(rates).length ? 'MIXED: ' + Object.entries(rates).map(([k, v2]) => `${k} (${v2} window(s))`).join(', ') : 'undeclared');
    let h = `<p class="hint"><b>${label}</b> &middot; ${d.observedEdgeCount == null ? NA('observedEdgeCount absent') : esc(d.observedEdgeCount)} observed edge(s) &middot; total <b>${d.totalSampledObservations == null ? NA('absent') : esc(num(d.totalSampledObservations))}</b> <code>sampledObservations</code> <span class="rate">@ ${esc(rateTxt)}</span></p>`;
    if (!d.tierReported) {
      h += `<div class="note alarm"><b>The runtime edge tier never reported for this build.</b> The numbers above are <b>no data</b>, not zero. <code>edgesEnabled</code> defaults to false &mdash; this is a configuration fact.</div>`;
    }
    if (!list.length) {
      h += `<div class="note warn"><b>No observed ${label.toLowerCase()}.</b> ${esc(d.absenceNote || 'Absence of an observed edge is not evidence of anything.')}</div>`;
    } else {
      h += '<ul class="plain">' + list.map((e) => `<li class="edgerow">
        <span class="who">${esc(e['class'])}<br>#${esc(e.method || '(idx ' + e.idx + ' — not in manifest)')}</span>
        <span class="cnt">${e.sampledObservations == null ? NA('absent') : esc(num(e.sampledObservations))} obs<br>
          <span class="rate">@1-in-${e.edgesSampleRate == null ? '?' : esc(e.edgesSampleRate)} &middot; ~${e.estimatedCalls == null ? 'n/a' : esc(num(e.estimatedCalls))} calls</span></span></li>`).join('') + '</ul>';
      h += `<div class="note">${esc(d.countSemantics || '')}</div>`;
    }
    return h;
  };

  box.className = '';
  box.innerHTML = side(callers, 'callers', 'Callers') + side(callees, 'callees', 'Callees');

  sbox.className = '';
  if (!blast.ok) {
    sbox.innerHTML = `<div class="note alarm"><b>blast-radius request failed</b> &mdash; ${esc(blast.error)}. No static caller information is available; that is a failed request, not an empty static graph.</div>`;
  } else {
    const st = blast.data['static'] || {};
    const list = st.callers || [];
    sbox.innerHTML =
      `<p class="hint">${st.callerCount == null ? NA('callerCount absent') : esc(st.callerCount)} static caller(s) from the build-time whole-program scan.</p>` +
      (list.length ? '<ul class="plain">' + list.map((c) => `<li class="edgerow">
          <span class="who">${esc(c['class'])}<br>#${esc(c.method)}${esc(c.desc)}</span>
          <span class="cnt">${esc(c.resolution || '?')}<br><span class="rate">${esc(c.semantics || '')}</span></span></li>`).join('') + '</ul>'
        : `<div class="note warn"><b>No static caller found.</b> The static scan resolved nothing inbound &mdash; and it misses ~61% of methods that actually execute, so this is <b>not</b> evidence that nothing calls it.</div>`) +
      `<div class="note warn">${esc(st.graphSemantics || '')}</div>` +
      (blast.data.readBeforeDeleting ? `<h3 class="sec">read before deleting</h3><div class="note alarm big">${esc(blast.data.readBeforeDeleting)}</div>` : '');
  }
}

/* ==================================================================== */
/* rail: dead code                                                      */
/* ==================================================================== */

function renderDead() {
  const pane = $('#tab-dead');
  const r = S.api.dead;
  if (!r || !r.ok) {
    pane.innerHTML = `<div class="note alarm big"><b>/dead-candidates failed</b> &mdash; ${esc(r ? r.error : 'not requested')}.<br>
      There is <b>no dead-code answer</b> for this build. That is a failed request; it is not "nothing is dead".</div>`;
    return;
  }
  const d = r.data;
  const missing = d.phasesMissing || [];
  const inst = d.instrumentation || {};
  const cov = S.api.coverage && S.api.coverage.ok ? S.api.coverage.data : null;

  const phaseBlock = missing.length
    ? `<div class="note alarm big"><b>${missing.length} business phase(s) have not been spanned: ${esc(missing.join(', '))}.</b><br>
        While this list is non-empty, <b>a DEAD_CANDIDATE cannot exist</b> &mdash; <code>phases-missing</code> is a hard blocker in the rule, so a count of
        <b>${esc(d.count)}</b> below means <i>"we have not looked long enough yet"</i>, <b>not</b> <i>"nothing is dead"</i>.<br>
        Observation window: <b>${d.windowDays == null ? '?' : esc(d.windowDays)} day(s)</b>${cov && cov.span ? `, spanning <b>${esc(cov.span[0])}</b> &rarr; <b>${esc(cov.span[1])}</b>` : ''}.
        Phases covered: ${(d.phasesCovered && d.phasesCovered.length) ? esc(d.phasesCovered.join(', ')) : '<b>none</b>'}.</div>
       ${cov && cov.phaseDetail ? '<table class="kv">' + Object.entries(cov.phaseDetail).map(([k, v]) => `<tr><td>${esc(k)}</td><td class="v">${esc(v)}</td></tr>`).join('') + '</table>' : ''}`
    : `<div class="note ok big"><b>Every required business phase has been spanned</b> (${esc((d.phasesCovered || []).join(', ') || 'none required')}) over <b>${esc(d.windowDays)} day(s)</b>. The phase blocker is not suppressing candidates.</div>`;

  const instBlock = inst.maskReported === false
    ? `<div class="note alarm"><b>No window reported an installed-probe mask.</b> These verdicts were computed <b>without</b> the bug-#18 gate: a JVM with <code>ax.tier1.enabled=false</code> would read as a codebase full of dead code. Do not delete anything on this build.</div>`
    : inst.observableButNeverInstrumented
      ? `<div class="note alarm"><b>${esc(inst.observableButNeverInstrumented)} observable method(s) never had a probe installed by any JVM.</b> Their zero bits are <b>silence</b>, not observations, and they can never be DEAD_CANDIDATEs. A large number here is a misconfigured deployment, not dead code.</div>`
      : `<div class="note ok"><b>Installed-probe mask is complete</b> (<code>observableButNeverInstrumented: 0</code>): an unset probe bit in this build is a real observation.</div>`;

  const items = d.candidates || [];
  const list = items.length
    ? '<ul class="plain">' + items.map((v) => `<li>
        <div class="edgerow"><span class="who">${esc(v['class'])}<br>#${esc(v.method)}${esc(v.desc)}</span>
        <span class="cnt"><a href="#" data-goto="m:${esc(v['class'] + '#' + v.method + v.desc)}">open</a></span></div>
        <div class="rate" style="font-size:10px">${esc((v.reasons || []).length)} reason(s) &middot; windowDays ${esc(v.windowDays)} &middot; static ${v.staticReachable === null ? 'unresolved' : String(v.staticReachable)}</div></li>`).join('') + '</ul>'
    : `<div class="note warn"><b>Zero DEAD_CANDIDATEs were returned.</b> Read that as <i>the rule refused to propose anything</i>, not as <i>there is no dead code</i>. ${missing.length ? 'The phase blocker above is sufficient on its own to produce this zero.' : 'Check the reasons on individual UNKNOWN methods to see which clause is refusing.'}</div>`;

  pane.innerHTML = `
    <h2 class="rail-h">dead code &mdash; ${esc(d.buildSha)}</h2>
    <p class="hint">artifact <b>${esc(d.artifact)}</b> &middot; <b>${esc(d.count)}</b> candidate(s) returned${items.length >= LIMITS.deadCandidates ? ` (capped at limit=${LIMITS.deadCandidates})` : ''}</p>

    <div class="note alarm big">
      <b>PRECISION POSTURE &mdash; read this before deleting anything.</b><br>
      auxin is <b>false-negative-biased</b> and <b>claims no precision number</b>. In the closest published study, roughly
      <b>1 in 3 flagged items was genuinely deletable</b>; ~15% of removals approved by combined static <i>and</i> dynamic
      analysis still broke on unseen executions (JShrink). Every item here is a <b>proposal for human review</b>,
      re-derived and revocable on every request &mdash; auxin never deletes anything.
    </div>
    <div class="note">${esc(d.posture || '')}</div>

    <h3 class="sec">window &amp; phase coverage</h3>
    ${phaseBlock}

    <h3 class="sec">instrumentation (bug #18)</h3>
    ${instBlock}
    <div class="note">${esc(inst.note || '')}</div>

    <h3 class="sec">suppressions &mdash; the filter applied before any verdict</h3>
    ${suppressionBlock()}

    <h3 class="sec">standing proposals (C53 ledger)</h3>
    ${proposalBlock()}

    <h3 class="sec">candidates</h3>
    ${list}`;

  pane.querySelectorAll('[data-goto]').forEach((a) => a.addEventListener('click', (ev) => {
    ev.preventDefault(); select(a.getAttribute('data-goto')); switchTab('detail');
  }));
}

/** C53: a proposal is a standing, revocable claim -- not a snapshot. The
 *  ledger says when each was first proposed, when it was last confirmed, and
 *  why it was revoked. */
function proposalBlock() {
  const r = S.api.proposals;
  if (!r || !r.ok) {
    return `<div class="note alarm"><b>/proposals is not available</b> &mdash; ${esc(r ? r.error : 'not requested')}. The proposal ledger cannot be read, so there is no history of what was proposed before, or of what has since been revoked.</div>`;
  }
  const rows = Array.isArray(r.data) ? r.data : [];
  if (!rows.length) {
    return '<div class="note">The ledger holds <b>no standing proposal</b> for this build. Because verdicts are re-derived on every request (C53), a proposal exists only while every clause of the rule still passes &mdash; an empty ledger means nothing has ever qualified, not that a past proposal was lost.</div>';
  }
  return '<table class="kv">' + rows.map((p) => `<tr>
      <td><code>${esc(p['class'])}#${esc(p.method)}${esc(p.desc)}</code></td>
      <td class="v">${p.active ? '<b>active</b>' : '<b>revoked</b>'}${p.revokedReason ? ' — ' + esc(p.revokedReason) : ''}
        <br><span class="rate">first ${esc(p.firstProposedAt || '?')}<br>last confirmed ${esc(p.lastConfirmedAt || '?')}</span></td></tr>`).join('') + '</table>';
}

/** BUG #28: the shipped default suppression list is an invisible filter
 *  unless it is listed. It runs BEFORE any verdict, so it belongs next to the
 *  dead-code count it shortens. */
function suppressionBlock() {
  const r = S.api.suppressions;
  if (!r || !r.ok) {
    return `<div class="note alarm"><b>/v1/suppressions is not available</b> &mdash; ${esc(r ? r.error : 'not requested')}.
      ${r && r.status === 404 ? 'This server does not serve the route. ' : ''}
      <b>You cannot see which suppression rules were applied</b>, so you cannot tell whether a method is missing from the candidate list because it is alive or because a pattern hid it. That is an unaudited filter, not an empty one.</div>`;
  }
  const d = r.data, rules = d.rules || [];
  const byOrigin = Object.entries(d.countsByOrigin || {}).map(([k, v]) => `${k} ${v}`).join(', ');
  return `<table class="kv">
      <tr><td>active rules</td><td class="v">${count(d.count, true)}</td></tr>
      <tr><td>by origin</td><td class="v">${esc(byOrigin) || NA('countsByOrigin absent')}</td></tr>
      <tr><td>user rules</td><td class="v">${count(d.userRules, true)}</td></tr>
      <tr><td>defaults enabled</td><td class="v">${d.defaultsEnabled === true ? '<b>yes</b>' : d.defaultsEnabled === false ? '<b>no</b>' : NA('defaultsEnabled absent')}</td></tr>
    </table>
    ${rules.length ? `<details><summary style="cursor:pointer;color:#9fd0ef;font-size:11px;margin:4px 0">list all ${rules.length} rule(s) in match order</summary>
      <table class="kv">${rules.map((x) => `<tr><td><code>${esc(x.pattern)}</code></td><td class="v">${esc(x.origin || '?')}${x.comment ? ' &mdash; ' + esc(x.comment) : ''}<br><span class="rate">${esc(x.source || '')}${x.line != null ? ':' + esc(x.line) : ''}</span></td></tr>`).join('')}</table></details>`
      : '<div class="note">No suppression rule is active, so nothing was filtered out before the verdicts were computed.</div>'}
    <div class="note">${esc(d.note || '')}</div>`;
}

/* ==================================================================== */
/* rail: evidence                                                       */
/* ==================================================================== */

function renderEvidence() {
  const pane = $('#tab-evidence');
  const cov = S.api.coverage, gaps = S.api.gaps, health = S.api.health, sum = S.api.summary;
  const out = [];

  out.push('<h2 class="rail-h">evidence</h2>');

  /* windows: the BUG #22b breakdown first, when the server serves it */
  const sw = sum && sum.ok && sum.data.windows && typeof sum.data.windows === 'object' ? sum.data.windows : null;
  if (sw) {
    out.push('<h3 class="sec">windows &mdash; did my data count as evidence?</h3>');
    out.push(`<table class="kv">
      <tr><td>stored</td><td class="v">${esc(num(sw.stored))}</td></tr>
      <tr><td>usable as death evidence</td><td class="v">${esc(num(sw.usableAsDeathEvidence))}</td></tr>
      <tr><td>excluded</td><td class="v">${esc(num(sw.excluded))}</td></tr>
      <tr><td>non-production <span class="rate">(<code>livenessEvidence:false</code>)</span></td><td class="v">${esc(num(sw.nonProduction))}</td></tr>
      <tr><td>non-production environments</td><td class="v">${esc(Object.entries(sw.nonProductionEnvironments || {}).map(([k, v]) => `${k} ×${v}`).join(', ')) || '<span class="na">none</span>'}</td></tr>
      <tr><td>degraded</td><td class="v">${esc(num(sw.degraded))}</td></tr>
      <tr><td>testTainted</td><td class="v">${esc(num(sw.testTainted))}</td></tr>
    </table>
    <div class="note ${sw.nonProduction ? 'alarm' : ''}">${esc(sw.note || '')}</div>
    ${sw.nonProduction ? '<div class="note alarm"><b>Non-production windows are marked and excluded.</b> They are stored, they are arriving, and they contribute to <b>neither</b> liveness <b>nor</b> death. Production classification is fail-closed: an unset or unrecognised <code>environment</code> label counts as non-production.</div>' : ''}`);
  }

  /* windows */
  out.push('<h3 class="sec">observation windows</h3>');
  if (!cov || !cov.ok) out.push(`<div class="note alarm">coverage-windows failed &mdash; ${esc(cov ? cov.error : 'not requested')}. Window provenance is <b>unknown</b>.</div>`);
  else {
    const d = cov.data;
    const win = (w, usable) => `<tr>
      <td>#${esc(w.windowId)} ${esc(w.instanceId || '')}</td>
      <td class="v">${esc((w.durationSeconds == null ? '?' : w.durationSeconds) + 's')} &middot; <b>${esc(w.environment || '?')}</b>
        ${w.production === false ? ' <span class="pill alarm">non-production &mdash; excluded from liveness (livenessEvidence:false)</span>' : ''}
        ${w.degraded ? ' <span class="pill alarm">degraded &mdash; never death evidence</span>' : ''}
        ${w.testTainted ? ' <span class="pill alarm">test-tainted</span>' : ''}
        ${usable ? '' : ' <span class="pill alarm">not usable as death evidence</span>'}</td></tr>`;
    out.push(`<table class="kv">
      <tr><td>windowDays</td><td class="v">${d.windowDays == null ? NA('absent') : esc(d.windowDays)}</td></tr>
      <tr><td>span</td><td class="v">${d.span ? esc(d.span[0] + ' → ' + d.span[1]) : NA('no span: no accepted window')}</td></tr>
      <tr><td>usable</td><td class="v">${(d.usable || []).length}</td></tr>
      <tr><td>excluded</td><td class="v">${(d.excluded || []).length}</td></tr></table>`);
    out.push('<table class="kv">' + (d.usable || []).map((w) => win(w, true)).join('') + (d.excluded || []).map((w) => win(w, false)).join('') + '</table>');
    if (!(d.excluded || []).length) out.push('<div class="note">No window was excluded. A window is excluded when it is degraded, test-tainted, or not explicitly production-classified (<code>livenessEvidence:false</code>, fail-closed) &mdash; such windows are never used as evidence of liveness or of death.</div>');
    else out.push('<div class="note warn">Excluded windows above are <b>not</b> counted towards liveness or death. Non-production classification is <b>fail-closed</b>: an unset or unrecognised environment label counts as non-production.</div>');
  }

  /* instrumentation gaps */
  out.push('<h3 class="sec">instrumentation gaps &mdash; what could we even see?</h3>');
  if (!gaps || !gaps.ok) out.push(`<div class="note alarm">instrumentation-gaps failed &mdash; ${esc(gaps ? gaps.error : 'not requested')}.</div>`);
  else {
    const g = gaps.data, be = g.byEligibility || {};
    out.push(`<table class="kv">
      <tr><td>methods</td><td class="v">${esc(num(g.methods))}</td></tr>
      <tr><td>OBSERVABLE</td><td class="v">${esc(num(be.OBSERVABLE))}</td></tr>
      <tr><td>NO_PROBE_INSTALLED <span class="rate">(bug #18: silence)</span></td><td class="v">${esc(num(be.NO_PROBE_INSTALLED))}</td></tr>
      <tr><td>NOT_DYNAMICALLY_OBSERVABLE <span class="rate">(C51: could never be seen)</span></td><td class="v">${esc(num(be.NOT_DYNAMICALLY_OBSERVABLE))}</td></tr>
      <tr><td>DE_INSTRUMENTED <span class="rate">(C4: we stripped it)</span></td><td class="v">${esc(num(be.DE_INSTRUMENTED))}</td></tr>
      <tr><td>classes / listed</td><td class="v">${esc(g.classes)} / ${esc(g.classesListed)}</td></tr>
      <tr><td>installedIndices</td><td class="v">${esc(num(g.installedIndices))}</td></tr>
      <tr><td>classesWithNothingInstalled</td><td class="v">${esc(num(g.classesWithNothingInstalled))}</td></tr>
      <tr><td>stripMaskMissing</td><td class="v">${esc(num((g.stripMaskMissing || {}).total))} across ${esc(num((g.stripMaskMissing || {}).windows))} window(s)</td></tr>
    </table>
    <div class="note ${g.observableButNeverInstrumented ? 'alarm' : 'ok'}"><b>diagnosis:</b> ${esc(g.diagnosis || '')}</div>
    <div class="note">${esc(g.readingRule || '')}</div>
    <div class="note warn"><b>These four counts are never summed.</b> "Could never be observed" (C51), "no probe installed" (#18) and "we removed the probe" (C4) are three different facts, and only <code>OBSERVABLE</code> methods can ever contribute to a death argument.</div>`);
  }

  /* exception classes for the whole build (BUG #24) */
  out.push('<h3 class="sec">exception classes &mdash; what does it throw?</h3>');
  const ex = S.api.exceptions;
  if (!ex || !ex.ok) {
    out.push(`<div class="note alarm"><b>/exception-classes is not available</b> &mdash; ${esc(ex ? ex.error : 'not requested')}.
      ${ex && ex.status === 404 ? 'This server does not serve the route (it predates BUG&nbsp;#24). ' : ''}
      Exception <b>type names are unknown</b>; the error <i>counts</i> on individual methods are unaffected. <b>Do not read this as "no exceptions".</b></div>`);
  } else if (ex.data.supported === false) {
    out.push(`<div class="note alarm"><b>This store does not implement <code>Tier2ErrorStore</code></b>, so no exception-class breakdown exists. ${esc(ex.data.note || '')} <code>errors</code> counts are unaffected.</div>`);
  } else {
    const d = ex.data, top = d.topClasses || [];
    out.push(`<table class="kv">
      <tr><td>errors <span class="rate">(unconditional total)</span></td><td class="v">${count(d.errors, true)}</td></tr>
      <tr><td>attributed to a named class</td><td class="v">${count(d.attributed, true)}</td></tr>
      <tr><td>unattributed <span class="rate">(legal, and not an error)</span></td><td class="v">${count(d.unattributed, true)}</td></tr>
      <tr><td>unresolvedIds <span class="rate">(id not in its own window's table)</span></td><td class="v">${count(d.unresolvedIds, true)}</td></tr>
      <tr><td>typesAvailable</td><td class="v">${d.typesAvailable === true ? '<b>true</b>' : d.typesAvailable === false ? '<b>false</b> — count without names' : NA('absent')}</td></tr>
      <tr><td>source</td><td class="v">${d.source ? esc(d.source) : NA('absent')}</td></tr>
      <tr><td>distinct classes / listed</td><td class="v">${d.distinctClasses == null ? NA('absent') : esc(d.distinctClasses)} / ${d.classesListed == null ? NA('absent') : esc(d.classesListed)}</td></tr>
      <tr><td>windows contributing</td><td class="v">${count(d.windows, true)}</td></tr>
    </table>`);
    out.push(top.length
      ? '<ul class="plain">' + top.map((t) => `<li class="edgerow"><span class="who">${esc(t['class'])}</span><span class="cnt">${esc(num(t.errors))}</span></li>`).join('') + '</ul>'
      : d.errors
        ? `<div class="note alarm"><b>${esc(num(d.errors))} error(s) were counted and not one has a class name.</b> This is a <b>count without names</b> (BUG #24's failure mode), not an absence of exceptions.</div>`
        : `<div class="note"><b>No error was recorded on any tier-2 method</b> in this window range, so there is nothing to attribute. Note that only tier-2 (boundary / <code>--tier2</code>) methods have error counters at all &mdash; an exception thrown and handled inside a non-tier-2 method is invisible here.</div>`);
    out.push(`<div class="note">${esc(d.reading || '')}</div>`);
    out.push(`<div class="note warn">${esc(d.note || '')}</div>`);
  }

  /* agent health / edge tier */
  out.push('<h3 class="sec">agent health &amp; edge tier</h3>');
  if (!health || !health.ok) out.push(`<div class="note alarm">agent-health failed &mdash; ${esc(health ? health.error : 'not requested')}.</div>`);
  else {
    const h = health.data, e = h.edgeTier || {};
    out.push(`<table class="kv">
      <tr><td>windows</td><td class="v">${esc(h.windows)}</td></tr>
      <tr><td>degradedWindows</td><td class="v">${esc(h.degradedWindows)}</td></tr>
      <tr><td>clockDegradedWindows</td><td class="v">${esc(h.clockDegradedWindows)}</td></tr>
      <tr><td>transformFailuresTotal</td><td class="v">${esc(num(h.transformFailuresTotal))}</td></tr>
      <tr><td>ringDroppedTotal</td><td class="v">${esc(num(h.ringDroppedTotal))}</td></tr>
      <tr><td>stripMaskMissingTotal</td><td class="v">${esc(num(h.stripMaskMissingTotal))}</td></tr>
      <tr><td>instances</td><td class="v">${esc((h.instances || []).join(', ')) || NA('none')}</td></tr>
      <tr><td>classesSkipped</td><td class="v">${esc(Object.entries(h.classesSkippedTotal || {}).map(([k, v]) => k + '=' + v).join(', ')) || NA('none')}</td></tr>
    </table>
    <div class="note">${esc(h.note || '')}</div>
    <h3 class="sec">every way an edge was lost</h3>
    <table class="kv">
      <tr><td>windowsWithTierEnabled</td><td class="v">${esc(num(e.windowsWithTierEnabled))}</td></tr>
      <tr><td>sampledRoots</td><td class="v">${esc(num(e.sampledRoots))}</td></tr>
      <tr><td>recorded</td><td class="v">${esc(num(e.recorded))}</td></tr>
      <tr><td>dropped <span class="rate">(ring full)</span></td><td class="v">${esc(num(e.dropped))}</td></tr>
      <tr><td>truncatedDepth</td><td class="v">${esc(num(e.truncatedDepth))}</td></tr>
      <tr><td>truncatedRoot</td><td class="v">${esc(num(e.truncatedRoot))}</td></tr>
      <tr><td>truncatedDistinct</td><td class="v">${esc(num(e.truncatedDistinct))}</td></tr>
      <tr><td>tierFailures</td><td class="v">${esc(num(e.tierFailures))}</td></tr>
      <tr><td>tracesReaped</td><td class="v">${esc(num(e.tracesReaped))}</td></tr>
      <tr><td>edgesSampleRates</td><td class="v">${esc(Object.entries(e.edgesSampleRates || {}).map(([k, v]) => `1-in-${k} × ${v} window(s)`).join(', ')) || NA('none')}</td></tr>
    </table>
    <div class="note warn">${esc(e.note || '')}</div>
    ${e.dropped ? `<div class="note alarm"><b>${esc(num(e.dropped))} edge observation(s) were dropped</b> before they reached the collector. Every drop is an edge you are not seeing &mdash; which is exactly why a missing edge is never evidence of death.</div>` : ''}`);
  }

  /* summary counts */
  if (sum && sum.ok) {
    const c = sum.data.counts || {};
    out.push('<h3 class="sec">verdict counts (server-side summary)</h3>');
    out.push('<table class="kv">' + STATUSES.map((s) => `<tr><td>${esc(s)}</td><td class="v">${c[s] == null ? '0 <span class="rate">(absent from counts{}; the server omits empty statuses)</span>' : esc(num(c[s]))}</td></tr>`).join('') + '</table>');
    out.push(`<div class="note">${esc(sum.data.precisionPosture || '')}</div>`);
    const rev = sum.data.revoked || [];
    out.push(rev.length ? `<div class="note warn"><b>${rev.length} proposal(s) revoked on this derivation:</b><br>${rev.map((r) => esc(r[0]) + ' &mdash; ' + esc(r[1])).join('<br>')}</div>` : '<div class="note">No proposal was revoked on this derivation. Verdicts are re-derived on every request (C53), so a new observation can revoke one between two reads.</div>');
  }

  /* C55 effective false positives: the only accuracy number auxin will ever
     have about its OWN rules, and it is fed by human feedback, not guesses. */
  out.push('<h3 class="sec">effective false positives (C55)</h3>');
  const efp = S.api.efp;
  if (!efp || !efp.ok) {
    out.push(`<div class="note alarm"><b>/v1/effective-false-positives is not available</b> &mdash; ${esc(efp ? efp.error : 'not requested')}. The per-rule-class false-positive rate cannot be read.</div>`);
  } else {
    const d = efp.data, classes = d.ruleClasses || [];
    out.push(`<div class="note"><b>definition:</b> ${esc(d.definition || '')}</div>`);
    out.push(`<table class="kv">
      <tr><td>probation threshold</td><td class="v">${d.thresholds && d.thresholds.probation != null ? esc(d.thresholds.probation) : NA('absent')}</td></tr>
      <tr><td>auto-disable threshold</td><td class="v">${d.thresholds && d.thresholds.autoDisable != null ? esc(d.thresholds.autoDisable) : NA('absent')}</td></tr></table>`);
    out.push(classes.length
      ? '<table class="kv">' + classes.map((c) => `<tr>
          <td><code>${esc(c.ruleKey || '?')}</code><br><span class="rate">${esc(c.status || '')}</span></td>
          <td class="v">${c.judged
            ? `rate <b>${esc(c.effectiveFalsePositiveRate)}</b> over ${esc(c.judged)} judged`
            : `<span class="na" title="reports exist but no human has judged any of them; a rate over zero judged reports would be meaningless">rate unmeasured (0 judged)</span>`}
            <br><span class="rate">${esc(c.reports)} report(s) · ${esc(c.actioned)} actioned · ${esc(c.notUseful)} not useful</span></td></tr>`).join('') + '</table>'
      : '<div class="note warn"><b>No rule class has any feedback yet.</b> The effective-false-positive rate is therefore <b>unmeasured</b> &mdash; not zero, and not evidence that the rules are accurate. Feedback intake (C55) is deliberately not on the HTTP surface, so nothing can have been recorded through this API.</div>');
  }

  pane.innerHTML = out.join('');
}

/* ==================================================================== */
/* rail: data sources                                                   */
/* ==================================================================== */

function renderSources() {
  const pane = $('#tab-sources');
  const rows = S.sources.map((s) => `<div class="srcrow"><span class="r">${esc(s.route)}</span>
     <span class="s ${s.ok ? 'ok' : 'bad'}">${s.status || 'ERR'} &middot; ${esc(s.note || '')}</span></div>`).join('');
  const probs = (S.problems || []);
  pane.innerHTML = `
    <h2 class="rail-h">data sources</h2>
    <p class="hint">Every route this page fetched for build <b>${esc(S.build)}</b>, with the HTTP status it got. If a row is red, the part of the UI that depends on it says <i>not available</i> &mdash; it does not draw a zero.</p>
    ${probs.length ? `<div class="note alarm"><b>${probs.length} thing(s) this page could not get, or got only partly:</b><ul style="margin:5px 0 0 14px;padding:0">${probs.map((p) => '<li>' + p + '</li>').join('')}</ul></div>` : '<div class="note ok">Every route this page needs returned 200 and nothing was truncated.</div>'}
    <h3 class="sec">requests</h3>
    ${rows || '<p class="hint">none</p>'}
    <h3 class="sec">what is NOT shown, and why</h3>
    ${(S.api.exceptions && S.api.exceptions.ok)
      ? '<div class="note ok"><b>Per-method exception classes</b> (BUG #24) <b>are</b> served: <code>hot-methods[].errorClasses</code> plus the build-wide <code>exception-classes</code> route. Both are rendered.</div>'
      : `<div class="note alarm"><b>Per-method exception classes</b> (BUG #24) are not served by this API (<code>exception-classes</code> returned ${esc(S.api.exceptions ? S.api.exceptions.status : '?')}). Methods show their error <i>count</i> and an explicit <b>"types unavailable"</b> &mdash; never a zero.</div>`}
    <div class="note"><b>The full static call graph</b> has no bulk route. Static edges are read per-method from <code>blast-radius</code> (inbound only), so the static overlay is loaded on demand and capped at ${LIMITS.staticFetch} methods.</div>
    <div class="note"><b>Runtime edges</b> come from <code>hot-paths</code>, which is a <b>ranked top-N</b> (limit=${LIMITS.hotPaths} here). There is no "all edges" route, so an edge below the cut is simply not drawn.</div>
    <div class="note"><b>The class list</b> comes from <code>instrumentation-gaps.byClass[].class</code> &mdash; the only bulk route that names every class. <code>verdicts</code> requires <code>class=</code>/<code>package=</code>/<code>file=</code>, so package prefixes are derived from that list.</div>
    <h3 class="sec">static-edge overlay</h3>
    <p class="hint">${S.staticLoaded ? `loaded: ${S.staticEdges.length} static edge(s) from ${S.staticProgress ? S.staticProgress.done : '?'} blast-radius call(s)${S.staticProgress && S.staticProgress.skipped ? `, ${S.staticProgress.skipped} method(s) skipped (cap ${LIMITS.staticFetch})` : ''}` : 'not loaded'}</p>
    <button id="btn-static" type="button">${S.staticLoaded ? 'reload' : 'load'} static edges (one blast-radius call per visible method)</button>
  `;
  const b = $('#btn-static');
  if (b) b.addEventListener('click', loadStaticEdges);
}

async function loadStaticEdges() {
  const btn = $('#btn-static');
  const targets = S.methodNodes.filter(methodVisible);
  const take = targets.slice(0, LIMITS.staticFetch);
  const skipped = targets.length - take.length;
  S.staticEdges = [];
  let done = 0, failed = 0;
  const b = encodeURIComponent(S.build);
  const tick = () => { if (btn) btn.textContent = `loading static edges… ${done}/${take.length}`; };
  tick();
  const CONC = 8;
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(CONC, take.length) }, async () => {
    while (i < take.length) {
      const n = take[i++];
      const r = await api(`/v1/builds/${b}/blast-radius?class=${encodeURIComponent(n.cls)}&method=${encodeURIComponent(n.verdict.method + n.verdict.desc)}`, { quiet: true });
      done++;
      if (done % 12 === 0) tick();
      if (!r.ok) { failed++; continue; }
      const st = (r.data['static'] || {}).callers || [];
      for (const c of st) {
        const srcV = S.verdicts.find((v) => v['class'] === c['class'] && v.method === c.method && v.desc === c.desc);
        if (!srcV || srcV.probeIdx == null) continue; // endpoint not in this build's manifest
        S.staticEdges.push({
          from: { class: c['class'], idx: srcV.probeIdx },
          to: { class: n.cls, idx: n.idx },
          resolution: c.resolution, semantics: c.semantics,
        });
      }
    }
  }));
  S.staticLoaded = true;
  S.staticProgress = { done, skipped, failed };
  S.sources.push({ route: `/v1/builds/${S.build}/blast-radius (×${done})`, status: failed ? 207 : 200, ok: !failed, note: `${S.staticEdges.length} static edge(s); ${failed} failed; ${skipped} method(s) skipped (cap ${LIMITS.staticFetch})` });
  render();
  renderSources();
}

/* ==================================================================== */
/* interaction                                                          */
/* ==================================================================== */

function select(id) {
  S.selected = id;
  render();
  renderDetail();
}
function switchTab(name) {
  for (const b of document.querySelectorAll('#tabs button')) {
    const on = b.dataset.tab === name;
    b.classList.toggle('on', on); b.setAttribute('aria-selected', on ? 'true' : 'false');
  }
  for (const p of document.querySelectorAll('.tabpane')) p.classList.toggle('on', p.id === 'tab-' + name);
}

function onBoxActivate(id, ev) {
  const n = S.nodesById.get(id);
  if (!n) return;
  if (n.kind === 'method') { select(id); switchTab('detail'); return; }
  if (n.kind === 'class') {
    if (S.collapsed.has(id)) S.collapsed.delete(id); else S.collapsed.add(id);
  } else {
    // a package box: collapse/expand every class beneath it
    const classes = [];
    (function walk(x) { if (x.kind === 'class') classes.push(x.id); else x.children.forEach(walk); })(n);
    const anyOpen = classes.some((c) => !S.collapsed.has(c));
    for (const c of classes) { if (anyOpen) S.collapsed.add(c); else S.collapsed.delete(c); }
  }
  S.selected = id;
  render();
  renderDetail();
}

function fatal(html) { const f = $('#fatal'); f.hidden = false; f.innerHTML = html; }
function clearFatal() { const f = $('#fatal'); f.hidden = true; f.innerHTML = ''; }
function note(t) { const f = $('#fatal'); f.hidden = false; f.innerHTML = esc(t); f.dataset.transient = '1'; }
function clearNote() { const f = $('#fatal'); if (f.dataset.transient) { delete f.dataset.transient; clearFatal(); } }

function wire() {
  $('#build-select').addEventListener('change', (e) => { S.build = e.target.value; S.collapsed.clear(); S.selected = null; loadBuild(); });
  $('#btn-reload').addEventListener('click', () => loadBuild());
  $('#btn-fit').addEventListener('click', fitToView);
  $('#btn-zoom-in').addEventListener('click', () => zoomBy(1.25));
  $('#btn-zoom-out').addEventListener('click', () => zoomBy(1 / 1.25));
  $('#btn-collapse-all').addEventListener('click', () => {
    for (const n of S.nodesById.values()) if (n.kind === 'class') S.collapsed.add(n.id);
    render(); fitToView(); renderDetail();
  });
  $('#btn-expand-all').addEventListener('click', () => { S.collapsed.clear(); render(); fitToView(); renderDetail(); });

  let t = null;
  $('#search').addEventListener('input', (e) => {
    clearTimeout(t);
    t = setTimeout(() => { S.filter = e.target.value.trim(); render(); }, 120);
  });
  $('#filter-hide').addEventListener('change', (e) => { S.hideNonMatching = e.target.checked; render(); fitToView(); });
  for (const cb of document.querySelectorAll('.statusfilter input')) {
    cb.addEventListener('change', () => {
      S.statusOn = new Set([...document.querySelectorAll('.statusfilter input')].filter((x) => x.checked).map((x) => x.dataset.status));
      render(); fitToView();
    });
  }
  for (const b of document.querySelectorAll('#tabs button')) b.addEventListener('click', () => switchTab(b.dataset.tab));

  /* canvas: click / pan / zoom */
  const cv = $('#canvas');
  let drag = null, moved = 0;
  cv.addEventListener('pointerdown', (e) => {
    // The hit target has to be captured HERE: setPointerCapture retargets the
    // pointerup at the <svg>, so reading e.target on pointerup finds no box.
    drag = {
      x: e.clientX, y: e.clientY, vx: S.view.x, vy: S.view.y,
      box: e.target && e.target.closest ? e.target.closest('.box') : null,
    };
    moved = 0;
    cv.classList.add('dragging'); cv.setPointerCapture(e.pointerId);
  });
  cv.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    moved = Math.max(moved, Math.abs(dx) + Math.abs(dy));
    S.view.x = drag.vx + dx; S.view.y = drag.vy + dy; applyZoom();
  });
  cv.addEventListener('pointerup', (e) => {
    const g = drag && drag.box;
    cv.classList.remove('dragging'); drag = null;
    if (moved > 4) return;
    if (g && g.dataset.id) onBoxActivate(g.dataset.id, e);
  });
  cv.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const g = e.target.closest ? e.target.closest('.box') : null;
    if (g) { e.preventDefault(); onBoxActivate(g.dataset.id, e); }
  });
  cv.addEventListener('wheel', (e) => {
    e.preventDefault();
    const r = cv.getBoundingClientRect();
    zoomBy(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });
  window.addEventListener('resize', () => applyZoom());
}

/* ==================================================================== */
/* boot                                                                 */
/* ==================================================================== */

(async function main() {
  wire();
  renderDetail();
  const ok = await loadBuilds();
  if (ok) { await loadBuild(); return; }
  /* Even with no build list, every panel must say WHY it is empty. Leaving
     the caveat chips on "loading" forever would be the UI's own version of
     "reports success while doing nothing". */
  S.problems = ['<code>/v1/builds</code> did not return a usable build list, so no per-build route was called at all.'];
  renderCaveats();
  renderDead();
  renderEvidence();
  renderSources();
  const box = $('#canvas-empty');
  box.hidden = false;
  box.innerHTML = '<div><b>Nothing is drawn because nothing was loaded.</b><br>The build list request failed or came back empty &mdash; see the banner above and the <b>data sources</b> tab. An empty canvas here is a <b>failed or empty request</b>, never a statement about your code.</div>';
})();
