(ns nr.player-view
  (:require [reagent.core :as r]
            [nr.avatar :refer [avatar]]
            [nr.deckbuilder :refer [num->percent]]
            [nr.utils :refer [faction-icon notnum->zero]]))

(defn user-status-span
  "Returns a [:span] showing players game completion rate"
  [player]
  (r/with-let [started (get-in player [:user :stats :games-started])
               completed (get-in player [:user :stats :games-completed])
               completion-rate (str (notnum->zero (num->percent completed started)) "%")
               completion-rate (if (< started 10) "Too little data" completion-rate)]
    [:span.user-status (get-in player [:user :username])
     [:div.status-tooltip.blue-shade
      [:div "Game Completion Rate: " completion-rate]]]))


(defn player-view
  ([player] (player-view player nil))
  ([player game]
   [:span.player
    [avatar (:user player) {:opts {:size 22}}]
    [user-status-span player]
    (when (not (:password game))
      (let [side (:side player)
            faction (:faction (:identity (:deck player)))
            identity (:title (:identity (:deck player)))
            specs (:allow-spectator game)]
        (cond
          (and (some? faction)
               (not= "Neutral" faction)
               specs)
          (faction-icon faction identity)

          side
          (str " (" side ")"))))]))

