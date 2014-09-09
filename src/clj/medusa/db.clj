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

(declare user alert metric detector user_metric user_detector)

(defentity user
  (many-to-many metric :user_metric)
  (many-to-many detector :user_detector))

(defentity user_metric)

(defentity user_detector)

(defentity alert
  (belongs-to metric))

(defentity metric
  (has-many alert)
  (belongs-to detector)
  (many-to-many user :user_metric))

(defentity detector
  (has-many metric)
  (many-to-many user :user_detector))

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

   "CREATE TABLE IF NOT EXISTS user_metric
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       user_id INTEGER NOT NULL,
       metric_id INTEGER NOT NULL,
       FOREIGN KEY(user_id) REFERENCES user(id)
       FOREIGN KEY(metric_id) REFERENCES metric(id))"

   "CREATE TABLE IF NOT EXISTS user_detector
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       user_id INTEGER NOT NULL,
       detector_id INTEGER NOT NULL,
       FOREIGN KEY(user_id) REFERENCES user(id)
       FOREIGN KEY(detector_id) REFERENCES detector(id))"

   "CREATE TABLE IF NOT EXISTS detector
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       name TEXT NOT NULL UNIQUE,
       date TEXT NOT NULL,
       url TEXT NOT NULL)"

   "CREATE TABLE IF NOT EXISTS metric
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       detector_id INTEGER NOT NULL,
       name TEXT NOT NULL,
       description TEXT NOT NULL,
       date TEXT NOT NULL,
       FOREIGN KEY(detector_id) REFERENCES detector(id))"

   "CREATE TABLE IF NOT EXISTS alert
      (id INTEGER PRIMARY KEY AUTOINCREMENT,
       metric_id INTEGER NOT NULL,
       date TEXT NOT NULL,
       description TEXT NOT NULL,
       FOREIGN KEY(metric_id) REFERENCES metric(id))"))

(defn populate_db_test []
  (when (empty? (select user))
    (insert user (values {:email "rvitillo@mozilla.com"})))
  (when (empty? (select detector))
    (insert detector (values [{:name "Histogram Regression Detector"
                               :date "2014-05-01"
                               :url "foobar.com"}
                              {:name "Mainthread-IO Regression Detector"
                               :date "2014-05-01"
                               :url "foobar1.com"}])))
  (when (empty? (select metric))
    (insert metric (values {:name "metric1",
                            :date "2014-07-02",
                            :description "metric descr",
                            :detector_id 1}))
    (insert metric (values {:name "metric2",
                            :date "2014-07-02",
                            :description "metric descr",
                            :detector_id 1})))
  (when (empty? (select alert))
    (insert alert (values {:date "2014-07-02",
                           :description "{}",
                           :metric_id 2}))
    (insert alert (values {:date "2014-07-05",
                           :description "{}",
                           :metric_id 2})))
  (when (empty? (select user_metric))
    (insert user_metric (values {:user_id 1
                                 :metric_id 1})))
  (when (empty? (select user_detector))
    (insert user_detector (values {:user_id 1
                                   :detector_id 1}))))

(defn load []
  (info "Loading database...")
  (initialize-db)
  (populate_db_test))

(defn detector-is-valid? [detector]
  (= #{:name :url} (set (keys detector))))

(defn metric-is-valid? [metric]
  (= #{:name :description :detector_id} (set (keys metric))))

(defn alert-is-valid? [alert]
  (= #{:date :description :metric_id :detector_id} (set (keys alert))))

(defn add-detector [{:keys [name url]}]
  (let [date (->> (time/now) (timef/unparse time-formatter))]
    (-> (insert detector
                (values {:name name, :date date, :url url}))
        (first)
        (second))))

(defn add-metric [{:keys [detector_id name description]}]
  (let [date (->> (time/now) (timef/unparse time-formatter))]
    (-> (insert metric
                (values {:name name,
                         :description description,
                         :date date
                         :detector_id detector_id}))
        (first)
        (second))))

(defn add-alert [{:keys [:metric_id :date :description :date]}] ; returns rowid
  (-> (insert alert
              (values {:date date
                       :description description
                       :metric_id metric_id}))
      (first)
      (second)))

(defn add-user [email]
  (-> (insert user (values {:email email}))))

(defn edit-subscription [{:keys [user-id detector-id metric-id op]}]
  (if (= op "subscribe")
    (if metric-id
      (insert user_metric
              (values {:user_id user-id
                       :metric_id metric-id}))
      (insert user_detector
              (values {:user_id user-id
                       :detector_id detector-id})))
    (if metric-id
      (delete user_metric
              (where {:user_id user-id
                      :metric_id metric-id}))
      (delete user_detector
              (where {:user_id user-id
                      :detector_id detector-id})))))

(defn get-user [email]
  (first (select user
                 (fields :id :email)
                 (where (= :email email)))))

(defn get-subscriptions [email]
  (first (select user
                 (fields :id :email)
                 (where (= :email email))
                 (with metric
                       (fields [:id :metric_id])
                       (with detector
                             (fields [:id :detector_id])))
                 (with detector
                       (fields [:id :detector_id])))))

(defn get-detectors
  ([]
     (get-detectors {}))

  ([{:keys [id name] :as params}]
     (let [id (if id id "%")
           name (if name name "%")]
       (select detector
               (fields :id :name :date)
               (where (and
                       (like :id id)
                       (like :name name)))
               (order :date :DESC)))))

(defn get-alerts
  ([]
     (get-alerts {}))

  ([{:keys [id detector_id metric_id from to date] :as params}]
     (let [id (if id id "%")
           detector_id (if detector_id detector_id "%")
           metric_id (if metric_id metric_id "%")
           from (if from from "0000-00-00")
           to (if to to "9999-00-00")
           date (if date date "%")]
       (->> (select alert
                    (fields :id :date :description)
                    (where (and (>= :date from)
                                (<= :date to)
                                (like :date date)
                                (like :id id)))
                    (order :date :DESC)
                    (with metric
                          (fields [:id :metric_id] [:name :metric_name])
                          (where (like :metric_id metric_id))
                          (with detector
                                (fields [:id :detector_id])
                                (where (like :detector_id detector_id)))))))))

(defn get-metrics
  ([]
     (get-metrics {}))

  ([{:keys [:metric_id :detector_id :name]}]
     (let [detector_id (if detector_id detector_id "%")
           metric_id (if metric_id metric_id "%")
           name (if name name "%")]
       (->> (select metric
                    (fields :id :name :description)
                    (where (and (like :id metric_id)
                                (like :name name)))
                    (with detector
                          (fields [:id :detector_id])
                          (where (like :detector_id detector_id))))))))
