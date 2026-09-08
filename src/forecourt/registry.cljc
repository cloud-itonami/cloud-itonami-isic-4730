(ns forecourt.registry
  "Pure-function fuel-dispense + sale-settlement record construction --
  an append-only forecourt book-of-record draft -- AND the pure
  forecourt-safety range-check functions the Forecourt Safety Governor
  calls to re-verify a fuel-sale's own ground truth before any dispense.

  Unlike `freightops`/4920's own registry (which delegates tracking-
  number validation to a real, pre-existing bespoke capability library
  `kotoba-lang/logistics`), this automotive-fuel-retail vertical has NO
  pre-existing capability library to wrap -- there is no 'kotoba-lang/
  forecourt' to call. So this namespace is self-contained: the range
  checks (meter-certainty vs legal validity, unit-price vs price band,
  dispense volume vs tank ullage) are pure functions defined HERE, not
  delegated. The actor layer adds the governed proposal/approval loop on
  top; the governor calls these same pure functions to INDEPENDENTLY
  re-verify the fuel-sale's own recorded values before any real-world
  dispense, rather than trusting the advisor's self-reported confidence.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a fuel-dispense or sale-settlement
  record -- every operator/jurisdiction assigns its own reference
  format. This namespace does NOT invent one beyond a jurisdiction-
  scoped sequence number; it validates the record's required fields,
  the same honest, non-fabricating discipline `forecourt.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real POS / pump-controller / tank-gauge system. It builds
  the RECORD an operator would keep, not the act of dispensing real fuel
  through a pump or settling a real sale itself (that is `forecourt.
  operation`'s `:pump/dispense`/`:sale/settle`, always human-gated --
  see README `Actuation`)."
  (:require [kotoba.lang.text :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- parse-int
  "Parse a base-10 integer from `s`, or nil. Pure and portable across
  JVM / cljs (no date library needed for the meter-certainty check)."
  [s]
  (when (and s (re-find #"^-?[0-9]+$" (str s)))
    #?(:clj  (try (Integer/parseInt (str s)) (catch Exception _ nil))
       :cljs (try (js/parseInt (str s)) (catch :default _ nil)))))

(defn- add-years
  "ISO yyyy-MM-dd( ...) string -> the same month/day, with `years` added
  to the year. Returns nil on any parse failure. ISO calendar dates
  compare lexicographically, so the result is directly comparable to a
  reference date string. (Day-of-month edges like Feb 29 are not
  specially normalized -- demo dates use the first of the month; the
  conservative choice on an unparsable date is nil -> unsafe.)"
  [date-str years]
  (when (and (string? date-str) (>= (count date-str) 7) (int? years))
    (when-let [year (parse-int (subs date-str 0 4))]
      (str (+ year years) (subs date-str 4)))))

;; ----------------------------- forecourt-safety range checks (pure) -----------------------------
;;
;; The Forecourt Safety Governor calls these to INDEPENDENTLY re-verify
;; the fuel-sale's own recorded values before authorizing a dispense.
;; Each returns true when the value is provably OUTSIDE the safe envelope
;; -- the conservative forecourt-safety choice, matching the price-band
;; discipline of the retail siblings (`retailops.registry/price-within-
;; band?`), the measured-value-vs-rated-limit discipline of the
;; fabrication siblings, and the legal-meter-certainty discipline every
;; retail actor's meter-calibration check establishes: a value that
;; cannot be certified inside the safe envelope is treated as a
;; violation, not as 'unknown therefore ok'. Missing data -> violation
;; (cannot verify safe to dispense).

(defn meter-uncertain?
  "Legal meter-certainty: is the pump's `meter-certified-date` past the
  jurisdiction's legal reverification validity? The fuel-sale carries
  the operator-declared `validity-years` (the jurisdiction's legal
  reverification interval -- e.g. 7 years for a fixed fuel dispenser
  under JPN 計量法, state-set under NIST HB 44 in the US), exactly as
  the crude sibling's well carries its own operator-declared reservoir-
  pressure window. Pure ISO-date arithmetic: expiry = certified-date +
  validity-years; the meter is uncertain once the reference date passes
  the expiry. The retailops meter-calibration discipline, evaluated
  against the entity's own recorded ground truth. Missing any value ->
  unsafe (cannot verify the meter is in legal validity before
  authorizing a dispense)."
  [meter-certified-date validity-years reference-date]
  (let [expiry (add-years meter-certified-date validity-years)]
    (cond
      (or (nil? meter-certified-date) (nil? validity-years)
          (nil? reference-date) (nil? expiry)) true
      (pos? (compare reference-date expiry))   true
      :else                                    false)))

(defn price-anomaly?
  "Unit-price vs the fuel-sale's own recorded competitive/legal price
  band `[price-band-min, price-band-max]` -- the retailops price-band
  discipline (an honest reapplication of `retailops.registry/price-
  within-band?`), inverted: returns true when the unit-price lies
  OUTSIDE the band -- an anomalous price that may indicate a mis-keyed
  pump price or a deceptive-pricing concern. Missing any bound ->
  anomaly (cannot verify the price is in the legal/competitor band)."
  [unit-price price-band-min price-band-max]
  (cond
    (or (nil? unit-price) (nil? price-band-min) (nil? price-band-max)) true
    (or (< unit-price price-band-min) (> unit-price price-band-max))   true
    :else                                                             false))

(defn overfill-risk?
  "Underground-storage-tank ullage protection: would a dispense of
  `volume-liters` exceed the tank's available `ullage-liters` of free
  headspace? An honest reapplication of the fabrication 'measured value
  exceeds rated limit' discipline to the forecourt's tank ullage (the
  same overfill-protection envelope an automated tank-gauge system
  enforces). A dispense whose volume would breach the tank's recorded
  ullage envelope risks a spill / overfill condition the forecourt must
  not authorize. Evaluated UNCONDITIONALLY on every dispense.
  `tank-level-pct` is the tank's current fill gauge (the reading this
  ullage derives from); it is carried for the audit record and nil-
  checked here. Missing any value -> unsafe (cannot verify the tank's
  ullage envelope can accommodate the dispense)."
  [tank-level-pct volume-liters ullage-liters]
  (cond
    (or (nil? tank-level-pct) (nil? volume-liters) (nil? ullage-liters)) true
    (> volume-liters ullage-liters)                                     true
    :else                                                              false))

;; ----------------------------- record construction -----------------------------

(defn register-dispense-record
  "Validate + construct the FUEL-DISPENSE registration DRAFT -- the
  operator's own legal act of physically running a real volume of fuel
  through the pump into a vehicle. Pure function -- does not touch any
  real POS or pump-controller system; it builds the RECORD an operator
  would keep. `forecourt.governor` independently re-verifies the
  fuel-sale's own meter-certainty, price-band, ullage-envelope and
  vapor-recovery ground truth, and blocks a double-dispense of the same
  sale, before this is ever allowed to commit."
  [fuel-sale-id jurisdiction sequence]
  (when-not (and fuel-sale-id (not= fuel-sale-id ""))
    (throw (ex-info "fuel-dispense: fuel_sale_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "fuel-dispense: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "fuel-dispense: sequence must be >= 0" {})))
  (let [dispense-number (str (str/upper jurisdiction) "-DISPENSE-" (zero-pad sequence 6))
        record {"record_id" dispense-number
                "kind" "fuel-dispense-draft"
                "fuel_sale_id" fuel-sale-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "dispense_number" dispense-number
     "certificate" (unsigned-certificate "FuelDispense" dispense-number dispense-number)}))

(defn register-sale-record
  "Validate + construct the SALE-SETTLEMENT registration DRAFT -- the
  operator's own legal act of settling a real retail fuel sale (real
  money moving, the retail transaction finalized). Pure function --
  does not touch any real POS or payment system; it builds the RECORD an
  operator would keep. `forecourt.governor` independently re-verifies
  the fuel-sale's own evidence completeness and blocks a double-settle
  of the same sale, before this is ever allowed to commit."
  [fuel-sale-id jurisdiction sequence]
  (when-not (and fuel-sale-id (not= fuel-sale-id ""))
    (throw (ex-info "sale-settlement: fuel_sale_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "sale-settlement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "sale-settlement: sequence must be >= 0" {})))
  (let [sale-number (str (str/upper jurisdiction) "-SALE-" (zero-pad sequence 6))
        record {"record_id" sale-number
                "kind" "sale-settlement-draft"
                "fuel_sale_id" fuel-sale-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "sale_number" sale-number
     "certificate" (unsigned-certificate "SaleSettlement" sale-number sale-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
