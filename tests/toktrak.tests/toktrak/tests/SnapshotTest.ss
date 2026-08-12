╔═ given_eventEnvelope_when_serializingJson_then_matchesApprovedDocument ═╗
{"id":"00000000-0000-4000-8000-000000000001","at":"2026-07-10T00:00:00Z","type":"projection-snapshot","schemaVersion":1,"actor":"system","data":{"eventCount":7}}
╔═ given_healthPayload_when_serializingJson_then_matchesApprovedDocument ═╗
{"status":"degraded","reason":"writes_failed"}
╔═ given_productionErrorPage_when_renderingHtml_then_matchesApprovedDocument ═╗
<!doctype html><meta charset="utf-8"><title>418 · TokTrak</title><link rel="stylesheet" href="/assets/main.0123456789abcdef0123456789abcdef.css"><main class="error-page"><h1>418</h1><p>cannot brew coffee</p><dl><dt>Path</dt><dd><code>/coffee</code></dd><dt>Request ID</dt><dd><code>00000000-0000-4000-8000-000000000001</code></dd></dl><p><a href="/">Return to TokTrak</a></p></main>
╔═ given_requestLog_when_formattingJson_then_matchesApprovedLine ═╗
{"timestamp":"1970-01-01T00:00:00Z","level":"INFO","message":"request complete","requestId":"request-1","method":"GET","path":"/health","mode":"production","userId":"user-1","tokenId":"token-1"}

╔═ [end of file] ═╗
