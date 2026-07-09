# Operator Guide

## First Deployment
1. Register operators, stations, fuel-sales, and station managers.
2. Import fuel-sale, meter-certification, and tank-gauge history.
3. Seed the per-jurisdiction spec-basis catalog (`forecourt.facts`) for the
   jurisdictions you actually operate in, citing real official sources only.
4. Run read-only spec-basis validation per jurisdiction.
5. Configure exception / hold escalation and POS / payment accounts.
6. Publish a dry-run settlement and audit export.

## Minimum Production Controls
- spec-basis validation before any verification, dispense, or settlement
- full legal-metrology / forecourt evidence (meter certification / legal
  verification, vapor-recovery system record where mandated) before any dispense
- meter-certainty (legal validity), price-band, ullage / overfill, and
  vapor-recovery-operational checks before any dispense
- exception / hold escalation gate
- audit export for every dispense, settlement, and hold
- backup manual dispense and sale-settlement process

## A Day in the Life: Intake → Verify → Dispense → Settle → Audit

Community Automotive Fuel Retail (ISIC 4730, `cloud-itonami-isic-4730`)
runs on the same intake / advise / govern / decide / commit-or-hold loop as
every itonami blueprint, but here the loop is concrete: a station operator
needs to bring a fuel-sale (say, a regular-grade dispense at pump P-03 in a
JPN station) from intake through price/meter/vapor-recovery safety
verification to a fuel dispense and a sale settlement. Walking through one
fuel-sale, end to end:

1. **Intake.** The operator books the fuel-sale through `:forms`: sale-id,
   pump-id, product grade, jurisdiction, and the fuel-sale's own physical
   record (volume-liters and unit-price, the meter-certified-date with its
   legal validity-years, the competitive/legal price band [min, max], the
   tank-level-pct and the underground tank's ullage-liters, and the
   vapor-recovery-operational flag). This creates a fuel-sale record at
   `:sale/intake` status. The ForecourtAdvisor only normalizes the patch; it
   does not invent the pump-id, product-grade, unit-price, jurisdiction, or any
   physical value.
2. **Verify.** The ForecourtAdvisor drafts a per-jurisdiction legal-metrology
   / fire-safety / vapor-recovery evidence checklist (`:price/verify`) from
   `forecourt.facts`, citing the jurisdiction's official spec-basis (owner
   authority, legal basis, provenance) and listing the required evidence (meter
   certification / legal verification, vapor-recovery system record where
   mandated). The `:forecourt-safety-governor` sign-off gate must clear: it
   checks the jurisdiction actually has an official spec-basis on file (never
   invent one). A jurisdiction with no spec-basis is a HARD hold at the
   governor node -- it never even reaches a human. This verification always
   escalates to a human for approval; it is never auto.
3. **Dispense.** Before fuel can be run through the pump, the
   `:forecourt-safety-governor` sign-off gate runs the full HARD check set
   against the fuel-sale's own ground truth: the spec-basis exists, the
   evidence checklist is complete, the pump's meter is still inside its legal
   validity, the unit-price is inside its band, the dispense volume would not
   breach the tank's ullage, the vapor-recovery system is operational (in a
   jurisdiction that mandates it), and the fuel-sale has not already been
   dispensed. Any failure is a HARD hold that a human cannot override. If every
   check is clean, the proposal STILL always escalates to a human station
   manager -- a `:pump/dispense` never auto-commits at any phase. On approval,
   the dispense record is drafted (`<JURISDICTION>-DISPENSE-000001`) and the
   fuel-sale's `:dispensed?` flag is set.
4. **Settle.** Once fuel has actually been dispensed, the retail sale is
   settled (`:sale/settle`): retail-transaction finalization, real money
   moving. The governor re-checks the spec-basis, the evidence completeness,
   and that this fuel-sale has not already been settled. As with the dispense,
   a clean settlement STILL always escalates to a human station manager --
   `:sale/settle` never auto-commits. On approval the settlement record is
   drafted (`<JURISDICTION>-SALE-000001`) and the fuel-sale's `:settled?` flag
   is set.
5. **Audit.** The verification, the dispense sign-off, the dispense record, the
   settlement sign-off, and the settlement record are all appended to the
   `:audit-ledger` -- immutable and exportable, so a short-measure, pricing, or
   custody dispute can be traced back to the exact spec-basis citation,
   evidence checklist, and station-manager sign-off that authorized the
   dispense and settlement. If something is wrong with the fuel-sale (an
   expired meter, a mis-keyed price, an overfill risk, an inoperational
   vapor-recovery system), that surfaces as a HARD hold at the governor node
   instead of being silently suppressed -- a dispense for that fuel-sale then
   waits on the underlying condition being resolved.

Any deviation from this loop is exactly what the Trust Controls in
`docs/business-model.md` exist to catch: a fuel-sale verified against a
fabricated spec-basis, a dispense started with incomplete evidence, an expired
meter, an out-of-band price, an ullage breach, or an inoperational
vapor-recovery system, or a settlement posted without a human sign-off.

## Feel the Decision Gate: `clojure -M:dev:run`

This vertical has no companion playable prototype yet (unlike the freight
sibling's `itonami/freight-dispatch` game). The fastest hands-on way to feel
why the `:forecourt-safety-governor` gate exists is the bundled demo, which
walks one clean fuel-sale through intake → verify → dispense → settle (each
dispense/settle pausing for human approval) and then exercises every
HARD-hold failure mode in isolation:

- a jurisdiction with no official spec-basis → HOLD (`:no-spec-basis`),
- a pump meter past its legal validity → HOLD (`:meter-uncertain`),
- a unit-price outside the price band → HOLD (`:price-anomaly`),
- a dispense volume that would breach tank ullage → HOLD (`:overfill-risk`),
- an inoperational vapor-recovery system in a VR-mandated jurisdiction → HOLD
  (`:vapor-recovery-inoperational`),
- a double dispense of the same fuel-sale → HOLD (`:already-dispensed`),
- a double settlement of the same fuel-sale → HOLD (`:already-settled`).

Each HOLD settles at the governor node and never reaches a human approver --
the same failure mode the audit ledger is built to catch and the minimum
production controls above are built to prevent. It is not a substitute for
those controls, but it is the fastest way for a new operator (or a reviewer)
to feel, hands-on, why the gate exists before touching a real deployment.

## Certification
Certified operators must prove spec-basis-grounded verification, evidence-backed
dispense readiness (meter in legal validity, price in band, ullage ok,
vapor-recovery operational where mandated), and human review for every
dispense- and settlement-affecting action.
