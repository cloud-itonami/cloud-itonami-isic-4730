(ns forecourt.facts
  "Per-jurisdiction downstream forecourt-safety regulatory catalog -- the
  G2-style spec-basis table the Forecourt Safety Governor checks every
  `:price/verify` proposal against ('did the advisor cite an OFFICIAL
  public source for this jurisdiction's retail-fuel metering / fire-safety
  / vapor-recovery requirements, or did it invent one?').

  Each entry below is a REAL jurisdiction with a REAL retail-automotive-
  fuel safety + legal-metrology regime: Japan's METI 計量法 (Measurement
  Act) fuel-dispenser verification jurisdiction plus the 消防庁 (Fire and
  Disaster Management Agency) 危険物第4類 (Class-4 hazardous materials)
  fire-safety regime over gasoline stations, the US NIST Handbook 44
  Weights-and-Measures motor-fuel-dispenser regime plus NFPA 30/30A
  flammable-liquids fire code, the UK Trading Standards Petroleum
  (Consolidation) Regulations plus weights-and-measures, and Norway's
  Måleenhetslova (Measurement Units Act) plus brannfarlige varer
  (flammable goods) regime. The required-evidence set (legal meter
  certification / verification, vapor-recovery system record where
  mandated) mirrors the evidence a forecourt regulator actually demands
  before a pump is authorized to dispense; `:vapor-recovery` records
  whether this jurisdiction's Stage-II petrol-vapor-recovery regime is
  `:mandated` (GBR and NOR, implementing EU Directive 2009/126/EC) or
  `:not-mandated` (JPN and USA, where onboard refueling vapor recovery
  has displaced station-side Stage II) -- the governor's
  `vapor-recovery-inoperational` check applies ONLY where mandated.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the legal-metrology
  / forecourt-evidence set (meter certification / legal verification,
  vapor-recovery system record where mandated); `:legal-basis` /
  `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any `:price/verify` proposal can commit. `:vapor-
  recovery` (:mandated | :not-mandated) is the jurisdiction split the
  governor's `vapor-recovery-inoperational` check is grounded in."
  {"JPN" {:name "JPN"
          :owner-authority "経済産業省 / 消防庁"
          :legal-basis "計量法 (型式適合検定); 消防法 危険物第4類"
          :provenance "https://www.meti.go.jp/policy/measurement/"
          :required-evidence ["meter certification (legal verification)"
                              "vapor-recovery system record (where mandated)"]
          :vapor-recovery :not-mandated}
   "USA" {:name "USA"
          :owner-authority "NIST Weights and Measures / NFPA"
          :legal-basis "NIST Handbook 44; NFPA 30/30A"
          :provenance "https://www.nist.gov/weights-and-measures"
          :required-evidence ["meter certification (legal verification)"
                              "vapor-recovery system record (where mandated)"]
          :vapor-recovery :not-mandated}
   "GBR" {:name "GBR"
          :owner-authority "Trading Standards / Petroleum Regs"
          :legal-basis "Petroleum (Consolidation) Regulations; weights and measures"
          :provenance "https://www.gov.uk/government/publications/petroleum-storage-and-filling-stations"
          :required-evidence ["meter certification (legal verification)"
                              "vapor-recovery system record (where mandated)"]
          :vapor-recovery :mandated}
   "NOR" {:name "NOR"
          :owner-authority "Justis- og beredskapsdepartementet / DTIM"
          :legal-basis "Måleenhetslova; brannfarlige varer"
          :provenance "https://www.regjeringen.no/"
          :required-evidence ["meter certification (legal verification)"
                              "vapor-recovery system record (where mandated)"]
          :vapor-recovery :mandated}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to dispense fuel or
  settle a sale on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4730 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `forecourt.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn vapor-recovery-mandated?
  "Does this jurisdiction's forecourt regime MANDATE Stage-II petrol
  vapor recovery? Returns true only where the seeded spec-basis declares
  `:vapor-recovery :mandated`; false (not 'unknown') where it declares
  `:not-mandated`. A jurisdiction with no spec-basis returns nil -- the
  governor's spec-basis check catches such a proposal before this check
  ever runs. The governor passes this to its
  `vapor-recovery-inoperational-violations` to decide whether an
  inoperational VR system is a violation at all in this jurisdiction."
  [iso3]
  (when-let [v (:vapor-recovery (spec-basis iso3))]
    (= :mandated v)))
