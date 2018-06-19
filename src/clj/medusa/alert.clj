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
        alert-url (str "http://" hostname "/index.html#/detectors/" detector_id "/metrics/" metric_id "/alerts/?from=" date "&to=" date)
        evo-url (str "https://telemetry.mozilla.org/new-pipeline/evo.html#!measure=" metric_name)]
    (try
      (let [[earliest-build latest-build] (changesets/bounding-buildids date "mozilla-central") ; get the buildids for the given date
            changeset-url (changesets/pushlog-url earliest-build latest-build "mozilla-central")]
        (log/info "Changeset URL" changeset-url)
        (send-email (str "Telemetry Alert for " metric_name " on " date)
                    (str "We have detected a change in the Telemetry probe " metric_name " in Firefox Nightly builds from " date "."
                         "\n\n"
                         "Alert details: " alert-url
                         "\n"
                         "Changes new to Nightly builds on " date ": " changeset-url
                         "\n"
                         "The value of " metric_name " over time: " evo-url
                         "\n\n"
                         "What to do about this:"
                         "\n\n"
                         "1. File a bug to track your investigation. You can just copy this email into the bug Description to get you started."
                         "\n"
                         "2. Reply-All to this email to let the list know that you are investigating. Include the bug number so we can help out."
                         "\n"
                         "3. Triage the alert. You can find instructions here: " alert-url
                         "\n\n"
                         "If you have any problems, please ask for help on the #telemetry IRC channel or on Slack in #fx-metrics. We'll give you a hand."
                         "\n\n"
                         "What this is:"
                         "We have a system called cerberus[1] that compares Telemetry collected on different Nightly builds and "
                         "looks for sudden changed in value distributions using the Bhattacharyya Distance[2]. It found such a "
                         "change in " metric_name " on " date " so it asked its buddy medusa[3] to send this email to the "
                         "dev-telemetry-alerts mailing list and to all email addresses listed in the alert_emails field of "
                         metric_name "'s definition."
                         "\n\n"
                         "You can do this!"
                         "\n\n"
                         "Your Friendly, Neighbourhood Firefox Telemetry Team"
                         "\n"
                         "[1]: https://github.com/mozilla/cerberus"
                         "\n"
                         "[2]: https://en.wikipedia.org/wiki/Bhattacharyya_distance"
                         "\n"
                         "[3]: https://github.com/mozilla/medusa"
                         "\n")
                    (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"])))
      (catch Throwable e ; could not find revisions for the given build date
        (log/info e "Retrieving changeset failed")
        (send-email (str "Alert for " metric_name " (" detector_name ") on " date)
                    (str "Alert details: " alert-url)
                         "\n\n"
                         "Evolution dashboard: " evo-url
                    (concat subscribers foreign_subscribers ["dev-telemetry-alerts@lists.mozilla.org"]))))))
