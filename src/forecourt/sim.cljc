(ns forecourt.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean fuel-sale through
  intake -> price/meter/VR safety assessment -> fuel dispense (escalate/
  approve/commit) -> sale settlement (escalate/approve/commit), then
  shows HARD-hold scenarios: a jurisdiction with no spec-basis, an
  uncertified meter, an anomalous unit-price, an ullage/overfill breach,
  an inoperational vapor-recovery system (in a VR-mandated jurisdiction),
  a double dispense, and a double settlement.

  Like every sibling actor's new checks, this actor's forecourt-safety
  checks (`meter-uncertain?`, `price-anomaly?`, `overfill-risk?`,
  `vapor-recovery-inoperational`) are evaluated directly at
  `:pump/dispense` time rather than via a separate screening op -- a
  real dispense decision validates meter certainty, price band, tank
  ullage and vapor-recovery readiness at the point of the act itself,
  not as a discrete pre-screening ceremony. Each check is still
  exercised directly and independently below, one fuel-sale per
  HARD-hold scenario, following the SAME 'exercise the failure mode
  directly, never only via a happy-path actuation' discipline
  `parksafety`'s ADR-2607071922 Decision 5 and every sibling since
  establish."
  (:require [langgraph.graph :as g]
            [forecourt.store :as store]
            [forecourt.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :station-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== sale/intake sale-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :sale/intake :subject "sale-1"
                                  :patch {:id "sale-1" :pump-id "P-03"}} operator))

    (println "== price/verify sale-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :price/verify :subject "sale-1"} operator))
    (println (approve! actor "t2"))

    (println "== pump/dispense sale-1 (always escalates -- :pump/dispense) ==")
    (let [r (exec-op actor "t3" {:op :pump/dispense :subject "sale-1"} operator)]
      (println r)
      (println "-- human station manager approves --")
      (println (approve! actor "t3")))

    (println "== sale/settle sale-1 (always escalates -- :sale/settle) ==")
    (let [r (exec-op actor "t4" {:op :sale/settle :subject "sale-1"} operator)]
      (println r)
      (println "-- human station manager approves --")
      (println (approve! actor "t4")))

    (println "== price/verify sale-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :price/verify :subject "sale-2"} operator))

    (println "== price/verify sale-3 (escalates -- human approves; sets up the meter-uncertain test) ==")
    (println (exec-op actor "t6" {:op :price/verify :subject "sale-3"} operator))
    (println (approve! actor "t6"))

    (println "== pump/dispense sale-3 (meter past legal validity -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :pump/dispense :subject "sale-3"} operator))

    (println "== price/verify sale-4 (escalates -- human approves; sets up the price-anomaly test) ==")
    (println (exec-op actor "t8" {:op :price/verify :subject "sale-4"} operator))
    (println (approve! actor "t8"))

    (println "== pump/dispense sale-4 (unit-price outside band -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :pump/dispense :subject "sale-4"} operator))

    (println "== price/verify sale-5 (escalates -- human approves; sets up the overfill test) ==")
    (println (exec-op actor "t10" {:op :price/verify :subject "sale-5"} operator))
    (println (approve! actor "t10"))

    (println "== pump/dispense sale-5 (dispense volume exceeds tank ullage -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :pump/dispense :subject "sale-5"} operator))

    (println "== price/verify sale-6 (escalates -- human approves; sets up the vapor-recovery test) ==")
    (println (exec-op actor "t12" {:op :price/verify :subject "sale-6"} operator))
    (println (approve! actor "t12"))

    (println "== pump/dispense sale-6 (vapor-recovery inoperational in a VR-mandated jurisdiction -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :pump/dispense :subject "sale-6"} operator))

    (println "== pump/dispense sale-1 AGAIN (double-dispense -> HARD hold) ==")
    (println (exec-op actor "t14" {:op :pump/dispense :subject "sale-1"} operator))

    (println "== sale/settle sale-1 AGAIN (double-settlement -> HARD hold) ==")
    (println (exec-op actor "t15" {:op :sale/settle :subject "sale-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft fuel-dispense records ==")
    (doseq [r (store/dispense-history db)] (println r))

    (println "== draft sale-settlement records ==")
    (doseq [r (store/sale-history db)] (println r))))
