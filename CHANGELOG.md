# Changelog

## v2

- Keep scheduled tracker uploads working when native schedulers omit Node.js
  from `PATH`.
- Reject elevated Windows tracker installation before creating user-scoped
  scheduler state.
- Restrict automatic development login to loopback authorities.
- Document Node.js 22 as the minimum tracker runtime.
- Show the running release and its change history in the UI.

## v1

- Bind OIDC discovery metadata to the configured issuer before trusting
  advertised endpoints.
- Restore production EUR exchange-rate refresh.
- Make Windows trackers catch up after sleep and continue on battery power.

## v0

- Initial TokTrak server, dashboard, workstation tracker, and production
  container.
- Opt-in remote JMX/JFR diagnostics with shell-free `jcmd` and `jfr` tooling.
