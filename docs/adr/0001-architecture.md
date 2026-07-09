# ADR-0001: ForecourtAdvisor ⊣ Forecourt Safety Governor architecture

## Status

Accepted. `cloud-itonami-isic-4730` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-4730` publishes an OSS business blueprint for community
automotive fuel retail (fuel-sale intake, per-jurisdiction legal-metrology /
fire-safety / vapor-recovery regulatory assessment, fuel dispense, and sale
settlement). Like every prior actor in this fleet, the blueprint alone is not
an implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established by
`cloud-itonami-isic-6511` (life insurance) and applied across the fleet.

This actor is the **downstream retail** sibling in the petroleum value chain --
distinct from the upstream crude- and natural-gas-extraction siblings. The
shared discipline with those siblings is the sequential dual-actuation shape
(dispense first, settle later, on the SAME fuel-sale entity -- exactly like the
crude sibling's own `well` entity where `lift` and `settle` apply sequentially
to the same well); the domain difference is everything else. This is a retail
forecourt, not a wellhead: the governing concerns are legal metrology (no short
measure), honest pricing, tank ullage / spill protection, and vapor-recovery
readiness -- not the well-safety / sour-service envelope the upstream
extraction siblings govern. The meter-calibration and price-band disciplines
are reused from the `retailops` siblings (`retailops.governor`'s
meter-calibration and price-band checks); the ullage check is an honest
reapplication of the fabrication measured-value-vs-rated-limit discipline.

Like the crude- and natural-gas-extraction siblings and
`cloud-itonami-isic-0810` (quarrying), this vertical has NO bespoke domain
capability library in `kotoba-lang` to wrap (verified: no
`kotoba-lang/forecourt`-style repo exists, and `kotoba-lang/robotics` is the
generic cross-cutting robotics contract every cloud-itonami vertical already
uses, not a domain-specific library for this vertical). This build therefore
uses self-contained domain logic -- the same pattern the majority of this
fleet's actors use, and the explicit differentiator from
`cloud-itonami-isic-4920` (which wraps a pre-existing `kotoba-lang/logistics`
library). The forecourt-safety range checks (meter-certainty, price-band,
overfill/ullage) live as pure functions in `forecourt.registry`, with the
vapor-recovery-mandated jurisdiction split in `forecourt.facts`, and are
re-verified independently by the governor.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:forecourt-safety-governor`, is grep-verified UNIQUE fleet-wide -- no naming-
collision precedent question, a fresh independent build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:forecourt-safety-governor` is grep-verified unique across every
`blueprint.edn` in this fleet. This build follows the SAME governed-actor
architecture as every prior actor, but with its own distinct governor identity.

### Decision 2: self-contained domain logic (no `kotoba-lang/forecourt` to wrap)

Unlike `cloud-itonami-isic-4920` (freight, which delegates tracking-number
validation to a real, pre-existing `kotoba-lang/logistics` capability
library), this automotive-fuel-retail vertical has NO pre-existing forecourt
capability library to delegate forecourt-safety validation to. The three
physical range checks (meter-certainty vs legal validity, unit-price vs price
band, dispense volume vs tank ullage) are therefore pure functions defined in
`forecourt.registry` and called directly by `forecourt.governor` -- the SAME
'reuse a capability's own validated function' discipline
`retailops.governor`'s price-band and meter-calibration checks establish for a
capability library, here applied to this vertical's OWN pure registry functions
rather than a separate library. No literal code is shared with any sibling
(different domain), but the discipline is the same.

### Decision 3: dual-actuation shape, SEQUENTIAL on the SAME `fuel-sale` entity

Unlike the retail sibling's `order` entity (distinguished by `:kind`,
alternative sale-or-reorder actions), this vertical's `dispense` and `settle`
actuation events apply SEQUENTIALLY to the SAME `fuel-sale` -- a fuel dispense
happens first (a real volume of fuel run through the pump into a vehicle), the
sale settlement happens later (retail-transaction finalization, real money
moving), on the same fuel-sale record. This matches the crude sibling's `well`,
the repair-shop cluster's `ticket`, and the quarrying cluster's `extraction`
shape (two real-world acts, in order, on one entity). `high-stakes` is
`#{:pump/dispense :sale/settle}`; neither ever auto-commits at any phase.

### Decision 4: the forecourt-safety physical range-check suite -- honest reapplications of established fleet disciplines

The three physical range checks the governor runs on every `:pump/dispense` are
each an honest reapplication of an established fleet discipline to a forecourt
value, documented as such rather than claimed as novel inventions (the same
convention `cloud-itonami-isic-0162`'s Decision 3 establishes for
`dose-matches-claim?`):

- `meter-uncertain?` reapplies the **retailops meter-calibration** discipline to
  legal fuel-dispenser metrology: the pump's own recorded `meter-certified-date`
  must still be inside its jurisdiction's legal reverification validity
  (`validity-years`, e.g. 7 years for a fixed fuel dispenser under JPN 計量法,
  state-set under NIST HB 44 in the US). The meter is uncertain once the
  reference date passes the certified-date + validity-years; dispensing past
  legal validity is a short-measure violation. Pure ISO-date arithmetic
  (expiry = certified-date + validity-years), evaluated against the reference
  date.
- `price-anomaly?` reapplies the **retailops price-band** discipline
  (`retailops.registry/price-within-band?`), inverted: returns true when the
  fuel-sale's own recorded unit-price lies OUTSIDE its declared competitive/
  legal band `[price-band-min, price-band-max]` -- an anomalous price that may
  indicate a mis-keyed pump price or a deceptive-pricing concern.
- `overfill-risk?` reapplies the **fabrication measured-value-vs-rated-limit**
  discipline to the underground-storage tank's ullage: the dispense
  `volume-liters` must not exceed the tank's available `ullage-liters` of free
  headspace (the same overfill-protection envelope an automated tank-gauge
  system enforces). A dispense that would breach the tank's ullage envelope
  risks a spill / overfill condition -- a fire hazard and an environmental-
  contamination precursor.

Each returns `true` when the value is provably OUTSIDE the safe envelope; the
conservative forecourt-safety choice, missing data is a violation (cannot
verify safe to dispense). All three are evaluated UNCONDITIONALLY on every
`:pump/dispense`. No new unconditional-evaluation ordinals are claimed: every
check in this suite is a discipline-reapplication, documented per Decision 3 of
`cloud-itonami-isic-0162`.

### Decision 5: `vapor-recovery-inoperational` -- the construction threshold-model jurisdiction split

The fourth dispense-time HARD check does NOT reappraise a measured value against
a limit; it is a **jurisdiction-conditional construction threshold-model gate**.
Where a jurisdiction's spec-basis declares `:vapor-recovery :mandated` (GBR and
NOR, implementing EU Directive 2009/126/EC on Stage-II petrol vapor recovery),
an inoperational vapor-recovery system is a HARD, un-overridable hold -- a
VOC-release prevention gate. Where the jurisdiction declares
`:vapor-recovery :not-mandated` (JPN and USA, where onboard refueling vapor
recovery / ORVR has displaced station-side Stage II), the check does NOT apply
-- an inoperational VR system is not a violation in such a jurisdiction. The
split is grounded in `forecourt.facts/vapor-recovery-mandated?`, evaluated on
every `:pump/dispense`. This honestly models the real-world regulatory
construction (a threshold model: the requirement exists only above a mandated
threshold) rather than pretending every jurisdiction mandates the same thing.
This replaces the upstream petroleum siblings' `integrity-flag-unresolved`
check (Decision 5 in the crude-extraction sibling's own ADR): a forecourt has
no integrity-flag lifecycle, but it does have a jurisdiction-conditional
vapor-recovery requirement, which is the domain-appropriate gate here.

### Decision 6: dedicated double-actuation-guard booleans

`:dispensed?` / `:settled?` are dedicated booleans on the `fuel-sale` record,
never a single `:status` value -- the same discipline every prior governor's
guards establish, informed by `cloud-itonami-isic-6492`'s real status-lifecycle
bug (ADR-2607071320).

### Decision 7: Store protocol, MemStore + DatomicStore parity

`forecourt.store/Store` is implemented by both `MemStore` (atom-backed, default
for dev/tests/demo) and `DatomicStore` (`langchain.db`-backed), proven to
satisfy the same contract in `test/forecourt/store_contract_test.clj`. The
ledger stays append-only on every backend: which fuel-sale was screened for a
meter past legal validity, a unit-price outside its band, a dispense volume that
would breach ullage, or an inoperational vapor-recovery system in a mandated
jurisdiction, which fuel-sale had fuel dispensed, which sale was settled, on
what jurisdictional basis, approved by whom -- always a query over an immutable
log.

### Decision 8: Phase 0->3 with `:pump/dispense`/`:sale/settle` NEVER auto

`forecourt.phase`'s phase table puts `:sale/intake` (no direct capital risk) in
phase 3's `:auto` set as its only member; `:pump/dispense` and `:sale/settle`
are deliberately ABSENT from every phase's `:auto` set, including phase 3 -- a
permanent structural fact. `forecourt.governor`'s high-stakes gate enforces the
same invariant independently: two layers agree that actuation is always a human
station manager's call.

### Decision 9: mock + LLM advisor pair

`forecourt.forecourtadvisor` provides a deterministic `mock-advisor` (default,
runs offline) and an `llm-advisor` backed by a `langchain.model/ChatModel`. The
LLM advisor's EDN proposal is parsed defensively: any parse/shape failure
yields a safe low-confidence noop so the governor escalates/holds -- an LLM
hiccup can never auto-dispense fuel or auto-settle a sale.

## Alternatives considered

- **Wrapping a bespoke `kotoba-lang/forecourt` capability library.**
  Considered and explicitly ruled out: no such library exists, and
  `kotoba-lang/robotics` is generic, not forecourt-specific. Forcing a false
  capability-library integration would be dishonest; this build correctly
  uses self-contained domain logic instead.
- **Reusing the upstream petroleum extraction siblings' well-safety /
  sour-service checks.** Rejected: those are wellhead concerns and are not
  meaningful at a retail forecourt, where the governing concerns are legal
  metrology (meter certification), honest pricing (price band), spill/overfill
  protection (ullage), and vapor-recovery readiness. The honest
  domain-appropriate replacements are the meter-certainty, price-band,
  overfill and vapor-recovery checks, which
  reapply the retailops meter-calibration / price-band and fabrication
  measured-value-vs-rated-limit disciplines to forecourt-relevant values.
- **A `:kind`-distinguished entity** (matching the retail sibling's `order`
  shape). Rejected: dispense and settlement happen SEQUENTIALLY on the SAME
  fuel-sale in this domain, not as alternative actions -- the crude / repair-
  shop / quarrying cluster's sequential shape is the honest match here.
- **Pretending every jurisdiction mandates Stage-II vapor recovery.** Rejected
  in favor of the construction threshold-model `:mandated`/`:not-mandated`
  jurisdiction split (GBR/NOR mandated via EU Directive 2009/126/EC; JPN/USA
  not-mandated via ORVR displacement). Pretending uniform mandate would be the
  same dishonesty as fabricating a jurisdiction's requirements.
- **Claiming genuinely-new unconditional-evaluation ordinals for the physical
  range checks.** Rejected: each check reapplies an established fleet
  discipline (retailops meter-calibration, retailops price-band, fabrication
  ratio/value-vs-rated-limit) to a new domain. Per `cloud-itonami-isic-0162`
  Decision 3's convention, these are documented as honest
  discipline-reapplications, not claimed as novel inventions -- the same
  honesty discipline that forbids fabricating coverage also forbids
  over-claiming novelty.
- **Building forecourt inventory replenishment / tank-level optimization in
  this R0.** Rejected in favor of a scoped R0 slice (the `:optimization`
  capability is correctly marked required, the integration is a follow-up),
  consistent with this fleet's 'extending coverage is additive' convention.

## Consequences

- Adds the downstream automotive-fuel-retail (ISIC 4730) sibling to the
  petroleum value chain, complementing the upstream crude- and natural-gas-
  extraction siblings; the cloud-itonami fleet continues to populate on the
  same governed-actor architecture.
- Establishes the forecourt-safety physical range-check suite as honest
  reapplications of established fleet disciplines (retailops meter-calibration,
  retailops price-band, fabrication measured-value-vs-rated-limit) to a
  downstream retail forecourt -- plus the construction threshold-model
  vapor-recovery jurisdiction split as the domain-appropriate replacement for
  the upstream siblings' integrity-flag check, all discipline-reuse documented
  as such per `cloud-itonami-isic-0162` Decision 3.
- `MemStore` || `DatomicStore` parity is proven by
  `test/forecourt/store_contract_test.clj`.
- 38 tests / 194 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean dispense + settlement lifecycle,
  plus seven HARD-hold scenarios (no spec-basis, meter-uncertain,
  price-anomaly, overfill-risk, vapor-recovery-inoperational, double dispense,
  double settlement), end-to-end.
- `blueprint.edn` required no field-sync fixes (already correct) -- only the
  `:maturity` flip itself.

## References

- `cloud-itonami-isic-0610/docs/adr/0001-architecture.md` (crude sibling;
  shares the sequential dual-actuation shape on a single entity, here the
  fuel-sale vs the well)
- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of the
  general governed-actor architecture pattern)
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (freight sibling;
  contrast: wraps a pre-existing `kotoba-lang/logistics` capability library)
- `cloud-itonami-isic-0162/docs/adr/0001-architecture.md` (origin of the
  'honest reapplication, documented as such' convention this build follows
  for its physical range checks)
- 計量法 (Measurement Act), 燃料販売機器の型式適合検定; 消防法 危険物第4類 (Japan, 経済産業省 / 消防庁) — https://www.meti.go.jp/policy/measurement/
- NIST Handbook 44 (Specifications, Tolerances, and Other Technical Requirements for Weighing and Measuring Devices), motor-fuel dispensers; NFPA 30/30A (Flammable and Combustible Liquids Code / Code for Motor Fuel Dispensing Facilities and Repair Garages) (US) — https://www.nist.gov/weights-and-measures
- Petroleum (Consolidation) Regulations; weights and measures (UK, Trading Standards) — https://www.gov.uk/government/publications/petroleum-storage-and-filling-stations
- Måleenhetslova (Measurement Units Act); brannfarlige varer (flammable goods) (Norway, Justis- og beredskapsdepartementet / DTIM) — https://www.regjeringen.no/
- EU Directive 2009/126/EC (Stage II petrol vapor recovery during refuelling of motor vehicles at service stations) — the construction-threshold mandate basis for the GBR and NOR `:vapor-recovery :mandated` split
