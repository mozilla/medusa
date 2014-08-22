(ns cljs.medusa.core
  (:require [cljs.core.async :refer  [<! >! put! close! chan pub sub]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros  [html]]
            [cljs.reader :as reader]
            [ajax.core :refer  [GET POST]])
  (:require-macros [cljs.core.async.macros :refer  [go]]))

(enable-console-print!)

(def app-state (atom {:detectors []
                      :metrics []
                      :alerts []
                      :selected-detector nil
                      :selected-metric nil
                      :error {}}))

(defn detector-list [{:keys [detectors selected-detector]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.list-group
             (for [detector detectors]
               [:a.list-group-item
                {:class (when (= selected-detector detector) "active")
                 :on-click #(go (>! event-channel [:detector-selected detector]))}
                (:name detector)])]))))

(defn metrics-list [{:keys [metrics]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (let [name>metric (zipmap (map :name metrics) metrics)]
       (html [:select {:on-change #(let [selected-metric (get name>metric (.. % -target -value))]
                                     (go
                                       (>! event-channel [:metric-selected selected-metric])))}
              (for [metric metrics]
                [:option (:name metric)])])))))

(defn alerts-list [{:keys [alerts]}]
  (reify
    om/IRender
    (render [_]
      (html [:div.list-group
             (for [alert alerts]
               [:a.list-group-item
                (:description alert)])]))))

(defn error-notification [{:keys [error]}]
  (reify
    om/IRender
    (render [_]
      (html [:div (:message error)]))))

(defn get-resource [uri]
  (let [ch (chan)]
    (GET uri
      {:handler #(go (>! ch {:data %}))
       :error-handler #(go (>! ch {:error (str "Failure to load detectors list, reason: " (:status-text %))}))})
    ch))

(defn update-resource [state update-key uri]
  (go
    (let [{:keys [data error]} (<! (get-resource uri))]
      (if-not error
        (om/update! state [update-key] data)
        (do
          (om/update! state [update-key] nil)
          (om/update! state [:error :message] error))))))

(defn layout [state owner]
  (reify
    om/IInitState
    (init-state [_]
      {:event-channel (chan)})

    om/IWillMount
    (will-mount [_]
      (update-resource state :detectors "/detectors/")

      (go
        (loop []
          (let [event-channel (om/get-state owner :event-channel)
                [topic message] (<! event-channel)]
            (condp = topic
              :detector-selected
              (do
                (om/update! state [:error :message] "")
                (om/update! state [:selected-detector] message)
                (update-resource state :metrics (str "/detectors/" (:id @message) "/metrics/"))
                (update-resource state :alerts (str "/detectors/" (:id @message) "/alerts/")))

              :metric-selected
              (let [detector (:selected-detector @state)]
                (om/update! state [:error :message] "")
                (om/update! state [:selected-metric] message)
                (update-resource
                  state
                  :alerts
                  (str "/detectors/" (:id @detector) "/metrics/" (:id @message) "/alerts/"))))
            (recur)))))

    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.container-fluid
             (om/build detector-list
                       (select-keys state [:detectors :selected-detector])
                       {:init-state {:event-channel event-channel}})
             (om/build metrics-list
                       (select-keys state [:metrics])
                       {:init-state {:event-channel event-channel}})
             (om/build alerts-list (select-keys state [:alerts]))
             (om/build error-notification (select-keys state [:error]))]))))

(om/root layout app-state {:target (.getElementById js/document "app")})
