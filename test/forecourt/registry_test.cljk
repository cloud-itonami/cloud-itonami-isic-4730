(ns forecourt.registry-test
  (:require [clojure.test :refer [deftest is]]
            [forecourt.registry :as r]))

;; ----------------------------- range-check pure functions -----------------------------

(deftest meter-certainty-vs-legal-validity
  (is (not (r/meter-uncertain? "2025-06-01" 7 "2026-07-09")) "well within validity -> ok")
  (is (not (r/meter-uncertain? "2019-07-09" 7 "2026-07-09")) "exactly at expiry boundary -> ok (not past)")
  (is (not (r/meter-uncertain? "2019-07-10" 7 "2026-07-09")) "one day before expiry -> ok")
  (is (r/meter-uncertain? "2018-06-01" 7 "2026-07-09") "past expiry -> uncertain")
  (is (r/meter-uncertain? "2019-07-08" 7 "2026-07-09") "one day past expiry -> uncertain")
  (is (r/meter-uncertain? nil 7 "2026-07-09") "missing certified-date -> unsafe")
  (is (r/meter-uncertain? "2025-06-01" nil "2026-07-09") "missing validity-years -> unsafe")
  (is (r/meter-uncertain? "2025-06-01" 7 nil) "missing reference-date -> unsafe"))

(deftest unit-price-vs-band
  (is (not (r/price-anomaly? 175.0 150.0 200.0)) "in band -> ok")
  (is (not (r/price-anomaly? 150.0 150.0 200.0)) "at min boundary -> ok")
  (is (not (r/price-anomaly? 200.0 150.0 200.0)) "at max boundary -> ok")
  (is (r/price-anomaly? 300.0 150.0 200.0) "above max -> anomaly")
  (is (r/price-anomaly? 100.0 150.0 200.0) "below min -> anomaly")
  (is (r/price-anomaly? nil 150.0 200.0) "missing unit-price -> anomaly"))

(deftest dispense-volume-vs-ullage
  (is (not (r/overfill-risk? 60.0 40.0 2000.0)) "volume within ullage -> ok")
  (is (not (r/overfill-risk? 60.0 2000.0 2000.0)) "volume equals ullage -> ok (not a breach)")
  (is (r/overfill-risk? 96.0 50.0 30.0) "volume exceeds ullage -> overfill risk")
  (is (r/overfill-risk? 60.0 nil 2000.0) "missing volume -> unsafe")
  (is (r/overfill-risk? 60.0 40.0 nil) "missing ullage -> unsafe"))

;; ----------------------------- register-dispense-record -----------------------------

(deftest dispense-is-a-draft-not-a-real-dispense
  (let [result (r/register-dispense-record "sale-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest dispense-assigns-dispense-number
  (let [result (r/register-dispense-record "sale-1" "JPN" 7)]
    (is (= (get result "dispense_number") "JPN-DISPENSE-000007"))
    (is (= (get-in result ["record" "fuel_sale_id"]) "sale-1"))
    (is (= (get-in result ["record" "kind"]) "fuel-dispense-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest dispense-validation-rules
  (is (thrown? Exception (r/register-dispense-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-dispense-record "sale-1" "" 0)))
  (is (thrown? Exception (r/register-dispense-record "sale-1" "JPN" -1))))

;; ----------------------------- register-sale-record -----------------------------

(deftest settlement-is-a-draft-not-a-real-settlement
  (let [result (r/register-sale-record "sale-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest settlement-assigns-sale-number
  (let [result (r/register-sale-record "sale-1" "JPN" 7)]
    (is (= (get result "sale_number") "JPN-SALE-000007"))
    (is (= (get-in result ["record" "fuel_sale_id"]) "sale-1"))
    (is (= (get-in result ["record" "kind"]) "sale-settlement-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest settlement-validation-rules
  (is (thrown? Exception (r/register-sale-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-sale-record "sale-1" "" 0)))
  (is (thrown? Exception (r/register-sale-record "sale-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-dispense-record "sale-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-dispense-record "sale-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DISPENSE-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DISPENSE-000001" (get-in hist2 [1 "record_id"])))))
