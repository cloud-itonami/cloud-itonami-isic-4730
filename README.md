# cloud-itonami-isic-4730

Open Business Blueprint for **ISIC Rev.5 4730**: Retail sale of
automotive fuel -- fuel-sale intake, per-jurisdiction legal-metrology /
fire-safety / vapor-recovery regulatory assessment, fuel dispense
through the pump, and retail sale settlement for a community operator.

This repository publishes an automotive-fuel-retail actor -- fuel-sale
intake, per-jurisdiction forecourt-safety regulatory assessment, fuel
dispense and sale settlement -- as an OSS business that any qualified
operator can fork, deploy, run, improve and sell, so a regional fuel
retailer never surrenders legal-metrology and forecourt transaction
data to a closed POS / forecourt-AI SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **ForecourtAdvisor ⊣
Forecourt Safety Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:forecourt-safety-governor`,
is a UNIQUE keyword fleet-wide (grep-verified: no other blueprint
declares it) -- a fresh, independent build.

**Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing
bespoke capability library `kotoba-lang/logistics`), this vertical is
SELF-CONTAINED**: there is no `kotoba-lang/forecourt` to delegate
forecourt-safety validation to, so the meter-certainty / price-band /
overfill-ullage range checks live as pure functions in
`forecourt.registry` (and the vapor-recovery-mandated jurisdiction
split in `forecourt.facts`) and are re-verified independently by the
governor, rather than wrapping an external capability library's own
validated function.

> **Why an actor layer at all?** An LLM is great at drafting a
> fuel-sale summary, normalizing records, and reading a tank gauge --
> but it has **no notion of which jurisdiction's retail-fuel metering
> / fire-safety / vapor-recovery law is official, no license to
> dispense real fuel through a physical pump or settle a real retail
> sale, and no way to know on its own whether the pump's meter
> certification is actually still inside its legal validity, whether
> the unit-price actually lies inside the recorded competitive/legal
> price band, whether a dispense volume would actually breach the
> underground tank's ullage, or whether the vapor-recovery system is
> actually operational in a jurisdiction that mandates it**. Letting
> it dispense fuel or settle a sale directly invites fabricated
> regulatory citations, a dispense from an uncertified meter (a
> short-measure violation), a dispense into an already-full tank (a
> spill / overfill), and a dispense with an inoperational vapor-
> recovery system in a mandated jurisdiction (a VOC release) --
> exposing the public to a forecourt fire and environmental harm and
> the operator to real liability, for whoever runs it. This project
> seals the ForecourtAdvisor into a single node and wraps it with an
> independent **Forecourt Safety Governor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers fuel-sale intake through legal-metrology / fire-
safety / vapor-recovery regulatory assessment, fuel dispense and sale
settlement. It does **not**, by itself, hold any retail-fuel license,
hazardous-materials handling permit, or petroleum-storage license
required to operate a service station in a given jurisdiction, and it
does not claim to. It also does not perform the actual physical
construction or maintenance of the forecourt equipment itself, or
optimize forecourt inventory -- forecourt inventory replenishment and
tank-level optimization (the blueprint's own `:optimization`
technology) is a follow-up slice, not in this R0. Whoever deploys and
operates a live instance (a qualified station operator/manager)
supplies any jurisdiction-specific operating license, the real
POS / pump-controller / tank-gauge dispatch integration, and bears
that jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not have
to build the compliance layer from scratch.

### Actuation

**Dispensing real fuel through a physical pump and settling a real
retail sale are never autonomous, at any phase, by construction.**
Two independent layers enforce this (`forecourt.governor`'s
`:pump/dispense`/`:sale/settle` high-stakes gate and
`forecourt.phase`'s phase table, which never puts either op in any
phase's `:auto` set) -- see `forecourt.phase`'s docstring and
`test/forecourt/phase_test.cljk`'s
`pump-dispense-never-auto-at-any-phase`/`sale-settle-never-auto-at-
any-phase`. The actor may draft, check and recommend; a human station
manager is always the one who actually dispenses fuel through the
pump or settles a retail sale. Grounded in forecourt-safety doctrine
(the same discipline every regulator in `forecourt.facts` codifies: a
real dispense and a real settlement are human sign-off acts) -- a
genuine DUAL-actuation shape, applied SEQUENTIALLY to the SAME
fuel-sale (dispense first, settlement later), unlike `retailops`/
4711's own `:kind`-distinguished alternative-action shape.

## The core contract

```
fuel-sale intake + jurisdiction facts (forecourt.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────────┐
   │ ForecourtAdvisor      │ ─────────────▶ │ Forecourt Safety Governor │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence-     │
   └───────────────────────┘                 │ incomplete · meter-uncertain│
          │                 commit ◀┼ · price-anomaly · overfill-risk ·│
          │                         │ vapor-recovery-inoperational ·  │
    record + ledger        escalate ┼ already-dispensed ·              │
          │              (ALWAYS for│ already-settled                 │
          │       :pump/dispense/   │                                 │
          │       :sale/settle)     │                                 │
          ▼                          └───────────────────────────┘
      human approval
```

**The ForecourtAdvisor never dispenses fuel through a pump or settles
a sale the Forecourt Safety Governor would reject, and never does so
without a human sign-off.** Hard violations (fabricated regulatory
requirements; unsupported evidence; a meter past its legal validity;
a unit-price outside its band; a dispense volume that would breach
the tank's ullage; an inoperational vapor-recovery system in a
mandated jurisdiction; a double dispense/settlement) force **hold**
and *cannot* be approved past; a clean dispense/settlement proposal
still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean dispense + settlement lifecycle, plus seven HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here the fuel dispenser (the
pump) is the physical actuator -- an autonomous forecourt robot / the
pump controller performs the physical act of running a real volume of
fuel through the pump into a vehicle (and eventually stopping it),
under the actor, gated by the independent **Forecourt Safety
Governor**. The governor never dispatches hardware itself: a
dispense-clearing action must have cleared the same sign-off a human
station manager would need. This restates the fleet-wide robotics
premise three ways (ADR-2607011000): the blueprint declares
`:robotics true`, the README names the robot that performs the
physical act, and the Forecourt Safety Governor is the independent
gate that robot's command must pass -- a robot may run the pump, but
only after the governor and a human station manager both agree it is
safe to.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Forecourt Safety Governor, dispense/settlement draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4730`). Unlike the freight sibling, this vertical is NOT backed by a
separate bespoke domain capability lib: the forecourt-safety range
checks (meter-certainty vs legal validity, unit-price vs price band,
dispense volume vs tank ullage) are self-contained pure functions in
`forecourt.registry`, with the vapor-recovery-mandated jurisdiction
split in `forecourt.facts`, on top of the generic
robotics/identity/forms/dmn/bpmn/audit-ledger stack.

## Layout

| File | Role |
|---|---|
| `src/forecourt/store.cljk` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + dispense AND sale history (dual history). The double-actuation guard checks dedicated `:dispensed?`/`:settled?` booleans rather than a `:status` value |
| `src/forecourt/registry.cljk` | Dispense/settlement draft records, plus the self-contained forecourt-safety range-check pure functions (`meter-uncertain?`, `price-anomaly?`, `overfill-risk?`) the governor re-verifies against -- no external capability library to delegate to |
| `src/forecourt/facts.cljk` | Per-jurisdiction legal-metrology / fire-safety / vapor-recovery catalog with an official spec-basis citation + the `:mandated`/`:not-mandated` vapor-recovery jurisdiction split per entry, honest coverage reporting |
| `src/forecourt/forecourtadvisor.cljk` | **ForecourtAdvisor** -- `mock-advisor` ‖ `llm-advisor`; intake/price-assessment/dispense/settlement proposals |
| `src/forecourt/governor.cljk` | **Forecourt Safety Governor** -- 6 HARD checks (spec-basis · evidence-incomplete · meter-uncertain, the retailops meter-calibration discipline · price-anomaly, the retailops price-band discipline · overfill-risk, the fabrication ullage discipline · vapor-recovery-inoperational, the `:mandated`/`:not-mandated` jurisdiction split) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/forecourt/phase.cljk` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (dispense/settlement always human; fuel-sale intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/forecourt/operation.cljk` | **OperationActor** -- langgraph StateGraph |
| `src/forecourt/sim.cljk` | demo driver |
| `test/forecourt/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers fuel-sale intake through legal-metrology / fire-
safety / vapor-recovery regulatory assessment, fuel dispense and sale
settlement -- the core governed lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Fuel-sale intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:sale/intake`/`:price/verify`) | Real POS / pump-controller / tank-gauge integration, forecourt inventory replenishment and tank-level optimization |
| Fuel dispense, HARD-gated on full evidence, a meter in legal validity, a unit-price in band, tank ullage ok, an operational vapor-recovery system where mandated, plus a double-dispense guard (`:pump/dispense`) | |
| Sale settlement, HARD-gated on full evidence and no double-settlement (`:sale/settle`) | |
| Immutable audit ledger for every intake/assessment/dispense/settlement decision | |

Extending coverage is additive: add the next gate (e.g. an age-
verification or prepay-check) as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`forecourt.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `forecourt.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, NOR) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `forecourt.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

`:implemented` -- `ForecourtAdvisor` + `Forecourt Safety Governor`
run as real, tested code (see `Run` above), promoted from the
originally-published `:blueprint`-tier scaffold, following the SAME
governed-actor architecture as the other prior actors across this
fleet, with its own distinct, independently-named governor and its
own self-contained forecourt-safety range checks. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
