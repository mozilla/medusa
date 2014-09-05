(ns cljs.medusa.core
  (:require [cljs.core.async :refer  [<! >! put! close! chan pub sub]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros  [html]]
            [cljs.reader :as reader]
            [ajax.core :refer  [GET POST]]
            [weasel.repl :as ws-repl]
            [clojure.browser.repl :as repl]
            [cljs.core.match]
            [cljs-time.core :as time]
            [cljs-time.format :as timef])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs.core.match.macros :refer [match]]))

(ws-repl/connect "ws://localhost:9001" :verbose true)
(enable-console-print!)

(def date-formatter (timef/formatter "yyyy-MM-dd"))

(let [end-date (time/today-at 12 00)
      start-date (time/minus end-date (time/months 1))
      end-date (timef/unparse date-formatter end-date)
      start-date (timef/unparse date-formatter start-date)]
  (def app-state (atom {:login {:user nil}
                        :detectors []
                        :metrics []
                        :alerts []
                        :selected-detector nil
                        :selected-metric nil
                        :selected-date-range [start-date end-date]
                        :error {}})))

(defn detector-list [{:keys [detectors selected-detector]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.list-group
             (for [detector detectors]
               [:a.list-group-item
                {:class (when (= selected-detector detector) "active")
                 :on-click #(put! event-channel [:detector-selected detector])}
                (:name detector)])]))))

(defn metrics-list [{:keys [metrics selected-metric]} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [element (om/get-node owner "metrics-selector")
            jq-element (js/$ element)]
        (.select2 jq-element #js {:placeholder ""
                                  :allowClear true})))

    om/IDidUpdate
    (did-update [_ _ _]
      (let [event-channel (om/get-state owner :event-channel)
            name->metric (zipmap (map :name metrics) metrics)
            element (om/get-node owner "metrics-selector")
            jq-element (js/$ element)]

        ; needed to clean current selection
        (.select2 jq-element #js {:placeholder ""
                                  :allowClear true})
        (.off jq-element)
        (.on jq-element "change" (fn [e]
                                   (let [selected-metric (get name->metric (.-val e))]
                                     (put! event-channel [:metric-selected selected-metric]))))))
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html
       [:div.form-group
        [:label {:for "metrics-selector"} "Metric:"]
        [:select.form-control {:ref "metrics-selector"
                               :id "metrics-selector"}
         [:option ""]
         (for [metric (sort-by :name metrics)]
           [:option (when (= metric selected-metric) {:selected true}) (:name metric)])]]))))

(defn date-selector [{:keys [selected-date-range]} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [event-channel (om/get-state owner :event-channel)
            start-element (js/$ (om/get-node owner "start-date"))
            end-element (js/$ (om/get-node owner "end-date"))
            start-datepicker (.datepicker start-element #js {:format "yyyy-mm-dd"})
            end-datepicker (.datepicker end-element #js {:format "yyyy-mm-dd"})
            format-date #(timef/unparse date-formatter (time/plus (time/date-time (.-date %))
                                                                  (time/days 1)))]
        (.on start-datepicker "changeDate" (fn [e]
                                             (.datepicker start-element "hide")
                                             (put! event-channel [:from-selected (format-date e)])))
        (.on end-datepicker "changeDate" (fn [e]
                                           (.datepicker end-element "hide")
                                           (put! event-channel [:to-selected (format-date e)])))
        start-datepicker))

    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html
       [:div.form-group
        [:label {:for "start-date"} "From: "]
        (html/text-field {:ref "start-date", :class "form-control"} "start-date" (get selected-date-range 0))

        [:label {:for "end-date"} "To: "]
        (html/text-field {:ref "end-date", :class "form-control"} "end-date" (get selected-date-range 1))]))))

(defn query-controls [{:keys [metrics selected-metric selected-date-range] :as state} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html
       [:form
        (om/build metrics-list
                  (select-keys state [:metrics :selected-metric])
                  {:init-state {:event-channel event-channel}})
        (om/build date-selector
                  (select-keys state [:selected-date-range])
                  {:init-state {:event-channel event-channel}})]))))

(defn alert-description->googleframe [date description]
  (let [series (.-series description)
        series-label (.-series-label description)
        reference-series (.-reference-series description)
        reference-series-label (.-reference-series-label description)
        buckets (.-buckets description)]
    (clj->js (concat [#js ["Buckets" series-label reference-series-label]]
                     (map (fn [a b c] #js [(str a) b c]) buckets series reference-series)))))


(defn alert-graph [{:keys [description date]} owner]
  (let [draw-chart (fn []
                     (let [element (om/get-node owner "alert-description")
                           chart (js/google.visualization.LineChart. element)
                           data (->> (alert-description->googleframe date description)
                                     (.arrayToDataTable js/google.visualization))
                           options #js {:curveType 'function'
                                        :height 500
                                        :colors #js ["red", "black"]
                                        :vAxis #js {:title (.-y_label description)}
                                        :hAxis #js {:title (.-x_label description)
                                                    :slantedText true
                                                    :slatedTextAngle 90}}]
                       (.draw chart data options)))]
    (reify
      om/IDidMount
      (did-mount [_]
        (draw-chart))

      om/IDidUpdate
      (did-update [_ _ _]
        (draw-chart))

      om/IRender
      (render [_]
        (html [:a.list-group-item
               [:div
                [:h5.text-center (.-title description)]]
               [:div {:ref "alert-description"}]])))))

(defn alert [{:keys [description date]} owner]
  (let [description (.parse js/JSON description)]
    (condp = (.-type description)
      "graph" (alert-graph {:date date, :description description} owner)
      nil)))

(defn alerts-list [{:keys [alerts]}]
  (reify
    om/IRender
    (render [_]
      (html [:div.list-group
             (om/build-all alert alerts)]))))

(defn error-notification [{:keys [error]}]
  (reify
    om/IRender
    (render [_]
      (html [:div (:message error)]))))

(defn get-resource
  ([update-key uri]
     (get-resource update-key uri {}))
  ([update-key uri params]
     (let [ch (chan)]
       (GET uri
            {:handler #(do (put! ch {:data %}) (close! ch))
             :format :raw
             :params params
             :error-handler #(do (put! ch {:error (str "Failure to load "
                                                       (name update-key)
                                                       ", reason: "
                                                       (:status-text %))})
                                 (close! ch))})
       ch)))

(defn update-resource
  ([state update-key uri]
     (update-resource state update-key uri {}))
  ([state update-key uri params]
     (go
       (let [{:keys [data error]} (<! (get-resource update-key uri params))]
         (if-not error
           (om/update! state [update-key] data)
           (do
             (om/update! state [update-key] nil)
             (om/update! state [:error :message] error)))))))

(defn load-google-charts []
  (let [ch (chan)
        cb (fn []
             (put! ch "LOADED")
             (close! ch))]
    (.load js/google "visualization" "1" (clj->js {:packages ["corechart"]}))
    (.setOnLoadCallback js/google cb)
    ch))

(defn persona [{:keys [user] :as state} owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [event-channel (om/get-state owner :event-channel)
            login (fn [assertion]
                    (let [ch (chan)]
                      (GET "/login"
                           {
                            :handler #(do (put! ch %) (close! ch))
                            :format :raw
                            :params {:assertion assertion}
                            :error-handler #(do (put! ch {}) (close! ch))})
                      ch))]
        (js/navigator.id.watch #js {:loggedInUser user
                                    :onlogin (fn [assertion]
                                               (go
                                                 (let [user (<! (login assertion))
                                                       email (:email user)]
                                                   (put! event-channel [:login email]))))
                                    :onlogout (fn []
                                                (put! event-channel [:logout nil]))})))

    om/IRenderState
    (render-state [_ _]
      (html
       [:div.text-right
        [:button.btn.btn-default {:on-click (fn [_]
                                              (if-not user
                                                (js/navigator.id.request)
                                                (js/navigator.id.logout)))}
         (if user "Logout" "Login")]]))))

(defn layout [state owner]
  (reify
    om/IInitState
    (init-state [_]
      {:event-channel (chan)})

    om/IWillMount
    (will-mount [_]
      (let [google-load-ch (load-google-charts)]
        (go
          (<! google-load-ch)
          (update-resource state :detectors "/detectors/")

          (loop []
            (let [event-channel (om/get-state owner :event-channel)
                  [topic message] (<! event-channel)]
              ;process event
              (condp = topic
                :login
                (do
                  (println "login")
                  (om/update! state :login {:user message}))

                :logout
                (do
                  (println "logout")
                 (om/update! state :login {:user nil}))

                :detector-selected
                (do
                  (om/transact! state (fn [state]
                                        (let [state (assoc state
                                                      :selected-detector message
                                                      :selected-metric nil
                                                      :alerts nil)
                                              state (assoc-in state [:error :message] "")]
                                          state))))

                :metric-selected
                (let [detector (:selected-detector @state)]
                  (om/transact! state (fn [state]
                                        (let [state (assoc state :selected-metric message)
                                              state (assoc-in state [:error :message] "")]
                                          state))))

                :from-selected
                (do
                  (om/update! state [:selected-date-range 0] message))

                :to-selected
                (do
                  (om/update! state [:selected-date-range 1] message)))

              ;retrieve resources
              (let [selected-detector (:selected-detector @state)
                    selected-metric (:selected-metric @state)
                    selected-start-date (get-in @state [:selected-date-range 0])
                    selected-end-date (get-in @state [:selected-date-range 1])]
                (match [selected-detector selected-metric]
                       [nil nil]
                       ()

                       [_ nil]
                       (do
                         (update-resource state :metrics
                                          (str "/detectors/" (:id @selected-detector) "/metrics/"))
                         (update-resource state
                                          :alerts
                                          (str "/detectors/" (:id @selected-detector) "/alerts/")
                                          {:from selected-start-date
                                           :to selected-end-date}))

                       [_ _]
                       (do
                         (update-resource state
                                          :alerts
                                          (str "/detectors/"
                                               (:id @selected-detector)
                                               "/metrics/"
                                               (:id @selected-metric)
                                               "/alerts/")
                                          {:from selected-start-date
                                           :to selected-end-date}))))
              (recur))))))

    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.container
             [:div.page-header
              [:h1 "Telemetry alerting dashboard"]]
             [:div.row
              [:div.col-md-3
               (om/build persona
                         (:login state)
                         {:init-state {:event-channel event-channel}})
               [:br]
               (om/build detector-list
                         (select-keys state [:detectors :selected-detector])
                         {:init-state {:event-channel event-channel}})
               (om/build query-controls
                         (select-keys state[:metrics :selected-metric :selected-date-range])
                         {:init-state {:event-channel event-channel}})
               (om/build error-notification (select-keys state [:error]))]
              [:div.col-md-9
               (om/build alerts-list (select-keys state [:alerts]))]]]))))

(om/root layout app-state {:target (.getElementById js/document "app")})
