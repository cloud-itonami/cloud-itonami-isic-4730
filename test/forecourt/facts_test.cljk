(ns forecourt.facts-test
  (:require [clojure.test :refer [deftest is]]
            [forecourt.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest vapor-recovery-mandate-is-reported-honestly-per-jurisdiction
  ;; GBR and NOR mandate Stage-II petrol vapor recovery (EU 2009/126/EC,
  ;; carried in UK / EEA law); JPN and USA do not mandate station-side
  ;; Stage II at the national level (ORVR has displaced it). Reported
  ;; honestly here, not fabricated.
  (is (true?  (facts/vapor-recovery-mandated? "GBR")) "GBR mandates VR")
  (is (true?  (facts/vapor-recovery-mandated? "NOR")) "NOR mandates VR")
  (is (false? (facts/vapor-recovery-mandated? "JPN")) "JPN does not mandate VR")
  (is (false? (facts/vapor-recovery-mandated? "USA")) "USA does not mandate VR (federal)"))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL")))
  (is (nil? (facts/vapor-recovery-mandated? "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
