# ADR-0001: ElecLightingAdvisor ⊣ Electric Lighting Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2740` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2740` publishes an OSS blueprint for electric-
lighting-equipment **plant operations coordination** (production-batch
product-type/dielectric-safety-test-voltage/quantity/defect-rate data
logging, assembly/test-bench-equipment maintenance scheduling,
safety-concern flagging, and outbound electric-lighting-equipment
product shipment coordination). Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor +
Phase 0->3 rollout pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2750` (Manufacture of
domestic appliances): both are back-office coordination actors for a
fixed processing PLANT with electronics/electromechanical assembly/test
equipment and a real physical safety dimension, and share the same
four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). This build mirrors
`cloud-itonami-isic-2750`'s architecture module-for-module
(`eleclighting.*` in place of `domappl.*`) but adapts the hazard
profile and equipment/product vocabulary to the electric-lighting-
equipment plant: this vertical's central equipment is an LED/lamp-
assembly line and a fixture-housing/optics-assembly/photometric test
bench (photometric test, electrical-safety hipot/withstand test, and,
where applicable, a photobiological-hazard assessment per IEC 62471),
rather than 2750's compressor/motor/wiring assembly line and final-
assembly/test bench; its permanent equipment-actuation block guards
LED/lamp-assembly and fixture-housing/optics-assembly/test-bench
EQUIPMENT (`:actuate-equipment?`, same field name, same posture)
rather than 2750's compressor/motor/wiring assembly/test-bench
equipment; its production-batch record declares a `:product-type`
(closed set spanning led-lamp/led-luminaire/led-driver/downlight-
fixture/street-light-fixture) and a `:dielectric-test-kv` (the routine
hipot/withstand safety-test voltage in kV for mains-connected units,
plausibility-checked 0-4 kV against IEC 60598-1's [luminaires --
general requirements and tests] and IEC 61347-1's [lamp control gear
-- general and safety requirements] electric-strength test-voltage
tables, a ceiling grounded in the double/reinforced-insulation [Class
II] mains-connected lighting-equipment class in this vertical's own
product line, rather than 2750's IEC 60335-1 domestic-appliance
table) in addition to a `:defect-rate-percent`; and its shipment
quantity is tracked in finished-unit UNITS (`:units`/`:quantity-
units`/`:shipped-units`), the same shape 2750 uses for finished
discrete products (counted, not weighed, for freight coordination)
since electric lighting equipment is likewise a discrete counted unit
rather than a bulk weight.

This vertical additionally has a DOMAIN-SPECIFIC safety-concern
vocabulary distinct from 2750's own: manufacture of electric lighting
equipment carries a photobiological-hazard dimension (IEC 62471 risk-
group assessment for LED/lamp emission -- blue-light/UV/IR hazard to
eyes and skin) alongside the electrical-safety hazard every sibling
plant-operations actor shares, without 2750's refrigerant-leak
dimension (electric lighting equipment has no refrigerant circuit).
`:flag-safety-concern` therefore surfaces an "electrical-safety/
photobiological-hazard/UL-CE-compliance concern" (vs. 2750's
"electrical-safety/refrigerant-leak/UL-CE-compliance concern"), and
this actor is never the certification authority -- any proposal
(regardless of op) that declares `:issue-certification? true` is a
HARD, PERMANENT, unconditional block
(`eleclighting.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture 2750 establishes for
its own certification block, adapted to the electric-lighting-
equipment certification regimes (e.g. UL 8750/UL 1598, CE marking
under the EU Low Voltage Directive and RoHS Directive).

This vertical has NO pre-existing `kotoba-lang/eleclighting`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`eleclighting.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, dielectric-safety-test-
voltage plausibility validation, defect-rate plausibility validation)
are re-verified independently by the governor, the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2750`'s `domappl.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:electric-lighting-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"electric-lighting-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external electric-lighting-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
electric-lighting vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-quantity /
product-type / dielectric-safety-test-voltage / defect-rate validation
functions live as pure functions in `eleclighting.registry` and are
re-verified independently by `eleclighting.governor` — the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2750`'s `domappl.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of electric-
lighting-equipment plant operations. It does NOT:
- Control assembly or test-bench equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate assembly/test-bench equipment
- Self-issue an electric-lighting-equipment safety-certification mark (e.g. UL/CE)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: electric-lighting-equipment manufacturing
is a safety-critical domain (electrical-safety hazard, photobiological
hazard, UL/CE-compliance requirement, downstream product-safety and
worker-safety consequence). Safety-concern flagging NEVER auto-commits.
All safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (electrical-safety concern, photobiological-
hazard concern, UL/CE-compliance concern, equipment-safety concern)
ALWAYS escalates, never auto-commits. This is not a "low-stakes
proposal" — it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2750`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date unit quantity plus the proposal's
own claimed unit quantity would exceed the batch's own recorded
production quantity — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `eleclighting.governor`, mirroring `cloud-itonami-isic-2750`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct assembly/test-bench-equipment control, equipment actuation, or self-issued electric-lighting-equipment safety certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Electric-lighting-equipment plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2740`: `clojure -M:test` green (see the
  superproject ADR and `kotoba-lang/industry` registry entry for the
  exact raw output, verified from an independent fresh clone; 77
  tests / 210 assertions, 0 failures, 0 errors), `clojure -M:lint`
  clean, `clojure -M:dev:run` demo narrative exercises proposal
  submission, escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, equipment-not-verified, batch-not-
  verified, shipment-quantity-exceeded, equipment-actuate-blocked,
  certification-authority-blocked, already-scheduled, invalid-
  product-type, invalid-dielectric-test-kv, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
