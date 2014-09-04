(ns clj.medusa.core
  (:require [org.httpkit.server :as http]
            [liberator.dev :refer [wrap-trace]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [clj.medusa.config :as config]
            [clj.medusa.db :as db]
            [clj.medusa.resource :as resource]
            [ring.middleware.reload :as reload]))

(defroutes app
  (ANY "/login" [& params]
       (resource/login params))
  (ANY "/detectors/" [& params]
       (resource/detectors-resource params))
  (ANY "/detectors/:id" [& params]
       (resource/detector-resource params))
  (ANY "/detectors/:detector_id/metrics/" [& params]
       (resource/metrics-resource params))
  (ANY "/detectors/:detector_id/metrics/:metric_id" [& params]
       (resource/metrics-resource params))
  (ANY "/detectors/:detector_id/metrics/:metric_id/alerts/" [& params]
       (resource/alerts-resource params))
  (ANY "/detectors/:detector_id/metrics/:metric_id/alerts/:id" [& params]
       (resource/alerts-resource params))
  (ANY "/detectors/:detector_id/alerts/" [& params]
       (resource/alerts-resource params))
  (route/resources "/")
  (route/not-found "Page not found"))

(def handler
  (-> app
      (wrap-trace :header :ui)))

(config/load)
(db/load)

(http/run-server (reload/wrap-reload (handler/site #'handler)) {:port (:port @config/state)})
