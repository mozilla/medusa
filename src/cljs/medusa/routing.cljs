(ns cljs.medusa.routing
  (:require [secretary.core :as secretary :include-macros true :refer [defroute]]
            [cljs.core.async :refer  [<! >! put! close! chan pub sub]]
            [cljs.core.match]
            [goog.events :as events]
            [goog.history.EventType :as EventType])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs.core.match.macros :refer [match]])
  (:import goog.History))

(enable-console-print!)
(secretary/set-config! :prefix "#")

(def route-channel (chan))

(defroute detectors "/detectors/" {:as params}
  (put! route-channel params))

(defroute detector "/detectors/:detector-id/alerts/" [detector-id query-params]
  (put! route-channel (merge query-params {:detector-id (int detector-id)})))

(defroute metric "/detectors/:detector-id/metrics/:metric-id/alerts/" [detector-id metric-id query-params] 
  (put! route-channel (merge query-params {:detector-id (int detector-id)
                                           :metric-id (int metric-id)})))

(let [h (History.)]
  (goog.events/listen h EventType/NAVIGATE #(secretary/dispatch! (.-token %)))
  (doto h (.setEnabled true)))

(defn goto [& {:keys [detector-id metric-id from to] :as params}]
  (let [uri (match [detector-id metric-id from to]
                   [nil nil _ _] nil
                   [_ nil _ _] (detector {:detector-id detector-id
                                          :query-params {:from from, :to to}})
                   [_ _ _ _] (metric {:detector-id detector-id
                                      :metric-id metric-id
                                      :query-params {:from from, :to to}}))]
    (set! js/location.hash uri)))
