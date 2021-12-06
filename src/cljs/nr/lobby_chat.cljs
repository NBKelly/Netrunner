(ns nr.lobby-chat
  (:require
   [nr.avatar :refer [avatar]]
   [nr.translations :refer [tr]]
   [nr.ws :as ws]
   [reagent.core :as r]))

(defn send-message [state current-game]
  (let [text (:msg @state)]
    (when (and (string? text) (not-empty text))
      (ws/ws-send! [:lobby/say {:gameid (:gameid @current-game)
                                :text text}])
      (swap! state assoc :should-scroll true)
      (swap! state assoc :msg ""))))

(defn scrolled-to-end?
  [el tolerance]
  (> tolerance (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el))))

(defn lobby-chat [_current-game _messages]
  (r/with-let [state (r/atom {:message-list nil
                              :msg ""
                              :should-scroll false})
               message-list (r/cursor state [:message-list])
               current-input (r/cursor state [:msg])
               should-scroll (r/cursor state [:should-scroll])]
    (r/create-class
      {:display-name "lobby-chat"
       :component-did-mount
       (fn []
         (let [el (r/dom-node @message-list)]
           (set! (.-scrollTop el) (.-scrollHeight el))))
       :component-will-update
       (fn []
         (let [el (r/dom-node @message-list)]
           (swap! state assoc :should-scroll (or @should-scroll
                                                 (scrolled-to-end? el 15)))))
       :component-did-update
       (fn []
         (let [el (r/dom-node @message-list)]
           (when @should-scroll
             (swap! state assoc :should-scroll false)
             (set! (.-scrollTop el) (.-scrollHeight el)))))
       :reagent-render
       (fn [current-game messages]
         [:div.chat-box
          [:h3 (tr [:lobby.chat "Chat"])]
          (into [:div.message-list {:ref #(swap! state assoc :message-list %)}]
                (map
                  (fn [{:keys [user text timestamp]}]
                    (if (= user "__system__")
                      [:div.system {:key timestamp} text]
                      [:div.message {:key timestamp}
                       [avatar user {:opts {:size 38}}]
                       [:div.content
                        [:div.username (:username user)]
                        [:div text]]]))
                  @messages))
          [:div
           [:form.msg-box {:on-submit #(do (.preventDefault %)
                                           (send-message state current-game))}
            [:input {:placeholder (tr [:chat.placeholder "Say something"])
                     :type "text"
                     :value @current-input
                     :on-change #(swap! state assoc :msg (-> % .-target .-value))}]
            [:button (tr [:chat.send "Send"])]]]])})))
