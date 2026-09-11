(ns eleclighting.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo had a demo DRIVER
  (`eleclighting.sim`, `clojure -M:dev:run`) but no rendered page. This
  namespace drives the REAL actor stack -- `eleclighting.operation`
  (langgraph StateGraph) -> `eleclighting.advisor` -> `eleclighting.governor`
  -> `eleclighting.phase` -> `eleclighting.store` -- and renders the
  resulting SSoT + append-only ledger.

  NOTHING on the generated page is hand-written domain content. Every
  id, quantity, verdict, hold reason, draft record number and policy
  constant is read back out of the store, the ledger, or the governor/
  phase/registry vars themselves after the graph has actually run. The
  scenario inputs (unit counts, destinations, maintenance types, dates,
  the safety-concern text) are taken verbatim from this repo's own
  `eleclighting.sim` demo driver, and every entity they reference
  (`batch-001`..`batch-003`, `led-assy-001`, `photometric-test-002`) is
  a `eleclighting.store/sample-data!` seed entity.

  Determinism: no clock read, no randomness, no `System/getenv`, no
  map-iteration-order dependency (every set/map is sorted explicitly
  before rendering). Two runs against the same seed are byte-identical.

  Safety gate: `-main` REFUSES to write the page if the run produced no
  HARD governor hold. A console that shows only green rows would be
  advertising a governor that was never exercised.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [eleclighting.advisor :as advisor]
            [eleclighting.governor :as governor]
            [eleclighting.operation :as op]
            [eleclighting.phase :as phase]
            [eleclighting.registry :as registry]
            [eleclighting.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- scenario driver -----------------------------

(def ^:private coordinator
  "The requesting context. NOTE the actor-id is deliberately DIFFERENT
  from every approver id used below -- that is what makes the approver-
  attribution probe at the bottom of this file discriminating. If the
  requester and the approver shared an id (as `eleclighting.sim` has
  them do), `:actor \"coord-1\"` on a committed ledger fact would be
  indistinguishable from a retained approver id."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- at-phase [n] (assoc coordinator :phase n))

(defn- exec!
  ([actor tid request] (exec! actor tid request coordinator))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- decide!
  "Resume a paused (escalated) run with a human decision."
  [actor tid decision]
  (g/run* actor {:approval decision} {:thread-id tid :resume? true}))

(defn- hallucinating-advisor
  "An advisor that has been compromised/has hallucinated: for a
  perfectly legitimate `:schedule-maintenance` request against a
  VERIFIED, REGISTERED equipment unit it returns a proposal whose own
  `:effect` is a direct assembly-line control effect -- exactly the
  `:equipment-control-blocked` threat `eleclighting.governor`'s
  docstring names ('a hallucinated `:assembly-line/actuate`').

  Only the advisor node is swapped. The governor, phase gate, store and
  graph are the repo's own unmodified code -- this is how the
  `:equipment-control-blocked` check is reached on its own rather than
  only as a side effect of an unknown op (the stock
  `eleclighting.advisor/mock-advisor` never emits an out-of-allowlist
  effect for a KNOWN op, so the check would otherwise be invisible)."
  []
  (reify advisor/Advisor
    (-advise [_ _st req]
      {:summary    (str (:subject req) " 向け組立ライン直接起動提案")
       :rationale  "(compromised advisor -- 直接操作を提案している)"
       :cites      []
       :effect     :assembly-line/actuate
       :value      (:value req)
       :stake      nil
       :confidence 0.95})))

(defn run-demo!
  "Drives a freshly seeded store through every disposition this actor
  can reach, and returns `{:db .. :approvals [..]}`.

  Clean lifecycle (batch-001 / led-assy-001):
    1. `:log-production-batch` -- governor-clean, high-confidence, the
       ONLY op in any phase's `:auto` set -> AUTO-COMMIT at phase 3, no
       human involved.
    2. `:schedule-maintenance` mnt-1 -> escalate (never auto at any
       phase) -> approved by supervisor-1 -> commit (MNT draft).
    3. `:flag-safety-concern` concern-1 -> escalate (ALWAYS high-stakes)
       -> approved by supervisor-1 -> commit.
    4. `:coordinate-shipment` ship-1 (200 of batch-001's 5000) ->
       escalate -> approved by shipping-approver-1 -> commit (SHP draft).
    5. `:coordinate-shipment` ship-2 (50 of batch-002, filling it
       EXACTLY to its recorded 800) -> escalate -> approved -> commit.
       This is the exact-fill case the 1/10000-unit comparison in
       `registry/shipment-quantity-exceeded?` exists for.

  Reached a human and was REFUSED (contrast case -- not a HARD hold):
    6. `:coordinate-shipment` ship-3, governor-clean, human rejects.

  HARD governor holds -- none of these ever reaches a human:
    7.  `:not-propose-effect`            request `:effect :direct-write`
    8.  `:unknown-op`                    `:actuate-assembly-line`
    9.  `:equipment-control-blocked`     compromised advisor (see above)
    10. `:equipment-actuate-blocked`     `:actuate-equipment? true`
    11. `:certification-authority-blocked` `:issue-certification? true`
    12. `:equipment-not-verified`        photometric-test-002 (unverified)
    13. `:already-scheduled`             mnt-1 a second time
    14. `:batch-not-verified`            batch-003 (unverified)
    15. `:shipment-quantity-exceeded`    over batch-002's recorded quantity
    16. `:shipment-quantity-exceeded`    un-checkable (no stated units)
    17. `:invalid-product-type`          :unobtainium
    18. `:invalid-dielectric-test-kv`    12.0 kV (over the IEC-derived 4.0)
    19. `:invalid-defect-rate`           140.0 %

  Phase-gate holds -- also never reach a human, but carry NO governor
  violation (the governor was clean; the rollout phase simply does not
  enable the op yet):
    20. `:coordinate-shipment` at phase 1 -> `:phase-disabled`
    21. `:schedule-maintenance` at phase 0 -> `:phase-disabled`"
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        ;; same store, same governor, same graph -- only the advisor node differs
        compromised (op/build db {:advisor (hallucinating-advisor)})
        approvals (atom [])
        approve! (fn [tid by]
                   (let [r (decide! actor tid {:status :approved :by by})]
                     (swap! approvals conj {:thread tid :by by :result r})
                     r))]

    ;; ---- 1. clean intake, phase-3 auto-commit (no human) ----
    (exec! actor "t1-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :led-lamp :dielectric-test-kv 2.0
                    :defect-rate-percent 0.6 :last-assessed "2026-07-14"}})

    ;; ---- 2. maintenance window, escalate -> approved ----
    (exec! actor "t2-mnt"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "led-assy-001" :maintenance-type :optics-alignment-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! "t2-mnt" "supervisor-1")

    ;; ---- 3. safety concern, ALWAYS escalates -> approved ----
    (exec! actor "t3-concern"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "led-assy-001" :severity :moderate
                    :description "LEDドライバー基板の絶縁抵抗異常兆候、感電リスク懸念"}})
    (approve! "t3-concern" "supervisor-1")

    ;; ---- 4. shipment within headroom, escalate -> approved ----
    (exec! actor "t4-ship"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 200.0
                    :destination "buyer-warehouse-north"}})
    (approve! "t4-ship" "shipping-approver-1")

    ;; ---- 5. shipment filling batch-002 EXACTLY to capacity -> approved ----
    (exec! actor "t5-ship-exact"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-002" :units 50.0
                    :destination "buyer-warehouse-east"}})
    (approve! "t5-ship-exact" "shipping-approver-1")

    ;; ---- 6. governor-clean, but the human REFUSES ----
    (exec! actor "t6-ship-rejected"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-001" :units 500.0
                    :destination "buyer-warehouse-north"}})
    (decide! actor "t6-ship-rejected" {:status :rejected :by "shipping-approver-1"})

    ;; ---- 7..19 HARD governor holds ----
    (exec! actor "t7-not-propose"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :led-lamp}})

    (exec! actor "t8-unknown-op"
           {:op :actuate-assembly-line :effect :propose :subject "batch-001"})

    (exec! compromised "t9-control-blocked"
           {:op :schedule-maintenance :effect :propose :subject "mnt-9"
            :value {:equipment-id "led-assy-001" :maintenance-type :optics-alignment-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "t10-actuate"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "led-assy-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! actor "t11-certification"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:issue-certification? true}})

    (exec! actor "t12-unverified-equipment"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "photometric-test-002" :maintenance-type :calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "t13-already-scheduled"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "led-assy-001" :maintenance-type :optics-alignment-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "t14-unverified-batch"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-warehouse-south"}})

    (exec! actor "t15-over-quantity"
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "buyer-warehouse-east"}})

    ;; no :units at all -- headroom cannot be recomputed, so it is not headroom
    (exec! actor "t16-uncheckable"
           {:op :coordinate-shipment :effect :propose :subject "ship-6"
            :value {:batch-id "batch-001" :destination "buyer-warehouse-north"}})

    (exec! actor "t17-product-type"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :unobtainium}})

    (exec! actor "t18-dielectric"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:dielectric-test-kv 12.0}})

    (exec! actor "t19-defect-rate"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 140.0}})

    ;; ---- 20..21 phase-gate holds (governor clean, phase not there yet) ----
    (exec! actor "t20-phase1-ship"
           {:op :coordinate-shipment :effect :propose :subject "ship-7"
            :value {:batch-id "batch-001" :units 10.0
                    :destination "buyer-warehouse-north"}}
           (at-phase 1))

    (exec! actor "t21-phase0-mnt"
           {:op :schedule-maintenance :effect :propose :subject "mnt-4"
            :value {:equipment-id "led-assy-001" :maintenance-type :optics-alignment-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}}
           (at-phase 0))

    {:db db :approvals @approvals}))

;; ----------------------------- ledger classification -----------------------------

(defn hard-holds
  "The HARD governor holds on `ledger`: a `:governor-hold` fact carrying
  at least one governor violation. A phase-gate hold (`:phase-disabled`)
  is a `:governor-hold` fact with an EMPTY `:violations` -- the governor
  was clean and the rollout phase held it, which is a different claim --
  and an `:approval-rejected` fact is a hold that DID reach a human. Both
  are excluded here on purpose."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn- phase-holds [ledger]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn- human-rejections [ledger]
  (filterv #(= :approval-rejected (:t %)) ledger))

(defn- commits [ledger]
  (filterv #(= :committed (:t %)) ledger))

(defn- committed-shipments
  "The shipment entities the store actually holds, found by asking the
  LEDGER which `:coordinate-shipment` requests committed and then
  reading each one back out of the store. `store/Store` has no
  `all-shipments`, so the ledger is the only enumeration available --
  which is the right one anyway: a shipment that never committed must
  not appear on the page."
  [db ledger]
  (keep #(store/shipment db (:subject %))
        (filter #(= :coordinate-shipment (:op %)) (commits ledger))))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- ok [v] (str "<span class=\"ok\">" (esc v) "</span>"))
(defn- warn [v] (str "<span class=\"warn\">" (esc v) "</span>"))
(defn- bad [v] (str "<span class=\"critical\">" (esc v) "</span>"))
(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))
(defn- num-cell [v] (str "<span class=\"num\">" (esc v) "</span>"))

(defn- flag-cell [b yes no]
  (if (true? b) (ok yes) (bad no)))

(defn- row [& cells]
  (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       body
       "  </section>\n"))

(defn- kw-list
  "A deterministic, sorted, comma-joined `<code>` list of a set of
  keywords -- sets have no reliable iteration order."
  [s]
  (str/join ", " (map code (sort (map str s)))))

;; ----------------------------- derived sections -----------------------------

(defn- disposition-rows [ledger approvals]
  (let [hs (hard-holds ledger)
        ps (phase-holds ledger)
        rs (human-rejections ledger)
        cs (commits ledger)
        approved-subjects (set (keep #(get-in % [:result :state :request :subject]) approvals))
        auto (remove #(approved-subjects (:subject %)) cs)]
    [(row (ok "committed") (num-cell (count cs))
          "governor-clean and either phase-3 auto-eligible or human-approved")
     (row (ok "&nbsp;&nbsp;· auto-committed (no human)") (num-cell (count auto))
          (str "only " (code ":log-production-batch") " is in any phase's "
               (code ":auto") " set"))
     (row (ok "&nbsp;&nbsp;· human-approved") (num-cell (count approvals))
          "paused by <code>interrupt-before #{:request-approval}</code>, resumed by a human")
     (row (bad "HARD governor hold") (num-cell (count hs))
          "at least one governor violation &mdash; never reaches a human, no override")
     (row (warn "phase-gate hold") (num-cell (count ps))
          "governor clean, but the rollout phase does not enable the op yet")
     (row (warn "human-refused") (num-cell (count rs))
          "escalated, reached a human, and the human said no")]))

(defn- batch-row [b]
  (let [{:keys [id product-type model dielectric-test-kv quantity-units
                shipped-units defect-rate-percent verified? registered?
                last-assessed]} b
        headroom (- (double (or quantity-units 0.0)) (double (or shipped-units 0.0)))]
    (row (code id)
         (code product-type)
         (esc model)
         (num-cell (str dielectric-test-kv " kV"))
         (num-cell quantity-units)
         (num-cell shipped-units)
         (if (zero? headroom) (warn (str headroom)) (num-cell headroom))
         (num-cell (str defect-rate-percent " %"))
         (flag-cell verified? "verified" "UNVERIFIED")
         (flag-cell registered? "registered" "UNREGISTERED")
         (esc last-assessed))))

(defn- equipment-row [e]
  (let [{:keys [id kind verified? registered? last-maintenance-date
                last-scheduled-maintenance-date]} e]
    (row (code id)
         (code kind)
         (flag-cell verified? "verified" "UNVERIFIED")
         (flag-cell registered? "registered" "UNREGISTERED")
         (if last-maintenance-date (esc last-maintenance-date) (muted "none on record"))
         (if last-scheduled-maintenance-date
           (ok last-scheduled-maintenance-date)
           (muted "&mdash;")))))

(defn- maintenance-row [m]
  (let [{:keys [id equipment-id maintenance-type scheduled-date scheduled?
                maintenance-number actuate-equipment?]} m]
    (row (code id)
         (code equipment-id)
         (code maintenance-type)
         (esc scheduled-date)
         (if scheduled? (ok "scheduled") (muted "draft"))
         (code maintenance-number)
         (if (true? actuate-equipment?) (bad "actuate requested") (ok "draft only")))))

(defn- shipment-row [s]
  (let [{:keys [id batch-id units destination shipment-number]} s]
    (row (code id) (code batch-id) (num-cell units) (esc destination)
         (code shipment-number))))

(defn- concern-row [c]
  (let [{:keys [id equipment-id severity description]} c]
    (row (code id) (code equipment-id) (warn (str severity)) (esc description))))

(defn- draft-record-row [r]
  (row (code (get r "record_id"))
       (esc (get r "kind"))
       (esc (or (get r "maintenance_id") (get r "shipment_id")))
       (esc (or (get r "equipment_id") "&mdash;"))
       (if (get r "immutable") (ok "immutable") (bad "mutable"))))

(defn- rule-coverage-rows [ledger]
  (let [violations (for [f (hard-holds ledger)
                         v (:violations f)]
                     (assoc v :subject (:subject f) :op (:op f)))
        by-rule (group-by :rule violations)]
    (for [rule (sort (keys by-rule))
          :let [vs (get by-rule rule)]]
      (row (code rule)
           (num-cell (count vs))
           (str/join ", " (map code (sort (distinct (map :op vs)))))
           (str/join ", " (map code (sort (distinct (map :subject vs)))))
           (esc (:detail (first vs)))))))

(defn- governor-check-inventory
  "The governor's own check functions, read out of the loaded namespace
  rather than transcribed -- so this count cannot drift away from the
  code the page claims to describe."
  []
  (sort (map (comp name key)
             (filter #(str/ends-with? (name (key %)) "-violations")
                     (ns-interns 'eleclighting.governor)))))

(defn- phase-matrix-rows []
  (let [ops (sort (map str phase/write-ops))
        phase-ids (sort (keys phase/phases))]
    (for [op-str ops
          :let [op (keyword (subs op-str 1))]]
      (apply row (code op-str)
             (for [p phase-ids
                   :let [{:keys [writes auto]} (get phase/phases p)]]
               (cond (contains? auto op) (ok "auto")
                     (contains? writes op) (warn "approval")
                     :else (muted "&mdash; disabled")))))))

(defn- policy-rows []
  [(row (code "governor/confidence-floor") (num-cell governor/confidence-floor)
        "below this the proposal escalates to a human (SOFT &mdash; approvable)")
   (row (code "governor/allowed-ops") (kw-list governor/allowed-ops)
        "closed op allowlist &mdash; anything else is a HARD hold")
   (row (code "governor/allowed-proposal-effects") (kw-list governor/allowed-proposal-effects)
        "closed effect allowlist &mdash; every member is propose-shaped, never direct control")
   (row (code "governor/high-stakes") (kw-list governor/high-stakes)
        "always escalates to a human even when the governor is clean")
   (row (code "registry/valid-product-types") (kw-list registry/valid-product-types)
        "closed product-type set &mdash; a fabricated type is a HARD hold")
   (row (code "registry/dielectric-test-kv-{min,max}")
        (num-cell (str registry/dielectric-test-kv-min " .. " registry/dielectric-test-kv-max " kV"))
        "IEC 60598-1 / IEC 61347-1 electric-strength (hipot) plausibility band")
   (row (code "registry/defect-rate-{min,max}-percent")
        (num-cell (str registry/defect-rate-min-percent " .. " registry/defect-rate-max-percent " %"))
        "a batch cannot reject more than 100 % of its own output")
   (row (code "phase/default-phase") (num-cell phase/default-phase)
        "the phase this run used unless a scenario step overrode it")])

(defn- ledger-row [{:keys [t op subject disposition basis violations confidence phase-reason phase]}]
  (row (case t
         :committed (ok "committed")
         :governor-hold (if (seq violations) (bad "HARD hold") (warn "phase hold"))
         :approval-rejected (warn "human refused")
         (muted (str t)))
       (code op)
       (code subject)
       (esc (name (or disposition :n-a)))
       (if (seq basis)
         (str/join ", " (map #(code (str %)) basis))
         (if phase-reason
           (str (code phase-reason) " " (muted (str "(phase " phase ")")))
           (muted "&mdash;")))
       (if (seq violations)
         (str/join "<br>" (map #(esc (:detail %)) violations))
         (muted "&mdash;"))
       (if (some? confidence) (num-cell confidence) (muted "&mdash;"))))

;; ----------------------------- approver attribution probe -----------------------------

(def ^:private approver-key-re #"(?i)approv|(?i)\bby\b")

(defn- approver-entries
  "Every [k v] entry of `m` whose key name mentions an approver."
  [m]
  (when (map? m)
    (sort-by (comp str first)
             (filter (fn [[k _]] (and (keyword? k) (re-find approver-key-re (name k)))) m))))

(defn- approver-probe
  "MEASURES, rather than assumes, where a human approver's id actually
  lands after an approval.

  Fleet-wide, `eleclighting.operation`-shaped actors put the approver on
  the record channel's `:payload` while the store's `commit-record!`
  reads `:value` -- but whether that costs the approver id depends on
  each store's own commit dispatch, so this walks the ACTUAL store after
  the ACTUAL run and reports what it finds. Rendering this derived means
  the page self-corrects if the seam is ever fixed."
  [db ledger approvals]
  (let [ids (sort (distinct (map :by approvals)))
        entities (concat (store/all-batches db)
                         (store/all-equipment db)
                         (store/all-maintenance db)
                         (committed-shipments db ledger)
                         (store/safety-concerns db)
                         (vals (store/get-records db)))
        in-entities (mapcat approver-entries entities)
        in-ledger (mapcat approver-entries (store/ledger db))
        in-run-audit (for [{:keys [by result]} approvals
                           f (get-in result [:state :audit])
                           :when (= :approval-granted (:t f))]
                       {:by by :fact f})
        in-record-channel (for [{:keys [by result]} approvals
                                :let [rec (get-in result [:state :record])]
                                :when (seq (approver-entries (:payload rec)))]
                            {:by by :effect (:effect rec)
                             :payload-has (map first (approver-entries (:payload rec)))
                             :value-has (map first (approver-entries (:value rec)))})]
    {:approver-ids ids
     :approvals (count approvals)
     :in-entities in-entities
     :in-ledger in-ledger
     :in-run-audit in-run-audit
     :in-record-channel in-record-channel
     :retained-in-store? (boolean (or (seq in-entities) (seq in-ledger)))}))

(defn- approver-rows [probe]
  (let [{:keys [in-entities in-ledger in-run-audit in-record-channel]} probe]
    [(row "committed store entity (batch / equipment / maintenance / shipment / concern / records)"
          (if (seq in-entities)
            (ok (str (count in-entities) " approver key(s) present"))
            (bad "NOT retained"))
          (if (seq in-entities)
            (str/join ", " (map (fn [[k v]] (str (code k) " = " (esc v))) in-entities))
            (str "<code>store/commit-record!</code> destructures "
                 (code ":value") ", and the approver id is only ever put on "
                 (code ":payload"))))
     (row "append-only audit ledger (<code>store/ledger</code>)"
          (if (seq in-ledger)
            (ok (str (count in-ledger) " approver key(s) present"))
            (bad "NOT retained"))
          (if (seq in-ledger)
            (str/join ", " (map (fn [[k v]] (str (code k) " = " (esc v))) in-ledger))
            (str "the " (code ":commit") " node appends only "
                 (code ":t :committed") ", whose " (code ":actor")
                 " is the REQUESTER (" (code "coord-1")
                 "), not the approver")))
     (row "graph run <code>:record</code> channel"
          (if (seq in-record-channel)
            (warn (str (count in-record-channel) " run(s) carry it, in-memory only"))
            (bad "NOT present"))
          (if (seq in-record-channel)
            (str/join "<br>"
                      (for [{:keys [by effect payload-has value-has]} in-record-channel]
                        (str (code effect) ": " (code ":payload") " has "
                             (str/join ", " (map code payload-has))
                             " = " (esc by) "; " (code ":value") " has "
                             (if (seq value-has) (str/join ", " (map code value-has))
                                 "nothing"))))
            (muted "&mdash;")))
     (row "graph run <code>:audit</code> channel"
          (if (seq in-run-audit)
            (warn (str (count in-run-audit) " <code>:approval-granted</code> fact(s), in-memory only"))
            (bad "NOT present"))
          (if (seq in-run-audit)
            (str/join ", " (sort (distinct (map #(str (code ":by") " = " (esc (:by (:fact %))))
                                                in-run-audit))))
            (muted "&mdash;")))]))

;; ----------------------------- page -----------------------------

(defn render
  "Pure function: `{:db .. :approvals ..}` (already run) -> the full
  operator-console HTML string. No I/O, no clock, no randomness."
  [{:keys [db approvals]}]
  (let [ledger (vec (store/ledger db))
        hs (hard-holds ledger)
        probe (approver-probe db ledger approvals)
        shipments (committed-shipments db ledger)
        checks (governor-check-inventory)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2740 · electric lighting equipment — Operator Console</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of electric lighting equipment (ISIC 2740) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">propose-only · never actuates equipment</span> "
     "<span class=\"badge\">never issues a UL/CE mark</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>eleclighting.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by actually running the "
     "<code>eleclighting.operation</code> langgraph actor — advisor ⊣ "
     "<strong>Electric Lighting Plant Operations Governor</strong> ⊣ phase gate ⊣ store. "
     "Every value below is read back out of the store, the append-only ledger, or the "
     "governor/phase/registry vars after the run. Nothing here is hand-written.</p>\n"
     "<main>\n"

     (section "この run で何が起きたか — dispositions"
              (str "One coordination request = one supervised graph run. A HARD governor hold "
                   "is <strong>not</strong> an escalation: it never reaches a human and no "
                   "approval can override it.")
              (table ["Disposition" "Count" "Meaning"]
                     (disposition-rows ledger approvals)))

     (section "Production batches (SSoT after the run)"
              (str "Seeded by <code>eleclighting.store/sample-data!</code>; "
                   "<code>shipped-units</code> is moved only by a committed "
                   "<code>:shipment/propose</code>. Headroom is "
                   "<code>quantity-units − shipped-units</code>; the governor recomputes it "
                   "independently and refuses to ship when it cannot be computed at all.")
              (table ["Batch" "Product type" "Model" "Dielectric test" "Quantity (units)"
                      "Shipped" "Headroom" "Defect rate" "QC" "Registry" "Last assessed"]
                     (map batch-row (store/all-batches db))))

     (section "Assembly / test-bench equipment (SSoT after the run)"
              (str "Maintenance may only ever be <em>scheduled</em> against equipment that is "
                   "independently both <code>:verified?</code> and <code>:registered?</code>. "
                   "This actor never actuates any of it.")
              (table ["Equipment" "Kind" "Inspection" "Registry" "Last maintenance"
                      "Last scheduled window"]
                     (map equipment-row (store/all-equipment db))))

     (section "Maintenance windows (committed drafts)"
              "Draft windows only — production downtime is always a human plant supervisor's call, so <code>:schedule-maintenance</code> is absent from every phase's <code>:auto</code> set, phase 3 included."
              (table ["Maintenance" "Equipment" "Type" "Scheduled date" "Guard"
                      "Draft number" "Actuation"]
                     (map maintenance-row (store/all-maintenance db))))

     (section "Coordinated shipments (committed drafts)"
              "Coordination records only — this actor dispatches no freight carrier."
              (table ["Shipment" "Batch" "Units" "Destination" "Draft number"]
                     (map shipment-row shipments)))

     (section "Safety concerns (append-only)"
              (str "<code>:flag-safety-concern</code> carries "
                   "<code>:stake :coordination/safety-concern</code> unconditionally, so it "
                   "ALWAYS escalates to a human regardless of confidence — and it is never "
                   "gated on the referenced equipment being verified, so an administrative "
                   "technicality can never suppress a safety report.")
              (table ["Concern" "Equipment" "Severity" "Description"]
                     (map concern-row (store/safety-concerns db))))

     (section "Unsigned draft records (registry output)"
              (str "Every certificate this actor produces is <code>status: draft-unsigned</code> "
                   "with <code>issued_by_registry: false</code> — signature is the human's act, "
                   "never this actor's.")
              (table ["Record" "Kind" "Subject" "Equipment" "Immutability"]
                     (concat (map draft-record-row (store/maintenance-history db))
                             (map draft-record-row (store/shipment-history db)))))

     (section (str "HARD governor rules exercised — " (count (rule-coverage-rows ledger))
                   " distinct rules, " (count hs) " holds")
              (str "Derived from the ledger, not transcribed. "
                   "<code>eleclighting.governor</code> defines " (count checks)
                   " check functions (read out of the namespace itself): "
                   (str/join ", " (map code checks)) ".")
              (table ["Rule" "Holds" "Ops" "Subjects" "Governor's own detail (first occurrence)"]
                     (rule-coverage-rows ledger)))

     (section "Phase gate (0 → 3)"
              (str "A governor HOLD always stays a HOLD — the phase gate can only ever make an "
                   "op <em>more</em> restricted. Derived from <code>eleclighting.phase/phases</code>.")
              (table (cons "Op" (map #(str "phase " % " · " (:label (get phase/phases %)))
                                     (sort (keys phase/phases))))
                     (phase-matrix-rows)))

     (section "Policy constants (read from the code, not retyped)"
              nil
              (table ["Var" "Value" "Why"] (policy-rows)))

     (section "Approver attribution — measured, not assumed"
              (str "This run approved " (:approvals probe) " escalation(s) as "
                   (str/join " / " (map code (:approver-ids probe)))
                   ", deliberately using ids that differ from the requesting "
                   "<code>:actor-id coord-1</code> so a retained approver id would be "
                   "unambiguous. The table below is produced by walking the real store and "
                   "the real run state after the run — if this seam is ever fixed, the page "
                   "corrects itself on the next build. "
                   (if (:retained-in-store? probe)
                     "<strong class=\"ok\">Finding: the approver id IS retained durably.</strong>"
                     (str "<strong class=\"critical\">Finding: the approver id is NOT retained "
                          "durably.</strong> It exists only in the in-memory graph run and is "
                          "lost the moment the process exits, so the durable record cannot "
                          "distinguish &lsquo;a human approved this&rsquo; from &lsquo;nobody "
                          "did&rsquo;. Stated here rather than omitted; fixing it changes actor "
                          "SSoT semantics and does not belong in a demo commit.")))
              (table ["Where" "Approver id retained?" "Evidence"]
                     (approver-rows probe)))

     (section (str "Append-only audit ledger — " (count ledger) " facts")
              "Every proposal outcome this scenario produced, in commit order. The ledger is the audit trail a plant owner or downstream buyer trusting this coordinator reads."
              (table ["Fact" "Op" "Subject" "Disposition" "Basis" "Governor detail" "Confidence"]
                     (map ledger-row ledger)))

     "</main>\n"
     "<footer>\n"
     "  <p>ISIC Rev.5 2740 · <code>cloud-itonami-isic-2740</code> · AGPL-3.0-or-later. "
     "Deterministic build-time artefact: no timestamps, no randomness, no network. "
     "Regenerate with <code>clojure -M:dev:render-html</code>.</p>\n"
     "  <p>What this actor never does: actuate assembly or test-bench equipment, dispatch a real "
     "freight carrier, or issue an electric-lighting-equipment safety-certification mark "
     "(UL/CE) — the last two of which are permanently blocked by the governor with no "
     "human override.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        hs (hard-holds ledger)]
    ;; Refuse to publish a console that would advertise a governor which
    ;; was never actually exercised. Checked BEFORE the file is written.
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count hs) " HARD governor holds across "
                  (count (distinct (mapcat #(map :rule (:violations %)) hs)))
                  " distinct rules, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
