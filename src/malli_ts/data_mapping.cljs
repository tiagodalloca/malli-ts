(ns malli-ts.data-mapping
  (:require
   [camel-snake-kebab.core :as csk]
   [malli-ts.core          :as-alias mts]
   [malli.core             :as m]))

(def complex-types #{'seqable?
                     'indexed?
                     'map?
                     'vector?
                     'list?
                     'seq?
                     'set?
                     'empty?
                     'sequential?
                     'coll?
                     'associative?
                     ::m/val})

(defn primitive? [x]
  (nil? (complex-types x)))

(defrecord Mapping [key prop schema])

(defn- walk-schema->clj<>js-mapping
  ([schema {::keys [prop-name-fn] :as options}]
   (m/walk
    schema
    (fn [schema' path children {::keys [*definitions] :as opts}]
      (let [s-type (m/type schema')]
        (case s-type
          :ref
          , {::ref (m/-ref schema')}

          ::m/schema
          , (let [result (walk-schema->clj<>js-mapping (m/deref schema') opts)]
              (if-let [ref (m/-ref schema')]
                (do
                  (swap! *definitions assoc! ref result)
                  {::ref ref})
                result))

          (:schema :set :sequential :vector)
          , (first children)

          (:enum :or)
          , (m/form schema')

          :map-of
          , (second children)

          :map
          , (->> children
                 (reduce
                  (fn [x [k opts s]]
                    (let [p (-> opts ::mts/clj<->js :prop (or (prop-name-fn k)))
                          m (Mapping. k p (first s))]
                      (assoc! x, k m, p m)))
                  (transient {}))
                 (persistent!))

          ; else
          (cond
            (empty? path)
            , (first children)

            (primitive? s-type)
            , s-type

            :else
            , children))))
    options)))

(defn- -clj<>js-mapping
  ([schema]
   (-clj<>js-mapping schema {}))
  ([schema {:keys [default-to-camel-case] :as options}]
   (let [*defs (or (::*definitions options)
                   (atom (transient {})))
         options
         (merge options
                {::*definitions       *defs
                 ::prop-name-fn       (if default-to-camel-case csk/->camelCaseString #_else name)
                 ::m/walk-schema-refs true
                 ::m/walk-refs        true
                 ::m/walk-entry-vals  true})
         root  (walk-schema->clj<>js-mapping schema options)]
     (-> @*defs
         (assoc! ::root root)
         (persistent!)))))

(def clj<->js-mapping (memoize -clj<>js-mapping))
