(ns medusa.config
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(def state (atom {}))

(defn load []
  (info "Loading configuration...")
  (with-open [rdr (java.io.PushbackReader. (io/reader "resources/config.edn"))]
,   (swap! state (fn [c] (edn/read rdr)))))
