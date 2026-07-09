(ns forecourt.governor-contract-test
  "The governor contract as executable tests. The single invariant
  under test:

    ForecourtAdvisor never dispenses fuel or settles a sale the
    Forecourt Safety Governor would reject, `:pump/dispense`/
    `:sale/settle` NEVER auto-commit at any phase, `:sale/intake`
    (no direct capital risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [forecourt.store :as store]
            [forecourt.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :station-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through price/verify -> approve, leaving a price/
  meter/VR evidence assessment on file. Uses distinct thread-ids per
  call site by suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :price/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- dispense!
  "Walks `subject` through price/verify -> dispense, both approved,
  leaving the sale dispensed. Uses distinct thread-ids per call site."
  [actor tid-prefix subject]
  (assess! actor (str tid-prefix "-pre") subject)
  (exec-op actor (str tid-prefix "-dispense") {:op :pump/dispense :subject subject} operator)
  (approve! actor (str tid-prefix "-dispense")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :sale/intake :subject "sale-1"
                   :patch {:id "sale-1" :pump-id "P-03"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "P-03" (:pump-id (store/fuel-sale db "sale-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest price-verify-always-needs-approval
  (testing "price/verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :price/verify :subject "sale-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/price-assessment-of db "sale-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a price/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :price/verify :subject "sale-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/price-assessment-of db "sale-2")) "no assessment written"))))

(deftest pump-dispense-without-assessment-is-held
  (testing "pump/dispense before any price/verify assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :pump/dispense :subject "sale-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest meter-uncertain-is-held-and-unoverridable
  (testing "a meter past its legal validity -> HOLD, and never reaches request-approval -- the retailops meter-calibration discipline"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "sale-3")
          res (exec-op actor "t5" {:op :pump/dispense :subject "sale-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:meter-uncertain} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispense-history db))))))

(deftest price-anomaly-is-held-and-unoverridable
  (testing "a unit-price outside the recorded band -> HOLD, and never reaches request-approval -- the retailops price-band discipline"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "sale-4")
          res (exec-op actor "t6" {:op :pump/dispense :subject "sale-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:price-anomaly} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispense-history db))))))

(deftest overfill-risk-is-held-and-unoverridable
  (testing "a dispense volume breaching the tank ullage -> HOLD, and never reaches request-approval -- the fabrication measured-value-vs-rated-limit discipline"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "sale-5")
          res (exec-op actor "t7" {:op :pump/dispense :subject "sale-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:overfill-risk} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispense-history db))))))

(deftest vapor-recovery-inoperational-is-held-and-unoverridable
  (testing "an inoperational vapor-recovery system in a VR-mandated jurisdiction -> HOLD, and never reaches request-approval -- the construction threshold-model :mandated/:not-mandated jurisdiction split"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "sale-6")
          res (exec-op actor "t8" {:op :pump/dispense :subject "sale-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:vapor-recovery-inoperational} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispense-history db))))))

(deftest pump-dispense-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, meter-in-validity, price-in-band, ullage-ok, VR-ok sale still ALWAYS interrupts for human approval -- :pump/dispense is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "sale-1")
          r1 (exec-op actor "t9" {:op :pump/dispense :subject "sale-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, dispense record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispensed? (store/fuel-sale db "sale-1"))))
          (is (= 1 (count (store/dispense-history db))) "one draft dispense record"))))))

(deftest sale-settle-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, already-dispensed sale still ALWAYS interrupts for human approval -- :sale/settle is never auto"
    (let [[db actor] (fresh)
          _ (dispense! actor "t10pre" "sale-1")
          r1 (exec-op actor "t10" {:op :sale/settle :subject "sale-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, settlement record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:settled? (store/fuel-sale db "sale-1"))))
          (is (= 1 (count (store/sale-history db))) "one draft settlement record"))))))

(deftest pump-dispense-double-dispense-is-held
  (testing "dispensing the same sale twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (dispense! actor "t11pre" "sale-1")
          res (exec-op actor "t11" {:op :pump/dispense :subject "sale-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispensed} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/dispense-history db))) "still only the one earlier dispense"))))

(deftest sale-settle-double-settlement-is-held
  (testing "settling the same sale twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (dispense! actor "t12pre" "sale-1")
          _ (exec-op actor "t12a" {:op :sale/settle :subject "sale-1"} operator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :sale/settle :subject "sale-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-settled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/sale-history db))) "still only the one earlier settlement"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :sale/intake :subject "sale-1"
                          :patch {:id "sale-1" :pump-id "P-03"}} operator)
      (exec-op actor "b" {:op :price/verify :subject "sale-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
