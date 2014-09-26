(ns clj.medusa.alert
  (:require [amazonica.aws.simpleemail :as ses]
            [amazonica.core :as aws]
            [clojure.string :as string]
            [clj.medusa.config :as config]
            [clj.medusa.db :as db]
            [clj.medusa.config :as config]))

(defn send-email [subject body destinations]
  (let [destinations ["rvitillo@mozilla.com"]] ;; TODO: remove once everything works
    (when-not (:dry-run @config/state)
      (ses/send-email :destination {:to-addresses destinations}
                      :source "telemetry-alerts@mozilla.com"
                      :message {:subject subject
                                :body {:html (str "<a href=\"" body "\">" body "</a>")}}))))

(defn notify-subscribers [{:keys [metric_id date emails]}]
  (let [{:keys [hostname port]} @config/state
        foreign_subscribers (string/split emails #",")
        {metric_name :name,
         detector_id :detector_id
         metric_id :id} (db/get-metric metric_id)
        {detector_name :name} (db/get-detector detector_id)
        subscribers (db/get-subscribers-for-metric metric_id)]
    (send-email (str "Alert for " metric_name " (" detector_name ") on the " date)
                (str "http://" hostname ":" port "/index.html#/detectors/" detector_id "/"
                     "metrics/" metric_id "/alerts/?from=" date "&to=" date)
                (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"])))) 
