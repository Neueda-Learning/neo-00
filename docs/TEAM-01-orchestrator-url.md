# Team 01 — fix where local callbacks go

This is a notification for team 01, not a change neo-00 should make inside their submodule.

`neo-01/docker-compose.yml` and `neo-01/backend/src/main/resources/application.yml` currently
commit `ORCHESTRATOR_URL` defaults pointing at the dev ALB root. The other nine module repos point
their local backend at the sidecar. As a result, team 01's local sidecar can dispatch a scenario,
but the module reports to the real dev orchestrator, which correctly drops the unknown id; the
sidecar never receives the decision.

Please restore the local defaults documented by the repo itself:

- compose backend → `http://sidecar:8080`
- IDE/application default → `http://localhost:9000`
- system-stack override → `http://orchestrator:8080`

The committed `.env.example`, `README.md`, and `AGENTS.md` already describe that arrangement and
should be checked against the fix. This is team 01's repository and therefore team 01's change.
