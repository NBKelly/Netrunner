(ns game.core.onr-utils
  (:require
   [game.core.onr-trace :refer [boost-link set-base-link cancel-successful-trace]]
   [game.macros :refer [continue-ability effect msg req wait-for]]
   [game.core.effects :refer [gather-effects register-lingering-effect]]
   [game.core.engine :refer [not-used-once? register-events gather-events]]
   [game.core.damage :refer [damage-prevent]]
   [game.core.say :refer [system-msg]]
   [game.core.tags :refer [gain-tags]]
   [game.utils :refer [quantify]]
   ))


(defn- give-tags
  "Basic give runner n tags subroutine."
  [n]
  {:label (str "Give the Runner " (quantify n "tag"))
   :msg (str "give the Runner " (quantify n "tag"))
   :async true
   :effect (effect (gain-tags :corp eid n))})

(defn onr-trace-ability
  "Run a trace with specified max strength.
  If successful trigger specified ability"
  ([max {:keys [label] :as ability} only-tags]
   {:label (str "Trace " max " - " label)
    :onr-trace {:max-strength max
                :label label
                :only-tags only-tags
                :successful ability}})
  ([max ability un-ability only-tags]
   (let [label (str (:label ability) " / " (:label un-ability))]
     {:label (str "Trace " max " - " label)
      :onr-trace {:max-strength max
                  :label label
                  :only-tags only-tags
                  :successful ability
                  :unsuccessful un-ability}})))

(defn onr-trace-tag
  ([max] (onr-trace-tag max 1))
  ([max tags]
   (onr-trace-ability max (give-tags tags) true)))


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

(defn register-effect-once [state side card effect]
  (let [em (gather-effects state side (:type effect))
        matches (filter #(= (:ability-name %) (:ability-name effect)) em)]
    (when (empty? matches)
      (register-lingering-effect
        state side card
        effect))))

(defn base-link-abi
  [cost val]
  (let [cost (if (integer? cost) [:credit cost] cost)]
    {:onr-base-link true
     :req (req true)
     :cost cost
     :base-link val
     :label (str "Base Link " val)
     :msg (str "set their Base Link to " val)
     :effect (req (set-base-link state val))}))

(defn boost-link-abi
  [cost val]
  (let [cost (if (integer? cost) [:credit cost] cost)]
    {:onr-boost-link true
     :cost cost
     :label (str "+" val " Link")
     :msg (str "gain +" val " Link")
     :effect (req (boost-link state val))}))

(defn dice-roll [] (inc (rand-int 6)))

(defn deep-merge [a & maps]
   (if (map? a)
     (apply merge-with deep-merge a maps)
     (apply merge-with deep-merge maps)))

(defn generic-prevent-damage
  ([x type]
   {:interactions {:prevent [{:type #{type}
                              :req (req (not-used-once? state {:once :per-turn} card))}]}
    :abilities [{:cost [:credit 0]
                 :once :per-turn
                 :label (str "Prevent " x " " (name type) " damage")
                 :msg (msg "prevent " x " " (name type) " damage")
                 :effect (effect (damage-prevent type x))}]})
  ([x typea typeb]
   {:interactions {:prevent [{:type #{typea typeb}
                              :req (req (not-used-once? state {:once :per-turn} card))}]}
    :abilities [{:cost [:credit 0]
                 :once :per-turn
                 :label (str "Prevent " x " " (name typea) " or " (name typeb) " damage")
                 :msg (msg "prevent " x " " (name typea) " or " (name typeb) " damage")
                 :effect (effect (damage-prevent typea x)
                                 (damage-prevent typeb x))}]}))
