# ui-kit — the design system, and a hello-world app around it

Two things live here:

- **`src/design-system/`** — the canonical copy of the design system. Every other
  frontend in the project carries a byte-identical copy of this folder.
- **everything else** — a Vite app whose only screen says "Hello world". It exists
  so the design system can be run, seen and diffed with no product code in the way.

```bash
npm install
npm run dev      # http://localhost:5180
```

## The spec is inside the folder

`src/design-system/DESIGN.md` is how you build screens with this. It travels with
every copy on purpose: a team that has the folder has the spec, with no link to
follow and nothing to keep in sync.

## Distribution — why it is a copied folder

The obvious answers do not work here:

| option | why not |
| --- | --- |
| npm workspace | the module repos are separate git repos, not packages in one tree |
| published package | `docker compose up` must work offline, on a laptop, with no registry |
| `file:../design-system` | the Docker build context is `frontend/` — a sibling path is outside it |
| git submodule | teams need "always latest" without a pointer bump; `PLAN-repo-orchestration.md` already ruled this out |

So it is a vendored source folder, copied verbatim, with a drift check — the same
pattern `attempt-01/scripts/make-modules.sh` already uses for the ten services.

```bash
../scripts/sync-design-system.sh          # copy canonical → every target
../scripts/sync-design-system.sh --check  # fail if any copy has drifted (CI)
```

**Fix the design system here, never in a copy.** A copy that has been edited is
reported as drift and is overwritten on the next sync.

## Targets

| repo | path |
| --- | --- |
| neo-00 (orchestrator) | `frontend/src/design-system/` |
| neo-01 (module) | `neo-01/frontend/src/design-system/` |

Modules 02–10 add one line each to `scripts/sync-design-system.sh` as their repos
appear.

## One theme, and the gate that protects it

The product ships a single theme, `glass` — the Havn Glass Console handoff, kept in
each frontend at `frontend/handoff/glass/` and translated into
`theme/glass.css`. Every inference made in that translation is numbered in
DESIGN.md §4a, because the handoff's own `design.md` did not come with it.

A single theme costs us the regression detector a second one provided for free: with
two themes, a component that hardcodes a colour shows up the moment you switch. With
one, it hides until somebody writes theme number two.

So the proof is a gate instead. `sync-design-system.sh --check` fails if any colour
appears outside `theme/`, and it runs in CI beside the drift check. Both have been
verified to fail, not just to pass.

If a component ever needs editing to make a theme work, the component has leaked a
decision that belongs in the theme.
