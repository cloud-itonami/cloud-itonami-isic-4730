# Business Model: Community Automotive Fuel Retail

## Classification
- Repository: `cloud-itonami-isic-4730`
- ISIC Rev.5: `4730` — retail sale of automotive fuel
- Domain: `downstream/fuel-retail`
- Social impact: public safety, environmental protection, transparency
- Governor: `:forecourt-safety-governor`
- License: AGPL-3.0-or-later

## Scope
This actor covers fuel-sale intake through per-jurisdiction legal-metrology /
fire-safety / vapor-recovery regulatory assessment, fuel dispense (running a
real volume of fuel through the physical pump into a vehicle), and sale
settlement (retail-transaction finalization, real money moving) for a community
automotive-fuel retailer. It does **not**, by itself, hold any retail-fuel
license, hazardous-materials handling permit, or petroleum-storage license
required to operate a service station in a given jurisdiction, perform the
actual physical construction or maintenance of the forecourt equipment, or
optimize forecourt inventory (forecourt inventory replenishment and tank-level
optimization is a follow-up slice, not this R0). Whoever deploys a live
instance supplies the jurisdiction-specific operating license, the real
POS / pump-controller / tank-gauge integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited execution
scaffold so the operator does not have to build the compliance layer from
scratch.

## Customer
- independent gas-station / service-station operators and franchisees
- regional and community fuel retailers leaving closed POS / forecourt-AI SaaS
- convenience-store chains operating a forecourt alongside retail
- state weights-and-measures inspectors and franchise auditors who need an
  auditable, spec-cited fuel-sale record

## Offer
- fuel-sale intake and directory management
- per-jurisdiction legal-metrology / fire-safety / vapor-recovery regulatory
  assessment with an official spec-basis citation
- fuel dispense (through the pump) gated on full evidence, a meter in legal
  validity, a unit-price in band, tank ullage ok, and an operational
  vapor-recovery system where mandated
- sale settlement (retail-transaction finalization) with double-settlement
  prevention
- evidence checklisting (meter certification / legal verification,
  vapor-recovery system record where mandated)
- exception and hold workflows
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per operator / station
- support retainer with SLA
- POS and pump-controller / tank-gauge integration

## The `:forecourt-safety-governor` Decision Rule

This blueprint's `:itonami.blueprint/governor` is `:forecourt-safety-governor`.
It is the single authority that stands between "fuel could be dispensed through
a pump" and "fuel is allowed to be dispensed," and between "a sale could be
settled" and "it is allowed to settle." Every rule it enforces is traceable to
the domain (Community Automotive Fuel Retail, ISIC 4730) and to the three
`:social-impact` tags in `blueprint.edn` (`:safety`,
`:environmental-protection`, `:transparency`).

This is the rule the companion contract test
(`test/forecourt/governor_contract_test.clj`) encodes end-to-end: the
ForecourtAdvisor never dispenses fuel through a pump or settles a sale the
Forecourt Safety Governor would reject, `:pump/dispense` and `:sale/settle`
NEVER auto-commit at any phase, `:sale/intake` (no direct capital risk) MAY
auto-commit when clean, and every decision (commit OR hold) leaves exactly one
ledger fact.

**Authorizes a fuel dispense (`:pump/dispense`) or sale settlement
(`:sale/settle`) only when ALL of the following hold:**

1. **An official spec-basis citation exists for the jurisdiction** -- the
   governor will not authorize any `:price/verify`, `:pump/dispense`, or
   `:sale/settle` proposal whose jurisdiction has no entry in the
   `forecourt.facts` catalog (`:no-spec-basis`). This is the direct enforcement
   of `:transparency`: a jurisdiction whose retail-fuel metering / fire-safety /
   vapor-recovery requirements cannot be traced to an OFFICIAL public source is
   never guessed. The advisor must not fabricate a jurisdiction's requirements.
2. **The jurisdiction's required evidence is fully on file** -- for a dispense
   or settlement the fuel-sale's jurisdiction must have been verified with a
   complete legal-metrology / forecourt evidence checklist on record: the meter
   certification (legal verification) and the vapor-recovery system record
   (where mandated) (`:evidence-incomplete`). This protects `:safety`,
   `:environmental-protection`, and `:transparency`: a pump that cannot prove a
   legally verified meter and (where mandated) an operational vapor-recovery
   system never dispenses.
3. **The pump's meter certification is still inside its legal validity** -- the
   governor INDEPENDENTLY re-verifies the fuel-sale's own recorded
   `meter-certified-date` against its jurisdiction's legal reverification
   interval (`forecourt.registry/meter-uncertain?`, the retailops
   meter-calibration discipline, evaluated against the reference date). The
   meter is uncertain once the reference date passes the certified-date +
   validity-years (e.g. 7 years for a fixed fuel dispenser under JPN 計量法,
   state-set under NIST HB 44 in the US); dispensing past legal validity is a
   short-measure violation (`:meter-uncertain`).
4. **The unit-price lies inside its declared competitive/legal price band** --
   the governor INDEPENDENTLY re-verifies the fuel-sale's own recorded
   unit-price against its `[price-band-min, price-band-max]` band
   (`forecourt.registry/price-anomaly?`, the retailops price-band discipline,
   inverted). A unit-price outside its band may indicate a mis-keyed pump price
   or a deceptive-pricing concern; this is a `:transparency` (honest-pricing)
   gate (`:price-anomaly`).
5. **The dispense volume would not breach the underground tank's ullage** -- the
   governor INDEPENDENTLY re-verifies the dispense volume against the tank's
   recorded `ullage-liters` of free headspace (`forecourt.registry/overfill-risk?`,
   the fabrication measured-value-vs-rated-limit discipline applied to forecourt
   tank ullage -- the same overfill-protection envelope an automated tank-gauge
   system enforces). A dispense whose volume would breach the tank's ullage
   envelope risks a spill / overfill condition -- a fire hazard and an
   environmental-contamination precursor (`:overfill-risk`). Evaluated
   UNCONDITIONALLY on every dispense.
6. **The vapor-recovery system is operational in jurisdictions that MANDATE
   Stage-II vapor recovery** -- the governor INDEPENDENTLY re-verifies the
   vapor-recovery system is operational, but ONLY where the jurisdiction's
   spec-basis declares `:vapor-recovery :mandated` (GBR and NOR, implementing
   EU Directive 2009/126/EC). In a `:not-mandated` jurisdiction (JPN and USA,
   where onboard refueling vapor recovery / ORVR has displaced station-side
   Stage II) an inoperational VR system is NOT a violation here -- the
   requirement does not apply. This is a `:environmental-protection` gate (VOC-
   release prevention) grounded in the construction threshold-model
   `:mandated`/`:not-mandated` jurisdiction split in `forecourt.facts`
   (`:vapor-recovery-inoperational`).
7. **The fuel-sale has not already been dispensed, and the sale has not already
   been settled** -- a double dispense of the same fuel-sale is refused off a
   dedicated `:dispensed?` fact, and a double settlement off a dedicated
   `:settled?` fact (never a `:status` value), the double-actuation guard every
   sibling actor in this fleet enforces (`:already-dispensed` /
   `:already-settled`).

**Rejects (HOLD, un-overridable, never even reaches a human) when any of the
above fail.** A proposal with no spec-basis, incomplete evidence, a meter past
legal validity, a unit-price outside its band, a dispense volume that would
breach ullage, an inoperational vapor-recovery system in a mandated
jurisdiction, or a double dispense/settlement is held at the governor node -- a
human approver cannot override these, by construction.

**Always escalates to a human (never auto-commits) for `:pump/dispense` and
`:sale/settle`**, even when every check above is clean. Dispensing real fuel
through a physical pump (a real volume leaving the underground tank into a
vehicle) and settling a real retail sale (real money moving, the retail
transaction finalized) are the two real-world actuation events this actor
performs; both are always a human station manager's call. This is enforced by
TWO independent layers that agree on purpose: the governor's confidence /
actuation SOFT gate (a `:pump/dispense` / `:sale/settle` stake always
escalates) and `forecourt.phase`'s phase table, which never puts either op in
any phase's `:auto` set.

## Required Technologies

`blueprint.edn`'s `:itonami.blueprint/required-technologies` for this business,
and what each one is actually load-bearing for here (not a generic capability
list):

| Technology | What it is FOR in Community Automotive Fuel Retail |
|---|---|
| `:robotics` | The fuel dispenser (the pump) as physical actuator -- an autonomous forecourt robot / the pump controller performs the physical act of running a real volume of fuel through the pump into a vehicle (and eventually stopping it). The governor never dispatches hardware itself: a dispense-clearing action must have cleared the same sign-off a human station manager would need (see Robotics Premise). |
| `:identity` | Operator, station-manager, and cashier identity plus role-based access, so the governor's sign-off is tied to *who* authorized a dispense or settlement, not just *that* someone did. |
| `:forms` | Structured intake for fuel-sale booking, per-jurisdiction evidence capture (meter certification, vapor-recovery system record), and exception submission -- the data the Decision Rule above actually evaluates comes in through these forms. |
| `:dmn` | Encodes the `:forecourt-safety-governor` Decision Rule itself (spec-basis, evidence completeness, the three physical range checks, the vapor-recovery jurisdiction-split gate, the double-actuation guards, the actuation gate) as an evaluable decision table rather than code buried in application logic -- this is what makes the governor auditable and swappable per-deployment. |
| `:bpmn` | Orchestrates the intake -> verify -> dispense -> settle -> audit loop end-to-end (see `docs/operator-guide.md`) across fuel-sale intake, price/meter/vapor-recovery assessment, fuel dispense, and sale settlement, including the hold/escalation gate. |
| `:audit-ledger` | The immutable record of every assessment, dispense, settlement, hold, and approval -- this is what "an auditable, spec-cited fuel-sale record for every dispense and settlement" (Trust Controls, below) actually means in practice, and the evidence an operator needs if a dispense or settlement is later disputed by a customer, a franchisee, a weights-and-measures inspector, or a regulator. |
| `:optimization` | Forecourt inventory replenishment and tank-level optimization -- selects the reorder strategy for a station's underground tanks from the tank-gauge data this actor already records (`tank-level-pct`, `ullage-liters`). This R0 build deliberately scopes optimization OUT (see README `Business-process coverage`); the capability is correctly marked required, the integration is a follow-up slice. |

There is NO bespoke `:forecourt` capability library in this stack (unlike the
freight sibling's `:logistics`): the forecourt-safety range checks
(meter-certainty vs legal validity, unit-price vs price band, dispense volume
vs tank ullage) are self-contained pure functions in `forecourt.registry`, with
the vapor-recovery-mandated jurisdiction split in `forecourt.facts`, on top of
the generic robotics/identity/forms/dmn/bpmn/audit-ledger stack (see Capability
layer).

## Trust Controls
- a jurisdiction with no official spec-basis can never be verified, dispensed
  against, or settled against
- a dispense never starts with incomplete legal-metrology / forecourt evidence
- a dispense never starts with a meter past legal validity, a unit-price outside
  its band, a dispense volume that would breach tank ullage, or an inoperational
  vapor-recovery system in a mandated jurisdiction
- the same fuel-sale can never be dispensed or settled twice
- a dispense or settlement never auto-commits; both always need a human station
  manager
- every dispense and settlement (commit OR hold) leaves exactly one immutable
  ledger fact
- fuel-sale, meter, tank-gauge, and transaction data stays outside Git

## Implementation notes (`:implemented`)

The Decision Rule above is implemented faithfully by `forecourt.governor` as
eight HARD checks (a human approver cannot override them) plus one SOFT gate:

- `spec-basis-violations` -- the spec-basis check above, evaluated on every
  `:price/verify`, `:pump/dispense`, and `:sale/settle`.
- `evidence-incomplete-violations` -- the evidence-completeness check above,
  for `:pump/dispense` / `:sale/settle`.
- `meter-uncertain-violations` -- the legal-meter-certainty check above, an
  honest reapplication of the retailops meter-calibration discipline; pure ISO-
  date arithmetic (expiry = certified-date + validity-years) evaluated against
  the reference date; evaluated unconditionally on every `:pump/dispense`.
- `price-anomaly-violations` -- the unit-price-vs-band check above, an honest
  reapplication of the retailops price-band discipline
  (`retailops.registry/price-within-band?`), inverted; evaluated on every
  `:pump/dispense`.
- `overfill-risk-violations` -- the dispense-volume-vs-ullage check above, an
  honest reapplication of the fabrication measured-value-vs-rated-limit
  discipline to forecourt tank ullage; evaluated unconditionally on every
  `:pump/dispense`.
- `vapor-recovery-inoperational-violations` -- the vapor-recovery-operational
  check above, grounded in the construction threshold-model
  `:mandated`/`:not-mandated` jurisdiction split in `forecourt.facts`
  (`forecourt.facts/vapor-recovery-mandated?`); a violation ONLY where the
  jurisdiction mandates Stage-II vapor recovery; evaluated on every
  `:pump/dispense`.
- `already-dispense-violations` / `already-sale-violations` -- the
  double-actuation guards above, off dedicated `:dispensed?` / `:settled?`
  booleans (never a `:status` value), the same discipline every sibling
  governor's guards establish.
- the confidence floor / actuation SOFT gate -- low confidence, OR a
  `:pump/dispense` / `:sale/settle` stake, escalates to a human; and
  `forecourt.phase` independently never auto-commits either op at any phase.

`:pump/dispense` and `:sale/settle` are the two real-world actuation events
(`#{:pump/dispense :sale/settle}`), applied SEQUENTIALLY to the SAME fuel-sale
(dispense first, settlement later) rather than the retail sibling's `:kind`-
distinguished alternative-action shape -- the same sequential dual-actuation
shape the crude sibling's `well`, the repair-shop cluster's `ticket`, and the
quarrying cluster's `extraction` use (two real-world acts, in order, on one
entity). Neither ever auto-commits at any phase. Forecourt inventory
replenishment and tank-level optimization (the `:optimization` line above) is a
follow-up slice, not in this R0 build -- see README `Business-process coverage`.

## Capability layer

Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing bespoke
capability library `kotoba-lang/logistics`), this vertical is SELF-CONTAINED:
there is no `kotoba-lang/forecourt` to delegate forecourt-safety validation to.
The meter-certainty / price-band / overfill-ullage range checks live as pure
functions in `forecourt.registry` (and the vapor-recovery-mandated jurisdiction
split in `forecourt.facts`) and are re-verified independently by the governor,
rather than wrapping an external capability library's own validated function --
the same 'reuse a capability's own validated function' discipline
`retailops.governor`'s price-band and meter-calibration checks establish, here
applied to this vertical's OWN pure registry functions rather than a separate
library.

## Jurisdiction coverage (honest)

`forecourt.facts/catalog` currently seeds 4 jurisdictions with an official
spec-basis, each a REAL retail-automotive-fuel regime: Japan (METI 計量法
Measurement Act fuel-dispenser 型式適合検定 verification plus the 消防庁 Fire and
Disaster Management Agency 危険物第4類 Class-4 hazardous-materials fire-safety
regime over gasoline stations), the United States (NIST Handbook 44
Weights-and-Measures motor-fuel-dispenser regime plus NFPA 30/30A
flammable-liquids fire code), the United Kingdom (Trading Standards Petroleum
(Consolidation) Regulations plus weights-and-measures), and Norway
(Måleenhetslova Measurement Units Act plus brannfarlige varer flammable-goods
regime). The `:vapor-recovery` field records the construction threshold-model
split: GBR and NOR MANDATE Stage-II petrol vapor recovery (implementing EU
Directive 2009/126/EC); JPN and USA are `:not-mandated` (onboard refueling
vapor recovery / ORVR has displaced station-side Stage II), and the
`:vapor-recovery-inoperational` check applies ONLY where mandated. This is a
starting catalog to prove the governor contract end-to-end, not a claim of
global coverage (4 of ~194 jurisdictions worldwide). Adding a jurisdiction is
additive: one map entry in `forecourt.facts/catalog`, citing a real official
source -- never fabricate a jurisdiction's requirements to make coverage look
bigger.

## Maturity

`:implemented` -- `ForecourtAdvisor` + `Forecourt Safety Governor` run as real,
tested code (`clojure -M:dev:test`: 38 tests / 194 assertions, 0 failures; lint
clean), promoted from the originally-published `:blueprint`-tier scaffold,
following the SAME governed-actor architecture as the other prior actors
across this fleet, with its own distinct, independently-named governor and its
own self-contained forecourt-safety range checks. See
`docs/adr/0001-architecture.md` for the history and design.

## Robotics Premise

`blueprint.edn` sets `:itonami.blueprint/robotics true`. In this domain the
fuel dispenser (the pump) is the physical actuator -- an autonomous forecourt
robot / the pump controller performs the physical act of running a real volume
of fuel through the pump into a vehicle (and eventually stopping it), under the
actor, gated by the independent **Forecourt Safety Governor**. The governor
never dispatches hardware itself: a dispense-clearing action must have cleared
the same sign-off a human station manager would need. A robot may run the pump,
but only after the governor (every HARD check clean) and a human station
manager both agree it is safe to -- the same operating-state-machine-gated-by-
governor premise every cloud-itonami vertical restates (ADR-2607011000): the
blueprint declares `:robotics true`, the README names the robot that performs
the physical act, and the Forecourt Safety Governor is the independent gate
that robot's command must pass.
