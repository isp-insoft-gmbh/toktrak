╔═ given_createdTokenView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Tracker token created · TokTrak</title>
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <header class="site-header">
    <a class="wordmark" href="/">TOKTRAK</a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens" aria-current="page">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <h1>Tracker token created</h1>
  <p>Copy this token now. It will not be shown again.</p>
  <pre>tt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</pre>
  <p><a href="/tokens">Return to My Tracker</a></p>

  </main>
</body>
</html>

╔═ given_developmentHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TokTrak</title>
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <div class="environment-banner" role="status">DEV AUTH</div>
  <main id="content">
  <h1>TokTrak</h1>
  <p>Signed in · <a href="/tokens">My Tracker</a></p>
  </main>
</body>
</html>

╔═ given_emptyTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My Tracker · TokTrak</title>
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <header class="site-header">
    <a class="wordmark" href="/">TOKTRAK</a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens" aria-current="page">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <h1>My Tracker</h1>
  <ul>
    <li>No tracker tokens.</li>
  </ul>
  <form method="post" action="/tokens">
    <input type="hidden" name="csrf" value="csrf-value">
    <label>Label <input name="label" maxlength="128" required></label>
    <button>Create token</button>
  </form>
  <form method="post" action="/account/deactivate">
    <input type="hidden" name="csrf" value="csrf-value">
    <button>Deactivate account</button>
  </form>
  <form method="post" action="/logout">
    <input type="hidden" name="csrf" value="csrf-value">
    <button>Sign out</button>
  </form>

  </main>
</body>
</html>

╔═ given_eventEnvelope_when_serializingJson_then_matchesApprovedDocument ═╗
{"id":"00000000-0000-4000-8000-000000000001","at":"2026-07-10T00:00:00Z","type":"projection-snapshot","schemaVersion":1,"actor":"system","data":{"eventCount":7}}
╔═ given_healthPayload_when_serializingJson_then_matchesApprovedDocument ═╗
{"status":"degraded","reason":"writes_failed"}
╔═ given_productionErrorPage_when_renderingHtml_then_matchesApprovedDocument ═╗
<!doctype html><meta charset="utf-8"><title>418 · TokTrak</title><link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css"><main class="error-page"><h1>418</h1><p>cannot brew coffee</p><dl><dt>Path</dt><dd><code>/coffee</code></dd><dt>Request ID</dt><dd><code>00000000-0000-4000-8000-000000000001</code></dd></dl><p><a href="/">Return to TokTrak</a></p></main>
╔═ given_productionHomeView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TokTrak</title>
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <main id="content">
  <h1>TokTrak</h1>
  <form action="/login">
    <button>Sign in</button>
  </form>
  </main>
</body>
</html>

╔═ given_representativeTokenListView_when_renderingEncodedHtml_then_matchesApprovedDocument ═╗
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My Tracker · TokTrak</title>
  <link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css">
  <script type="module" src="/assets/datastar.0123456789abcdef0123456789abcdef.js"></script>
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <div class="environment-banner" role="status">DEV AUTH</div>
  <header class="site-header">
    <a class="wordmark" href="/">TOKTRAK</a>
    <nav aria-label="Primary navigation">
      <a href="/">Overview</a>
      <a href="/visualizations">Visualizations</a>
      <a href="/tokens" aria-current="page">My Tracker</a>
      <a href="/scope">Data scope</a>
    </nav>
  </header>
  <main id="content">
  <h1>My Tracker</h1>
  <ul>
    <li>
      <strong>Laptop &lt;primary&gt;</strong>
      <code>00000000-0000-4000-8000-000000000001</code>
      · active
      <form method="post" action="/tokens/revoke">
        <input type="hidden" name="csrf" value="csrf-value">
        <input type="hidden" name="tokenId" value="00000000-0000-4000-8000-000000000001">
        <button>Revoke</button>
      </form>
    </li>
    <li>
      <strong>Old workstation</strong>
      <code>00000000-0000-4000-8000-000000000002</code>
      · revoked
      · last used <time>2026-07-10T12:00:00Z</time>
    </li>
  </ul>
  <a href="/tokens?page&#x3D;1">Previous</a>
  <a href="/tokens?page&#x3D;3">Next</a>
  <form method="post" action="/tokens">
    <input type="hidden" name="csrf" value="csrf-value">
    <label>Label <input name="label" maxlength="128" required></label>
    <button>Create token</button>
  </form>
  <form method="post" action="/account/deactivate">
    <input type="hidden" name="csrf" value="csrf-value">
    <button>Deactivate account</button>
  </form>
  <form method="post" action="/logout">
    <input type="hidden" name="csrf" value="csrf-value">
    <button>Sign out</button>
  </form>

  </main>
</body>
</html>

╔═ given_requestLog_when_formattingJson_then_matchesApprovedLine ═╗
{"timestamp":"1970-01-01T00:00:00Z","level":"INFO","message":"request complete","requestId":"request-1","method":"GET","path":"/health","mode":"production","userId":"user-1","tokenId":"token-1"}

╔═ [end of file] ═╗
