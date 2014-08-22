(ns cljs.medusa.core
  (:require [cljs.core.async :refer  [<! >! put! close! chan pub sub]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros  [html]]
            [cljs.reader :as reader]
            [ajax.core :refer  [GET POST]])
  (:require-macros [cljs.core.async.macros :refer  [go]]))

(enable-console-print!)

(def event-channel (chan))

(def app-state (atom {:detectors []
                      :metrics []
                      :alerts []
                      :selected-detector []
                      :error {}}))

(defn get-detectors-list [{:keys [detectors error]}]
  (go
    (GET "/detectors/"
      {:handler
       (fn [data]
         (om/update! detectors data))

       :error-handler
       (fn [e]
         (om/update! error [:message] (str "Failure to load detectors list, reason: " (:status-text e)))) })))

(defn get-metrics-list [detector state]
  (go
    (GET (str "/detectors/" (:id @detector) "/metrics/")
      {:handler
       (fn [data]
         (om/update! state [:metrics] data))

       :error-handler
       (fn [e]
         (om/update! state [:error :message] (str "Failure to load metrics list, reason: " (:status-text e)))
         (om/update! state [:metrics] []))})))

(defn get-alerts-list [detector state]
  (go
    (GET (str "/detectors/" (:id @detector) "/alerts/")
      {:handler
       (fn [data]
         (om/update! state [:alerts] data))

       :error-handler
       (fn [e]
         (om/update! state [:error :message] (str "Failure to load metrics list, reason: " (:status-text e)))
         (om/update! state [:alerts] []))})))

(defn detector-list [{:keys [detectors selected-detector]}]
  (reify
    om/IRender
    (render [_]
      (html [:div.list-group
             (for [detector detectors]
               [:a.list-group-item
                {:class (when (= selected-detector detector) "active")
                 :on-click #(go (>! event-channel [:detector-selected detector]))}
                (:name detector)])]))))

(defn metrics-list [{:keys [metrics]}]
  (reify
    om/IRender
    (render [_]
      (html [:select
             (for [metric metrics]
               [:option (:name metric)])]))))

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

(defn event-loop [state]
  (go
    (loop []
      (let [[topic message] (<! event-channel)]
        (condp = topic
          :detector-selected
          (do
            (om/update! state [:error :message] "")
            (om/update! state [:selected-detector] message)
            (get-metrics-list message state)
            (get-alerts-list message state)))
        (recur)))))

(defn layout [state]
  (reify
    om/IWillMount
    (will-mount [_]
      (event-loop state)
      (let [{:keys [detectors error]} state]
       (get-detectors-list {:detectors detectors :error error})))

    om/IRender
    (render [_]
      (html [:div.container-fluid
             (om/build detector-list (select-keys state [:detectors :selected-detector]))
             (om/build metrics-list (select-keys state [:metrics]))
             (om/build alerts-list (select-keys state [:alerts]))
             (om/build error-notification (select-keys state [:error]))]))))

(om/root layout app-state {:target (.getElementById js/document "app")})
