(ns malli-ts.data-mapping.to-clj
  (:require
   [malli-ts.core               :as-alias mts]
   [malli-ts.data-mapping       :as mts-dm]
   [malli.core :as m]
   [cljs-bean.core :as b :refer [bean?]]))

(defn unwrap [v]
  (when v
    (or (unchecked-get v "unwrap/clj")
        (when (bean? v) v))))

(deftype BeanContext [js<->clj-mapping mapping ^:mutable sub-cache]
  b/BeanContext
  (keywords? [_] true)
  (key->prop [_ key']
    ; When prop is nil, the schema is for a :map-of and we return the given key as is.
    ; If there is a schema, we return the prop from the schema
    ; or if there is no schema, we return the name of the given key'
    (let [s (get mapping key')] (set! sub-cache s) (if-let [p (some-> s .-prop)] p (name key'))))
  (prop->key [_ prop]
    ; When key is nil, the schema is for a :map-of and we return the given prop as is.
    ; If there is a schema, we return the key from the schema
    ; or if there is no schema, we keywordize the given prop
    (let [s (get mapping prop)] (set! sub-cache s) (if s (or (.-key s) prop) (keyword prop))))
  (transform [_ v prop key' nth']
    (if-some [v (unwrap v)] v
    ;else
      (if-some [bean' (cond (object? v) true (array? v) false)]
        (let [sub-mapping
              (if (or nth' (not sub-cache))
                mapping
              ;else
                (let [s (.-schema sub-cache)]
                  (if-let [ref (::mts-dm/ref s)] (js<->clj-mapping ref) s)))
              bean-context
              (BeanContext. js<->clj-mapping sub-mapping nil)]
          (if bean'
            (b/Bean. nil v bean-context true nil nil nil)
            (b/ArrayVector. nil bean-context v nil)))
      ;else
        v))))

(defn ^:export to-clj
  ([v js<->clj-mapping]
   (if-some [v (unwrap v)] v 
   ;else
     (if-some [bean' (cond (object? v) true (array? v) false)]
       (let [root
             (::mts-dm/root js<->clj-mapping)
             root
             (if-let [ref (::mts-dm/ref root)] (js<->clj-mapping ref) #_else root)
             bean-context
             (BeanContext. js<->clj-mapping root nil)]
         (if bean'
           (b/Bean. nil v bean-context true nil nil nil)
           (b/ArrayVector. nil bean-context v nil)))
     ;else
       v)))

  ([x registry schema & [mapping-options]]
   (let [s (m/schema [:schema {:registry registry}
                      schema])]
     (to-clj x (mts-dm/clj<->js-mapping s mapping-options)))))

(comment

  (let [order-items-schema [:vector [:map
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
                                        [:ref ::order-items]
                                        #_[:vector [:map
                                                    [:related-item/how-is-related string?
                                                     :related-item/order-item-id uuid?]]]]]]
                                     [:order/credit {:optional true}
                                      [:map
                                       [:order.credit/valid-for-timespan [:enum :milliseconds :seconds :minutes :hours :days]]
                                       [:order-credit/amount number?]]]]]
        order-schema       [:map
                            [:model-type [:= ::order]]
                            [:order/id {::mts/clj<->js {:prop "orderId"}}
                             string?]
                            [:order/type {::mts/clj<->js {:prop "orderType"}}
                             [:or keyword? string?]]
                            #_[:order/items {:optional      true
                                             ::mts/clj<->js {:prop "orderItems"}}
                               [:ref ::order-items]]
                            [:order/items {:optional      true
                                           ::mts/clj<->js {:prop "orderItems"}} ::order-items]
                            [:order/total-amount {:optional      true
                                                  ::mts/clj<->js {:prop "totalAmount"}}
                             number?]
                            [:order/user {::mts/clj<->js {:prop "user"}
                                          :optional      true}
                             [:map
                              [:user/id {::mts/clj<->js {:prop "userId"}} string?]
                              [:user/name {:optional true} string?]]]]
        r                  {::order-items order-items-schema}
        s                  (m/schema [:schema {:registry {::order-items order-items-schema}}
                                      order-schema])
        clj-map            (to-clj #js {"modelType"  ::order
                                        "orderId"    "2763yughjbh333"
                                        "orderType"  "Sport Gear"
                                        "user"       #js {"userId" "u678672"
                                                          "name"   "Kosie"}
                                        "orderItems" #js [#js {:orderItem
                                                               #js {:type         "some-test-order-item-type-1"
                                                                    :price        #js {:currency :EUR
                                                                                       :amount   22.3}
                                                                    :TESTDummyXYZ "TD-A1"
                                                                    :relatedItems #js [#js {:credit
                                                                                            #js {:amount 676.30}}]}}
                                                          #js {:orderItem
                                                               #js {:type         "some-test-order-item-type-2"
                                                                    :price        #js {:currency :ZAR
                                                                                       :amount   898}
                                                                    :TESTDummyXYZ "TD-B2"}}]} s)]
    #_{:js->clj-mapping (mts-dm/clj<->js-mapping s :prop)
       :clj->js-mapping (mts-dm/clj<->js-mapping s :key)}
    #_(-> clj-map :order/user :user/id)
    (get-in clj-map [:order/items 0 :order/item :order-item/related-items 0
                     :order/credit :order-credit/amount])
    #_(get-in clj-map [:order/items 1 :order/item :order-item/price :order-item/currency])))
