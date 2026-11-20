╔═ given_changesView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="application-name" content="TokTrak">
  <meta name="theme-color" content="#b5b9f0" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0b0909" media="(prefers-color-scheme: dark)">
  <title>What’s new · TokTrak</title>
  <link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg">
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
  <script type="module" src="/assets/dashboard.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <header class="site-header">
    <a class="wordmark" href="/" aria-label="TokTrak home">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg">
        <img src="/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg" alt="" width="540" height="120">
      </picture>
    </a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <div class="scope-page changes-page">
    <header class="page-heading">
      <div>
        <h1>What’s new</h1>
        <p>Changes that affect how you install, run, or use TokTrak.</p>
      </div>
    </header>
    <article class="current-release">
      <header>
        <h2>TokTrak v2</h2>
        <span>Current release</span>
      </header>
      <ul>
        <li>Keep scheduled tracker uploads working when native schedulers omit Node.js from <code>PATH</code>.</li>
        <li>Reject elevated Windows tracker installation before creating user-scoped scheduler state.</li>
        <li>Restrict automatic development login to loopback authorities.</li>
        <li>Document Node.js 22 as the minimum tracker runtime.</li>
        <li>Show the running release and its change history in the UI.</li>
      </ul>
    </article>
    <section class="release-history" aria-labelledby="release-history-title">
      <h2 id="release-history-title">Earlier releases</h2>
      <details>
        <summary><span>TokTrak v1</span><span class="disclosure-mark" aria-hidden="true"></span></summary>
        <ul>
          <li>Bind OIDC discovery metadata to the configured issuer before trusting advertised endpoints.</li>
          <li>Restore production EUR exchange-rate refresh.</li>
          <li>Make Windows trackers catch up after sleep and continue on battery power.</li>
        </ul>
      </details>
      <details>
        <summary><span>TokTrak v0</span><span class="disclosure-mark" aria-hidden="true"></span></summary>
        <ul>
          <li>Initial TokTrak server, dashboard, workstation tracker, and production container.</li>
          <li>Opt-in remote JMX/JFR diagnostics with shell-free <code>jcmd</code> and <code>jfr</code> tooling.</li>
        </ul>
      </details>
    </section>
  </div>

  </main>
  <footer class="site-footer">
    <a href="/changes"><strong>TokTrak v2</strong><span aria-hidden="true"> · </span>What’s new</a>
  </footer>
</body>
</html>

╔═ given_createdTokenView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="application-name" content="TokTrak">
  <meta name="theme-color" content="#b5b9f0" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0b0909" media="(prefers-color-scheme: dark)">
  <title>Tracker token created · TokTrak</title>
  <link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg">
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
  <script type="module" src="/assets/dashboard.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <header class="site-header">
    <a class="wordmark" href="/" aria-label="TokTrak home">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg">
        <img src="/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg" alt="" width="540" height="120">
      </picture>
    </a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens" aria-current="page">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <div class="scope-page tracker-page">
    <h1>Tracker token created</h1>
    <div class="token-copy">
      <pre id="tracker-token" class="token-secret">tt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</pre>
      <button type="button" data-copy-token aria-controls="tracker-token">Copy token</button>
    </div>
    <p class="secret-note token-warning" role="alert">Copy it now. This token will never be shown again.</p>
    <section class="tracker-panel" aria-labelledby="download-heading">
      <h2 id="download-heading">Install tracker</h2>
      <textarea id="tracker-script" hidden>const TOKEN &#x3D; &quot;tt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&quot;;
</textarea>
      <button type="button" data-download-tracker data-sha256="0000000000000000000000000000000000000000000000000000000000000000" aria-controls="tracker-script">Download toktrak.mjs</button>
      <p class="secret-note installer-secret"><strong>Secret storage:</strong> This personalized installer contains your token.</p>
      <p data-platform-status>After downloading, run the command for your platform:</p>
      <div class="platform-instructions" data-platform="win32">
        <h3>Windows</h3>
        <p><code>node .\toktrak.mjs</code></p>
      </div>
      <div class="platform-instructions" data-platform="darwin">
        <h3>macOS</h3>
        <p><code>node ~/Downloads/toktrak.mjs</code></p>
      </div>
      <div class="platform-instructions" data-platform="linux">
        <h3>Linux</h3>
        <p><code>node ~/Downloads/toktrak.mjs</code></p>
      </div>
      <p>The installer creates one daily user job and uploads existing usage. It never needs administrator or root access.</p>
    </section>
    <p><a class="button-link button-secondary" href="/tokens">Return to My Tracker</a></p>
    <script type="module" src="/assets/clipboard.0123456789abcdef0123456789abcdef.js"></script>
    <script type="module" src="/assets/platform.0123456789abcdef0123456789abcdef.js"></script>
  </div>

  </main>
  <footer class="site-footer">
    <a href="/changes"><strong>TokTrak v2</strong><span aria-hidden="true"> · </span>What’s new</a>
  </footer>
</body>
</html>

╔═ given_developmentHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="application-name" content="TokTrak">
  <meta name="theme-color" content="#b5b9f0" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0b0909" media="(prefers-color-scheme: dark)">
  <title>TokTrak</title>
  <link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg">
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
  <script type="module" src="/assets/dashboard.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <div class="environment-banner" role="status">Dev auth</div>
  <main id="content">
  <div class="scope-page home-page">
    <h1 class="home-brand">
      <picture>
        <source media="(max-width: 32rem) and (prefers-color-scheme: dark)" srcset="/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg">
        <source media="(max-width: 32rem)" srcset="/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg">
        <source media="(prefers-color-scheme: dark)" srcset="/assets/logo-lockup-dark.0123456789abcdef0123456789abcdef.svg">
        <img src="/assets/logo-lockup.0123456789abcdef0123456789abcdef.svg" alt="TokTrak" width="800" height="250">
      </picture>
    </h1>
    <p class="home-intro">Understand projected AI coding subscription costs from your team's local harness usage.</p>
    <p class="home-detail">No prompts, code, cloud, web, or CI usage. Estimates inform awareness—not billing or performance reviews.</p>
    <p><a class="button-link" href="/tokens">My Tracker</a></p>
  </div>

  </main>
  <footer class="site-footer">
    <a href="/changes"><strong>TokTrak dev</strong><span aria-hidden="true"> · </span>What’s new</a>
  </footer>
</body>
</html>

╔═ given_emptyTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="application-name" content="TokTrak">
  <meta name="theme-color" content="#b5b9f0" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0b0909" media="(prefers-color-scheme: dark)">
  <title>My Tracker · TokTrak</title>
  <link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg">
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
  <script type="module" src="/assets/dashboard.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <header class="site-header">
    <a class="wordmark" href="/" aria-label="TokTrak home">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg">
        <img src="/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg" alt="" width="540" height="120">
      </picture>
    </a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens" aria-current="page">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <div class="scope-page tracker-page">
    <h1>My Tracker</h1>
    <p class="lede">Create and revoke user-scoped tokens for the workstation uploader. Token secrets are shown once.</p>
    <section class="tracker-panel platform-panel" aria-labelledby="node-heading">
      <h2 id="node-heading">Install Node.js</h2>
      <p data-platform-status>Choose your platform:</p>
      <div class="platform-instructions" data-platform="win32">
        <h3>Windows</h3>
        <p>Run <code>winget install OpenJS.NodeJS.LTS</code>, then open a new terminal.</p>
      </div>
      <div class="platform-instructions" data-platform="darwin">
        <h3>macOS</h3>
        <p>Run <code>brew install node</code>.</p>
      </div>
      <div class="platform-instructions" data-platform="linux">
        <h3>Linux</h3>
        <p>Install Node.js 22 or newer with your distribution package manager.</p>
      </div>
      <p>Verify installation with <code>node --version</code>.</p>
    </section>
    <ul class="token-list">
      <li class="empty">No tracker tokens yet.</li>
    </ul>
    <div class="tracker-pagination">
    </div>
    <section class="tracker-panel" aria-labelledby="create-token-heading">
      <h2 id="create-token-heading">Create tracker token</h2>
      <form class="tracker-form" method="post" action="/tokens">
        <input type="hidden" name="csrf" value="csrf-value">
        <label for="tracker-label">Label</label>
        <div class="tracker-form-row">
          <input id="tracker-label" name="label" maxlength="128" placeholder="Work laptop" autocomplete="off" required>
          <button>Create token</button>
        </div>
      </form>
    </section>
    <script type="module" src="/assets/platform.0123456789abcdef0123456789abcdef.js"></script>
    <footer class="tracker-actions">
      <form method="post" action="/account/deactivate">
        <input type="hidden" name="csrf" value="csrf-value">
        <button class="button-danger">Deactivate account</button>
      </form>
      <form method="post" action="/logout">
        <input type="hidden" name="csrf" value="csrf-value">
        <button class="button-secondary">Sign out</button>
      </form>
    </footer>
  </div>

  </main>
  <footer class="site-footer">
    <a href="/changes"><strong>TokTrak v2</strong><span aria-hidden="true"> · </span>What’s new</a>
  </footer>
</body>
</html>

╔═ given_eventEnvelope_when_serializingJson_then_matchesApprovedDocument ═╗
{"id":"00000000-0000-4000-8000-000000000001","at":"2026-07-10T00:00:00Z","type":"projection-snapshot","schemaVersion":1,"actor":"system","data":{"eventCount":7}}
╔═ given_healthPayload_when_serializingJson_then_matchesApprovedDocument ═╗
{"status":"degraded","reason":"writes_failed"}
╔═ given_productionErrorPage_when_renderingHtml_then_matchesApprovedDocument ═╗
<!doctype html><meta charset="utf-8"><title>418 · TokTrak</title><link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css"><link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg"><main class="error-page"><img class="error-mark" src="/assets/logo-mark.0123456789abcdef0123456789abcdef.svg" alt="" width="160" height="160"><h1>418</h1><p>cannot brew coffee</p><dl><dt>Path</dt><dd><code>/coffee</code></dd><dt>Request ID</dt><dd><code>00000000-0000-4000-8000-000000000001</code></dd></dl><p><a class="button-link" href="/">Return to TokTrak</a></p></main>
╔═ given_productionHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="application-name" content="TokTrak">
  <meta name="theme-color" content="#b5b9f0" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0b0909" media="(prefers-color-scheme: dark)">
  <title>TokTrak</title>
  <link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg">
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
  <script type="module" src="/assets/dashboard.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <main id="content">
  <div class="scope-page home-page">
    <h1 class="home-brand">
      <picture>
        <source media="(max-width: 32rem) and (prefers-color-scheme: dark)" srcset="/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg">
        <source media="(max-width: 32rem)" srcset="/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg">
        <source media="(prefers-color-scheme: dark)" srcset="/assets/logo-lockup-dark.0123456789abcdef0123456789abcdef.svg">
        <img src="/assets/logo-lockup.0123456789abcdef0123456789abcdef.svg" alt="TokTrak" width="800" height="250">
      </picture>
    </h1>
    <p class="home-intro">Understand projected AI coding subscription costs from your team's local harness usage.</p>
    <p class="home-detail">No prompts, code, cloud, web, or CI usage. Estimates inform awareness—not billing or performance reviews.</p>
    <a class="button-link" href="/login">Sign in</a>
  </div>

  </main>
  <footer class="site-footer">
    <a href="/changes"><strong>TokTrak v2</strong><span aria-hidden="true"> · </span>What’s new</a>
  </footer>
</body>
</html>

╔═ given_representativeTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="application-name" content="TokTrak">
  <meta name="theme-color" content="#b5b9f0" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0b0909" media="(prefers-color-scheme: dark)">
  <title>My Tracker · TokTrak</title>
  <link rel="icon" type="image/svg+xml" href="/assets/favicon.0123456789abcdef0123456789abcdef.svg">
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
  <script type="module" src="/assets/dashboard.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <div class="environment-banner" role="status">Dev auth</div>
  <header class="site-header">
    <a class="wordmark" href="/" aria-label="TokTrak home">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="/assets/logo-wordmark-dark.0123456789abcdef0123456789abcdef.svg">
        <img src="/assets/logo-wordmark.0123456789abcdef0123456789abcdef.svg" alt="" width="540" height="120">
      </picture>
    </a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens" aria-current="page">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <div class="scope-page tracker-page">
    <h1>My Tracker</h1>
    <p class="lede">Create and revoke user-scoped tokens for the workstation uploader. Token secrets are shown once.</p>
    <section class="tracker-panel platform-panel" aria-labelledby="node-heading">
      <h2 id="node-heading">Install Node.js</h2>
      <p data-platform-status>Choose your platform:</p>
      <div class="platform-instructions" data-platform="win32">
        <h3>Windows</h3>
        <p>Run <code>winget install OpenJS.NodeJS.LTS</code>, then open a new terminal.</p>
      </div>
      <div class="platform-instructions" data-platform="darwin">
        <h3>macOS</h3>
        <p>Run <code>brew install node</code>.</p>
      </div>
      <div class="platform-instructions" data-platform="linux">
        <h3>Linux</h3>
        <p>Install Node.js 22 or newer with your distribution package manager.</p>
      </div>
      <p>Verify installation with <code>node --version</code>.</p>
    </section>
    <ul class="token-list">
      <li>
        <div class="token-details">
          <strong>Laptop &lt;primary&gt;</strong>
          <code>00000000-0000-4000-8000-000000000001</code>
          <span class="token-status">active</span>
        </div>
        <form class="token-action" method="post" action="/tokens/revoke">
          <input type="hidden" name="csrf" value="csrf-value">
          <input type="hidden" name="tokenId" value="00000000-0000-4000-8000-000000000001">
          <button class="button-secondary">Revoke</button>
        </form>
      </li>
      <li>
        <div class="token-details">
          <strong>Old workstation</strong>
          <code>00000000-0000-4000-8000-000000000002</code>
          <span class="token-status">revoked</span>
          · last used <time datetime="2026-07-10T12:00:00Z">10 Jul 2026 · 12:00 UTC</time>
        </div>
      </li>
    </ul>
    <div class="tracker-pagination">
      <a href="/tokens?page&#x3D;1">Previous</a>
      <a href="/tokens?page&#x3D;3">Next</a>
    </div>
    <section class="tracker-panel" aria-labelledby="create-token-heading">
      <h2 id="create-token-heading">Create tracker token</h2>
      <form class="tracker-form" method="post" action="/tokens">
        <input type="hidden" name="csrf" value="csrf-value">
        <label for="tracker-label">Label</label>
        <div class="tracker-form-row">
          <input id="tracker-label" name="label" maxlength="128" placeholder="Work laptop" autocomplete="off" required>
          <button>Create token</button>
        </div>
      </form>
    </section>
    <script type="module" src="/assets/platform.0123456789abcdef0123456789abcdef.js"></script>
    <footer class="tracker-actions">
      <form method="post" action="/account/deactivate">
        <input type="hidden" name="csrf" value="csrf-value">
        <button class="button-danger">Deactivate account</button>
      </form>
      <form method="post" action="/logout">
        <input type="hidden" name="csrf" value="csrf-value">
        <button class="button-secondary">Sign out</button>
      </form>
    </footer>
  </div>

  </main>
  <footer class="site-footer">
    <a href="/changes"><strong>TokTrak dev</strong><span aria-hidden="true"> · </span>What’s new</a>
  </footer>
</body>
</html>

╔═ given_requestLog_when_formattingJson_then_matchesApprovedLine ═╗
{"timestamp":"1970-01-01T00:00:00Z","level":"INFO","message":"request complete","requestId":"request-1","method":"GET","path":"/health","mode":"production","userId":"user-1","tokenId":"token-1"}

╔═ given_twoToolsOverEightDays_when_renderingDailyCostChart_then_matchesApprovedMarkup ═╗
<article class="chart-card chart-card-wide" aria-labelledby="daily-cost-heading">
        <h2 id="daily-cost-heading">Cost per day by tool</h2>
        <p>Estimated cost per day, stacked by coding tool. Line: 7-day average. Empty columns are days without usage.</p>
        <svg class="chart" viewBox="0 0 960 260" role="img" aria-labelledby="daily-cost-heading">
          <line class="chart-grid-line" x1="64" y1="236" x2="952" y2="236"/>
          <text class="chart-axis" x="58" y="240" text-anchor="end">$0.00</text>
          <line class="chart-grid-line" x1="64" y1="179.5" x2="952" y2="179.5"/>
          <text class="chart-axis" x="58" y="183.5" text-anchor="end">$5.00</text>
          <line class="chart-grid-line" x1="64" y1="123" x2="952" y2="123"/>
          <text class="chart-axis" x="58" y="127" text-anchor="end">$10</text>
          <line class="chart-grid-line" x1="64" y1="66.5" x2="952" y2="66.5"/>
          <text class="chart-axis" x="58" y="70.5" text-anchor="end">$15</text>
          <line class="chart-grid-line" x1="64" y1="10" x2="952" y2="10"/>
          <text class="chart-axis" x="58" y="14" text-anchor="end">$20</text>
          <rect class="series-2" x="64.5" y="207.8" width="110" height="28.3"><title>1 Jul 2026 · beta · $2.50</title></rect>
          <rect class="series-1" x="64.5" y="94.8" width="110" height="113"><title>1 Jul 2026 · alpha · $10.00</title></rect>
          <rect class="series-2" x="286.5" y="190.8" width="110" height="45.2"><title>3 Jul 2026 · beta · $4.00</title></rect>
          <rect class="series-3" x="841.5" y="224.7" width="110" height="11.3"><title>8 Jul 2026 · Not reported · $1.00</title></rect>
          <path class="chart-average" d="M785.5 209.4L896.5 227.9"><title>7-day average</title></path>
          <text class="chart-axis" x="119.5" y="254" text-anchor="middle">1 Jul</text>
          <text class="chart-axis" x="230.5" y="254" text-anchor="middle">2 Jul</text>
          <text class="chart-axis" x="341.5" y="254" text-anchor="middle">3 Jul</text>
          <text class="chart-axis" x="452.5" y="254" text-anchor="middle">4 Jul</text>
          <text class="chart-axis" x="563.5" y="254" text-anchor="middle">5 Jul</text>
          <text class="chart-axis" x="674.5" y="254" text-anchor="middle">6 Jul</text>
          <text class="chart-axis" x="785.5" y="254" text-anchor="middle">7 Jul</text>
          <text class="chart-axis" x="896.5" y="254" text-anchor="middle">8 Jul</text>
        </svg>
        <ul class="chart-legend">
          <li><span class="swatch series-1" aria-hidden="true"></span>alpha <strong>$10.00</strong></li>
          <li><span class="swatch series-2" aria-hidden="true"></span>beta <strong>$6.50</strong></li>
          <li><span class="swatch series-3" aria-hidden="true"></span>Not reported <strong>$1.00</strong></li>
          <li><span class="swatch swatch-line" aria-hidden="true"></span>7-day average</li>
        </ul>
        <dl class="chart-stats">
          <div><dt>Peak day</dt><dd>$12.50 · 1 Jul 2026</dd></div>
          <div><dt>Typical day with usage</dt><dd>$4.00</dd></div>
          <div><dt>Total</dt><dd>$17.50</dd></div>
        </dl>
        <details class="chart-data">
          <summary>Data table</summary>
          <div class="table-wrap">
            <table>
              <thead><tr><th scope="col">Period</th><th scope="col">Estimated cost</th></tr></thead>
              <tbody>
                <tr><td>1 Jul 2026</td><td>$12.50</td></tr>
                <tr><td>2 Jul 2026</td><td>$0.00</td></tr>
                <tr><td>3 Jul 2026</td><td>$4.00</td></tr>
                <tr><td>4 Jul 2026</td><td>$0.00</td></tr>
                <tr><td>5 Jul 2026</td><td>$0.00</td></tr>
                <tr><td>6 Jul 2026</td><td>$0.00</td></tr>
                <tr><td>7 Jul 2026</td><td>$0.00</td></tr>
                <tr><td>8 Jul 2026</td><td>$1.00</td></tr>
              </tbody>
            </table>
          </div>
        </details>
        <p class="chart-note">Tool totals add up to the estimated cost above. Costs without a reported tool appear as Not reported.</p>
      </article>
╔═ [end of file] ═╗
