(ns eleclighting.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously had a demo
  driver (`eleclighting.sim`) but no rendered sample page and no
  generator at all.

  WHAT IS REAL HERE. Every batch figure, equipment flag, maintenance
  number, shipment number, safety-concern row, hold rule, hold detail
  and ledger row on the generated page is produced by actually running
  this repo's own actor stack -- `eleclighting.operation` (the
  langgraph-clj StateGraph) -> `eleclighting.advisor` ->
  `eleclighting.governor` -> `eleclighting.phase` -> `eleclighting.store`
  -- inside `run-demo!` below, and then read back out of the resulting
  store. Nothing in those tables is hand-typed. The seeded entity ids
  (`batch-001`..`batch-003`, `led-assy-001`, `photometric-test-002`)
  are the ones `eleclighting.store/sample-data!` actually defines,
  confirmed by running `clojure -M:dev:run` BEFORE this file was
  written.

  WHAT IS STATIC HERE. Exactly one table -- `action-gate-rows` -- is a
  prose description of this actor's fixed op contract (the closed
  allowlist in `eleclighting.governor/allowed-ops` and the phase gate
  in `eleclighting.phase/phases`). It documents behaviour that is fixed
  in source, not telemetry from the run, and it is labelled as such on
  the page. It states no counts, ids or measurements.

  DETERMINISM. No timestamp, no random source and no wall-clock value
  reaches the page. The store's own accessors sort (`all-batches`,
  `all-equipment`, `all-maintenance` all `sort-by :id`) and the ledger
  is append-only in scenario order, so two consecutive runs are
  byte-identical. Verify by rendering twice to different paths and
  diffing.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [eleclighting.registry :as registry]
            [eleclighting.store :as store]
            [eleclighting.operation :as op]))

;; ----------------------------- scenario -----------------------------

(def ^:private coordinator
  "The phase-3 (`supervised-auto`) plant coordinator identity every
  request below is executed as, matching `eleclighting.sim`."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private read-only-coordinator
  "The SAME coordinator pinned back to phase 0 (`read-only`), used once
  below to show that the rollout phase is an independent gate from the
  governor: a proposal the governor itself finds clean is still held."
  (assoc coordinator :phase 0))

(defn- exec!
  "One coordination request = one full graph run."
  ([actor tid request] (exec! actor tid request coordinator))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- approve!
  "Resume an interrupted run with a human plant supervisor's approval --
  the real `interrupt-before #{:request-approval}` handoff."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives a freshly seeded store through a scenario that reaches every
  disposition this actor can produce, and returns that store.

  Committed / approved paths:
    - `batch-001` production-batch logging -- governor-clean and
      phase-3 auto-eligible, so it commits with NO human in the loop
      (the only op in any phase's `:auto` set).
    - `mnt-1` maintenance window on `led-assy-001` (verified +
      registered) -- governor-clean, but `:schedule-maintenance` is
      deliberately absent from every phase's `:auto` set, so it
      ESCALATES and is committed only after a human approves.
    - `concern-1` safety concern -- `:stake :coordination/safety-concern`
      is permanently high-stakes, so it escalates regardless of the
      advisor's 0.9 confidence; approved.
    - `ship-1` shipment of 200 units against `batch-001` -- within the
      independently recomputed headroom; escalates, approved. The
      commit moves `batch-001`'s own `:shipped-units` from 1000 to
      1200, which the rendered batch table then reads back.

  HARD holds (governor `:hard?` -- these never reach a human at all;
  the graph routes straight to `:hold` and no approval can override
  them). Both PERMANENT scope boundaries are exercised:
    - `:equipment-actuate-blocked` (PERMANENT) -- `mnt-3` tries to
      directly ACTUATE assembly/test-bench equipment.
    - `:certification-authority-blocked` (PERMANENT) -- a batch patch
      tries to self-issue a UL/CE-class safety-certification mark, an
      accredited certification body's exclusive authority.
    - `:equipment-not-verified` -- `mnt-2` against the UNVERIFIED,
      unregistered `photometric-test-002` bench.
    - `:batch-not-verified` -- `ship-2` against the UNVERIFIED,
      unregistered `batch-003`.
    - `:shipment-quantity-exceeded` -- `ship-3` would push
      `batch-002` past its own logged production quantity
      (750 shipped + 100 requested > 800 produced), recomputed by the
      governor from the batch's own fields, never from the proposal.
    - `:already-scheduled` -- `mnt-1` a second time.
    - `:invalid-product-type` / `:invalid-dielectric-test-kv` /
      `:invalid-defect-rate` -- fabricated or sensor-error readings.
    - `:not-propose-effect` -- a mis-wired caller whose own request
      declares `:direct-write`, rejected before anything else runs.
    - `:unknown-op` -- an operation outside the closed four-op
      allowlist.

  Non-HARD hold (the independent phase layer, not the governor):
    - the same clean `batch-003` logging replayed at phase 0
      (`read-only`) is held with `:phase-reason :phase-disabled` and NO
      governor violations -- proof the two layers are separate."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean, auto-committed (no human) -------------------------------
    (exec! actor "b1-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :led-lamp :dielectric-test-kv 2.0
                    :defect-rate-percent 0.6 :last-assessed "2026-07-14"}})

    ;; --- clean, but always human-approved --------------------------------
    (exec! actor "mnt-1"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "led-assy-001"
                    :maintenance-type :optics-alignment-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "mnt-1")

    (exec! actor "concern-1"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "led-assy-001" :severity :moderate
                    :description "LEDドライバー基板の絶縁抵抗低下の兆候 -- 感電リスク懸念"}})
    (approve! actor "concern-1")

    (exec! actor "ship-1"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 200.0
                    :destination "buyer-warehouse-north"}})
    (approve! actor "ship-1")

    ;; --- PERMANENT scope boundaries (HARD, never reach a human) ----------
    (exec! actor "mnt-3"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "led-assy-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! actor "b1-cert"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:issue-certification? true}})

    ;; --- ground-truth HARD holds ------------------------------------------
    (exec! actor "mnt-2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "photometric-test-002"
                    :maintenance-type :calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "ship-2"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-warehouse-south"}})

    (exec! actor "ship-3"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "buyer-warehouse-east"}})

    (exec! actor "mnt-1-again"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "led-assy-001"
                    :maintenance-type :optics-alignment-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    ;; --- fabricated / implausible readings --------------------------------
    (exec! actor "b1-type"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :unobtainium}})

    (exec! actor "b1-kv"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:dielectric-test-kv 999999.0}})

    (exec! actor "b1-defect"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 999.0}})

    ;; --- structural HARD holds --------------------------------------------
    (exec! actor "b1-bypass"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :led-lamp}})

    (exec! actor "unknown-op"
           {:op :actuate-assembly-line :effect :propose :subject "batch-001"})

    ;; --- the phase layer, independent of the governor ---------------------
    (exec! actor "b3-phase0"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:product-type :street-light-fixture}}
           read-only-coordinator)

    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc
  "HTML-escape any interpolated value. Applied to EVERY runtime value
  that reaches the document."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- flag-cell [ok? label-ok label-bad]
  (if ok?
    (str "<span class=\"ok\">" label-ok "</span>")
    (str "<span class=\"critical\">" label-bad "</span>")))

(defn- headroom-cell
  "Remaining shippable units, computed the way the governor itself
  decides shipments: `registry/shipment-quantity-exceeded-checkable?`
  first. Un-checkable headroom is NOT headroom (this repo's own fix,
  see `eleclighting.registry`), so it is reported as un-checkable
  rather than as a number."
  [{:keys [quantity-units shipped-units] :as batch}]
  (if (registry/shipment-quantity-exceeded-checkable? batch 0.0)
    (str "<span class=\"num\">"
         (esc (- (double quantity-units) (double (or shipped-units 0.0))))
         "</span>")
    "<span class=\"critical\">検算不能</span>"))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell
  "Disposition of the LAST ledger fact naming this subject. Purely a
  read of the append-only ledger this run produced."
  [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">記録操作なし</span>"
      (= :governor-hold (:t f))
      (if-let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold · " (esc (nm rule)) "</span>")
        (str "<span class=\"warn\">hold · " (esc (nm (:phase-reason f))) "</span>"))
      (= :approval-rejected (:t f)) "<span class=\"critical\">承認却下</span>"
      (= :committed (:t f)) "<span class=\"ok\">commit 済み</span>"
      :else "<span class=\"muted\">進行中</span>")))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

;; ----------------------------- table builders -----------------------------

(defn- batch-row [ledger {:keys [id product-type model dielectric-test-kv
                                 quantity-units shipped-units
                                 defect-rate-percent verified? registered?]
                          :as b}]
  (row (str "<code>" (esc id) "</code>")
       (esc (nm product-type))
       (esc model)
       (str "<span class=\"num\">" (esc dielectric-test-kv) "</span>")
       (str "<span class=\"num\">" (esc quantity-units) "</span>")
       (str "<span class=\"num\">" (esc shipped-units) "</span>")
       (headroom-cell b)
       (str "<span class=\"num\">" (esc defect-rate-percent) "</span>")
       (flag-cell (and verified? registered?) "検証済・登録済" "未検証/未登録")
       (status-cell ledger id)))

(defn- equipment-row [{:keys [id kind verified? registered?
                              last-maintenance-date
                              last-scheduled-maintenance-date]}]
  (row (str "<code>" (esc id) "</code>")
       (esc (nm kind))
       (flag-cell (and verified? registered?) "検証済・登録済" "未検証/未登録")
       (if last-maintenance-date
         (esc last-maintenance-date)
         "<span class=\"muted\">記録なし</span>")
       (if last-scheduled-maintenance-date
         (str "<span class=\"ok\">" (esc last-scheduled-maintenance-date) "</span>")
         "<span class=\"muted\">予定なし</span>")))

(defn- maintenance-row [ledger {:keys [id equipment-id maintenance-type
                                       scheduled-date maintenance-number
                                       approved-by scheduled?]}]
  (row (str "<code>" (esc id) "</code>")
       (str "<code>" (esc maintenance-number) "</code>")
       (str "<code>" (esc equipment-id) "</code>")
       (esc (nm maintenance-type))
       (esc scheduled-date)
       (if approved-by
         (str "<span class=\"ok\">" (esc approved-by) "</span>")
         "<span class=\"muted\">-</span>")
       (if scheduled?
         (status-cell ledger id)
         "<span class=\"muted\">draft</span>")))

(defn- shipment-row [ledger {:keys [id shipment-number batch-id units
                                    destination approved-by]}]
  (row (str "<code>" (esc id) "</code>")
       (str "<code>" (esc shipment-number) "</code>")
       (str "<code>" (esc batch-id) "</code>")
       (str "<span class=\"num\">" (esc units) "</span>")
       (esc destination)
       (if approved-by
         (str "<span class=\"ok\">" (esc approved-by) "</span>")
         "<span class=\"muted\">-</span>")
       (status-cell ledger id)))

(defn- concern-row [{:keys [id equipment-id severity description approved-by]}]
  (row (str "<code>" (esc id) "</code>")
       (str "<code>" (esc equipment-id) "</code>")
       (str "<span class=\"warn\">" (esc (nm severity)) "</span>")
       (esc description)
       (if approved-by
         (str "<span class=\"ok\">" (esc approved-by) "</span>")
         "<span class=\"muted\">-</span>")))

(defn- hold-rows
  "One row per governor violation actually recorded this run. A hold
  carrying violations is HARD (every governor check except the
  confidence / high-stakes gate is HARD, and that gate escalates
  instead of holding); a hold carrying none came from the phase gate."
  [ledger]
  (for [{:keys [t op subject violations phase-reason]} ledger
        :when (#{:governor-hold :approval-rejected} t)
        v (or (seq violations) [nil])]
    (row (if v
           "<span class=\"critical\">HARD · 上書き不可</span>"
           "<span class=\"warn\">phase gate</span>")
         (str "<code>" (esc (nm (or (:rule v) phase-reason :n-a))) "</code>")
         (str "<code>" (esc (nm (or op :n-a))) "</code>")
         (str "<code>" (esc subject) "</code>")
         (esc (or (:detail v)
                  "この rollout phase では当該 op の書き込みが未解禁")))))

(defn- ledger-row [{:keys [t op subject disposition basis confidence]}]
  (row (esc (nm t))
       (str "<code>" (esc (nm (or op :n-a))) "</code>")
       (str "<code>" (esc subject) "</code>")
       (esc (nm (or disposition "")))
       (esc (some->> basis (map nm) (str/join ", ")))
       (if (some? confidence)
         (str "<span class=\"num\">" (esc confidence) "</span>")
         "<span class=\"muted\">-</span>")))

(def ^:private action-gate-rows
  ;; STATIC. A prose description of this actor's fixed op contract --
  ;; the closed allowlist in `eleclighting.governor/allowed-ops` and
  ;; the `:writes`/`:auto` sets in `eleclighting.phase/phases`. This is
  ;; documentation of behaviour fixed in source, not telemetry from the
  ;; run; it carries no ids, counts or measurements. Every other table
  ;; on this page is real actor output.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase 3 で governor-clean なら自動 commit</span></td><td>product-type / 絶縁耐圧 kV / 不良率の妥当性を独立検査</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">常に人間承認 · どの phase でも auto にならない</span></td><td>設備の検証済・登録済を独立再導出 · 二重予定を拒否 · <span class=\"critical\">設備の直接操作(actuate)は恒久禁止</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">常に人間承認 · confidence によらず必ずエスカレーション</span></td><td>安全報告は設備・バッチの検証状態で決してブロックしない</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">常に人間承認 · どの phase でも auto にならない</span></td><td>バッチの検証済・登録済を独立再導出 · 出荷数量をバッチ自身の記録から再計算(検算不能なら出荷しない)</td></tr>"
   "        <tr><td colspan=\"3\"><span class=\"critical\">電気照明機器安全認証(UL/CE 等)の自己発行は op を問わず恒久的に禁止 — 認証機関の専権事項</span></td></tr>"])

;; ----------------------------- document -----------------------------

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" body-rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the whole operator-console document from a store `db` that
  has already been driven by `run-demo!` (or any other real scenario).
  Reads only through `eleclighting.store`'s protocol accessors -- every
  cell below is a value the actor actually wrote."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        maintenance (store/all-maintenance db)
        shipments (keep #(store/shipment db (get % "shipment_id"))
                        (store/shipment-history db))
        concerns (store/safety-concerns db)
        holds (hold-rows ledger)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2740 · 電気照明機器製造 オペレーターコンソール</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>電気照明機器の製造 (ISIC 2740) — オペレーターコンソール</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">保守予定・安全懸念・出荷調整は常に人間承認</span></p>\n"

     "<main>\n"
     "  <section class=\"banner\">\n"
     "    <p>このページは <code>eleclighting.render-html</code> が "
     "<strong>実際の actor スタック</strong>"
     "(<code>eleclighting.operation</code> → <code>eleclighting.advisor</code> → "
     "<code>eleclighting.governor</code> → <code>eleclighting.phase</code> → "
     "<code>eleclighting.store</code>)を 1 回走らせ、その結果の store を"
     "読み出して生成したものです。数値・id・判定・拒否理由は手書きではありません。"
     "再生成は <code>clojure -M:dev:render-html</code>。同じ seed に対して"
     "常に byte 単位で同一の出力になります(時刻・乱数を一切含みません)。</p>\n"
     "    <p class=\"muted\">この actor は組立・試験設備を直接操作せず、"
     "電気照明機器の安全認証(UL/CE 等)を自ら発行することもありません — "
     "いずれも恒久的にブロックされます。</p>\n"
     "  </section>\n"

     (section "生産バッチ"
              (str "SSoT の全バッチ。<code>出荷済</code>は commit された出荷調整が"
                   "実際に書き戻した値で、<code>空き</code>は governor と同じ判定"
                   "(<code>registry/shipment-quantity-exceeded-checkable?</code>)"
                   "を通してから算出しています — 検算できない空き容量は空きとして扱いません。")
              ["バッチ" "製品種別" "型式" "絶縁耐圧 kV" "生産数量" "出荷済" "空き"
               "不良率 %" "地上検証" "直近の記録操作"]
              (map (partial batch-row ledger) batches))

     (section "組立・試験設備"
              (str "設備台帳。<code>検証済・登録済</code>の両方が真でなければ、"
                   "その設備に対する保守予定の提案は governor が HARD hold します"
                   "(advisor の自己申告ではなく設備自身の記録から再導出)。")
              ["設備" "種別" "地上検証" "最終保守日" "今回予定された保守日"]
              (map equipment-row equipment))

     (section "保守作業予定 (draft)"
              (str "commit された保守予定ウィンドウ。<code>MNT-…</code> は "
                   "<code>eleclighting.registry/register-maintenance</code> が"
                   "実際に採番した草案番号です。設備は実際には一切操作されません。")
              ["保守 id" "採番" "設備" "種別" "予定日" "承認者" "状態"]
              (map (partial maintenance-row ledger) maintenance))

     (section "出荷調整 (draft)"
              (str "commit された出荷調整。<code>SHP-…</code> は "
                   "<code>eleclighting.registry/register-shipment</code> が実際に"
                   "採番した草案番号です。実際の運送手配は行いません。")
              ["出荷 id" "採番" "バッチ" "数量" "仕向先" "承認者" "状態"]
              (map (partial shipment-row ledger) shipments))

     (section "安全懸念ログ (append-only)"
              (str "flag された安全懸念。<code>:flag-safety-concern</code> は "
                   "confidence によらず必ず人間へエスカレーションされ、"
                   "どの phase でも自動 commit されません。")
              ["懸念 id" "設備" "深刻度" "内容" "承認者"]
              (map concern-row concerns))

     (section "アクションゲート (Electric Lighting Plant Operations Governor)"
              (str "<strong>この表は実行結果ではなく、source に固定された op 契約の説明です</strong>"
                   "(<code>governor/allowed-ops</code> と <code>phase/phases</code>)。"
                   "他の表はすべて実行時の出力です。")
              ["Op" "Gate" "独立検査")]
              action-gate-rows)

     (section "この実行で発生した hold"
              (str "governor が実際に拒否した提案。<code>HARD</code> は上書き不可で、"
                   "人間の承認画面にすら到達しません(グラフが <code>:hold</code> へ直行)。"
                   "違反を持たない hold は governor ではなく phase gate によるものです。")
              ["区分" "rule" "op" "対象" "理由(governor 自身の出力)"]
              holds)

     (section "監査台帳 (append-only)"
              (str "この実行が生成した決定事実の全列。commit / hold / 承認が"
                   "同じ 1 本の追記専用ログに並びます。")
              ["fact" "op" "対象" "判定" "根拠" "confidence"]
              (map ledger-row ledger))

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-2740 · AGPL-3.0-or-later · "
     "生成元 <code>src/eleclighting/render_html.clj</code></p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out
             "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-batches db)) "batches,"
             (count (store/all-equipment db)) "equipment,"
             (count (store/all-maintenance db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
