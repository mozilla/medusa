(ns cljs.medusa.core
  (:require [cljs.core.async :refer  [<! >! put! close! chan pub sub]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros  [html]]
            [cljs.reader :as reader]
            [ajax.core :refer  [GET POST]]
            [cljs.core.match])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs.core.match.macros :refer [match]]))

(enable-console-print!)

(def app-state (atom {:detectors []
                      :metrics []
                      :alerts []
                      :selected-detector nil
                      :selected-metric nil
                      :error {}}))

(defn detector-list [{:keys [detectors selected-detector]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.list-group
             (for [detector detectors]
               [:a.list-group-item
                {:class (when (= selected-detector detector) "active")
                 :on-click #(go (>! event-channel [:detector-selected detector]))}
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
        (.off jq-element)
        (.on jq-element "change" (fn [e]
                                   (let [selected-metric (get name->metric (.-val e))]
                                     (go
                                       (>! event-channel [:metric-selected selected-metric])))))))
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

(defn date-selector [state owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div "FOO"]))))

(defn query-controls [{:keys [metrics selected-metric] :as state} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html
       [:form
        (om/build metrics-list
                  (select-keys state [:metrics :selected-metric])
                  {:init-state {:event-channel event-channel}})
        (om/build date-selector
                  state
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

(defn get-resource [update-key uri]
  (let [ch (chan)]
    (GET uri
         {:handler #(go (>! ch {:data %}))
          :error-handler #(go (>! ch {:error (str "Failure to load " (name update-key) ", reason: " (:status-text %))}))})
    ch))

(defn update-resource [state update-key uri]
  (go
    (let [{:keys [data error]} (<! (get-resource update-key uri))]
      (if-not error
        (om/update! state [update-key] data)
        (do
          (om/update! state [update-key] nil)
          (om/update! state [:error :message] error))))))

(defn load-google-charts []
  (let [ch (chan)
        cb (fn []
             (go
               (>! ch "LOADED")
               (close! ch)))]
    (.load js/google "visualization" "1" (clj->js {:packages ["corechart"]}))
    (.setOnLoadCallback js/google cb)
    ch))

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
                :detector-selected
                (do
                  (om/update! state [:selected-detector] message)
                  (om/update! state [:error :message] "")
                  (om/update! state [:selected-metric] nil)
                  (om/update! state [:alerts] nil))

                :metric-selected
                (let [detector (:selected-detector @state)]
                  (om/update! state [:selected-metric] message)
                  (om/update! state [:error :message] "")))
                                        ;retrieve resources
              (let [selected-detector (:selected-detector @state)
                    selected-metric (:selected-metric @state)]
                (match [selected-detector selected-metric]
                       [_ nil]
                       (do
                         (update-resource state :metrics (str "/detectors/" (:id @selected-detector) "/metrics/"))
                         (update-resource state :alerts (str "/detectors/" (:id @selected-detector) "/alerts/")))

                       [_ _]
                       (do
                         (update-resource state :alerts (str "/detectors/" (:id @selected-detector) "/metrics/" (:id @selected-metric) "/alerts/")))))
              (recur))))))

    om/IRenderState
    (render-state [_ {:keys [event-channel]}]
      (html [:div.container
             [:div.page-header
              [:h1 "Telemetry alerting dashboard"]]
             [:div.row
              [:div.col-md-3
               (om/build detector-list
                         (select-keys state [:detectors :selected-detector])
                         {:init-state {:event-channel event-channel}})
               (om/build query-controls
                         (select-keys state[:metrics :selected-metric])
                         {:init-state {:event-channel event-channel}})
               (om/build error-notification (select-keys state [:error]))]
              [:div.col-md-9
               (om/build alerts-list (select-keys state [:alerts]))]]]))))

(om/root layout app-state {:target (.getElementById js/document "app")})
