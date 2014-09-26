(defproject medusa "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main clj.medusa.core
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [http-kit "2.1.16"]
                 [compojure "1.1.8"]
                 [korma "0.3.0"]
                 [org.xerial/sqlite-jdbc "3.7.15-M1"]
                 [com.taoensso/timbre "3.2.1"]
                 [ring "1.3.0"]
                 [cheshire "5.3.1"]
                 [liberator "0.12.1"]
                 [org.clojure/core.async  "0.1.338.0-5c5012-alpha"]
                 [org.clojure/core.match  "0.2.1"]
                 [clj-time "0.8.0"]
                 [com.cemerick/friend "0.2.1"]
                 [amazonica "0.2.24"]

                 [weasel "0.4.0-SNAPSHOT"]
                 [org.clojure/clojurescript "0.0-2311"]
                 [om "0.7.0"]
                 [sablono "0.2.21"]
                 [cljs-ajax  "0.2.6"]
                 [com.andrewmcveigh/cljs-time "0.1.6"]
                 [secretary "1.2.1"]]

  :plugins  [[lein-cljsbuild  "1.0.4-SNAPSHOT"]
             [com.cemerick/austin  "0.1.4"]
             [lein-daemon "0.5.4"]]

  :daemon {:medusa {:ns clj.medusa.core 
                    :pidfile "medusa.pid"}}

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/cljs/medusa"]
                        :compiler {
                                   :output-to "resources/public/medusa_dev.js"
                                   :output-dir "resources/public/out_dev"
                                   :optimizations :none
                                   :pretty-print true
                                   :source-map true}}
                       {:id "release"
                        :source-paths ["src/cljs/medusa"]
                        :compiler {
                                   :output-to "resources/public/medusa.js"
                                   :output-dir "resources/public/out"
                                   :optimizations :simple
                                   :pretty-print false}}]})
