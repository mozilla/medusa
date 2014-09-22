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
            [cljs-time.format :as timef]
            [cljs.medusa.routing :as routing])
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
                        :subscriptions []
                        :selected-detector nil
                        :selected-metric nil
                        :metrics-filter nil
                        :selected-date-range [start-date end-date]
                        :error {}})))

(defn detector-list [{:keys [detectors selected-detector]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.list-group
             (for [detector detectors]
               [:a.list-group-item
                {:class (when (= (:id selected-detector) (:id detector)) "active")
                 :on-click #(put! event-channel [:detector-selected @detector])}
                (:name detector)])]))))

(defn metrics-list [{:keys [metrics-filter metrics selected-metric]} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [element (om/get-node owner "metrics-selector")
            jq-element (js/$ element)]
        (.select2 jq-element #js {:placeholder ""
                                  :allowClear true
                                  :dropdownAutoWidth true})))

    om/IDidUpdate
    (did-update [_ _ _]
      (let [event-channel (om/get-state owner :event-channel)
            name->metric (zipmap (map :name metrics) metrics)
            element (om/get-node owner "metrics-selector")
            jq-element (js/$ element)]

        ;; needed to clean current selection
        (.select2 jq-element #js {:placeholder ""
                                  :allowClear true
                                  :dropdownAutoWidth true})
        (.off jq-element)
        (.on jq-element "change" (fn [e]
                                   (let [selected-metric (get name->metric (.-val e))
                                         selected-metric (when selected-metric @selected-metric)]
                                     (put! event-channel [:metric-selected selected-metric]))))))
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html
       [:div.form-group
        [:label {:for "metrics-selector"} "Select Metric:"]
        [:select.form-control {:ref "metrics-selector"
                               :id "metrics-selector"}
         [:option ""]
         (for [metric (->> metrics
                           (filter #(re-find (re-pattern (or metrics-filter ".*")) (:name %)))
                           (sort-by :name))]
           [:option (when (= (:id metric) (:id selected-metric))
                      {:selected true})
            (:name metric)])]]))))

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

(defn metrics-regexp-filter [{:keys [metrics metrics-filter]} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (let [handler (fn []
                      (let [el (om/get-node owner "metrics-filter")
                            value (.-value el)]
                        (put! event-channel [:filter-selected value])))]
        (html [:div.form-group
               [:label {:for "metrics-filter"} "Filter Metrics By: "]
               (html/text-field {:ref "metrics-filter"
                                 :class "form-control"
                                 :on-change handler}
                                "metrics-filter" metrics-filter)])))))

(defn query-controls [{:keys [metrics-filter metrics selected-metric selected-date-range] :as state} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html
       [:form
        (om/build metrics-regexp-filter
                  (select-keys state [:metrics-filter :metrics])
                  {:init-state {:event-channel event-channel}})
        (om/build metrics-list
                  (select-keys state [:metrics-filter :metrics :selected-metric])
                  {:init-state {:event-channel event-channel}})
        (om/build date-selector
                  (select-keys state [:selected-date-range])
                  {:init-state {:event-channel event-channel}})]))))

(defn alert-graph [{:keys [description date]} owner]
  (let [description->frame (fn [date description]
                             (let [series (.-series description)
                                   series-label (.-series-label description)
                                   reference-series (.-reference-series description)
                                   reference-series-label (.-reference-series-label description)
                                   buckets (.-buckets description)]
                               (clj->js (concat [#js ["Buckets" series-label reference-series-label]]
                                                (map (fn [a b c] #js [(str a) b c]) buckets series reference-series)))))
        update-title (fn []
                       (let [element (om/get-node owner "alert-title")
                             title (.-title description)
                             link (.-link description)]
                         (aset element "innerHTML" (str "<a href=\"" link "\">" title "</a>"))))

        draw-chart (fn []
                     (let [element (om/get-node owner "alert-description")
                           chart (js/google.visualization.LineChart. element)
                           data (->> (description->frame date description)
                                     (.arrayToDataTable js/google.visualization))
                           options #js {:curveType "function"
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
        (update-title)
        (draw-chart))

      om/IDidUpdate
      (did-update [_ _ _]
        (update-title)
        (draw-chart))

      om/IRender
      (render [_]
        (html [:a.list-group-item
               [:div
                [:h5.text-center {:ref "alert-title"} (.-title description)]]
               [:div {:ref "alert-description"}]])))))

(defn alert-boxplot [{:keys [description date]} owner]
  (let [draw-chart (fn []
                     (let [data (google.visualization.arrayToDataTable #js [#js ["Label" "Min - Max, Q1 - Q3" "" "" "" date #js {"type" "string", "role" "annotation"}]
                                                                            (clj->js (.-boxplot description))])
                           formatter (google.visualization.NumberFormat. #js {"fractionDigits" 4})
                           _ (do
                               (.format formatter data 1)
                               (.format formatter data 2)
                               (.format formatter data 3)
                               (.format formatter data 4)
                               (.format formatter data 5))
                           element (om/get-node owner "alert-description")
                           chart (js/google.visualization.ComboChart. element)
                           options #js {:height 500
                                        :legend "none"
                                        :series #js {"0" #js {"type" "candlesticks"}
                                                     "1" #js {"type" "line"
                                                              "pointSize" 10
                                                              "lineWidth" 0
                                                              "color" "red"}}}]
                       (.draw chart data options)))
        update-title (fn []
                       (let [element (om/get-node owner "alert-title")
                             title (.-title description)
                             link (.-link description)]
                         (aset element "innerHTML" (str "<a href=\"" link "\">" title "</a>"))))]
    (reify
      om/IDidMount
      (did-mount [_]
        (update-title)
        (draw-chart))

    om/IDidUpdate
    (did-update [_ _ _]
      (update-title)
      (draw-chart))

    om/IRender
    (render [_]
      (html [:a.list-group-item
             [:div
              [:h5.text-center {:ref "alert-title"} (.-title description)]]
             [:div {:ref "alert-description"}]])))))

(defn alert [{:keys [description date]} owner]
  (let [description (.parse js/JSON description)]
    (condp = (.-type description)
      "graph" (alert-graph {:date date, :description description} owner)
      "boxplot" (alert-boxplot {:date date, :description description} owner)
      nil)))

(defn alerts-list [{:keys [alerts metrics-filter]}]
  (reify
    om/IRender
    (render [_]
      (html [:div.list-group
             (om/build-all alert
                           (filter #(re-find (re-pattern (or metrics-filter ".*"))
                                             (:metric_name %))
                                   alerts))]))))

(defn description [{:keys [alerts selected-detector metrics-filter selected-metric login subscriptions]} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (let [is-subscribed? (fn []
                             (let [detector-id (:id selected-detector)
                                   metric-id (:id selected-metric)]
                               (= true
                                  (match [detector-id metric-id]
                                         [nil nil] false
                                         [_ nil] (some #(and (= detector-id (:detector_id %))
                                                             (= metrics-filter (:metrics_filter %)))
                                                       (:detector subscriptions))
                                         [_ _] (some #(= metric-id (:metric_id %)) (:metric subscriptions))))))]
        (html [:div
               (when (and selected-detector (:user login))
                 [:form
                  [:div.form-group.text-center
                   [:label
                    (html/check-box {:checked (is-subscribed?)
                                     :ref "check-box"
                                     :on-click (fn []
                                                 (let [el (om/get-node owner "check-box")
                                                       checked (.-checked el)]
                                                   (put! event-channel [(if checked :subscribe :unsubscribe)])))}
                                    "subscription-status")
                    " Keep me posted about the current selection"]]])
               (om/build alerts-list {:alerts alerts
                                      :metrics-filter metrics-filter})])))))

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
                      (GET "/login" {:handler #(do (put! ch %) (close! ch))
                                     :format :raw
                                     :params {:assertion assertion}
                                     :error-handler #(do (put! ch {}) (close! ch))})
                      ch))
            logout (fn []
                     (let [ch (chan)]
                       (GET "/logout" {:finally #(do (put! ch {}) (close! ch))})
                       ch))]
        (js/navigator.id.watch #js {:loggedInUser user
                                    :onlogin (fn [assertion]
                                               (go
                                                 (let [user (<! (login assertion))
                                                       email (:email user)]
                                                   (put! event-channel [:login email]))))
                                    :onlogout (fn []
                                                (go
                                                  (<! (logout))
                                                  (put! event-channel [:logout nil])))})))

    om/IRenderState
    (render-state [_ _]
      (html
       [:div.text-right
        [:button.btn.btn-default {:on-click (fn [_]
                                              (if-not user
                                                (js/navigator.id.request)
                                                (js/navigator.id.logout)))}
         (if user "Logout" "Login")]]))))

(defn subscription-list [{{:keys [detector metric] :as subscriptions} :subscriptions
                          [from to] :selected-date-range}
                         owner]
  (reify
    om/IRender
    (render [_]
      (let [format-detector (fn [{:keys [detector_name metrics_filter]}]
                              [:div {:style {:overflow "hidden"
                                             :white-space "nowrap"}}
                               [:div [:small "Detector: " [:em detector_name]]]
                               (when (seq metrics_filter)
                                 [:div [:small "Filter: " [:em metrics_filter]]])])
            format-metric (fn [{:keys [metric_name detector_name]}]
                            [:div {:style {:overflow "hidden"
                                           :white-space "nowrap"}}
                             [:div [:small "Detector: " [:em detector_name]]]
                             [:div [:small "Metric: " [:em metric_name]]]])
            detector-click (fn [detector]
                             (let [{:keys [detector_id metrics_filter]} @detector]
                               (routing/goto :detector-id detector_id
                                             :metrics-filter metrics_filter
                                             :from from
                                             :to to))
                             )
            metric-click (fn [metric]
                           (let [{:keys [detector_id metric_id]} @metric]
                             (routing/goto :detector-id detector_id
                                           :metric-id metric_id
                                           :from from
                                           :to to)))
            format (fn [e]
                     (if (:metric_name e)
                       [:a.list-group-item {:on-click (partial metric-click e)} (format-metric e)]
                       [:a.list-group-item {:on-click (partial detector-click e)} (format-detector e)]))]
        (when (seq subscriptions)
          (let [sorted-entries (sort-by (fn [{:keys [detector_name metric_name metrics_filter]}]
                                          (str detector_name metrics_filter metric_name))
                                        (concat detector metric))]
           (html [:div
                 [:label "Your Subscriptions:"]
                 [:div.list-group
                  (for [e sorted-entries]
                    (format e))]])))))))

(defn layout [state owner]
  (reify
    om/IInitState
    (init-state [_]
      {:event-channel (chan)})

    om/IWillMount
    (will-mount [_]
      (let [google-load-ch (load-google-charts)
            load-subscriptions (fn []
                                 (let [ch (chan)]
                                   (GET "/subscriptions" {:handler #(do (put! ch %) (close! ch))
                                                          :error-handler #(do (put! ch {}) (close! ch))})
                                   ch))
            change-subscription (fn [op]
                                  (let [ch (chan)
                                        selected-detector-id (get-in @state [:selected-detector :id])
                                        selected-metric-id (get-in @state [:selected-metric :id])
                                        metrics-filter (:metrics-filter @state)]
                                    (POST "/subscriptions"
                                          {:handler #(do (put! ch {}) (close! ch))
                                           :format :json
                                           :params {:op op
                                                    :detector-id selected-detector-id
                                                    :metric-id selected-metric-id
                                                    :metrics-filter metrics-filter}})
                                    ch))
            retrieve-alerts (fn []
                              (let [selected-detector (:selected-detector @state)
                                    selected-metric (:selected-metric @state)
                                    selected-start-date (get-in @state [:selected-date-range 0])
                                    selected-end-date (get-in @state [:selected-date-range 1])
                                    metrics (:metrics @state)]
                                (match [selected-detector selected-metric metrics]
                                       [nil nil _]
                                       ()

                                       [_ nil _]
                                       (do
                                         (update-resource state :metrics
                                                          (str "/detectors/" (:id selected-detector) "/metrics/"))
                                         (update-resource state
                                                          :alerts
                                                          (str "/detectors/" (:id selected-detector) "/alerts/")
                                                          {:from selected-start-date
                                                           :to selected-end-date}))
                                       [_ _ []] ;; happens when dashboard is loaded from URI
                                       (do
                                         (update-resource state :metrics
                                                          (str "/detectors/" (:id selected-detector) "/metrics/"))
                                         (update-resource state
                                                          :alerts
                                                          (str "/detectors/"
                                                               (:id selected-detector)
                                                               "/metrics/"
                                                               (:id selected-metric)
                                                               "/alerts/")
                                                          {:from selected-start-date
                                                           :to selected-end-date}))
                                       [_ _ _]
                                       (update-resource state
                                                        :alerts
                                                        (str "/detectors/"
                                                             (:id selected-detector)
                                                             "/metrics/"
                                                             (:id selected-metric)
                                                             "/alerts/")
                                                        {:from selected-start-date
                                                         :to selected-end-date}))))
            route-update (fn [updated-state]
                           (let [state (merge @state updated-state)
                                 {:keys [selected-detector metrics-filter selected-metric selected-date-range]} state
                                 [from to] selected-date-range]
                             (if selected-metric
                               (routing/goto :detector-id (:id selected-detector)
                                             :metrics-filter metrics-filter
                                             :metric-id (:id selected-metric)
                                             :from from
                                             :to to)
                               (routing/goto :detector-id (:id selected-detector)
                                             :metrics-filter metrics-filter
                                             :from from
                                             :to to))))
            route-handler (fn [state event-channel]
                            (go
                              (loop []
                                (let [{:keys [detector-id metrics-filter metric-id from to] :as ev} (<! routing/route-channel)
                                      detector {:id detector-id}
                                      metric (when metric-id {:id metric-id})]
                                  (>! event-channel [:query-has-changed [detector metrics-filter metric from to]])
                                  (recur)))))]
        (go
          (<! google-load-ch)
          (update-resource state :detectors "/detectors/")
          (route-handler state (om/get-state owner :event-channel))

          (loop []
            (let [event-channel (om/get-state owner :event-channel)
                  [topic message] (<! event-channel)]
              ;;process event
              (condp = topic
                :subscribe
                (do
                  (<! (change-subscription :subscribe))
                  (om/update! state :subscriptions (<! (load-subscriptions))))

                :unsubscribe
                (do
                  (<! (change-subscription :unsubscribe))
                  (om/update! state :subscriptions (<! (load-subscriptions))))

                :login
                (let [subscriptions (<! (load-subscriptions))]
                  (om/transact! state (fn [state]
                                        (-> state
                                            (assoc :subscriptions subscriptions)
                                            (assoc-in [:login :user] message)))))

                :logout
                (om/transact! state (fn [state]
                                      (assoc state :login {:user nil}, :subscriptions [])))

                :query-has-changed
                (let [[detector filter metric from to] message
                      extract-query #(select-keys @state [:selected-detector
                                                          :selected-metric
                                                          :selected-date-range
                                                          :alerts])
                      pre-query (extract-query)]
                  (om/transact! state (fn [state]
                                        (-> state
                                            (assoc :selected-detector detector
                                                   :selected-metric metric
                                                   :metrics-filter filter
                                                   :selected-date-range [from to]
                                                   :alerts nil)
                                            (assoc-in [:error :message] ""))))
                  ;; don't refetch data from the server when only the filter
                  ;; has changed since we perform client-side filtering
                  (when (not= pre-query (extract-query)) (retrieve-alerts)))

                :filter-selected
                (route-update {:metrics-filter message})

                :detector-selected
                (route-update {:selected-detector message
                               :selected-metric nil
                               :metrics-filter nil})

                :metric-selected
                (route-update {:selected-metric message})

                :from-selected
                (route-update {:selected-date-range [message (get-in @state [:selected-date-range 1])]})

                :to-selected
                (route-update {:selected-date-range [(get-in @state [:selected-date-range 0]) message]}))

              (recur))))))

    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.container
             [:div.page-header
              [:h1 "Telemetry alerts"]]
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
                         (select-keys state [:metrics-filter
                                             :metrics
                                             :selected-metric
                                             :selected-date-range])
                         {:init-state {:event-channel event-channel}})
               (om/build subscription-list
                         (select-keys state [:subscriptions :selected-date-range]))
               (om/build error-notification (select-keys state [:error]))]
              [:div.col-md-9
               (om/build description
                         (select-keys state [:selected-detector
                                             :metrics-filter
                                             :selected-metric
                                             :alerts
                                             :login
                                             :subscriptions])
                         {:init-state {:event-channel event-channel}})]]]))))

(om/root layout app-state {:target (.getElementById js/document "app")})
