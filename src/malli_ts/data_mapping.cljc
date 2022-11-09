(ns malli-ts.data-mapping
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.set            :as set]
   [malli-ts.core          :as-alias mts]
   [malli.core             :as m]
   [malli.util             :as mu])
  #?(:cljs
     (:require
      [cljs-bean.core :as b :refer [bean bean? ->js ->clj]])))

(defn get-schema
  [*schema-registry schema]
  (-> @*schema-registry
      schema
      (mu/get-in [0])))

(defn prop->key
  [js-props->clj-keys prop]
  (get js-props->clj-keys prop))

(defn key->prop
  [clj-keys->js-props key]
  (get clj-keys->js-props key))

(defn prop->key2
  [js-props->clj-keys prop]
  (println :prop->key prop)
  (get js-props->clj-keys prop))

(defn key->prop2
  [clj-keys->js-props key]
  (println :key->prop key)
  (get clj-keys->js-props key))

(def default-get-schema-name :schema)

(defn- clj<->js-key-mapping
  ([*registry schema-name]
   (let [schema (when schema-name
                  (get-schema *registry schema-name))]
     (clj<->js-key-mapping schema)))
  ([schema]
   (let [*result (atom [])]
     (when schema
       (m/walk
        schema
        (fn [schema _path _children _options]
          (when (= :map (m/type schema))
            (let [clj-keys->js-props (->> schema
                                          m/entries
                                          (map #(let [key  (key %)
                                                      s    (-> %
                                                               val
                                                               m/schema)
                                                      prop (-> s
                                                               m/properties
                                                               ::mts/clj<->js
                                                               :prop)
                                                      v    (or prop (csk/->camelCaseString key))]
                                                  (when v
                                                    [key v])))
                                          (remove nil?))]
              (when (seq clj-keys->js-props)
                (swap! *result concat clj-keys->js-props))))
          schema))
       (let [clj-keys->js-props (into {} @*result)
             js-props->clj-keys (set/map-invert clj-keys->js-props)]
         {:clj-keys->js-props clj-keys->js-props
          :js-props->clj-keys js-props->clj-keys})))))

(defn- clj<->js-key-mapping2
  ([*registry schema-name]
   (let [schema (when schema-name
                  (get-schema *registry schema-name))]
     (clj<->js-key-mapping schema)))
  ([schema]
   (let [*result (atom {})]
     (when schema
       (m/walk
        schema
        (fn [schema _path _children _options]
          (when (= :map (m/type schema))
            (let [path                 (->> (cons ::root _path)
                                            reverse
                                            (remove #(or (number? %)
                                                         (= % ::m/in))))
                  #_#_parent-schema    (some #(when (and (not (number? %))
                                                         (not= % ::m/in))
                                                %) path)
                  schema-key           (first path)
                  parent-schema-key    (second path)
                  #_#_parent-schema-nm (when parent-schema-key
                                         (get-in @*result [parent-schema-key schema-key :prop]))
                  clj-keys->js-props   (->> schema
                                            m/entries
                                            (mapcat #(let [k    (key %)
                                                           s    (-> %
                                                                    val
                                                                    m/schema)
                                                           prop (-> s
                                                                    m/properties
                                                                    ::mts/clj<->js
                                                                    :prop)
                                                           p    (or prop (csk/->camelCaseString k))]
                                                       (when p
                                                         [[p {:key    k
                                                              :schema s}]
                                                          [k {:prop p}]])))
                                            (remove nil?)
                                            (into {}))]
              (when (seq clj-keys->js-props)
                (swap! *result merge {schema-key {:parent       parent-schema-key
                                                  :keys<->props clj-keys->js-props}}))))
          schema))
       (->> @*result
            (map (fn [[k {:keys [parent] :as v}]]
                   (let [prop (when parent
                                (get-in @*result [parent :keys<->props k :prop]))]
                     [(or prop k) v])))
            (into {}))
       #_(into {} @*result)
       #_(let [clj-keys->js-props (into {} @*result)
               js-props->clj-keys (set/map-invert clj-keys->js-props)]
           {:clj-keys->js-props clj-keys->js-props
            :js-props->clj-keys js-props->clj-keys})))))

(def ^:private clj<->js-key-mapping-cached (memoize clj<->js-key-mapping))

;; js/Proxy is a strange creature, neither `type`
;; nor `instance?` works for it, probably because
;; a Proxy doesn't have `Proxy.prototype` & has
;; transparent virtualization.
(defprotocol IJsProxy)
(deftype JsProxy []
  IJsProxy)

#?(:cljs
   (defn default-js-get-schema-name [obj]
     (let [sn (csk/->camelCaseString default-get-schema-name)]
       (js/goog.object.get obj sn nil)))

   (defn- map-bean
     [obj clj<->js-map]
     (when clj<->js-map
       (let [{:keys [clj-keys->js-props
                     js-props->clj-keys]}
             clj<->js-map
             fn-key->prop (partial key->prop clj-keys->js-props)
             fn-prop->key (partial prop->key js-props->clj-keys)]
         (bean obj :prop->key fn-prop->key :key->prop fn-key->prop :recursive true))))

   (defn- map-bean2
     [obj clj<->js-map]
     (when clj<->js-map
       (let [fn-key->prop (partial key->prop2 clj<->js-map)
             fn-prop->key (partial prop->key2 clj<->js-map)]
         (println ::map-bean2)
         (bean obj :prop->key fn-prop->key :key->prop fn-key->prop :recursive true))))

   (declare to-clj')

   (defn- into-clj-vec
     [data clj<->js-map]
     (let [{:keys [clj-keys->js-props
                   js-props->clj-keys]}
           clj<->js-map
           fn-key->prop (partial key->prop clj-keys->js-props)
           fn-prop->key (partial prop->key js-props->clj-keys)]
       (#'b/->val data fn-prop->key fn-key->prop nil)))

   (defn- to-clj' [data clj<->js-map]
     (cond
       (array? data)
       (into-clj-vec data clj<->js-map)

       (bean? data)
       data

       (instance? JsProxy data)
       (js/goog.object.get data "unwrap/clj" nil)

       (object? data)
       (map-bean2 data clj<->js-map)

       :else
       data))

   (defn ^:export to-clj
     [data & {:keys [registry get-schema-name]
              :as   schema}]
     (cond
       (m/schema? schema)
       , (->> schema
              clj<->js-key-mapping2
              (to-clj' data))

       (and registry get-schema-name)
       , (let [get-schema-name (if (fn? get-schema-name)
                                 get-schema-name
                                 (fn to-clj-get-schema-nm [o]
                                   (let [schema-nm (if (keyword? get-schema-name)
                                                     (name get-schema-name)
                                                     (str get-schema-name))]
                                     (js/goog.object.get o schema-nm nil))))
               obj             (if (and (array? data)
                                        (>= (js/goog.object.get data "length" 0) 1))
                                 (aget data 0)
                                 data)
               clj<->js-map    (clj<->js-key-mapping-cached registry
                                                            (get-schema-name obj))]
           (to-clj' data clj<->js-map))

       :else
       , (to-clj data :registry registry :get-schema-name default-js-get-schema-name)))

   (comment
     (let [order-items-schema [:vector
                               [:map
                                [:order/item {:optional      true
                                              ::mts/clj<->js {:prop    "orderItem"
                                                              :fn-to   nil
                                                              :fn-from nil}}
                                 [:map
                                  [:order-item/id uuid?]
                                  [:order-item/type {::mts/clj<->js {:prop "type"}}
                                   string?]
                                  [:order-item/price
                                   [:map
                                    [:order-item/currency [:enum :EUR :USD :ZAR]]
                                    [:order-item/amount number?]]]
                                  [:order-item/test-dummy {::mts/clj<->js {:prop "TESTDummyXYZ"}}
                                   string?]
                                  [:order-item/related-items
                                   [:vector [:map
                                             [:related-item/how-is-related string?
                                              :related-item/order-item-id uuid?]]]]]]
                                [:order/credit {:optional true}
                                 [:map
                                  [:order.credit/valid-for-timespan [:enum :milliseconds :seconds :minutes :hours :days]]
                                  [:order.credit/amount number?]]]]]
           order-schema       [:map
                               [:model-type [:= ::order]]
                               [:order/id {::mts/clj<->js {:prop "orderId"}}
                                string?]
                               [:order/type {::mts/clj<->js {:prop "orderType"}}
                                [:or keyword? string?]]
                               #_[:order/order-items {:optional      true
                                                      ::mts/clj<->js {:prop "orderItems"}}
                                  order-items-schema]
                               [:order/total-amount {:optional      true
                                                     ::mts/clj<->js {:prop "totalAmount"}}
                                number?]
                               [:order/user {::mts/clj<->js {:prop "user"}
                                             :optional      true}
                                [:map
                                 [:user/id {::mts/clj<->js {:prop "userId"}} string?]
                                 [:user/name {:optional true} string?]]]]
           s                  (-> [:schema {::mts/t-name  "Order"
                                            ::mts/declare true}
                                   order-schema]
                                  m/schema)
           clj-map            (to-clj #js {"modelType" ::order
                                           "orderId"   "2763yughjbh333"
                                           "orderType" "Sport Gear"
                                           "user"      #js {"userId" "u678672"
                                                            "name"   "Kosie"}} s)]
       #_(cljs.pprint/pprint (:order/user clj-map))
       (map prn clj-map)
    #_(cljs.pprint/pprint (m/form (m/schema s)))
    #_(cljs.pprint/pprint (clj<->js-key-mapping2 (m/schema s))))

  )

   (declare map-proxy)

   (defn- array-push
     ([res] res)
     ([res x] (doto res (.push x))))

   (defn into-js-array
     [xform from]
     (transduce xform array-push (array) from))

   (defn- to-js'
     [data js-props->clj-keys]
     (when js-props->clj-keys
       (cond
         (or (sequential? data)
             (set? data))
         (into-js-array (map #(to-js' % js-props->clj-keys)) data)

         (associative? data)
         (map-proxy data js-props->clj-keys)

         :else
         data)))

   (defn ^:export to-js
     [data & {:keys [registry get-schema-name]
              :as   schema}]
     (cond
       (m/schema? schema)
       , (let [{:keys [js-props->clj-keys]} (clj<->js-key-mapping-cached
                                             schema)]
           (to-js' data js-props->clj-keys))

       (and registry get-schema-name)
       , (let [is-coll   (or (sequential? data)
                             (set? data))
               schema-nm (if is-coll
                           (-> data
                               first
                               get-schema-name)
                           (get-schema-name data))
               {:keys [js-props->clj-keys]}
               (clj<->js-key-mapping-cached registry schema-nm)]
           (to-js' data js-props->clj-keys))

       :else
       , (to-js data :registry registry :get-schema-name default-get-schema-name)))

   (defn- map-proxy-get
     [js-props->clj-keys target key]
     (case key
       "unwrap/clj" target

       (-> js-props->clj-keys
           (get key)
           (as-> k (get target k))
           (to-js' js-props->clj-keys))))

   (defn- map-proxy
     [data js-props->clj-keys]
     (if (instance? JsProxy data)
       data
       (js/Proxy. data
                  #js
                  {:get            (partial map-proxy-get js-props->clj-keys)
                   :getPrototypeOf (fn [k]
                                     (.-prototype JsProxy))})))
   )
