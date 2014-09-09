(ns clj.medusa.core
  (:require [org.httpkit.server :as http]
            [liberator.dev :refer [wrap-trace]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [clj.medusa.config :as config]
            [clj.medusa.db :as db]
            [clj.medusa.resource :as resource]
            [clj.medusa.persona :as persona]
            [ring.middleware.reload :as reload]
            [cemerick.friend :as friend]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds])))

(defroutes app
  (ANY "/login" []
       (resource/login))
  (ANY "/logout" []
       (friend/logout (resource/logout)))
  (ANY "/subscriptions" [& params]
       (resource/subscriptions params))
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
      (wrap-trace :header :ui)
      (friend/authenticate {:credential-fn persona/credential-fn
                            :workflows [(partial persona/workflow "/login")]})))

(config/initialize)
(db/initialize)

(http/run-server (reload/wrap-reload (handler/site #'handler)) {:port (:port @config/state)})
