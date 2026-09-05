(ns forecourt.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL OperationActor (`forecourt.operation/build` -> a
  compiled langgraph-clj StateGraph) over the REAL seeded store
  (`forecourt.store/seed-db`), through the REAL Forecourt Safety
  Governor (`forecourt.governor/check`) and the REAL rollout phase gate
  (`forecourt.phase/gate`), then renders whatever those actually
  produced. Nothing on the page is a hand-typed reading:

    - every fuel-sale row is read back out of the store after the run
      (`store/all-fuel-sales`, `store/price-assessment-of`,
      `store/dispense-history`, `store/sale-history`, `store/ledger`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the safety-check columns are recomputed by calling the same pure
      predicates the governor calls (`registry/meter-uncertain?`,
      `registry/price-anomaly?`, `registry/overfill-risk?`,
      `facts/vapor-recovery-mandated?`), not copied from the page's
      prose,
    - the op-gate table is derived from `forecourt.phase/phases` and
      `forecourt.governor/high-stakes`; the governor-configuration and
      jurisdiction tables are read off `forecourt.governor` and
      `forecourt.facts/catalog` public vars.

  Subject provenance: every subject driven below is a fuel-sale id that
  `forecourt.store/demo-data` actually seeds (`sale-1` .. `sale-6`),
  verified by running this repo's own `clojure -M:dev:run` demo driver
  BEFORE this file was written. The renderer invents no subject and no
  number.

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Sets are sorted before rendering (a set has no
  order, and an unsorted render would not be byte-stable). Re-running
  writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [forecourt.facts :as facts]
            [forecourt.governor :as governor]
            [forecourt.operation :as op]
            [forecourt.phase :as phase]
            [forecourt.registry :as registry]
            [forecourt.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :station-manager :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one forecourt operation driven through the real actor.
  `:approval`, when present, is the human station manager's decision
  handed back to the graph paused by `interrupt-before
  #{:request-approval}`. Every `:subject` is a seeded fuel-sale id.

  The HARD-hold scenarios exercise each of the Forecourt Safety
  Governor's rules DIRECTLY against a seeded fuel-sale that genuinely
  violates it -- no fact is ever appended to the ledger by hand."
  [{:tid "t01" :request {:op :sale/intake :subject "sale-1"
                         :patch {:id "sale-1" :pump-id "P-03"}}}
   {:tid "t02" :request {:op :price/verify :subject "sale-1"}
    :approval {:status :approved :by "op-1"}}
   {:tid "t03" :request {:op :pump/dispense :subject "sale-1"}
    :approval {:status :approved :by "op-1"}}
   {:tid "t04" :request {:op :sale/settle :subject "sale-1"}
    :approval {:status :approved :by "op-1"}}

   ;; sale-2 is seeded in jurisdiction "ATL", which forecourt.facts
   ;; deliberately does not carry -> the advisor cites nothing.
   {:tid "t05" :request {:op :price/verify :subject "sale-2"}}
   ;; ... and with no assessment on file, a dispense of the same sale
   ;; fails the evidence-completeness rule.
   {:tid "t06" :request {:op :pump/dispense :subject "sale-2"}}

   {:tid "t07" :request {:op :price/verify :subject "sale-3"}
    :approval {:status :approved :by "op-1"}}
   {:tid "t08" :request {:op :pump/dispense :subject "sale-3"}}

   {:tid "t09" :request {:op :price/verify :subject "sale-4"}
    :approval {:status :approved :by "op-1"}}
   {:tid "t10" :request {:op :pump/dispense :subject "sale-4"}}

   {:tid "t11" :request {:op :price/verify :subject "sale-5"}
    :approval {:status :approved :by "op-1"}}
   {:tid "t12" :request {:op :pump/dispense :subject "sale-5"}}

   {:tid "t13" :request {:op :price/verify :subject "sale-6"}
    :approval {:status :approved :by "op-1"}}
   {:tid "t14" :request {:op :pump/dispense :subject "sale-6"}}

   ;; the two double-actuation guards, off dedicated booleans
   {:tid "t15" :request {:op :pump/dispense :subject "sale-1"}}
   {:tid "t16" :request {:op :sale/settle :subject "sale-1"}}

   ;; a governor-clean proposal a human DECLINES -- the approval layer is
   ;; not a rubber stamp. sale-5 was never dispensed, so settling it is a
   ;; call the station manager refuses.
   {:tid "t17" :request {:op :sale/settle :subject "sale-5"}
    :approval {:status :rejected :by "op-1"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context operator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario
  through it. Returns {:db store :runs [..]} -- every field rendered
  below is real governor/store output."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (cond
    (true? v)  "<span class=\"ok\">true</span>"
    (false? v) "<span class=\"muted\">false</span>"
    :else      "<span class=\"muted\">—</span>"))

(defn- yes-no [truthy yes no]
  (if truthy
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"critical\">" no "</span>")))

(defn- codes
  "Render a SEQUENCE in the order the code produced it -- used for
  `:basis`, whose order is the governor's own evaluation order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET. Sorted, because a set has no order and an unsorted
  render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- derived views -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "The HARD holds the Forecourt Safety Governor actually wrote."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"num\">" (esc value) "</span> "
       "<span class=\"muted\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger after "
               "driving " (count runs) " requests through " (code "forecourt.operation/build") ". "
               "Nothing here is a usage, revenue or performance claim.")
          (str "<p>"
               (stat "requests driven" (count runs)) " · "
               (stat "ledger facts" (count led)) " · "
               (stat "committed" (n :committed)) " · "
               (stat "HARD holds (governor)" (n :governor-hold)) " · "
               (stat "declined by a human" (n :approval-rejected)) " · "
               (stat "fuel dispensed" (count (store/dispense-history db))) " · "
               (stat "sales settled" (count (store/sale-history db)))
               "</p>"))))

(defn- disposition-cell [{:keys [disposition paused? human]}]
  (str (case disposition
         :commit "<span class=\"ok\">commit</span>"
         :hold   "<span class=\"critical\">HOLD</span>"
         :escalate "<span class=\"warn\">escalate</span>"
         (str "<span class=\"muted\">" (fmt disposition) "</span>"))
       (when paused?
         (str " <span class=\"muted\">· paused for a human</span>"))
       (when human
         (str " <span class=\"" (if (= :approved human) "ok" "err") "\">· human "
              (esc (name human)) "</span>"))))

(defn- timeline-section [runs]
  (card "Run timeline"
        (str "Each row is one graph run. " (code ":paused?")
             " means the compiled graph actually stopped at "
             (code "interrupt-before #{:request-approval}") " and waited for a person; "
             "a HARD hold never reaches that node at all. "
             "The verdict columns are the governor's own returned map.")
        (table ["Thread" "Op" "Subject" "Governor hard?" "High stakes?" "Confidence"
                "Paused for a human?" "Final disposition" "Basis"]
               (for [{:keys [tid request verdict paused?] :as r} runs]
                 (tr (code tid) (code (:op request)) (code (:subject request))
                     (if (:hard? verdict)
                       "<span class=\"critical\">yes</span>"
                       "<span class=\"ok\">no</span>")
                     (flag (:high-stakes? verdict))
                     (str "<span class=\"num\">" (fmt (:confidence verdict)) "</span>")
                     (if paused? "<span class=\"warn\">yes</span>"
                         "<span class=\"muted\">no</span>")
                     (disposition-cell r)
                     (codes (map :rule (:violations verdict))))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card (str "HARD holds — " (count hs) " on this run")
          (str "Read straight off the ledger facts whose " (code ":t") " is "
               (code ":governor-hold") ". A HARD violation cannot be overridden by any "
               "approver at any rollout phase: the graph routes it to " (code ":hold")
               " without ever reaching the human node. Every rule name and every detail "
               "string below is the governor's own " (code ":violations") " entry.")
          (table ["Op" "Subject" "Rule" "Confidence" "Governor detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (code (:op h)) (code (:subject h))
                       (str "<span class=\"critical\">" (code (:rule v)) "</span>")
                       (str "<span class=\"num\">" (fmt (:confidence h)) "</span>")
                       (esc (:detail v))))))))

(defn- human-section [runs]
  (let [paused (filterv :paused? runs)]
    (card "Human decisions"
          (str "Every run the compiled graph actually paused at "
               (code ":request-approval") ", and what the station manager did with it. "
               "The approval layer is not a rubber stamp: a governor-CLEAN proposal still "
               "escalates whenever the op is high-stakes or the phase does not make it "
               "auto-eligible, and the person may decline it. A declined proposal is "
               "written to the ledger by the same " (code ":hold") " node, with basis "
               (code ":approver-rejected") " — a human call, not a compliance violation. "
               "The escalation reason is the graph's own "
               (code ":approval-requested") " audit fact.")
          (if (seq paused)
            (table ["Thread" "Op" "Subject" "Escalation reason" "Confidence"
                    "Human decision" "Outcome"]
                   (for [{:keys [tid request escalation human disposition]} paused]
                     (tr (code tid) (code (:op request)) (code (:subject request))
                         (code (:reason escalation))
                         (str "<span class=\"num\">" (fmt (:confidence escalation)) "</span>")
                         (case human
                           :approved "<span class=\"ok\">approved</span>"
                           :rejected "<span class=\"err\">declined</span>"
                           "<span class=\"muted\">still waiting</span>")
                         (case disposition
                           :commit "<span class=\"ok\">committed</span>"
                           :hold "<span class=\"err\">held — :approver-rejected</span>"
                           (str "<span class=\"muted\">" (fmt disposition) "</span>")))))
            "<p class=\"muted\">no run paused for a human in this scenario</p>"))))

;; The op-gate contract is FIXED behaviour of this actor, not run
;; telemetry -- but even so it is DERIVED here from `forecourt.phase/
;; phases` and `forecourt.governor/high-stakes` rather than typed out,
;; so the table cannot drift away from the code. The only hand-written
;; content on this page is the static prose describing that contract
;; (this note and the `card` notes) -- every cell is derived.
(defn- op-gate-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Op gate — rollout phase " ph " (" label ")")
          (str "A governor HOLD always stays a HOLD. An op that may write but is not "
               "auto-eligible escalates to a human even when the governor is clean. "
               (code ":pump/dispense") " and " (code ":sale/settle") " are absent from every "
               "phase's " (code ":auto") " set — a permanent structural fact, not a rollout "
               "milestone still to come — and the governor independently marks them "
               "high-stakes. Two layers, not one, agree that real actuation is a human call.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"
                  "Always-human stake"]
                 (for [o (sort-by str phase/write-ops)]
                   (tr (code o)
                       (yes-no (contains? writes o) "yes" "no — HOLD (:phase-disabled)")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")
                       (if (contains? governor/high-stakes o)
                         "<span class=\"critical\">yes</span>"
                         "<span class=\"muted\">no</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "forecourt.governor") ". The "
             "meter-certainty reference date is pinned in code (an operation context may "
             "override it per run) so this page carries no clock.")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "meter-certainty reference date" (code governor/reference-date))
                (tr "always-human stakes" (kw-codes governor/high-stakes))
                (tr "governed write ops" (kw-codes phase/write-ops))])))

(defn- jurisdiction-section []
  (card "Jurisdiction spec-basis catalog"
        (str "The G2-style citation table the governor checks every proposal against "
             "(" (code "forecourt.facts/catalog") "). A jurisdiction absent from this table "
             "has NO spec-basis, full stop — the advisor must not invent one, and the "
             "governor holds if it tries. Coverage is reported honestly: this is a starting "
             "catalog, not a survey of every jurisdiction.")
        (table ["ISO3" "Owner authority" "Legal basis" "Stage-II vapor recovery"
                "Required evidence" "Provenance"]
               (for [[iso3 m] (sort-by key facts/catalog)]
                 (tr (code iso3) (esc (:owner-authority m)) (esc (:legal-basis m))
                     (if (facts/vapor-recovery-mandated? iso3)
                       (str "<span class=\"warn\">" (code (:vapor-recovery m)) "</span>")
                       (str "<span class=\"muted\">" (code (:vapor-recovery m)) "</span>"))
                     (esc (str/join "; " (:required-evidence m)))
                     (str "<code>" (esc (:provenance m)) "</code>"))))))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"err\">declined by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- fuel-sales-section [db]
  (let [led (ledger-of db)
        ref-date governor/reference-date]
    (card "Fuel sales"
          (str "Read back from " (code "forecourt.store/all-fuel-sales") " after the run. "
               "The four safety columns are recomputed HERE by calling the same pure "
               "predicates the governor calls — " (code "registry/meter-uncertain?") ", "
               (code "registry/price-anomaly?") ", " (code "registry/overfill-risk?") " and "
               (code "facts/vapor-recovery-mandated?") " — against each sale's own stored "
               "ground truth, so the page cannot disagree with the governor. "
               (code ":dispensed?") " / " (code ":settled?") " are the dedicated "
               "double-actuation guards (never a " (code ":status") " value).")
          (table ["Sale" "Sale id" "Pump" "Grade" "Jurisdiction" "Volume (L)" "Unit price"
                  "Price band" "Tank level (%)" "Ullage (L)" "Meter certified"
                  "Meter in validity?" "Price in band?" "Ullage ok?" "Vapor recovery"
                  "dispensed?" "settled?" "Dispense no." "Sale no." "Ledger status"]
                 (for [fs (store/all-fuel-sales db)]
                   (tr (code (:id fs)) (fmt (:sale-id fs)) (fmt (:pump-id fs))
                       (fmt (:product-grade fs)) (code (:jurisdiction fs))
                       (str "<span class=\"num\">" (fmt (:volume-liters fs)) "</span>")
                       (str "<span class=\"num\">" (fmt (:unit-price fs)) "</span>")
                       (str "<span class=\"num\">[" (fmt (:price-band-min fs)) ", "
                            (fmt (:price-band-max fs)) "]</span>")
                       (str "<span class=\"num\">" (fmt (:tank-level-pct fs)) "</span>")
                       (str "<span class=\"num\">" (fmt (:ullage-liters fs)) "</span>")
                       (str (fmt (:meter-certified-date fs)) " <span class=\"muted\">+ "
                            (fmt (:meter-validity-years fs)) "y</span>")
                       (yes-no (not (registry/meter-uncertain?
                                     (:meter-certified-date fs)
                                     (:meter-validity-years fs)
                                     ref-date))
                               "in validity" "uncertain")
                       (yes-no (not (registry/price-anomaly?
                                     (:unit-price fs) (:price-band-min fs)
                                     (:price-band-max fs)))
                               "in band" "anomaly")
                       (yes-no (not (registry/overfill-risk?
                                     (:tank-level-pct fs) (:volume-liters fs)
                                     (:ullage-liters fs)))
                               "within ullage" "overfill risk")
                       (let [mandated? (facts/vapor-recovery-mandated? (:jurisdiction fs))]
                         (cond
                           (not mandated?)
                           (str "<span class=\"muted\">not mandated here</span>")
                           (true? (:vapor-recovery-operational? fs))
                           "<span class=\"ok\">mandated · operational</span>"
                           :else
                           "<span class=\"critical\">mandated · inoperational</span>"))
                       (flag (:dispensed? fs)) (flag (:settled? fs))
                       (fmt (:dispense-number fs)) (fmt (:sale-number fs))
                       (subject-status led (:id fs))))))))

(defn- assessments-section [db]
  (let [rows (keep (fn [fs]
                     (when-let [a (store/price-assessment-of db (:id fs))]
                       [fs a]))
                   (store/all-fuel-sales db))]
    (card "Committed evidence assessments"
          (str "Committed " (code ":price-assessment/set") " payloads, read back per sale via "
               (code "forecourt.store/price-assessment-of") ". The governor's "
               (code ":evidence-incomplete") " rule requires the jurisdiction's whole "
               "required-evidence checklist to be satisfied before any dispense or "
               "settlement — a sale with no assessment on file cannot actuate at all.")
          (if (seq rows)
            (table ["Sale" "Jurisdiction" "Checklist items" "Checklist" "Complete for this jurisdiction?" "Spec basis"]
                   (for [[fs a] rows]
                     (tr (code (:id fs)) (code (:jurisdiction a))
                         (str "<span class=\"num\">" (count (:checklist a)) "</span>")
                         (esc (str/join "; " (:checklist a)))
                         (yes-no (facts/required-evidence-satisfied?
                                  (:jurisdiction fs) (:checklist a))
                                 "complete" "incomplete")
                         (if (:spec-basis a)
                           (str "<code>" (esc (:spec-basis a)) "</code>")
                           "<span class=\"critical\">none</span>"))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- records-section [db]
  (let [ds (store/dispense-history db)
        ss (store/sale-history db)
        rows (fn [hist]
               (for [r hist]
                 (tr (code (get r "record_id")) (code (get r "fuel_sale_id"))
                     (fmt (get r "kind")) (code (get r "jurisdiction"))
                     (flag (get r "immutable")))))]
    (card "Book-of-record drafts"
          (str "The append-only fuel-dispense and sale-settlement drafts minted at commit time "
               "by " (code "forecourt.registry") " (" (code "store/dispense-history") " / "
               (code "store/sale-history") "). These are the records an operator keeps — "
               "every certificate this actor produces is UNSIGNED; signing, and the physical "
               "act itself, remain the operator's.")
          (str
           "<h3>Fuel dispenses</h3>"
           (if (seq ds)
             (table ["Record" "Fuel sale" "Kind" "Jurisdiction" "immutable"] (rows ds))
             "<p class=\"muted\">none committed in this run</p>")
           "<h3>Sale settlements</h3>"
           (if (seq ss)
             (table ["Record" "Fuel sale" "Kind" "Jurisdiction" "immutable"] (rows ss))
             "<p class=\"muted\">none committed in this run</p>")))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "forecourt.store/ledger")
             " returns it. Every row was written by the graph's own "
             (code ":commit") " or " (code ":hold") " node during this run.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (str "<span class=\"num\">" (esc (inc i)) "</span>")
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "err"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-4730 (forecourt)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Retail sale of automotive fuel — operator console</h1>"
       "</header>\n"
       "<p class=\"subtitle\">"
       "<span class=\"badge\">ISIC 4730</span> "
       "<span class=\"badge\">forecourt</span> "
       "<span class=\"badge\">read-only sample</span> "
       "governor " (code "forecourt-safety-governor") " · actor "
       (esc (:actor-id operator)) " · role " (code (:actor-role operator))
       " · rollout phase " (esc (:phase operator))
       "</p>\n<main>\n"
       (str/join "\n"
                 [(summary-section db runs)
                  (timeline-section runs)
                  (holds-section db)
                  (human-section runs)
                  (op-gate-section)
                  (governor-section)
                  (jurisdiction-section)
                  (fuel-sales-section db)
                  (assessments-section db)
                  (records-section db)
                  (ledger-section db)])
       "\n</main>\n<footer>"
       "Generated at build time by <code>forecourt.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>forecourt.operation</code> actor graph over the real "
       "<code>forecourt.store</code> seed, through the real "
       "<code>forecourt.governor</code>. Deterministic — no clock, no randomness, no "
       "network, no timestamp in the page. This is a demo of the governed decision path "
       "only: no fuel was dispensed, no sale was settled, no certificate is signed, and no "
       "usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is
    ;; not evidence of a governor. Refuse to write one.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests, "
                  (count (store/dispense-history db)) " dispense drafts, "
                  (count (store/sale-history db)) " settlement drafts)"))))
