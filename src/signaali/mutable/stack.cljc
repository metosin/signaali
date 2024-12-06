(ns signaali.mutable.stack
  #?(:clj (:import [java.util ArrayList]))
  (:refer-clojure :exclude [conj! pop! peek count]))

(defn conj! [stack item]
  #?(:cljs (.push ^array stack item)
     :clj (.add ^ArrayList stack item)))

(defn pop! [stack]
  #?(:cljs (.pop ^array stack)
     :clj (.remove ^ArrayList stack ^int (dec (.size stack)))))

(defn peek [stack]
  #?(:cljs (aget ^array stack (dec (.-length ^array stack)))
     :clj (let [s (.size ^ArrayList stack)]
            (when (pos-int? s)
              (.get ^ArrayList stack (dec s))))))

(defn count [stack]
  #?(:cljs (.-length ^array stack)
     :clj (.size ^ArrayList stack)))

(defn make-mutable-object-stack []
  #?(:cljs #js []
     :clj (ArrayList.)))
