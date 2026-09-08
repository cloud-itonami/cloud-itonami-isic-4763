(ns sportsretailops.governor
  "SportsRetailGovernor -- the independent compliance layer that earns
  the SportsRetailAdvisor the right to commit. The advisor has no notion
  of whether a store is actually registered and license-verified,
  whether a named supply-order vendor is itself a registered/verified
  counterparty, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has silently
  drifted into a permanently out-of-scope decision area, so this MUST be
  a separate system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (inventory/sale/return/fitting transaction logging, floor-staff
  scheduling, sporting-equipment supply-order coordination,
  product-safety-concern flagging). It NEVER performs or authorizes:
    - setting or overriding a shelf/unit price
    - directly finalizing a recall-compliance decision (declaring,
      issuing, or executing a product recall)
    - directly finalizing an equipment-safety-certification decision
      (issuing, granting or revoking a safety certification, certifying
      equipment as compliant)
    - product-safety-authority enforcement (making the actual
      recall/certification determination itself)

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Store unverified           -- the target store record must exist
                                     AND be independently confirmed
                                     `:registered?`/`:verified?` in the
                                     store before ANY proposal for it may
                                     commit or even escalate. Never trusts
                                     a proposal's own claim about the
                                     store -- re-derived from the store's
                                     own record, the same 'ground truth,
                                     not self-report' discipline every
                                     sibling actor's governor uses.
    2. Vendor unverified          -- for `:coordinate-supply-order` ONLY,
                                     the proposal's own drafted `:value`
                                     must name a `:vendor-id` that
                                     resolves to an independently
                                     `:registered?`/`:verified?` vendor
                                     record. A missing vendor-id, or one
                                     that resolves to an unregistered or
                                     unverified vendor, is a HARD block --
                                     a supply-chain counterparty-
                                     verification gate, same discipline as
                                     `store-unverified-violations`
                                     reapplied to the vendor.
    3. Effect not :propose        -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not merely
                                     low-confidence.
    4. Scope exclusion            -- ANY proposal (regardless of op)
                                     whose op, summary, rationale, cites
                                     or draft value touches directly
                                     finalizing a recall-compliance
                                     decision or an equipment-safety-
                                     certification decision is a HARD,
                                     PERMANENT block -- this actor's
                                     charter excludes that territory
                                     structurally, not as a rollout
                                     milestone. Evaluated UNCONDITIONALLY
                                     on every proposal. An op outside the
                                     closed four-op allowlist is the SAME
                                     failure mode (an advisor proposing
                                     something it was never authorized to
                                     propose) and is folded into this same
                                     check. `:flag-safety-concern` itself
                                     is never excluded by this check --
                                     surfacing a recall/defective-
                                     equipment/injury concern for a human
                                     is exactly this actor's job; only
                                     FINALIZING a recall/certification
                                     decision is excluded (see
                                     `scope-excluded-terms` below --
                                     phrased as the finalization/execution
                                     ACTION, never a bare noun like
                                     'recall' or 'certification', so the
                                     default mock advisor's own
                                     `:flag-safety-concern` rationale
                                     never self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-safety-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `sportsretailops.phase` independently agrees:
      `:flag-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      equipment procurement proposal always needs a human sign-off, even
      when the governor and phase would otherwise allow auto-commit."
  (:require [kotoba.lang.text :as str]
            [sportsretailops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example single-store sporting-equipment procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing an
  `:estimated-cost` above this value ALWAYS escalates to human sign-off,
  regardless of confidence or rollout phase."
  1000.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`)."
  #{:log-sales-record :schedule-staffing-operation
    :coordinate-supply-order :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  recall-compliance decision (declaring/issuing/executing a product
  recall) or an equipment-safety-certification decision (issuing,
  granting or revoking a safety certification, certifying equipment as
  compliant), rather than merely flagging a concern for a human. Scanned
  across the proposal's op/summary/rationale/cites/value, never trusting
  the advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'issued the recall', 'granted the safety certification'),
  never a bare noun like 'recall', 'certification', 'defective' or
  'safety' -- a bare noun would accidentally match inside this actor's
  own legitimate `:flag-safety-concern` default proposal text (whose
  whole job is to talk about recall/defective-equipment/safety concerns,
  and whose own printed `:op` keyword literally contains the substring
  'safety') and self-block the happy path. See
  `sportsretailops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["issue the recall" "issued the recall" "issuing the recall"
   "declare a recall" "declared a recall" "declaring a recall"
   "execute the recall" "executed the recall" "executing the recall"
   "finalize the recall" "finalized the recall" "finalizing the recall"
   "finalize the recall decision" "finalized the recall decision"
   "initiate the recall" "initiated the recall" "initiating the recall"
   "issue the safety certification" "issued the safety certification" "issuing the safety certification"
   "grant the safety certification" "granted the safety certification" "granting the safety certification"
   "revoke the safety certification" "revoked the safety certification" "revoking the safety certification"
   "certify the equipment as safe" "certified the equipment as safe" "certifying the equipment as safe"
   "finalize the certification decision" "finalized the certification decision" "finalizing the certification decision"
   "approve the equipment as compliant" "approved the equipment as compliant" "approving the equipment as compliant"
   "declare the equipment compliant" "declared the equipment compliant" "declaring the equipment compliant"
   "リコールを発令" "リコールを発令した" "リコールを実施した" "リコールを実行した"
   "リコールを決定した" "リコールを確定した"
   "安全認証を発行" "安全認証を発行した" "安全認証を付与した" "安全認証を取り消した"
   "適合認定を行った" "適合を認定した" "認証の取り消しを実行した"])

;; ----------------------------- checks -----------------------------

(defn- store-unverified-violations
  "The target store must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the proposal's
  own `:store-id` claim without a store lookup."
  [{:keys [store-id]} st]
  (let [s (store/store-record st store-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :store-unverified
        :detail (str store-id " は未登録または未検証の店舗 -- いかなる提案も進められない")}])))

(defn- vendor-unverified-violations
  "For `:coordinate-supply-order` ONLY, the proposal's own drafted
  `:value` must name a `:vendor-id` that resolves to an independently
  `:registered?`/`:verified?` vendor record. A missing vendor-id, or one
  that resolves to an unregistered/unverified vendor, is a HARD block --
  never trust the proposal's own vendor claim without a store lookup, the
  SAME 'ground truth, not self-report' discipline as
  `store-unverified-violations`, reapplied to the supply-chain
  counterparty."
  [proposal st]
  (when (= :coordinate-supply-order (:op proposal))
    (let [vendor-id (get-in proposal [:value :vendor-id])
          v (and vendor-id (store/vendor-record st vendor-id))]
      (when-not (and v (:registered? v) (:verified? v))
        [{:rule :vendor-unverified
          :detail (str (or vendor-id "(vendor-id missing)")
                        " は未登録または未検証の仕入先 -- 発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a recall-compliance
  decision or an equipment-safety-certification decision, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "製品リコールの確定発令・安全認証の発行/取り消しなど安全性確定行為(recall/certification finalization)に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  `supply-cost-threshold` -- always needs human sign-off (SOFT escalate,
  not a hard block: the order itself is in scope, only its size requires
  a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a SportsRetailAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [store-id (or (:store-id proposal) (:store-id request))
        hard (into []
                   (concat (store-unverified-violations {:store-id store-id} store)
                           (vendor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :store-id   (:store-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
