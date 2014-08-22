(ns clj.medusa.resource
  (:require [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
            [cheshire.core :as json]
            [clj.medusa.db :as db]))

(defn handle-ok [data-key ctx]
  (let [media-type (get-in ctx [:representation :media-type])
        data (get ctx data-key)]
    (condp = media-type
      "text/json" (json/generate-string data)
      "application/edn" (pr-str data))))

(defresource metrics-resource [query]
  :available-media-types
  ["application/edn" "text/json"]

  :allowed-methods
  [:get]

  :exists?
  (fn [ctx]
    (when-let [metrics (not-empty (vec (db/get-metrics query)))]
      {:metrics metrics}))

  :handle-ok
  (partial handle-ok :metrics))


(defresource alerts-resource [query]
  :available-media-types
  ["application/edn" "text/json"]

  :allowed-methods
  [:get]

  :exists?
  (fn [ctx]
    (when-let [alerts (not-empty (vec (db/get-alerts query)))]
      {:alerts alerts}))

  :handle-ok
  (partial handle-ok :alerts))

(defresource detectors-resource []
  :available-media-types
  ["application/edn" "text/json"]

  :allowed-methods
  [:get]

  :exists?
  (fn [ctx]
    (when-let [detectors (not-empty (vec (db/get-detectors)))]
      {:detectors detectors}))

  :handle-ok
  (partial handle-ok :detectors))

(defresource alert-resource [query]
  :available-media-types
  ["application/edn" "text/json"]

  :allowed-methods
  [:get]

  :malformed?
  (fn [ctx]
    (not (and (contains? query :id)
              (contains? query :metric_id)
              (contains? query :detector_id))))

  :exists?
  (fn [ctx]
    (when-let [alert (db/get-alerts query)]
      {:alert alert}))

  :etag
  (fn [ctx]
    (get-in ctx [:alert :date]))

  :handle-ok
  (partial handle-ok :alert))
