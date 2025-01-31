(ns synfron.options
  (:require
   [clojure.string :as str]
   [synfron.state :as state]
   [synfron.time :refer [from-ts offset offset-exp s-to-h-min cur-local-time cur-ny-time]]
   [synfron.filters :refer [trigger-search]]))

(def columns-w-names
  [[:contractSymbol "Contract Symbol" "CS"]
   [:symbol "Symbol" "S"]
   [:optType "Type" "T"]
   [:strike "Strike" "Str"]
   [:expiration "Expiration" "Exp"]
   [:lastTradeDate "Last Trade Date" "LTD"]
   [:impliedVolatility "Implied Volatility" "IV"]
   [:iv20d "IV 20 days avg" "IV20d"]
   [:iv100d "IV 100 days avg" "IV100d"]
   [:bid "Bid" "B"]
   [:bid20d "Bid 20 days avg" "B20d"]
   [:bid100d "Bid 100 days avg" "B100d"]
   [:ask "Ask" "A"]
   [:lastPrice "Last Price" "LP"]
   [:volume "Volume" "V"]
   [:v20d "Volume 20 days avg" "V20d"]
   [:v100d "Volume 100 days avg" "V100d"]
   [:openInterest "Open Interest" "OI"]
   [:oi20d "OI 20 days avg" "OI20d"]
   [:oi100d "OI 100 days avg" "OI100d"]
   [:yield "Yield" "Y"]
   [:monthlyyield "Monthly Yield" "MY"]
   [:inTheMoney "In the Money" "ItM"]
   [:pChange "Price Change" "PC"]
   [:regularMarketPrice "Market Price" "MP"]
   [:regularMarketDayLow "Market Day Low" "MDL"]
   [:regularMarketDayHigh "Market Day High" "MDH"]
   [:delta "Delta" "δ"]
   [:gamma "Gamma" "γ"]
   [:theta "Theta" "θ"]
   [:vega "Vega" "ν"]
   [:rho "Rho" "ρ"]
   [:quoteType "Quote Type" "QT"]
   [:lastCrawl "Last Updated" "LU"]])

(defn opt-sidebar
  []
  (let [activ-cols (get-in @state/app-state [:options :columns])
        sidebar (:sidebar @state/app-state)]
    [:div {:class ["sidebar" (when sidebar "show")]}
     [:div
      {:class ["sidebar-toggle"]
       :on-click state/toggle-sidebar}
      [:p (if sidebar "<" ">")]]
     [:h3 "Columns"]
     [:div.columns 
      (doall
       (map
        (fn [[col-id col-name abbrev]]
          (let [col-c-id (str "col-c-" (name col-id))]
            [:div {:class ["col-choice"]
                   :key col-c-id}
             [:input
              {:type "checkbox" :id col-c-id :default-checked (contains? activ-cols col-id)
               :on-change (fn [ev] (state/toggle-column col-id))}]
             [:label {:for col-c-id} (str col-name " (" abbrev ")")]]))
        columns-w-names))]]))

(defn draw-symbol
  [ticker]
  (let [catalysts (get-in @state/app-state [:options :data :catalysts (keyword ticker)])
        now (cur-ny-time)
        earnings (-> catalysts :earnings first)
        dividends (:dividends catalysts)]
    [:div.symb
     [:p ticker]
     (when (> (:raw earnings) now)
       [:div.catalyst.e [:p "E"] [:div.cat-info (str "earnings: " (:fmt earnings))]])
     (when (> (:raw dividends) now)
       [:div.catalyst.d [:p "D"] [:div.cat-info (str "dividends: " (:fmt dividends))]])]))

(defn ladder-next
  [{:keys [contractSymbol symbol expiration optType]}]
  (let [ladder (get-in @state/app-state [:options :ladder [symbol expiration optType]])]
    (when (not (empty? ladder))
      (try
        (let [css (to-array (sort (map name (keys ladder))))
              target-index (+ (.indexOf css contractSymbol)
                              (if (= optType "C") 1 -1))
              ;; If somehow we are at an extremity of the ladder, just go to next available
              target-index (cond (= (.-length css) target-index) (- (.-length css) 2)
                                 (= -1 target-index) 1
                                 :else target-index)
              target-cs (nth (js->clj css) target-index)]
          (get-in
           @state/app-state
           [:options :ladder [symbol expiration optType] (keyword target-cs)]))
        (catch js/Error _ nil)))))

(defn draw-cell
  [next id v]
  (cond
    (= id :symbol)
    (draw-symbol v)

    (= id :lastCrawl)
    (s-to-h-min (- (- (cur-local-time) offset) v))
    
    (= id :lastTradeDate)
    [:p
     (if (number? v)
       (-> (from-ts (+ v offset-exp)) (str/split #",") first)
       (str v))]

    (= id :expiration)
    [:p
     (if (number? v)
       (let [ts (+ v offset-exp)]
         (str
          (-> (from-ts (+ v offset-exp)) (str/split #",") first)
          " (" (inc (int (/ (- ts (cur-ny-time)) 86400))) "d)"))
       (str v))]
    
    (or (= id :impliedVolatility)
        (= id :yield) (= id :monthlyyield))
    [:p (if (number? v)
          (.toFixed v 2)
          (str v))]

    (or (= id :ask) (= id :bid) (= id :lastPrice))
    (if (number? v) [:<> "$" (.toFixed (if next (- v (get next id)) v) 2)] v)

    (or (= id :regularMarketPrice)
        (= id :regularMarketDayLow)
        (= id :regularMarketDayHigh))
    [:p (if (number? v) [:<> "$" (.toFixed v 2)] v)]

    (= id :strike)
    [:p (if next (str v "/" (:strike next)) (str v))]

    (= id :openInterest)
    [:p (if next (str (min v (get next id))) (str v))]

    :else [:p (str v)]))

(defn spread-button
  [{ticker :symbol expiration :expiration
    optType :optType contractSymbol :contractSymbol}
   home?]
  (when (nil?
         (get-in
          @state/app-state
          [:options :ladder [ticker expiration optType]]))
    (.postMessage
     state/worker
     (clj->js {:message "ladder" :data [ticker expiration optType]})))
  (state/toggle-spread home? contractSymbol))

(defn row
  [{:keys [contractSymbol inTheMoney] :as data}]
  (let [activ-cols (get-in @state/app-state [:options :columns])
        activ-spread?
        (contains? (get-in @state/app-state [:options :spreads]) contractSymbol)
        tracked?
        (contains? (get-in @state/app-state [:home :tracked-options]) contractSymbol)
        next (when activ-spread? (ladder-next data))]
    [:div {:class ["row" (when inTheMoney "itm")]
           :key (str "row-" contractSymbol)}
     [:div {:class ["cell" "buttons"]}
      [:button
       {:on-click (fn [] (state/toggle-tracked-options contractSymbol data))
        :class [(when tracked? "tracked")]}
       (if tracked? "forget" "follow")]
      [:button
       {:on-click (fn [] (spread-button data false)) :class [(when activ-spread? "spread")]}
       (if activ-spread? "close" "spread")]]
     (->> columns-w-names
          (keep
           (fn [[col-id _ _]]
             (when (contains? activ-cols col-id)
               [:div {:class ["cell"]
                      :key (str (name col-id) "-" contractSymbol)}
                (let [v (get data col-id)]
                  (draw-cell next col-id v))])))
          doall)]))

(defn render
  []
  (let [activ-cols (get-in @state/app-state [:options :columns])
        [order-col direction] (get-in @state/app-state [:options :order-col])
        data (get-in @state/app-state [:options :data :options])
        sorted-data (if order-col
                      (sort-by
                       order-col
                       (if (= "asc" direction)
                         #(compare %1 %2)
                         #(compare %2 %1))
                       data)
                      data)]
    [:<>
     (opt-sidebar)
     [:div {:class ["options"]}
      [:div {:class ["row" "header"]}
       [:div {:class ["cell"]}]
       (->> columns-w-names
            (keep
             (fn [[col-id descr abbrev]]
               (when (contains? activ-cols col-id)
                 [:div {:class ["cell"]
                        :key (str (name col-id) "-header")}
                  [:p abbrev [:span.descr descr]
                   (cond (and (= order-col col-id)
                              (= direction "asc"))
                         [:span.order-by
                          {:on-click (fn [] (state/toggle-order-by-opts [col-id "desc"]))}
                          "↑"]

                         (and (= order-col col-id)
                              (= direction "desc"))
                         [:span.order-by
                          {:on-click (fn [] (state/toggle-order-by-opts nil))}
                          "↓"]

                         :else
                         [:span.order-by
                          {:on-click (fn [] (state/toggle-order-by-opts [col-id "asc"]))}
                          "-"])]]))))]
      (->> sorted-data
           (map row)
           doall)]
     [:div {:class ["options-footer"]}
      [:button
       {:on-click
        (fn []
          (-> (get-in @state/app-state [:options :data :options])
              count
              (trigger-search false)))}
       "See more options"]]]))

