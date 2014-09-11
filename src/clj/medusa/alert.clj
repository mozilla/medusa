(ns clj.medusa.alert
  (:require [amazonica.aws.simpleemail :as ses]
            [clj.medusa.config :as config]
            [clj.medusa.db :as db]))

(defn send-email [subject body destinations]
  (ses/send-email :destination {:to-addresses destinations}
                  :source "telemetry-alerts@mozilla.com"
                  :message {:subject subject
                            :body {:text body}}))

(defn notify-subscribers [{:keys [metric_id date]}]
  (let [{:keys [hostname port]} @config/state
        {metric_name :name,
         detector_id :detector_id
         metric_id :id} (db/get-metric metric_id)
        {detector_name :name} (db/get-detector detector_id)
        subscribers (db/get-subscribers-for-metric metric_id)]
    (send-email (str "Alert for " metric_name " (" detector_name ")")
                (str hostname ":" port "/index.html#/detectors/" detector_id "/"
                     "metrics/" metric_id "/alerts/?from=" date "&to=" date)
                subscribers))) 
