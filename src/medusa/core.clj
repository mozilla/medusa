(ns medusa.core
  (:require [org.httpkit.server :as http]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [medusa.config :as config]
            [medusa.db :as db]
            [ring.middleware.reload :as reload]
            [medusa.resource :as resource]))

(defroutes app
  (GET "/" [] "Hello World!")
  (GET "/detectors/" []
    (resource/detectors-resource))
  (GET "/detectors/:detector_id/metrics/" [& params]
    (resource/metrics-resource params))
  (GET "/detectors/:detector_id/metrics/:metric_id" [& params]
    (resource/metrics-resource params))
  (GET "/detectors/:detector_id/metrics/:metric_id/alerts" [& params]
    (resource/alerts-resource params))
  (GET "/detectors/:detector_id/metrics/:metric_id/alerts/:id" [& params]
    (resource/alerts-resource params))
  (GET "/detectors/:detector_id/alerts/" [& params]
    (resource/alerts-resource params))
  (route/not-found "Page not found"))

(config/load)
(db/load)

(http/run-server (reload/wrap-reload (handler/site #'app)) {:port (:port @config/state)})
