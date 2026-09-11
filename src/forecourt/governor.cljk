(ns forecourt.governor
  "Forecourt Safety Governor -- the independent compliance layer that
  earns the ForecourtAdvisor the right to commit. The LLM has no notion
  of jurisdictional retail-fuel metering / fire-safety / vapor-recovery
  law, whether a pump's own meter certification is actually still inside
  its legal validity, whether the unit-price actually lies inside the
  recorded competitive/legal price band, whether a dispense volume would
  actually breach the underground tank's ullage, whether the vapor-
  recovery system is actually operational in a jurisdiction that
  mandates it, or when an act stops being a draft and becomes a real
  fuel dispense or a real sale settlement, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  Unlike `freightops`/4920's own governor (built on TOP of a real,
  pre-existing bespoke capability library `kotoba-lang/logistics`),
  this automotive-fuel-retail vertical has NO pre-existing forecourt
  capability library to delegate to -- so the four forecourt-safety
  range checks (meter certainty, price band, overfill/ullage, vapor-
  recovery-operational) are pure functions defined in
  `forecourt.registry` (and the vapor-recovery-mandated jurisdiction
  split in `forecourt.facts`) and called directly here, the SAME 'reuse
  a capability library's own validated function' discipline
  `retailops.governor`'s price-band check establishes, here applied to
  this vertical's OWN pure registry functions rather than a separate
  library.

  `:itonami.blueprint/governor` is `:forecourt-safety-governor`,
  grep-verified UNIQUE fleet-wide -- no naming-collision precedent
  question, a fresh independent build following the SAME governed-
  actor architecture (langgraph StateGraph + independent Governor +
  Phase 0->3 rollout) established by `cloud-itonami-isic-6511`.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `forecourt.phase`: for `:stake :pump/dispense`/
  `:sale/settle` (a real dispense or settlement) NO phase ever allows
  auto-commit either. Two independent layers agree that actuation is
  always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`forecourt.facts`), or invent one?
    2. Evidence incomplete         -- for `:pump/dispense`/`:sale/settle`,
                                       has the sale actually been verified
                                       with a full meter/price/vapor-
                                       recovery evidence checklist on
                                       file?
    3. Meter uncertain             -- for `:pump/dispense`, INDEPENDENTLY
                                       verify the pump's own meter-
                                       certified-date is still inside its
                                       jurisdiction's legal validity via
                                       `forecourt.registry/meter-uncertain?`
                                       (the retailops meter-calibration
                                       discipline).
    4. Price anomaly               -- for `:pump/dispense`, INDEPENDENTLY
                                       verify the unit-price lies inside
                                       the sale's own recorded price band
                                       via `forecourt.registry/price-anomaly?`
                                       (the retailops price-band discipline).
    5. Overfill risk               -- for `:pump/dispense`, INDEPENDENTLY
                                       verify the dispense volume would not
                                       breach the underground tank's ullage
                                       via `forecourt.registry/overfill-risk?`
                                       (the fabrication measured-value-vs-
                                       rated-limit discipline), evaluated
                                       UNCONDITIONALLY.
    6. Vapor-recovery inoperational -- for `:pump/dispense`, INDEPENDENTLY
                                       verify the vapor-recovery system is
                                       operational in jurisdictions that
                                       MANDATE it (the construction
                                       threshold-model :mandated/`:not-
                                       mandated` jurisdiction split in
                                       `forecourt.facts`).
    7. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:pump/dispense`/
                                       `:sale/settle` (REAL acts)
                                       -> escalate.

  Two more guards, double-dispense/double-settlement prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-dispense-violations`/
  `already-sale-violations` refuse to dispense/settle the SAME sale
  twice, off dedicated `:dispensed?`/`:settled?` facts (never a
  `:status` value) -- the SAME 'check a dedicated boolean, not status'
  discipline every prior governor's guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [forecourt.facts :as facts]
            [forecourt.registry :as registry]
            [forecourt.store :as store]))

(def confidence-floor 0.6)

;; Reference date for the meter-certainty check. The meter is uncertain
;; once this date passes the certified-date + validity-years. Tests/demo
;; pin it here for determinism; an operation context may override it via
;; :reference-date (e.g. a deployment reading the wall clock at intake).
(def reference-date "2026-07-09")

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispensing real fuel through a physical pump (a real volume leaving
  the underground tank into a vehicle) and settling a real retail sale
  (real money moving, the retail transaction finalized) are the two
  real-world actuation events this actor performs -- a two-member set,
  matching every sibling's own dual-actuation shape."
  #{:pump/dispense :sale/settle})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:price/verify` (or `:pump/dispense`/`:sale/settle`) proposal with
  no spec-basis citation is a HARD violation -- never invent a
  jurisdiction's retail-fuel metering / fire-safety / vapor-recovery
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:price/verify :pump/dispense :sale/settle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:pump/dispense`/`:sale/settle`, the jurisdiction's required
  meter-certification / vapor-recovery evidence must actually be
  satisfied -- do not trust the advisor's self-reported confidence
  alone."
  [{:keys [op subject]} st]
  (when (contains? #{:pump/dispense :sale/settle} op)
    (let [fs (store/fuel-sale st subject)
          assessment (store/price-assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction fs) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(計量器検定/vapor-recovery稼働記録等)が充足していない状態での提案"}]))))

(defn- meter-uncertain-violations
  "For `:pump/dispense`, INDEPENDENTLY verify the pump's own meter-
  certified-date is still inside its jurisdiction's legal validity via
  `forecourt.registry/meter-uncertain?` (the retailops meter-calibration
  discipline), evaluated against the fuel-sale's own recorded meter-
  certified-date, meter-validity-years and the reference date. Evaluated
  UNCONDITIONALLY (every dispense needs a meter in legal validity)."
  [{:keys [op subject]} st reference-date]
  (when (= op :pump/dispense)
    (let [fs (store/fuel-sale st subject)]
      (when (registry/meter-uncertain?
             (:meter-certified-date fs)
             (:meter-validity-years fs)
             reference-date)
        [{:rule :meter-uncertain
          :detail (str subject " の計量器検定証印(" (:meter-certified-date fs)
                      ")が有効期間(" (:meter-validity-years fs)
                      "年)を経過 -- 計量外のポンプ燃料供給提案は進められない")}]))))

(defn- price-anomaly-violations
  "For `:pump/dispense`, INDEPENDENTLY verify the unit-price lies inside
  the sale's own recorded competitive/legal price band via
  `forecourt.registry/price-anomaly?` (the retailops price-band
  discipline). A unit-price outside its band may indicate a mis-keyed
  pump price or a deceptive-pricing concern."
  [{:keys [op subject]} st]
  (when (= op :pump/dispense)
    (let [fs (store/fuel-sale st subject)]
      (when (registry/price-anomaly?
             (:unit-price fs) (:price-band-min fs) (:price-band-max fs))
        [{:rule :price-anomaly
          :detail (str subject " の単価(" (:unit-price fs)
                      ")が価格帯[" (:price-band-min fs) ", "
                      (:price-band-max fs) "] の外 -- 異常単価のため給油提案は進められない")}]))))

(defn- overfill-risk-violations
  "For `:pump/dispense`, INDEPENDENTLY verify the dispense volume would
  not breach the underground tank's ullage via `forecourt.registry/
  overfill-risk?` (the fabrication measured-value-vs-rated-limit
  discipline). Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :pump/dispense)
    (let [fs (store/fuel-sale st subject)]
      (when (registry/overfill-risk?
             (:tank-level-pct fs) (:volume-liters fs) (:ullage-liters fs))
        [{:rule :overfill-risk
          :detail (str subject " の給油量(" (:volume-liters fs)
                      "L)が地下タンク空容(" (:ullage-liters fs)
                      "L)を超過 -- オーバーフィルリスクのため給油提案は進められない")}]))))

(defn- vapor-recovery-inoperational-violations
  "For `:pump/dispense`, INDEPENDENTLY verify the vapor-recovery system
  is operational in jurisdictions that MANDATE Stage-II vapor recovery
  (the construction threshold-model :mandated/`:not-mandated`
  jurisdiction split in `forecourt.facts`). In a non-mandated
  jurisdiction an inoperational VR system is NOT a violation here --
  the requirement does not apply."
  [{:keys [op subject]} st]
  (when (= op :pump/dispense)
    (let [fs (store/fuel-sale st subject)]
      (when (and (facts/vapor-recovery-mandated? (:jurisdiction fs))
                 (not (true? (:vapor-recovery-operational? fs))))
        [{:rule :vapor-recovery-inoperational
          :detail (str subject " はVR義務法域(" (:jurisdiction fs)
                      ")でvapor-recovery系が非稼働 -- 給油提案は進められない")}]))))

(defn- already-dispense-violations
  "For `:pump/dispense`, refuses to dispense the SAME sale twice, off a
  dedicated `:dispensed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :pump/dispense)
    (when (store/fuel-sale-already-dispensed? st subject)
      [{:rule :already-dispensed
        :detail (str subject " は既に給油済み")}])))

(defn- already-sale-violations
  "For `:sale/settle`, refuses to settle the SAME sale twice, off a
  dedicated `:settled?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :sale/settle)
    (when (store/fuel-sale-already-settled? st subject)
      [{:rule :already-settled
        :detail (str subject " は既に販売精算済み")}])))

(defn check
  "Censors a ForecourtAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}. `context` may carry :reference-date
  to override the meter-certainty reference date."
  [request context proposal st]
  (let [ref-date (or (:reference-date context) reference-date)
        hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (meter-uncertain-violations request st ref-date)
                           (price-anomaly-violations request st)
                           (overfill-risk-violations request st)
                           (vapor-recovery-inoperational-violations request st)
                           (already-dispense-violations request st)
                           (already-sale-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
