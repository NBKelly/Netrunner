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
   [game.core.effects :refer [any-effects get-effect-maps register-lingering-effect
                              unregister-effects-for-card]]
   [game.core.eid :refer [effect-completed make-eid]]
   [game.core.engine :refer [ability-as-handler dissoc-req not-used-once?  gather-events pay
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

(defn- lose-all-stealth
  [state side card eid]
  (let [runner-cards (all-installed state :runner)
        stealthy (filter #(has-subtype? % "Stealth") runner-cards)
        chosen (first (filter #(or (pos? (get-counters % :credit)) (pos? (get-counters % :recurring))) stealthy))
        creds (when chosen (get-counters chosen :credit))
        rec (when chosen (get-counters chosen :recurring))]
    (if-not chosen
      (effect-completed state side eid)
      (continue-ability
        state side
        {:msg (msg "lose "
                   (when creds (str creds " credits " (when rec "and ")))
                   (when rec (str rec " recurring credits "))
                   "from " (card-str state chosen))
         :async true
         :effect (req (when creds (add-counter state side (get-card state chosen) :credit (- creds)))
                      (when rec (add-counter state side (get-card state chosen) :recurring (- rec)))
                      ;;(effect-completed state side eid))}
                      (continue-ability
                        state side
                        {:async true
                         :effect (lose-all-stealth state side card eid)}
                        card nil))}
        card nil))))

(defn handle-if-unique
  ([state side card handler] (handle-if-unique state side card handler nil))
  ([state side card handler targets] (handle-if-unique state side card handler targets false))
  ([state side card handler targets debug]
   (let [matching-events
         (seq (filter #(= (:ability-name handler) (:ability-name (:ability %)))
                      (gather-events state side (:event handler) targets)))]
     (when debug
       (do
         (system-msg state side (str "event type: " (:event handler)))
         (system-msg state side (str (gather-events state side (:event handler) targets)))
         ))
     (when-not matching-events
       (do (when debug (system-msg state side (str "registered " (:ability-name handler))))
           (register-events state side card [handler]))))))

(defn- daemon-abilities []
  [{:label "Install and host a program"
    :cost [:click 1]
    :prompt "Choose a program"
    :choices {:req (req (and (program? target)
                             (runner-can-install? state side target false)
                             (in-hand? target)))}
    :msg (msg "install from the grip and host " (:title target))
    :async true
    :effect (effect (runner-install eid target {:host-card card :no-mu true}))}
   {:label "Host an installed program (manual)"
    :prompt "Choose an installed program"
    :choices {:card #(and (program? %)
                          (installed? %))}
    :msg (msg "host " (:title target))
    :effect (effect (host card target)
                    (unregister-effects-for-card target #(= :used-mu (:type %)))
                    (update-mu))}])

(defn- host-with-negative-strength [x]
  {:type :breaker-strength
   :req (req (and (has-subtype? target "Icebreaker")
                  (some #(same-card? % target) (:hosted card))))
   :value (- x)})

(defn- dice-roll [] (inc (rand-int 6)))

(defn- register-effect-once [state side card target-side effect]
  (let [em (get-effect-maps state target-side (:type effect))
        matches (filter #(= (:ability-name %) (:ability-name effect)) em)]
    (when (empty? matches)
      (register-lingering-effect
        state side card
        effect))))

;; card implementations

(defcard "ONR Braniac: Espionage Enjoyer"
  {:interactions
   {:access-ability
    {:label "Trash card"
     :req (req (and (can-trash? state :runner target)
                    ;; 2+ crumble counters and in hq
                    (or
                      (and (<= 2 (get-counters (get-card state (:identity corp)) :garbage))
                           (or (= :rd (second (:zone target)))
                               (= :deck (first (:zone target)))))
                      (and (<= 2 (get-counters (get-card state (:identity corp)) :crumble))
                           (or (= :hq (second (:zone target)))
                               (= :hand (first (:zone target))))))))
     :cost [:credit 0]
     :async true
     :effect (effect
               (system-msg (str "trashes " (:title target) " at no cost"
                                (if (or (=  :hq (second (:zone target)))
                                        (= :hand (first (:zone target))))
                                  " due to Crumble counters (ONR Crumble)"
                                  " due to Garbage counters (ONR Garbage In)")))
               (trash eid (assoc target :seen true)
                            {:accessed true
                             :cause-card card}))}}})

;; I'm implementing braniac here


(defcard "ONR AI Boon"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Barrier")
                                (strength-pump 1 1)]
                    :events [{:event :run
                              :async true
                              :effect (req (let [str (dice-roll)]
                                             (continue-ability
                                               state side
                                               {:msg (msg "gain " str " strength for the run")
                                                :effect (effect (pump card str :end-of-run))}
                                               card nil)))}]}))

(defcard "ONR Afreet"
  {:implementation "MU Limit not enforced"
   :static-abilities [(host-with-negative-strength 1)]
   :abilities (daemon-abilities)})

(defcard "ONR Baedeker's Net Map"
  {:abilities [(base-link-abi 0 1)
               (boost-link-abi 1 1)]})

(defcard "ONR Bakdoor [TM]"
  {:abilities [(base-link-abi 0 3)
               (boost-link-abi 2 1)]})

(defcard "ONR Bartmoss Memorial Icebreaker"
  (auto-icebreaker {:abilities [(break-sub 1 1 "All")
                                (strength-pump 1 1)]
                    :events [{:event :end-of-encounter
                              :req (req (any-subs-broken-by-card? (:ice context) card))
                              :async true
                              :effect (req (let [di (dice-roll)]
                                             (continue-ability
                                               state side
                                               {:msg (msg "roll " di "(1d6)" (when (= 1 di) " and trash itself"))
                                                :effect (req (if (= 1 di)
                                                               (trash state side eid card {:cause-card card})
                                                               (effect-completed state side eid)))}
                                               card nil)))}]}))

(defcard "ONR Big Frackin' Gun"
  (auto-icebreaker {:abilities [(break-sub 6 5 "Sentry")
                                (strength-pump 1 1)]}))

(defcard "ONR Black Dahlia"
  (auto-icebreaker {:abilities [(break-sub 2 1 "Sentry")
                                (strength-pump 2 1)]}))

(defcard "ONR Black Widow"
  (auto-icebreaker
    {:on-install
     {:prompt "Choose a piece of ice to target for Black Widow"
      :choices {:card ice?}
      :effect (req (let [ice target]
                     (add-icon state side card ice "BW" (faction-label card))
                     (system-msg state side
                                 (str "selects " (card-str state ice)
                                      " for " (:title card) "'s strength gain ability"))
                     (register-events
                       state side card
                       [{:event :encounter-ice
                         :interactive (req true)
                         :req (req (same-card? ice (:ice context)))
                         :once :per-encounter
                         :msg "gain 5 strength for the encounter"
                         :effect (effect (pump (get-card state card) 5))}])))}
     :leave-play (effect (remove-icon card))
     :abilities [(break-sub 1 1 "Sentry")
                 (strength-pump 2 1)]}))

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
                                  die-result (dice-roll)
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

(defcard "ONR Boardwalk"
  (letfn [(boardwalk-event []
            {:event :runner-turn-begins
             :unregister-once-resolved true
             :ability-name "Boardwalk Counters"
             :async true
             :effect (req (let [counters (get-counters (get-card state (:identity corp)) :boardwalk)
                                to-reveal (int (/ counters 2))
                                can-reveal (count (:hand corp))]
                            ;; dereg these events on 0 counters to save on performance?
                            (when-not (zero? counters)
                              (register-events
                                state side
                                (:identity runner)
                                [(boardwalk-event)]))
                            (if-not (zero? (min to-reveal can-reveal))
                              (let [revealed (take (min to-reveal can-reveal) (shuffle (:hand corp)))]
                                (do (system-msg state side (str "forces the corp to reveal "
                                                                (enumerate-str (map :title (sort-by :title revealed)))
                                                                " from HQ due to boardwalk counters (ONR Boardwalk)"))
                                    (reveal state side eid revealed)))
                              (effect-completed state side eid))))})]
    {:events [{:event :successful-run
               :req (req (= :hq (target-server context)))
               :msg (msg "give the corp a Boardwalk counter")
               :effect (req
                         (handle-if-unique state side (:identity runner) (boardwalk-event))
                         (add-counter state :corp (get-card state (:identity corp)) :boardwalk 1))}]}))

(defcard "ONR Boring Bit"
  (auto-icebreaker {:abilities [(break-sub 2 1 "Wall")
                                (strength-pump 1 1)]}))

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

(defcard "ONR Cascade"
  (letfn [(cascade-event []
            {:event :corp-turn-begins
             :unregister-once-resolved true
             :ability-name "Cascade Counters"
             :async true
             :effect (req (let [counters (get-counters (get-card state (:identity corp)) :cascade)
                                to-mill (int (/ counters 2))]
                            (when-not (zero? counters)
                               (register-events
                                 state side
                                 (:identity runner)
                                 [(cascade-event)]))
                            (if-not (zero? to-mill)
                              (wait-for (mill state :runner (make-eid state eid) :corp to-mill)
                                        (let [trashed-cards async-result]
                                          ;; make them faceup
                                          (doseq [c trashed-cards]
                                            (update! state side (assoc c :seen true)))
                                          (system-msg state side (str "force the corp to trash "
                                                                      (enumerate-str (map :title (sort-by :title async-result)))
                                                                      " from the top of R&D due to cascade counters (ONR Cascade)"))
                                          (effect-completed state side eid)))
                              (effect-completed state side eid))))})]
    {:events [{:event :successful-run
               :req (req (= :rd (target-server context)))
               :msg (msg "give the corp a Cascade counter")
               :effect (req
                         (handle-if-unique state side (:identity runner) (cascade-event))
                         (add-counter state :corp (get-card state (:identity corp)) :cascade 1))}]}))

(defcard "ONR Butcher Boy"
  (letfn [(butcher-boy-event []
            {:event :runner-turn-begins
             :unregister-once-resolved true
             :ability-name "Butcher Boy Counters"
             :async true
             :effect (req (let [counters (get-counters (get-card state (:identity corp)) :butcher-boy)
                                to-gain (int (/ counters 2))]
                            (when-not (zero? counters)
                               (register-events
                                 state side
                                 (:identity runner)
                                 [(butcher-boy-event)]))
                            (if-not (zero? to-gain)
                              (do (system-msg state side (str "gains " to-gain "[Credits] from butcher boy counters (ONR Butcher Boy)"))
                                  (gain-credits state :runner eid (int (/ (get-counters (get-card state (:identity corp)) :butcher-boy) 2))))
                              (effect-completed state side eid))))})]
    {:events [{:event :successful-run
               :req (req (= :hq (target-server context)))
               :msg (msg "give the corp a Butcher Boy counter")
               :effect (req
                         (handle-if-unique state side (:identity runner) (butcher-boy-event))
                         (add-counter state :corp (get-card state (:identity corp)) :butcher-boy 1))}]}))

(defcard "ONR Cloak"
  {:recurring 3
   :interactions {:pay-credits {:req (req (and run
                                               (= :ability (:source-type eid))
                                               (has-subtype? target "Icebreaker")
                                               (not (has-subtype? target "Noisy"))))
                                :type :recurring}}})

(defcard "ONR Clown"
  {:static-abilities [{:type :ice-strength
                       :req (req (and (get-current-encounter state)
                                      (same-card? current-ice target)))
                       :value -1}]})

(defcard "ONR Cockroach"
  {:implemntation "TODO"})

(defcard "ONR Codecracker"
  (auto-icebreaker {:abilities [(break-sub 0 1 "Code Gate")
                                (strength-pump 1 1)]}))

(defcard "ONR Codeslinger"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Sentry")]}))

(defcard "ONR Corrosion"
  (auto-icebreaker {:abilities [(break-sub 0 1 "Wall")
                                (strength-pump 1 1)]}))

(defcard "ONR Crumble"
  {:implementation "Depends on you running the ONR Runner ID"
   :events [{:event :run-ends
             :req (req (and (= :hq (target-server context))
                                       (:successful context)))
             :msg (msg "give the corp a Crumble counter")
             :effect (req
                       (add-counter state :corp (get-card state (:identity corp)) :crumble 1))}]})

(defcard "ONR Cyfermaster"
  (auto-icebreaker {:abilities [(break-sub 2 1 "Code Gate")
                                (strength-pump 1 1)]}))

(defcard "ONR Deep Thought"
  (letfn [(thought-event []
            {:event :runner-turn-begins
             :unregister-once-resolved true
             :ability-name "Thought Counters"
             :async true
             :effect (req (let [counters (get-counters (get-card state (:identity corp)) :thought)
                                to-peek (<= 3 counters)]
                            (when-not (zero? counters)
                               (register-events
                                 state side
                                 (:identity runner)
                                 [(thought-event)]))
                            (if to-peek
                              (continue-ability
                                state side
                                {:prompt (req (->> corp :deck first :title (str "The top card of R&D is ")))
                                 :effect (req (system-msg state side "looks at the top cards of R&D due to Thought counters (ONR Deep Thought)"))
                                 :choices ["OK"]}
                                card nil)
                              (effect-completed state side eid))))})]
    {:events [{:event :successful-run
               :req (req (= :rd (target-server context)))
               :msg (msg "give the corp a Thought counter")
               :effect (req
                         (handle-if-unique state side (:identity runner) (thought-event))
                         (add-counter state :corp (get-card state (:identity corp)) :thought 1))}]}))

(defcard "ONR Disintegrator"
  {:implementation "TODO"})

(defcard "ONR Dogcatcher"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Hellhound")
                                (break-sub 1 1 "Bloodhound")
                                (break-sub 1 1 "Watchdog")
                                (break-sub 1 1 "Pit Bull")
                                (strength-pump 1 1)]}))

(defcard "ONR Dropp [TM]"
  {:implementation "Erratum - Should read '0[Credits] Break all subroutines of a piece
    of ice, and end the run. 1[Credits]: +1 strength. TODO"})

(defcard "ONR Dupré" {}) ;;TODO

(defcard "ONR Dwarf"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Wall")
                                (strength-pump 1 1)]}))

(defcard "ONR Early Worm"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Wall")
                                (strength-pump 2 3)]}))

(defcard "ONR Emergency Self-Construct" {}) ;;TODO

(defcard "ONR Enterprise, Inc., Shields"
  {:interactions {:prevent [{:type #{:net :brain}
                             :req (req true)}]}
   :abilities [{:cost [:credit 1]
                :msg "prevent up to 2 net damage"
                :effect (effect (damage-prevent :net 2))}
               {:label "Prevent 1 brain damage"
                :msg "prevent 1 brain damage"
                :cost [:credit 1]
                :effect (effect (damage-prevent :brain 1))}]})

(defcard "ONR Evil Twin"
  (letfn [(uses [state card]
            (or (get-in (get-card state card) [:special :usecount]) 0))
          (set-use [state side card x]
            (update! state side (assoc-in (get-card state card) [:special :usecount] x)))]
    (auto-icebreaker
      {:interactions {:prevent [{:type #{:net :brain}
                                 :req (req (if (< (uses state card) 2) true false))}]}
       :events [{:event :runner-turn-ends
                 :silent (req true)
                 :effect (req (set-use state side card 0))}
                {:event :corp-turn-ends
                 :silent (req true)
                 :effect (req (set-use state side card 0))}]
       :abilities [(break-sub 3 1 "Sentry")
                   (strength-pump 1 1)
                   {:label "Prevent 1 net damage"
                    :req (req (< (uses state card) 2))
                    :cost [:credit 0]
                    :msg "prevent 1 net damage"
                    :effect (req
                              (set-use state side card (inc (uses state card)))
                              (damage-prevent state side :net 1))}
                   {:label "Prevent 1 brain damage"
                    :req (req (< (uses state card) 2))
                    :msg "prevent 1 brain damage"
                    :cost [:credit 0]
                    :effect (req
                              (set-use state side card (inc (uses state card)))
                              (damage-prevent state side :brain 1))}]})))

(defcard "ONR Expert Schedule Analyzer" {}) ;;TODO

(defcard "ONR Fait Accompli" {}) ;;TODO

(defcard "ONR False Echo" {}) ;;TODO

(defcard "ONR Flak"
  (auto-icebreaker {:abilities [(break-sub 1 1 "AP")
                                (strength-pump 1 1)]}))

(defcard "ONR Force Shield"
  (letfn [(uses [state card]
            (or (get-in (get-card state card) [:special :usecount]) 0))
          (set-use [state side card x]
            (update! state side (assoc-in (get-card state card) [:special :usecount] x)))]
    {:interactions {:prevent [{:type #{:net :brain}
                               :req (req (if (< (uses state card) 2) true false))}]}
     :events [{:event :runner-turn-ends
               :silent (req true)
               :effect (req (set-use state side card 0))}
              {:event :corp-turn-ends
               :silent (req true)
               :effect (req (set-use state side card 0))}]
     :abilities [{:label "Prevent 1 net damage"
                  :req (req (< (uses state card) 2))
                  :cost [:credit 0]
                  :msg "prevent 1 net damage"
                  :effect (req
                            (set-use state side card (inc (uses state card)))
                            (damage-prevent state side :net 1))}
                 {:label "Prevent 1 brain damage"
                  :req (req (< (uses state card) 2))
                  :msg "prevent 1 brain damage"
                  :cost [:credit 0]
                  :effect (req
                            (set-use state side card (inc (uses state card)))
                            (damage-prevent state side :brain 1))}]}))

(defcard "ONR Forward's Legacy"
  (auto-icebreaker {:abilities [(break-sub 0 1 "Sentry")]
                    :events [{:event :run
                              :async true
                              :effect (req (let [str (dice-roll)]
                                             (continue-ability
                                               state side
                                               {:msg (msg "gain " str " strength for the run")
                                                :effect (effect (pump card str :end-of-run))}
                                               card nil)))}]}))


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

(defcard "ONR Garbage In"
  {:implementation "Depends on you running the ONR Runner ID"
   :events [{:event :run-ends
             :req (req (and (= :rd (target-server context))
                            (:successful context)))
             :msg (msg "give the corp a Garbage counter")
             :effect (req
                       (add-counter state :corp (get-card state (:identity corp)) :garbage 1))}]})

(defcard "ONR Gremlins"
  {:events [{:event :successful-run
             :req (req (= :hq (target-server context)))
             :msg (msg "give the corp a Gremlin counter")
             :effect (req
                       (add-counter state :corp (get-card state (:identity corp)) :gremlin 1)
                       (register-effect-once
                         state side card :corp
                         {:type :hand-size
                          :ability-name "Gremlins"
                          :req (req (= :corp side))
                          :value (req (- (int (/ (get-counters (get-card state (:identity corp)) :gremlin) 2))))}))}]})

(defcard "ONR Grubb"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Wall")
                                (strength-pump 2 1 :end-of-run)]}))

(defcard "ONR Hammer"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Wall" (lose-from-stealth 2))
                                (strength-pump 1 1)]}))

(defcard "ONR Highlighter"
  (letfn [(highlighter-event []
            {:event :breach-server
             :unregister-once-resolved true
             :side :runner
             :async true
             :ability-name "Highlighter Counters"
             :req (req (= target :rd))
             :effect (req (let [counters (get-counters (get-card state (:identity corp)) :highlighter)
                                add-access (dec counters)]
                            (when (pos? add-access)
                              (system-msg state side (str "accesses an additional " add-access " cards from R&D due to Highlighter counters (ONR Highlighter)"))
                              (access-bonus state side :rd add-access))
                            (when-not (zero? counters)
                              (register-events
                                state side
                                (:identity runner)
                                [(highlighter-event)]))
                            (effect-completed state side eid)))})]
    {:events [{:event :run-ends
               :req (req (and (= :rd (target-server context))
                              (:successful context)))
               :msg (msg "give the corp a Highlighter counter")
               :effect (req
                         (handle-if-unique state side (:identity runner) (highlighter-event) [:rd])
                         (add-counter state :corp (get-card state (:identity corp)) :highlighter 1))}]}))

(defcard "ONR Imp"
  {:implementation "MU Limit not enforced"
   :static-abilities [(host-with-negative-strength 1)]
   :abilities (daemon-abilities)})

(defcard "ONR Incubator" {}) ;;todo

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

(defcard "ONR Japanese Water Torture" {}) ;;todo

(defcard "ONR Joan of Arc"
  {:interactions {:prevent [{:type #{:trash-program}
                             :req (req
                                    (let [target-card (:prevent-target target)]
                                      (if-not (same-card? target-card card)
                                        true
                                        false)))}]}
   :abilities [{:cost [:trash-can]
                :label "prevent a program trash"
                :effect (effect (trash-prevent :program 1))}
               {:cost [:credit 1 :return-to-hand]
                :label "prevent a program trash"
                :effect (effect (trash-prevent :program 1))}]})

(defcard "ONR Krash"
  (auto-icebreaker {:abilities [(break-sub 2 1 "All")
                                (strength-pump 2 1)]}))

(defcard "ONR Lockjaw"
  {:abilities [{:req (req (get-current-encounter state))
                :cost [:trash-can]
                :label "Give icebreaker +2 strength for the run"
                :prompt "Choose an installed non-AI icebreaker"
                :choices {:card #(and (has-subtype? % "Icebreaker")
                                      (installed? %))}
                :msg (msg "give +2 strength to " (:title target))
                :effect (effect (pump target 2 :end-of-run))}]})

(defcard "ONR Loony Goon"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Sentry")
                                (strength-pump 1 1)]}))

(defcard "ONR MS-todon"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Sentry")
                                (strength-pump 1 1)]
                    :events [{:event :subroutines-broken
                              :once :per-run
                              :req (req (and
                                          (has-subtype? target "Sentry")
                                          (any-subs-broken-by-card? target card)))
                              :msg "lose all stealth credits and take a tag"
                              :effect (req (wait-for (gain-tags state :runner 1)
                                                     (lose-all-stealth state side card eid)))}]}))

(defcard "ONR Matador"
  (auto-icebreaker {:abilities [(break-sub 1 1 "Sentry")
                                (strength-pump 3 5)]}))

(defcard "ONR Microtech AI Interface"
  {:events [{:event :breach-server
             :async true
             :req (req (= target :rd))
             :effect (effect (continue-ability
                               {:req (req (< 1 (count (:deck corp))))
                                :prompt "How many cards from R&D do you cut to the bottom?"
                                :choices {:number (req (count (:deck corp)))
                                          :default (req 0)}
                                :msg (msg "cut "
                                          (when (= target (count (:deck corp))) "ALL ")
                                          (quantify target " card") " to the bottom of R&D")
                                :effect (req (let [cards (take target (:deck corp))]
                                               (doseq [c cards]
                                                 (move state :corp c :deck))))}
                               card nil))}]})

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
