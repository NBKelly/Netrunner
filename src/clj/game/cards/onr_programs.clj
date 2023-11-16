(ns game.cards.onr-programs
  (:require
   [clojure.string :as str]
   [game.core.access :refer [access-bonus max-access]]
   [game.core.board :refer [all-active all-active-installed all-installed all-installed-runner-type
                            card->server server->zone]]
   [game.core.card :refer [active? agenda? asset? card-index corp? facedown? faceup?
                           get-advancement-requirement get-card get-counters
                           get-nested-host get-title get-zone
                           hardware? has-subtype? in-hand? in-discard? ice? installed?
                           is-type? program? resource? rezzed? runner?]]
   [game.core.card-defs :refer [card-def]]
   [game.core.charge :refer [charge-ability]]
   [game.core.cost-fns :refer [all-stealth install-cost min-stealth rez-cost]]
   [game.core.costs :refer [total-available-credits]]
   [game.core.damage :refer [damage damage-prevent]]
   [game.core.def-helpers :refer [breach-access-bonus defcard offer-jack-out trash-on-empty get-x-fn]]
   [game.core.drawing :refer [draw]]
   [game.core.effects :refer [any-effects register-lingering-effect
                              unregister-effects-for-card]]
   [game.core.eid :refer [effect-completed make-eid]]
   [game.core.engine :refer [ability-as-handler dissoc-req not-used-once? pay
                             print-msg register-events register-once
                             trigger-event trigger-event-simult unregister-events]]
   [game.core.events :refer [run-events first-event? first-installed-trash? run-events
                             first-successful-run-on-server? turn-events]]
   [game.core.expose :refer [expose]]
   [game.core.finding :refer [find-cid]]
   [game.core.flags :refer [can-host? can-trash? card-flag? lock-zone release-zone zone-locked?]]
   [game.core.gaining :refer [gain-clicks gain-credits lose-credits]]
   [game.core.hosting :refer [host]]
   [game.core.ice :refer [all-subs-broken-by-card? all-subs-broken?
                          any-subs-broken-by-card? auto-icebreaker break-sub
                          break-subroutine! break-subroutines-msg breaker-strength-bonus dont-resolve-subroutine!
                          get-strength ice-strength pump pump-ice set-current-ice strength-pump
                          unbroken-subroutines-choice update-all-icebreakers update-breaker-strength]]
   [game.core.initializing :refer [ability-init card-init]]
   [game.core.installing :refer [install-locked? runner-can-install? runner-can-pay-and-install?
                                 runner-install]]
   [game.core.link :refer [get-link]]
   [game.core.mark :refer [identify-mark-ability]]
   [game.core.memory :refer [available-mu update-mu]]
   [game.core.moving :refer [flip-facedown mill move swap-cards swap-ice trash
                             trash-prevent]]
   [game.core.optional :refer [get-autoresolve set-autoresolve never?]]
   [game.core.payment :refer [build-cost-label can-pay? cost-target cost-value]]
   [game.core.prompts :refer [cancellable]]
   [game.core.props :refer [add-counter add-icon remove-icon]]
   [game.core.revealing :refer [reveal]]
   [game.core.rezzing :refer [derez get-rez-cost rez]]
   [game.core.runs :refer [active-encounter? bypass-ice continue end-run-prevent
                           get-current-encounter make-run successful-run-replace-breach
                           update-current-encounter]]
   [game.core.sabotage :refer [sabotage-ability]]
   [game.core.say :refer [system-msg]]
   [game.core.servers :refer [central->name is-central? is-remote? protecting-same-server?
                              remote->name target-server unknown->kw zone->name]]
   [game.core.shuffling :refer [shuffle!]]
   [game.core.tags :refer [gain-tags lose-tags]]
   [game.core.to-string :refer [card-str]]
   [game.core.threat :refer [threat threat-level]]
   [game.core.trace :refer [force-base]]
   [game.core.update :refer [update!]]
   [game.core.virus :refer [get-virus-counters]]
   [game.core.onr-trace :refer [boost-link set-base-link cancel-successful-trace]]
   [game.macros :refer [continue-ability effect msg req wait-for]]
   [game.utils :refer :all]
   [jinteki.utils :refer :all]))

(defn- base-link-abi
  [cost val]
  (let [cost (if (integer? cost) [:credit cost] cost)]
    {:onr-base-link true
     :req (req true)
     :cost cost
     :base-link val
     :label (str "Base Link " val)
     :msg (str "set their Base Link to " val)
     :effect (req (set-base-link state val))}))

(defn- boost-link-abi
  [cost val]
  (let [cost (if (integer? cost) [:credit cost] cost)]
    {:onr-boost-link true
     :cost cost
     :label (str "+" val " Link")
     :msg (str "gain +" val " Link")
     :effect (req (boost-link state val))}))

;; deal with stealth/noisy cards

(defn- count-stealth-creds
  [state]
  (let [inst (all-active-installed state :runner)
        stealth (filter #(has-subtype? % "Stealth") inst)
        creds (map #(+ (or (get-counters % :credit) 0) (or (get-counters % :recurring) 0)) stealth)
        total (reduce + 0 creds)]
    total))

(defn- lose-x-stealth-creds
  [amt]
  {:req (req (pos? amt))
   :prompt (msg "Lose a stealth credit (" amt " remain)")
   :async true
   :choices {:card #(or (pos? (get-counters % :credit)) (pos? (get-counters % :recurring)))}
   :effect (req (if (pos? (get-counters target :recurring))
                  (add-counter state side target :recurring -1)
                  (add-counter state side target :credit -1))
                (continue-ability
                  state side
                  (lose-x-stealth-creds (dec amt))
                  card nil))})

(defn- lose-from-stealth
  [amt]
  (if (integer? amt)
    {:additional-ability
     {:msg (msg "lose " amt " credits from stealth cards")
      :req (req (pos? (count-stealth-creds state)))
      :async true
      :effect (req (let [max (min amt (count-stealth-creds state))]
                     (continue-ability state side
                                       (lose-x-stealth-creds max)
                                       card nil)))}}
    nil))

(defn- handle-if-new
  [token event state side card target]
  (when (or (zero? (get-counters target token)) true)
    (register-events
      state side (get-card state card)
      [event])))

;; card implementations

(defcard "ONR Baedeker's Net Map"
  {:abilities [(base-link-abi 0 1)
               (boost-link-abi 1 1)]})

(defcard "ONR Bakdoor [TM]"
  {:abilities [(base-link-abi 0 3)
               (boost-link-abi 2 1)]})

(defcard "ONR Blink"
  (letfn [(attempt
            [state side card sub]
            (let [c (get-card state card)]
              (update!
                state side
                (assoc-in c [:special :attempted-breaks]
                          (concat (get-in c [:special :attempted-breaks]) [sub])))))
          (viable-sub
            [sub card]
            (not (seq (filter #(or (:broken %) (= % sub)) (get-in card [:special :attempted-breaks])))))
          (viable-subs
            [subs card]
            (filter #(viable-sub % card) subs))]
    ;; we're only allowed to attempt each subroutine once!
    {:implementation "always breaks identical subs from the top down (I'll fix this later, send me an @ if it's an issue)"
     :on-install {:silent (req true)
                  :effect (req (update! state :runner (assoc-in (get-card state card) [:special :attempted-breaks] [])))}
     :events [{:event :encounter-ice
               :silent (req true)
               :effect (req (update! state :runner (assoc-in (get-card state card) [:special :attempted-breaks] [])))}]
     :abilities [{:label "Maybe break a subroutine"
                  :req (req (and
                              current-ice
                              (<= (get-strength current-ice) (get-strength card))
                              (seq (viable-subs (remove :broken (:subroutines current-ice)) card))))
                  :cost [:credit 0]
                  :prompt "Choose a subroutine to try to break"
                  :choices (req (cancellable (map #(make-label (:sub-effect %)) (viable-subs (remove :broken (:subroutines current-ice)) card))))
                  :async true
                  :effect (req
                            (let [chosen-sub (first (filter #(and (= target (make-label (:sub-effect %)))
                                                                  (not (:broken %))
                                                                  (viable-sub % card)) (:subroutines current-ice)))
                                  die-result (+ 1 (rand-int 6))
                                  success (<= 4 die-result)]
                              (system-msg state side (str "rolls a " die-result " (1d6)"))
                              (if success
                                (do
                                  (system-msg state side (str (break-subroutines-msg current-ice [chosen-sub] card)))
                                  (break-subroutine! state (get-card state current-ice) chosen-sub)
                                  (effect-completed state side eid))
                                (do
                                  (system-msg state side (str "takes " die-result " net damage"))
                                  (damage state side eid :net die-result {:card card})))))}]}))

(defcard "ONR Bulldozer"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Wall" (lose-from-stealth 2))
                                (strength-pump 2 1)]
                    :events [{:event :subroutines-broken
                              :req (req (and (all-subs-broken-by-card? target card)
                                             (has-subtype? target "Wall")))
                              :once :per-encounter
                              :effect (req (register-events
                                             state side card
                                             [{:event :encounter-ice
                                               :duration :end-of-run
                                               :unregister-once-resolved :true
                                               :async true
                                               :effect (req (if (has-subtype? current-ice "Sentry")
                                                              (continue-ability
                                                                state side
                                                                (break-sub 0 1 "Sentry" {:req (req true) :repeatable false})
                                                                card nil)
                                                              (effect-completed state side eid)))}]))}]}))

(defcard "ONR Butcher Boy"
  (letfn [(butcher-boy-event []
            {:event :runner-turn-begins
             :unregister-once-resolved true
             :ability-name "Butcher Boy Tokens"
             :req (req (and
                         (>= (get-counters (get-card state (:identity corp)) :butcher-boy) 2)))
             :async true
             :effect (req (let [to-gain (int (/ (get-counters (get-card state (:identity corp)) :butcher-boy) 2))]
                            (register-events
                              state side
                              (:identity runner)
                              [(butcher-boy-event)])
                            (system-msg state side (str "gains " to-gain "[Credits] from butcher boy tokens (ONR Butcher Boy)"))
                            (gain-credits state :runner eid (int (/ (get-counters (get-card state (:identity corp)) :butcher-boy) 2)))))})]
    {:events [{:event :successful-run
               :req (req (= :hq (target-server context)))
               :msg (msg "give the corp a Butcher Boy token")
               :effect (req
                         (when (zero? (get-counters (:identity corp) :butcher-boy))
                           (register-events
                             state side (:identity corp)
                             [(butcher-boy-event)]))
                         (add-counter state :corp (get-card state (:identity corp)) :butcher-boy 1))}]}))

(defcard "ONR Cloak"
  {:recurring 3
   :interactions {:pay-credits {:req (req (and run
                                               (= :ability (:source-type eid))
                                               (has-subtype? target "Icebreaker")
                                               (not (has-subtype? target "Noisy"))))
                                :type :recurring}}})

(defcard "ONR Cyfermaster"
  (auto-icebreaker {:abilities [(break-sub 2 1 "Code Gate")
                                (strength-pump 1 1)]}))

(defcard "ONR Fubar"
  (auto-icebreaker
    (letfn [(chosen-type [card] (get-in card [:card-target]))]
      {:abilities [(break-sub 1 1 "All"
                              (assoc (lose-from-stealth 1)
                                     :req (req (and (chosen-type card)
                                                    (has-subtype? current-ice (chosen-type card))))))
                   (strength-pump 2 1)
                   {:prompt "Choose target type"
                    :req (req (not (:card-target card)))
                    :label "Choose subtype (once per game)"
                    :choices ["Sentry" "Code Gate" "Wall"]
                    :cost [:credit 0]
                    :msg (msg "choose to be able to break " target " subroutines")
                    :effect (effect (update! (assoc card :card-target target)))}]})))

(defcard "ONR Hammer"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Wall" (lose-from-stealth 2))
                                (strength-pump 1 1)]}))

(defcard "ONR Invisibility"
  {:recurring 1
   :interactions {:pay-credits {:req (req (and run
                                               (= :ability (:source-type eid))
                                               (has-subtype? target "Icebreaker")
                                               (not (has-subtype? target "Noisy"))))
                                :type :recurring}}})

(defcard "ONR Jackhammer"
  (auto-icebreaker {:abilities [(break-sub 0 1 "Wall" (lose-from-stealth 1))
                                (strength-pump 1 1)]}))

(defcard "ONR Pile Driver"
  (auto-icebreaker {:abilities [(break-sub 3 4 "Wall" (lose-from-stealth 3))
                                (strength-pump 1 1)]}))


(defcard "ONR Ramming Piston"
  (auto-icebreaker {:abilities [(break-sub 2 1 "Wall" (lose-from-stealth 2))
                                (strength-pump 1 1)]}))

(defcard "ONR Vewy Vewy Quiet"
  {:recurring 2
   :interactions {:pay-credits {:req (req (and run
                                               (= :ability (:source-type eid))
                                               (has-subtype? target "Icebreaker")
                                               (not (has-subtype? target "Noisy"))))
                                :type :recurring}}})

(defcard "ONR Wrecking Ball"
  (auto-icebreaker {:abilities [(break-sub 0 1 "Wall" (lose-from-stealth 1))
                                (strength-pump 2 1)]}))

(defcard "ONR Zetatech Software Installer"
  {:recurring 2
   :interactions {:pay-credits {:req (req (and (= :runner-install (:source-type eid))
                                               (program? target)))
                                :type :recurring}}})
