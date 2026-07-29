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

So it is a vendored source folder, copied verbatim into each frontend at build-out.

**The sync script and its drift check were removed on 2026-07-28.** They held all eleven
copies byte-identical and failed CI when one differed. That expectation is retired: the
ten module repos are handed over, the teams own their frontends, and neo-00 no longer
verifies anything inside them. Two teams had already improved their copy — a modal
focus-steal fix and keyboard activation on table rows — and the gate could only call that
a build error.

## Where the copies are

| repo | path | owner |
| --- | --- | --- |
| neo-00 (orchestrator) | `frontend/src/design-system/` | this repo |
| neo-01 … neo-10 (modules) | `neo-NN/frontend/src/design-system/` | that team |

Each copy is now independent. There is no mechanism that propagates a change made here
into a module, and none that reports when one differs — if you want a fix in all eleven,
it has to be carried by hand or by a PR to each team.

## One theme, and the proof that is no longer automated

The product ships a single theme, `glass` — the Havn Glass Console handoff, kept in
each frontend at `frontend/handoff/glass/` and translated into
`theme/glass.css`. Every inference made in that translation is numbered in
DESIGN.md §4a, because the handoff's own `design.md` did not come with it.

A single theme costs us the regression detector a second one provided for free: with
two themes, a component that hardcodes a colour shows up the moment you switch. With
one, it hides until somebody writes theme number two.

That proof used to be a gate: `sync-design-system.sh --check` failed the build if any
colour appeared outside `theme/`. **It went when the script did (2026-07-28)** — it lived
inside the file that was removed, even though it only ever read `ui-kit/` and never a
module repo. Nothing enforces colour containment now. The one-line check, if you want it
back by hand or as its own script:

```bash
grep -rnE '#[0-9a-fA-F]{3,8}\b|rgba?\(' src/design-system \
  --include='*.css' --include='*.jsx' --include='*.js' | grep -v 'design-system/theme/'
```

Empty output means the system is still reskinnable. Any hit is a component that has leaked
a decision belonging to the theme — which is also the rule in general: if a component ever
needs editing to make a theme work, the leak is in the component.
