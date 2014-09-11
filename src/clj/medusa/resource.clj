(ns clj.medusa.resource
  (:require [liberator.core :refer [resource defresource by-method]]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [cemerick.friend :as friend]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clj.medusa.db :as db]
            [clj.medusa.persona :as persona]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(defn simple-authorization [{{addr :remote-addr, method :request-method} :request :as ctx}]
  (if (= method :get)
    true
    (= addr "127.0.0.1")))

(defn handle-created [ks ctx]
  (let [id (get-in ctx ks)
        data {:id id}]
    data))

(defn parse [content-type data]
  (condp = content-type
    "application/json" (json/parse-string data true)
    "application/edn" (edn/read data)
    data))

;; needs to be parsed manually
;; https://github.com/clojure-liberator/liberator/issues/39
(defn get-body [ctx]
  (let [content-type (get-in ctx [:request :content-type])
        body (parse content-type (slurp (get-in ctx [:request :body])))]
    body))

(defresource metrics-resource [query]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :authorized? simple-authorization
  :exists? (by-method
            {:get
             (fn [ctx]
               (when-let [metrics (not-empty (vec (db/get-metrics query)))]
                 {:metrics metrics}))
             :post true})
  :processable? (by-method
                 {:get true
                  :post (fn [ctx]
                          (let [body (merge (get-body ctx) query)]
                            (when (and (db/metric-is-valid? body)
                                       (empty? (db/get-metrics body)))
                              {:metric body})))})
  :post! (fn [ctx]
           (let [metric (get ctx :metric)
                 rowid (db/add-metric metric)
                 metric (assoc metric :metric_id rowid)]
             {:metric metric}))
  :handle-created (partial handle-created [:metric :metric_id])
  :handle-ok :metrics)

(defresource alerts-resource [query]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :authorized? simple-authorization
  :exists? (by-method
            {:get (fn [ctx]
                    (when-let [alerts (not-empty (vec (db/get-alerts query)))]
                      {:alerts alerts}))
             :post true})
  :processable? (by-method {:get true
                            :post (fn [ctx]
                                    (let [query (merge (get-body ctx) query)]
                                      (when (and (db/alert-is-valid? query)
                                                 (empty? (db/get-alerts query)))
                                        {:alert query})))})
  :post! (fn [ctx]
           (let [alert (:alert ctx)
                 rowid (db/add-alert alert)
                 alert (assoc alert :alert_id rowid)]
             {:alert alert}))
  :handle-created (partial handle-created [:alert :alert_id])
  :handle-ok :alerts)

(defresource detectors-resource [query]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :authorized? simple-authorization
  :exists? (by-method {:get (fn [ctx] (when-let [detectors (not-empty (vec (db/get-detectors query)))]
                                        {:detectors detectors}))
                       :post true})
  :processable? (let [continue? (fn [ctx]
                                  (let [body (get-body ctx)]
                                    (when (and (db/detector-is-valid? body)
                                               (empty? (db/get-detectors body)))
                                      {:detector body})))]
                  (by-method
                   {:get true
                    :post continue?}))
  :post! (fn [ctx]
           (let [rowid (db/add-detector (get ctx :detector))]
             {:rowid rowid}))
  :handle-ok :detectors
  :handle-created (partial handle-created [:rowid]))

(defresource detector-resource [query]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:delete]
  :exists? (fn [ctx]
             (when-let [detector (first (db/get-detectors query))]
               {:detector detector}))
  :delete! (fn [ctx]
             ;;TODO: useful for debugging
             ))

(defresource alert-resource [query]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get]
  :malformed? (fn [ctx]
                (not (and (contains? query :id)
                          (contains? query :metric_id)
                          (contains? query :detector_id))))
  :exists? (fn [ctx]
             (when-let [alert (db/get-alerts query)]
               {:alert alert}))
  :etag (fn [ctx]
          (get-in ctx [:alert :date]))
  :handle-ok :alert)

(defresource login []
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get]
  :authorized? persona/authorized?
  :handle-ok (fn [{:keys [identity]}]
               (when-not (:current identity)
                 (warn "Persona identity missing, did you update the hostname in the configuration file?"))
               (db/get-user (:current identity))))

(defresource logout []
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get]
  :authorized? persona/authorized?
  :handle-ok nil)

(defresource subscriptions [query]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :authorized? persona/authorized?
  :post! (fn [ctx]
           (let [user (persona/user ctx)
                 body (get-body ctx)]
             (db/edit-subscription (assoc body :user-id (:id user)))))
  :handle-ok (fn [ctx]
               (let [user (persona/user ctx)]
                 (db/get-subscriptions (:email user)))))
