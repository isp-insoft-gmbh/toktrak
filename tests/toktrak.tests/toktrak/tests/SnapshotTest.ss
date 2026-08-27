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
      <h2 id="download-heading">3. Install tracker</h2>
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
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <div class="environment-banner" role="status">DEV AUTH</div>
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
    <p>Signed in · <a class="button-link" href="/tokens">My Tracker</a></p>
  </div>

  </main>
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
      <h2 id="node-heading">1. Install Node.js</h2>
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
      <h2 id="create-token-heading">2. Create tracker token</h2>
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
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <div class="environment-banner" role="status">DEV AUTH</div>
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
      <h2 id="node-heading">1. Install Node.js</h2>
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
          · last used <time>2026-07-10T12:00:00Z</time>
        </div>
      </li>
    </ul>
    <div class="tracker-pagination">
      <a href="/tokens?page&#x3D;1">Previous</a>
      <a href="/tokens?page&#x3D;3">Next</a>
    </div>
    <section class="tracker-panel" aria-labelledby="create-token-heading">
      <h2 id="create-token-heading">2. Create tracker token</h2>
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
</body>
</html>

╔═ given_requestLog_when_formattingJson_then_matchesApprovedLine ═╗
{"timestamp":"1970-01-01T00:00:00Z","level":"INFO","message":"request complete","requestId":"request-1","method":"GET","path":"/health","mode":"production","userId":"user-1","tokenId":"token-1"}

╔═ [end of file] ═╗
