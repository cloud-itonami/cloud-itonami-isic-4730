(ns forecourt.phase
  "Phase 0->3 staged rollout for the automotive-fuel-retail actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- fuel-sale intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-verify  -- adds price/meter/vapor-recovery
                                 assessment writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:sale/intake` (no capital risk
                                 yet) may auto-commit. `:pump/dispense`/
                                 `:sale/settle` NEVER auto-commit,
                                 at any phase.

  `:pump/dispense`/`:sale/settle` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural fact,
  not a rollout milestone still to come. Dispensing real fuel through a
  physical pump (a real volume leaving the underground tank into a
  vehicle) and settling the real sale (real money moving, the retail
  transaction finalized) are the two real-world physical / legal acts
  this actor performs; both are always a human station manager's call.
  `forecourt.governor`'s `:pump/dispense`/`:sale/settle` high-stakes
  gate enforces the same invariant independently -- two layers, not one,
  agree on this. Like every prior sibling's phase 3 `:auto` set, this
  domain has only ONE member (`:sale/intake`) -- no separate no-capital-
  risk 'file' lifecycle distinct from the fuel-sale itself.")

(def read-ops  #{})
(def write-ops #{:sale/intake :price/verify :pump/dispense :sale/settle})

;; NOTE the invariant: `:pump/dispense`/`:sale/settle` are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                              :auto #{}}
   1 {:label "assisted-intake"  :writes #{:sale/intake}                                                  :auto #{}}
   2 {:label "assisted-verify"  :writes #{:sale/intake :price/verify}                                    :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:sale/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:pump/dispense`/`:sale/settle` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or hold
    if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Forecourt Safety Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
