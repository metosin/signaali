(ns signaali.mutable.set
  #?(:clj (:import [java.util Collection HashSet LinkedHashSet]))
  (:refer-clojure :exclude [conj! disj! contains? count]))

(defn conj! [set item]
  #?(:cljs (.add ^set set item)
     :clj (.add ^HashSet set item)))

(defn disj! [set item]
  #?(:cljs (.delete ^set set item)
     :clj (.remove ^HashSet set item)))

(defn clear! [set]
  #?(:cljs (.clear ^set set)
     :clj (.clear ^HashSet set)))

(defn contains? [set item]
  #?(:cljs (.has ^set set item)
     :clj (.contains ^HashSet set item)))

(defn count [set]
  #?(:cljs (.-size ^set set)
     :clj (.size ^HashSet set)))

(defn make-mutable-object-set
  ([]
   #?(:cljs (js/Set.)
      :clj (HashSet.)))
  ([coll]
   #?(:cljs (js/Set. coll)
      :clj (HashSet. ^Collection (or coll [])))))

(defn make-mutable-object-linked-set
  ([]
   #?(:cljs (js/Set.)
      :clj (LinkedHashSet.)))
  ([coll]
   #?(:cljs (js/Set. coll)
      :clj (LinkedHashSet. ^Collection (or coll [])))))
