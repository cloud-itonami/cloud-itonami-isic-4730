(ns forecourt.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [forecourt.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/fuel-sale s "sale-1"))))
      (is (= "P-03" (:pump-id (store/fuel-sale s "sale-1"))))
      (is (= 175.0 (:unit-price (store/fuel-sale s "sale-1"))))
      (is (= 7 (:meter-validity-years (store/fuel-sale s "sale-1"))))
      (is (= "ATL" (:jurisdiction (store/fuel-sale s "sale-2"))))
      (is (= "2018-06-01" (:meter-certified-date (store/fuel-sale s "sale-3"))) "sale-3 meter expired")
      (is (= 300.0 (:unit-price (store/fuel-sale s "sale-4"))) "sale-4 price anomaly")
      (is (= 30.0 (:ullage-liters (store/fuel-sale s "sale-5"))) "sale-5 overfill risk")
      (is (= "GBR" (:jurisdiction (store/fuel-sale s "sale-6"))))
      (is (false? (:vapor-recovery-operational? (store/fuel-sale s "sale-6"))) "sale-6 VR inoperational")
      (is (false? (:dispensed? (store/fuel-sale s "sale-1"))))
      (is (false? (:settled? (store/fuel-sale s "sale-1"))))
      (is (= ["sale-1" "sale-2" "sale-3" "sale-4" "sale-5" "sale-6"]
             (mapv :id (store/all-fuel-sales s))))
      (is (nil? (store/price-assessment-of s "sale-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/dispense-history s)))
      (is (= [] (store/sale-history s)))
      (is (zero? (store/next-dispense-sequence s "JPN")))
      (is (zero? (store/next-sale-sequence s "JPN")))
      (is (false? (store/fuel-sale-already-dispensed? s "sale-1")))
      (is (false? (store/fuel-sale-already-settled? s "sale-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :sale/upsert
                                 :value {:id "sale-1" :pump-id "P-09"}})
        (is (= "P-09" (:pump-id (store/fuel-sale s "sale-1"))))
        (is (= "JPN" (:jurisdiction (store/fuel-sale s "sale-1"))) "unrelated field preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :price-assessment/set :path ["sale-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/price-assessment-of s "sale-1"))))
      (testing "fuel dispense drafts a record and advances the dispense sequence"
        (store/commit-record! s {:effect :sale/mark-dispensed :path ["sale-1"]})
        (is (= "JPN-DISPENSE-000000" (get (first (store/dispense-history s)) "record_id")))
        (is (= "fuel-dispense-draft" (get (first (store/dispense-history s)) "kind")))
        (is (true? (:dispensed? (store/fuel-sale s "sale-1"))))
        (is (= 1 (count (store/dispense-history s))))
        (is (= 1 (store/next-dispense-sequence s "JPN")))
        (is (true? (store/fuel-sale-already-dispensed? s "sale-1"))))
      (testing "sale settlement drafts a record and advances the sale sequence"
        (store/commit-record! s {:effect :sale/mark-settled :path ["sale-1"]})
        (is (= "JPN-SALE-000000" (get (first (store/sale-history s)) "record_id")))
        (is (= "sale-settlement-draft" (get (first (store/sale-history s)) "kind")))
        (is (true? (:settled? (store/fuel-sale s "sale-1"))))
        (is (= 1 (count (store/sale-history s))))
        (is (= 1 (store/next-sale-sequence s "JPN")))
        (is (true? (store/fuel-sale-already-settled? s "sale-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/fuel-sale s "nope")))
    (is (= [] (store/all-fuel-sales s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispense-history s)))
    (is (= [] (store/sale-history s)))
    (is (zero? (store/next-dispense-sequence s "JPN")))
    (is (zero? (store/next-sale-sequence s "JPN")))
    (store/with-fuel-sales s {"x" {:id "x" :sale-id "FS-X" :pump-id "P-1"
                                  :product-grade "regular"
                                  :volume-liters 40.0 :unit-price 175.0
                                  :meter-certified-date "2025-06-01" :meter-validity-years 7
                                  :price-band-min 150.0 :price-band-max 200.0
                                  :tank-level-pct 60.0 :ullage-liters 2000.0
                                  :vapor-recovery-operational? true
                                  :dispensed? false :settled? false
                                  :jurisdiction "JPN" :status :intake}})
    (is (= "P-1" (:pump-id (store/fuel-sale s "x"))))))
