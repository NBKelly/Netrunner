(ns web.telemetry
  (:require
   [clojure.core.async :refer [<! go timeout]]
   [cljc.java-time.temporal.chrono-unit :as chrono]
   [cljc.java-time.duration :as duration]
   [cljc.java-time.instant :as inst]
   [game.core.board :refer [all-active]]
   [web.app-state :refer [app-state]]
   [web.lobby :refer [lobby-update-uids]]
   [web.ws :refer [connected-sockets connections_]]
   [taoensso.encore :as enc]
   [taoensso.timbre :as timbre]))

(def log-stat-frequency (enc/ms :mins 5))

(defn subscriber-time-metrics
  "average time | oldest"
  [subs]
  (let [now (inst/now)
        age #(quot (duration/get (duration/between % now) chrono/seconds) 60)
        subs-by-minute (sort (map age subs))
        oldest (or (last subs-by-minute) 0)
        average (quot (reduce + 0 subs-by-minute) (max 1 (count subs)))]
    [average oldest]))

(defn- lobby->active-cards
  [lobby]
  (if (:started lobby)
    (let [state (:state lobby)]
      (map :title (concat (all-active state :runner) (all-active state :corp))))
    []))

(defn active-card-frequencies
  [lobbies]
  (frequencies (reduce concat [] (map lobby->active-cards (vals lobbies)))))

(defn lobby->recent-commands
  [lobby]
  (if (:started lobby)
    (let [state (:state lobby)
          commands (or (:command-log @state) [])
          recent (filter #(inst/is-after (inst/plus-seconds (:timestamp %) (* 5 60)) (inst/now))
                         commands)]
      (map :command recent))
    []))

(defn recent-command-frequencies
  [lobbies]
  (frequencies (reduce concat [] (map lobby->recent-commands (vals lobbies)))))

(defn- heap-usage
  []
  (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean)))

(defn- system-load-average
  []
  (let [bean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)]
    (str (int (* 100 (/ (.getSystemLoadAverage bean) (.getAvailableProcessors bean)))) "%")))

(defonce log-stats
  (go (timbre/info (str (into [] (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))))
      (while true
    (<! (timeout log-stat-frequency))
    (let [lobbies (:lobbies @app-state)
          lobbies-count (count lobbies)
          players (reduce + 0 (map #(count (:players %)) (vals lobbies)))
          spectators (reduce + 0 (map #(count (:spectators %)) (vals lobbies)))
          card-freqs (active-card-frequencies lobbies)
          cmd-freqs (recent-command-frequencies lobbies)
          user-cache-count (count (:users @app-state))
          lobby-sub-count (count (filter identity (vals (:lobby-updates @app-state))))
          lobby-update-uids (count (lobby-update-uids))
          [average-sub-time oldest-sub-time] (subscriber-time-metrics (filter identity (vals (:lobby-updates @app-state))))
          ajax-uid-count (count (:ajax @connected-sockets))
          ajax-conn-counts (seq (map count (:ajax @connections_)))
          ajax-conn-total (reduce + ajax-conn-counts)
          ws-uid-count (count (:ws @connected-sockets))
          ws-conn-counts (seq (map count (:ws @connections_)))
          ws-conn-total (reduce + ws-conn-counts)]
      (timbre/info (str
                     "stats -"
                     " lobbies: " lobbies-count
                     " players: " players
                     " spectators: " spectators
                     " cached-users: " user-cache-count
                     " lobby-subs: " lobby-sub-count
                     " lobby-update-uids: " lobby-update-uids
                     " average-lobby-subs-lifetime: " average-sub-time "m"
                     " oldest-lobby-sub: " oldest-sub-time "m"
                     " | "
                     "websockets -"
                     " :ajax { "
                     " uid: " ajax-uid-count
                     " conn: " ajax-conn-total
                     " } :ws { "
                     " uid: " ws-uid-count
                     " conn: " ws-conn-total
                     " }"))
      ;; TODO - once we've got this set up on the server, wrap it in a try/catch - only ever display
      ;; the warning once!
      (timbre/info (str "Active Cards (across all lobbies): " card-freqs))
      (timbre/info (str "Recent Commands (across all lobbies): " cmd-freqs))
      (timbre/info (str "System Load (average): " (system-load-average)
                        " - heap: " (heap-usage) "\n"))))))
