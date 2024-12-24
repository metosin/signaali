(ns signaali.reactive
  #?(:clj (:import [clojure.lang IAtom IFn ISeq IDeref IMeta]
                   [java.lang Object]))
  #?(:cljs (:require-macros [signaali.reactive :refer [notify-lifecycle-event]]))
  (:require [signaali.mutable.stack :as mut-stack]
            [signaali.mutable.set :as mut-set]))

;; ----------------------------------------------
;; Protocols

(defprotocol IReactiveNode
  (-add-source [this source])
  (-remove-source [this source])
  (-add-subscriber [this subscriber])
  (-remove-subscriber [this subscriber])
  (-notify-as-a-subscriber [this is-for-sure source])
  (-run [this])
  (-set-on-clean-up-callback [this callback])

  ;; Public API
  (run-after [this higher-priority-node])
  (set-on-dispose-callback [this callback])
  (dispose [this]))

(defprotocol IReactiveNodeInternals
  (-get-propagation-filter-fn [this])
  (-get-has-side-effect [this])
  (-get-higher-priority-nodes [this])
  (-set-value! [this new-value])
  (-notify-subscribers [this is-for-sure])
  (-unsubscribe-from-all-sources [this])
  (-run-on-clean-up-callback [this]))

;; ----------------------------------------------
;; Observer stack

(defonce ^:private observer-stack
  (mut-stack/make-mutable-object-stack))

(defn with-observer [observer body-fn]
  (try
    (mut-stack/conj! observer-stack observer)
    (body-fn)
    (finally
      (mut-stack/pop! observer-stack))))

(defn get-current-observer []
  (mut-stack/peek observer-stack))

;; ----------------------------------------------
;; Effects to re-run

(declare enlist-stale-effectful-node)
(declare unlist-stale-effectful-node)
(declare re-run-stale-effectful-nodes)

;; ----------------------------------------------
;; Lifecycle event global notification

(def ^:dynamic *notify-lifecycle-event* nil)

(defmacro notify-lifecycle-event [reactive-node event-type]
  (when (:ns &env)                            ;; When compiling CLJS code
    `(when ^boolean ~'js/goog.DEBUG           ;; When building in debug mode
       (when (some? *notify-lifecycle-event*) ;; When there is a callback registered
         (*notify-lifecycle-event* ~reactive-node ~event-type)))))

;; ----------------------------------------------

(deftype ReactiveNode #?(:cljs [^:mutable value
                                run-fn
                                propagation-filter-fn
                                ^boolean has-side-effect
                                ^boolean dispose-on-zero-subscribers
                                ^:mutable status ;; possible values are :idle, :maybe-stale, :stale, :up-to-date
                                ^:mutable last-run-propagated-value
                                ^:mutable maybe-sources
                                ^:mutable sources
                                ^:mutable subscribers
                                ^:mutable on-clean-up-callback
                                ^:mutable on-dispose-callback
                                ^:mutable higher-priority-nodes
                                metadata]
                         :clj [^:volatile-mutable value
                               run-fn
                               propagation-filter-fn
                               ^boolean has-side-effect
                               ^boolean dispose-on-zero-subscribers
                               ^:volatile-mutable status ;; possible values are :idle, :maybe-stale, :stale, :up-to-date
                               ^:volatile-mutable last-run-propagated-value
                               ^:volatile-mutable maybe-sources
                               ^:volatile-mutable sources
                               ^:volatile-mutable subscribers
                               ^:volatile-mutable on-clean-up-callback
                               ^:volatile-mutable on-dispose-callback
                               ^:volatile-mutable higher-priority-nodes
                               metadata])
  #?@(:cljs [ISwap
             (-swap! [this f]
               (-reset! this (f value)))
             (-swap! [this f a]
               (-reset! this (f value a)))
             (-swap! [this f a b]
               (-reset! this (f value a b)))
             (-swap! [this f a b xs]
               (-reset! this (apply f value a b xs)))

             IReset
             (-reset! [this new-value]
               (when (or (nil? propagation-filter-fn)
                         (propagation-filter-fn value new-value))
                 (set! value new-value)
                 (-notify-subscribers this true)))
             ,]

      :clj [IAtom
            (swap [this ^IFn f]
              (.reset this (f value)))
            (swap [this ^IFn f ^Object a]
              (.reset this (f value a)))
            (swap [this ^IFn f ^Object a ^Object b]
              (.reset this (f value a b)))
            (swap [this ^IFn f ^Object a ^Object b ^ISeq xs]
              (.reset this (apply f value a b xs)))
            (^boolean compareAndSet [this ^Object old-value, ^Object new-value]
              (if (identical? value old-value)
                (do (.reset this new-value)
                    true)
                false))
            (reset [this ^Object new-value]
              (when (or (nil? propagation-filter-fn)
                        (propagation-filter-fn value new-value))
                (-set-value! this new-value)
                (-notify-subscribers this true)))
            ,])

  IDeref
  (#?(:cljs -deref, :clj deref) [this]
    (when-some [^ReactiveNode current-observer (get-current-observer)]
      (-add-subscriber this current-observer)
      (-add-source current-observer this))
    (-run this)
    value)

  IReactiveNode
  (-add-source [this source]
    (mut-set/conj! sources source))

  (-remove-source [this source]
    (mut-set/disj! sources source))

  (-add-subscriber [this subscriber]
    (mut-set/conj! subscribers subscriber))

  (-remove-subscriber [this subscriber]
    (mut-set/disj! subscribers subscriber)
    (when (and dispose-on-zero-subscribers
               (zero? (mut-set/count subscribers)))
      (dispose this)))

  (-notify-as-a-subscriber [this is-for-sure source]
    (case status
      :up-to-date
      (do
        (when-not is-for-sure
          (mut-set/conj! maybe-sources source))
        (set! status (if is-for-sure :stale :maybe-stale))
        (notify-lifecycle-event this status)
        (when has-side-effect
          (enlist-stale-effectful-node this))
        (-notify-subscribers this (and is-for-sure (nil? propagation-filter-fn))))

      :maybe-stale
      (do
        (when-not is-for-sure
          (mut-set/conj! maybe-sources source))
        (when is-for-sure
          (set! status :stale)
          (notify-lifecycle-event this status)
          (-notify-subscribers this true)))

      nil))

  (-run [this]
    (when (some? run-fn)
      (when (= status :maybe-stale)
        ;; Decide if we should transition to :up-to-date or to :stale
        (loop [maybe-sources (seq maybe-sources)]
          (if (nil? maybe-sources)
            (do
              (set! status :up-to-date)
              (set! last-run-propagated-value false))
            (let [^ReactiveNode maybe-source (first maybe-sources)]
              (-run maybe-source)
              (if (.-last-run-propagated-value maybe-source)
                (set! status :stale)
                (recur (next maybe-sources))))))
        (mut-set/clear! maybe-sources))

      (when (or (= status :idle)
                (= status :stale))
        ;; Clean up the node.
        (-run-on-clean-up-callback this)
        (let [old-sources sources]
          (set! sources (mut-set/make-mutable-object-set))

          ;; Run the node.
          (notify-lifecycle-event this :run)
          (let [new-value (with-observer this run-fn)]
            (if (or (nil? propagation-filter-fn)
                    (propagation-filter-fn value new-value))
              (do
                (set! value new-value)
                (set! last-run-propagated-value true))
              (set! last-run-propagated-value false)))
          (set! status :up-to-date)
          (notify-lifecycle-event this status)

          ;; Unsubscribe from the old sources which are no longer used.
          (doseq [^ReactiveNode old-source old-sources]
            (when-not (mut-set/contains? sources old-source)
              (-remove-subscriber old-source this)))))))

  (-set-on-clean-up-callback [this callback]
    (set! on-clean-up-callback callback))

  (run-after [this higher-priority-node]
    (mut-set/conj! higher-priority-nodes higher-priority-node))

  (set-on-dispose-callback [this callback]
    (set! on-dispose-callback callback))

  (dispose [this]
    (-run-on-clean-up-callback this)
    (notify-lifecycle-event this :dispose)
    (-unsubscribe-from-all-sources this)
    (unlist-stale-effectful-node this)
    (set! status :idle)
    (mut-set/clear! higher-priority-nodes)
    (when (some? on-dispose-callback)
      (on-dispose-callback this)))

  IReactiveNodeInternals
  (-get-propagation-filter-fn [this] propagation-filter-fn)
  (-get-has-side-effect [this] has-side-effect)
  (-get-higher-priority-nodes [this] higher-priority-nodes)
  (-set-value! [this new-value] (set! value new-value))

  (-notify-subscribers [this is-for-sure]
    (doseq [^ReactiveNode subscriber subscribers]
      (-notify-as-a-subscriber subscriber is-for-sure this)))

  (-unsubscribe-from-all-sources [this]
    (doseq [^ReactiveNode source sources]
      (-remove-subscriber source this))
    (mut-set/clear! sources))

  (-run-on-clean-up-callback [this]
    (when (some? on-clean-up-callback)
      (notify-lifecycle-event this :clean-up)
      (on-clean-up-callback)
      (set! on-clean-up-callback nil)))

  IMeta
  (#?(:cljs -meta, :clj meta) [this]
    metadata)

  ,)

(defn make-reactive-node [{:keys [value
                                  run-fn
                                  propagation-filter-fn
                                  has-side-effect
                                  dispose-on-zero-subscribers
                                  on-dispose-callback
                                  metadata]}]
  (let [reactive-node (ReactiveNode. value
                                     run-fn
                                     propagation-filter-fn
                                     (boolean has-side-effect)
                                     (boolean dispose-on-zero-subscribers)
                                     :idle                             ;; status
                                     false                             ;; last-run-propagated-value
                                     (mut-set/make-mutable-object-set) ;; maybe-sources
                                     (mut-set/make-mutable-object-set) ;; sources
                                     (mut-set/make-mutable-object-set) ;; subscribers
                                     nil                               ;; on-clean-up-callback
                                     on-dispose-callback
                                     (mut-set/make-mutable-object-set) ;; higher-priority-nodes
                                     metadata
                                     ,)]
    (notify-lifecycle-event reactive-node :create)
    reactive-node))

;; ----------------------------------------------
;; Effectful nodes to re-run

(def ^:private stale-effectful-nodes (mut-set/make-mutable-object-set))

(defn enlist-stale-effectful-node [^ReactiveNode node]
  (mut-set/conj! stale-effectful-nodes node))

(defn unlist-stale-effectful-node [^ReactiveNode node]
  (mut-set/disj! stale-effectful-nodes node))

(defn re-run-stale-effectful-nodes []
  (let [run-nodes-in-order (fn run-this-first [^ReactiveNode node]
                             (when (mut-set/contains? stale-effectful-nodes node)
                               (run! run-this-first (-get-higher-priority-nodes node)))
                             ;; We need to check again, in case this node was disposed by a higher-priority node.
                             (when (mut-set/contains? stale-effectful-nodes node)
                               (-run node)
                               (mut-set/disj! stale-effectful-nodes node)))]
    (run! run-nodes-in-order #?(:cljs stale-effectful-nodes
                                ;; TODO: use Iterator.remove() instead of cloning.
                                :clj (.clone stale-effectful-nodes)))))

;; ----------------------------------------------
;; Lifecycle callbacks registration

(defn on-clean-up [callback]
  (when-some [^ReactiveNode current-observer (get-current-observer)]
    (-set-on-clean-up-callback current-observer callback)))

;; ----------------------------------------------
;; Factories for commonly used reactive nodes

(defn create-signal
  ([value]
   (create-signal value nil))
  ([value options]
   (make-reactive-node (into {:value value
                              :dispose-on-zero-subscribers true}
                             options))))

(defn create-derived
  ([run-fn]
   (create-derived run-fn nil))
  ([run-fn options]
   (make-reactive-node (into {:run-fn run-fn
                              :dispose-on-zero-subscribers true}
                             options))))

(defn- not-identical? [x y]
  (not (identical? x y)))

(defn create-state
  ([value]
   (create-state value nil))
  ([value options]
   (make-reactive-node (into {:value value
                              :propagation-filter-fn not-identical?
                              :dispose-on-zero-subscribers true}
                             options))))

(defn create-memo
  ([run-fn]
   (create-memo run-fn nil))
  ([run-fn options]
   (make-reactive-node (into {:run-fn run-fn
                              :propagation-filter-fn not-identical?
                              :dispose-on-zero-subscribers true}
                             options))))

(defn create-effect
  ([run-fn]
   (create-effect run-fn nil))
  ([run-fn options]
   (make-reactive-node (into {:run-fn run-fn
                              :has-side-effect true
                              :dispose-on-zero-subscribers false}
                             options))))

;; ----------------------------------------------
;; Specialized reactive nodes

#_
(defn scope-effect
  "This effects manages a static collection of effect's lifecycle so that they are
   first-run and disposed when this effect is run and cleaned up."
  ([owned-effects]
   (scope-effect owned-effects nil))
  ([owned-effects options]
   (when-some [owned-effects (seq (remove nil? owned-effects))]
     (let [scope (create-effect (fn []
                                  (run! -run owned-effects)
                                  (on-clean-up (fn []
                                                 (run! dispose owned-effects))))
                                options)]
       (doseq [owned-effect owned-effects]
         (run-after owned-effect scope))
       scope))))
