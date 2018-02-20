(ns clj.medusa.alert
  (:require [amazonica.aws.simpleemail :as ses]
            [clojure.string :as string]
            [clj.medusa.config :as config]
            [clj.medusa.db :as db]
            [clj.medusa.changesets :as changesets]
            [clojure.tools.logging :as log]))

(defn send-email [subject body destinations]
  (if (:dry-run @config/state) ; Dry run, don't actually send out emails
    (do (println "================ EMAIL NOTIFICATION ================")
        (println "EMAIL TO " destinations ":")
        (println "SUBJECT: " subject)
        (println "BODY: " subject)
        (println "===================================================="))
    (ses/send-email {:endpoint "us-west-2"}
                     :destination {:to-addresses destinations}
                     :source "telemetry-alerts@mozilla.com"
                     :return-path "telemetry-alert-bounces@mozilla.com"
                     :message {:subject subject
                               :body {:text body}})))

(defn- build-range
  "Returns 'abc123' if there's only 1 build. Otherwise, return 'abc123...def456'."
  [earliest-build latest-build]
  (if (= earliest-build latest-build) earliest-build (str earliest-build "..." latest-build)))

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
      (let [[earliest-build latest-build] (changesets/bounding-buildids date "mozilla-central") ; get the buildids for the given date
            changeset-url (changesets/pushlog-url earliest-build latest-build "mozilla-central")]
        (log/info "Changeset URL" changeset-url)
        (send-email (str "Alert for " metric_name " (" detector_name ") on " date)
                    (str "Alert details: " alert-url
                         "\n\n"
                         "Changeset for " (build-range earliest-build latest-build) ": " changeset-url)
                    (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"])))
      (catch Throwable e ; could not find revisions for the given build date
        (log/info e "Retrieving changeset failed")
        (send-email (str "Alert for " metric_name " (" detector_name ") on " date)
                    (str "Alert details: " alert-url)
                    (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"]))))))
