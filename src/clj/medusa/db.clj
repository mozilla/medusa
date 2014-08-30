(ns clj.medusa.db
  (:require [korma.db :refer :all]
            [korma.core :refer :all]
            [clj.medusa.config :as config]
            [clojure.java.jdbc :as sql]
            [clojure.set :as set]
            [clj-time.core :as time]
            [clj-time.format :as timef]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(def time-formatter (timef/formatter "yyyy-MM-dd"))

(declare user alert metric detector)

(defentity user
  (entity-fields :email))

(defentity alert
  (belongs-to metric))

(defentity metric
  (has-many alert)
  (belongs-to detector))

(defentity detector
  (has-many metric))

(defn initialize-db []
  (def db-spec {:classname "org.sqlite.JDBC"
                :subprotocol "sqlite"
                :subname (:database @config/state)})
  (defdb korma-db db-spec)
  (sql/db-do-commands
    db-spec
    "PRAGMA foreign_keys = ON"

    "CREATE TABLE IF NOT EXISTS user
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       email TEXT NOT NULL UNIQUE)"

    "CREATE TABLE IF NOT EXISTS detector
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       name TEXT NOT NULL UNIQUE,
       date TEXT NOT NULL,
       url TEXT NOT NULL)"

    "CREATE TABLE IF NOT EXISTS metric
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       detector_id INTEGER NOT NULL,
       name TEXT NOT NULL UNIQUE,
       description TEXT NOT NULL,
       FOREIGN KEY(detector_id) REFERENCES detector(id))"

    "CREATE TABLE IF NOT EXISTS alert
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       metric_id INTEGER NOT NULL,
       date TEXT NOT NULL,
       description TEXT NOT NULL,
       FOREIGN KEY(metric_id) REFERENCES metric(id))"))

(defn populate_db_test []
  (when (empty? (select user))
    (insert user (values {:email "ra.vitillo@gmail.com"})))
  (when (empty? (select detector))
    (insert detector (values [{:name "Histogram Regression Detector"
                               :date "2014-05-01"
                               :url "foobar.com"}
                              {:name "Mainthread-IO Regression Detector"
                               :date "2014-05-01"
                               :url "foobar1.com"}])))
  (when (empty? (select metric))
    (insert metric (values {:name "metric1", :description "metric descr", :detector_id 1}))
    (insert metric (values {:name "metric2", :description "metric descr", :detector_id 1})))
  (when (empty? (select alert))
    (insert alert (values {:date "2014-07-02", :description "alert descr", :metric_id 1}))
    (insert alert (values {:date "2014-07-05", :description "alert descr", :metric_id 2}))))

(defn load []
  (info "Loading database...")
  (initialize-db)
  (populate_db_test))

(defn detector-is-valid? [detector]
  (set/subset? #{:name :url} (set (keys detector))))

(defn get-detectors
  ([]
   (get-detectors {}))

  ([{:keys [name] :as params}]
   (let [name (if name name "%")]
     (select detector
       (fields :id :name :date)
       (where (like :name name))
       (order :date :DESC)))))

(defn add-detector [{:keys [name url]}]
  (let [date (->> (time/now) (timef/unparse time-formatter))]
    (insert detector
      (values {:name name, :date date, :url url}))))

(defn get-alerts
  ([]
   (get-alerts {}))

  ([{:keys [id detector_id metric_id from to] :as params}]
   (let [id (if id id "%")
         detector_id (if detector_id detector_id "%")
         metric_id (if metric_id metric_id "%")
         from (if from from "0000-00-00")
         to (if to to "9999-00-00")]
     (->> (select alert
            (fields :id :date :description)
            (where (and (>= :date from)
                        (<= :date to)
                        (like :id id)))
            (order :date :DESC)
            (with metric
              (fields [:id :metric_id])
              (where (like :metric_id metric_id))
              (with detector
                (fields [:id :detector_id])
                (where (like :detector_id detector_id)))))))))

(defn get-metrics
  ([]
   (get-metrics {}))

  ([{:keys [:metric_id :detector_id]}]
   (let [detector_id (if detector_id detector_id "%")
         metric_id (if metric_id metric_id "%")]
     (->> (select metric
            (fields :id :name :description)
            (where (like :id metric_id))
            (with detector
              (fields [:id :detector_id])
              (where (like :detector_id detector_id))))))))
