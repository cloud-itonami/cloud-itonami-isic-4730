(ns forecourt.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:pump/dispense`/`:sale/settle` must NEVER be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [forecourt.phase :as phase]))

(deftest pump-dispense-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real fuel dispense"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :pump/dispense))
          (str "phase " n " must not auto-commit :pump/dispense")))))

(deftest sale-settle-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real sale settlement"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :sale/settle))
          (str "phase " n " must not auto-commit :sale/settle")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":sale/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:sale/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :sale/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :pump/dispense} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :sale/settle} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :sale/intake} :commit)))))
