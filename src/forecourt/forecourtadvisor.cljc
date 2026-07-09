(ns forecourt.forecourtadvisor
  "ForecourtAdvisor client -- the *contained intelligence node* for the
  automotive-fuel-retail actor.

  It normalizes fuel-sale intake, drafts a per-jurisdiction meter/
  price/vapor-recovery evidence checklist, drafts the fuel-dispense
  action, and drafts the sale-settlement action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale
  + the fields it cited), never a committed record or a real dispense/
  settlement. Every output is censored downstream by `forecourt.governor`
  before anything touches the SSoT, and `:pump/dispense`/`:sale/settle`
  proposals NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :pump/dispense | :sale/settle | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [forecourt.facts :as facts]
            [forecourt.registry :as registry]
            [forecourt.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the pump-id, product-grade, unit-price or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "燃料販売記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :sale/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-price
  "Per-jurisdiction meter/price/vapor-recovery evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against: proposing
  a checklist for a jurisdiction with NO official spec-basis in
  `forecourt.facts` -- the Forecourt Safety Governor must reject this
  (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [fs (store/fuel-sale db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction fs))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "forecourt.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :price-assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :price-assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-dispense
  "Draft the actual FUEL-DISPENSE action -- running a real volume of
  fuel through the physical pump into a vehicle. ALWAYS `:stake
  :pump/dispense` -- this is a REAL-WORLD act (an autonomous forecourt
  robot / the pump controller physically dispenses fuel, or an operator
  does), never a draft the actor may auto-run. See README `Actuation`:
  no phase ever adds this op to a phase's `:auto` set
  (`forecourt.phase`); the governor also always escalates on
  `:pump/dispense`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [fs (store/fuel-sale db subject)
        ;; the advisor uses the governor's same reference date to draft a
        ;; read-only readiness rationale; the governor remains the authority.
        meter-ok? (and fs (not (registry/meter-uncertain?
                                (:meter-certified-date fs)
                                (:meter-validity-years fs)
                                "2026-07-09")))
        price-ok? (and fs (not (registry/price-anomaly?
                                (:unit-price fs) (:price-band-min fs) (:price-band-max fs))))
        overfill-ok? (and fs (not (registry/overfill-risk?
                                   (:tank-level-pct fs) (:volume-liters fs) (:ullage-liters fs))))
        vr-ok? (and fs
                    (or (not (facts/vapor-recovery-mandated? (:jurisdiction fs)))
                        (true? (:vapor-recovery-operational? fs))))]
    {:summary    (str subject " 向け給油提案"
                      (when fs (str " (pump=" (:pump-id fs) ", grade=" (:product-grade fs) ")")))
     :rationale  (if fs
                   (str "meter-in-validity?=" meter-ok?
                        " price-in-band?=" price-ok?
                        " ullage-ok?=" overfill-ok?
                        " vapor-recovery-ok?=" vr-ok?)
                   "fuel-saleが見つかりません")
     :cites      (if fs [subject] [])
     :effect     :sale/mark-dispensed
     :value      {:fuel-sale-id subject}
     :stake      :pump/dispense
     :confidence (if (and meter-ok? price-ok? overfill-ok? vr-ok?) 0.9 0.3)}))

(defn- propose-settlement
  "Draft the actual SALE-SETTLEMENT action -- settling a real retail
  fuel sale (real money moving, the retail transaction finalized).
  ALWAYS `:stake :sale/settle` -- this is a REAL-WORLD act (real money
  moves, the sale is finalized), never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`forecourt.phase`); the governor also always escalates
  on `:sale/settle`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [fs (store/fuel-sale db subject)
        dispensed? (and fs (:dispensed? fs))]
    {:summary    (str subject " 向け販売精算提案"
                      (when fs (str " (pump=" (:pump-id fs) ")")))
     :rationale  (if fs
                   (str "dispensed?=" dispensed?)
                   "fuel-saleが見つかりません")
     :cites      (if fs [subject] [])
     :effect     :sale/mark-settled
     :value      {:fuel-sale-id subject}
     :stake      :sale/settle
     :confidence (if dispensed? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :sale/intake       (normalize-intake db request)
    :price/verify      (assess-price db request)
    :pump/dispense     (propose-dispense db request)
    :sale/settle       (propose-settlement db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域自動車燃料小売事業者の給油・販売精算エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:sale/upsert|:price-assessment/set|:sale/mark-dispensed|"
       ":sale/mark-settled) "
       ":stake(:pump/dispense か :sale/settle か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の給油所安全要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "計量器検証状態・単価・タンク在庫・vapor-recovery稼働状態を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :price/verify  {:fuel-sale (store/fuel-sale st subject)}
    :pump/dispense {:fuel-sale (store/fuel-sale st subject)}
    :sale/settle   {:fuel-sale (store/fuel-sale st subject)}
    {:fuel-sale (store/fuel-sale st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Forecourt Safety Governor
  escalates/holds -- an LLM hiccup can never auto-dispense fuel or
  auto-settle a sale."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :forecourtadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
