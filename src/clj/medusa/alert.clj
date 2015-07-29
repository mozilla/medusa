(ns clj.medusa.alert
  (:require [amazonica.aws.simpleemail :as ses]
            [amazonica.core :as aws]
            [clojure.string :as string]
            [clj.medusa.config :as config]
            [clj.medusa.db :as db]
            [clj.medusa.config :as config]
            [clj.medusa.changesets :as changesets]
            [clojure.tools.logging :as log]))

(defn send-email [subject body destinations]
  (if (:dry-run @config/state) ; Dry run, don't actually send out emails
    (do (println "================ EMAIL NOTIFICATION ================")
        (println "EMAIL TO " destinations ":")
        (println "SUBJECT: " subject)
        (println "BODY: " subject)
        (println "===================================================="))
    (ses/send-email :destination {:to-addresses destinations}
                    :source "telemetry-alerts@mozilla.com"
                    :message {:subject subject
                              :body {:text body}})))

(defn notify-subscribers [{:keys [metric_id date emails]}]
  (let [{:keys [hostname]} @config/state
        foreign_subscribers (when (seq emails) (string/split emails #","))
        {metric_name :name,
         detector_id :detector_id
         metric_id :id} (db/get-metric metric_id)
        {detector_name :name} (db/get-detector detector_id)
        subscribers (db/get-subscribers-for-metric metric_id)
        alert-url (str "http://" hostname "/index.html#/detectors/" detector_id "/metrics/" metric_id "/alerts/?from=" date "&to=" date)]
    (try
      (let [buildid (changesets/find-date-buildid date "mozilla-central") ; get the buildid for the given date
            changeset-url (changesets/find-build-changeset buildid "mozilla-central")]
        (log/info "Changeset URL" changeset-url)
        (send-email (str "Alert for " metric_name " (" detector_name ") on " date)
                    (str "Alert details: " alert-url "\n\n" "Changeset for " buildid ": " changeset-url)
                    (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"])))
      (catch Exception e ; could not find revisions for the given build date
        (log/error e "Retrieving changeset failed")
        (send-email (str "Alert for " metric_name " (" detector_name ") on " date)
                    (str "Alert details: " alert-url)
                    (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"]))))))
