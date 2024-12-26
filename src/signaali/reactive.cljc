(ns signaali.reactive
  #?(:clj (:import [clojure.lang IAtom IFn ISeq IDeref IMeta]
                   [java.lang Object]))
  #?(:cljs (:require-macros [signaali.reactive :refer [notify-lifecycle-event]]))
  (:require [signaali.mutable.stack :as mut-stack]
            [signaali.mutable.set :as mut-set]))

;; ----------------------------------------------
;; Protocols

(defprotocol ISignalWatcher
  (notify-signal-watcher [this is-for-sure signal-source]))

(defprotocol ISignalSource
  (notify-signal-watchers [this is-for-sure])
  (add-signal-watcher [this signal-watcher])
  (remove-signal-watcher [this signal-watcher]))

(defprotocol IRunObserver
  (notify-deref-on-signal-source [this signal-source])
  (add-clean-up-callback [this callback]))

(defprotocol IReactiveNode
  (-run [this])

  ;; Public API
  (run-after [this higher-priority-node])
  (set-on-dispose-callback [this callback])
  (dispose [this]))

(defprotocol IReactiveNodeInternals
  (-get-propagation-filter-fn [this])
  (-get-has-side-effect [this])
  (-get-higher-priority-nodes [this])
  (-set-value! [this new-value])
  (-unsubscribe-from-all-signal-sources [this])
  (-run-on-clean-up-callbacks [this]))

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

(defn debug-prn
  "To use: (binding [*notify-lifecycle-event* debug-prn] ,,,)"
  [reactive-node event-type]
  (prn (-> reactive-node meta :name) event-type))

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
                                ^boolean dispose-on-zero-signal-watchers
                                ^:mutable status ;; possible values are :unset, :maybe-stale, :stale, :up-to-date
                                ^:mutable last-run-propagated-value
                                ^:mutable maybe-signal-sources
                                ^:mutable signal-sources
                                ^:mutable signal-watchers
                                ^:mutable clean-up-callbacks
                                ^:mutable on-dispose-callback
                                ^:mutable higher-priority-nodes
                                metadata]
                         :clj [^:volatile-mutable value
                               run-fn
                               propagation-filter-fn
                               ^boolean has-side-effect
                               ^boolean dispose-on-zero-signal-watchers
                               ^:volatile-mutable status ;; possible values are :unset, :maybe-stale, :stale, :up-to-date
                               ^:volatile-mutable last-run-propagated-value
                               ^:volatile-mutable maybe-signal-sources
                               ^:volatile-mutable signal-sources
                               ^:volatile-mutable signal-watchers
                               ^:volatile-mutable clean-up-callbacks
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
                 (notify-signal-watchers this true)))
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
                (notify-signal-watchers this true)))
            ,])

  IDeref
  (#?(:cljs -deref, :clj deref) [this]
    (when-some [^IRunObserver current-observer (get-current-observer)]
      (notify-deref-on-signal-source current-observer this))
    (-run this)
    value)

  ISignalWatcher
  (notify-signal-watcher [this is-for-sure signal-source]
    (case status
      (:unset :up-to-date)
      (do
        (when-not is-for-sure
          (mut-set/conj! maybe-signal-sources signal-source))
        (set! status (if is-for-sure :stale :maybe-stale))
        (notify-lifecycle-event this status)
        (when has-side-effect
          (enlist-stale-effectful-node this))
        (notify-signal-watchers this (and is-for-sure (nil? propagation-filter-fn))))

      :maybe-stale
      (do
        (when-not is-for-sure
          (mut-set/conj! maybe-signal-sources signal-source))
        (when is-for-sure
          (set! status :stale)
          (notify-lifecycle-event this status)
          (notify-signal-watchers this true)))

      nil))

  ISignalSource
  (notify-signal-watchers [this is-for-sure]
    (doseq [^ISignalWatcher signal-watcher signal-watchers]
      (notify-signal-watcher signal-watcher is-for-sure this)))

  (add-signal-watcher [this signal-watcher]
    (mut-set/conj! signal-watchers signal-watcher))

  (remove-signal-watcher [this signal-watcher]
    (mut-set/disj! signal-watchers signal-watcher)
    (when (and dispose-on-zero-signal-watchers
               (zero? (mut-set/count signal-watchers)))
      (dispose this)))

  IRunObserver
  (notify-deref-on-signal-source [this signal-source]
    (add-signal-watcher signal-source this)
    (mut-set/conj! signal-sources signal-source))

  (add-clean-up-callback [this callback]
    (when (nil? clean-up-callbacks)
      (set! clean-up-callbacks (mut-stack/make-mutable-object-stack)))
    (mut-stack/conj! clean-up-callbacks callback))

  IReactiveNode
  (-run [this]
    (when (some? run-fn)
      (when (= status :maybe-stale)
        ;; Decide if we should transition to :up-to-date or to :stale
        (loop [maybe-signal-sources (seq maybe-signal-sources)]
          (if (nil? maybe-signal-sources)
            (do
              (set! status :up-to-date)
              (set! last-run-propagated-value false))
            (let [^ReactiveNode maybe-signal-source (first maybe-signal-sources)]
              (-run maybe-signal-source)
              (if (.-last-run-propagated-value maybe-signal-source)
                (set! status :stale)
                (recur (next maybe-signal-sources))))))
        (mut-set/clear! maybe-signal-sources))

      (when (or (= status :unset)
                (= status :stale))
        ;; Clean up the node.
        (-run-on-clean-up-callbacks this)
        (let [old-signal-sources signal-sources]
          (set! signal-sources (mut-set/make-mutable-object-set))

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

          ;; Unsubscribe from the old signal sources which are no longer used.
          (doseq [^ISignalSource old-signal-source old-signal-sources]
            (when-not (mut-set/contains? signal-sources old-signal-source)
              (remove-signal-watcher old-signal-source this)))))))

  (run-after [this higher-priority-node]
    (mut-set/conj! higher-priority-nodes higher-priority-node))

  (set-on-dispose-callback [this callback]
    (set! on-dispose-callback callback))

  (dispose [this]
    (-run-on-clean-up-callbacks this)
    (notify-lifecycle-event this :dispose)
    (-unsubscribe-from-all-signal-sources this)
    (unlist-stale-effectful-node this)
    (set! status :unset)
    (mut-set/clear! higher-priority-nodes)
    (when (some? on-dispose-callback)
      (on-dispose-callback this)))

  IReactiveNodeInternals
  (-get-propagation-filter-fn [this] propagation-filter-fn)
  (-get-has-side-effect [this] has-side-effect)
  (-get-higher-priority-nodes [this] higher-priority-nodes)
  (-set-value! [this new-value] (set! value new-value))

  (-unsubscribe-from-all-signal-sources [this]
    (doseq [^ISignalSource signal-source signal-sources]
      (remove-signal-watcher signal-source this))
    (mut-set/clear! signal-sources))

  (-run-on-clean-up-callbacks [this]
    (notify-lifecycle-event this :clean-up)
    (when (some? clean-up-callbacks)
      ;; TODO: improve this loop
      (doseq [clean-up-callback (reverse clean-up-callbacks)]
        (clean-up-callback))
      (set! clean-up-callbacks nil)))

  IMeta
  (#?(:cljs -meta, :clj meta) [this]
    metadata)

  ,)

(defn make-reactive-node [{:keys [value
                                  run-fn
                                  signal-sources
                                  propagation-filter-fn
                                  has-side-effect
                                  dispose-on-zero-signal-watchers
                                  on-dispose-callback
                                  metadata]
                           :as options}]
  (let [reactive-node (ReactiveNode. value
                                     run-fn
                                     propagation-filter-fn
                                     (boolean has-side-effect)
                                     (boolean dispose-on-zero-signal-watchers)
                                     (if (contains? options :value) :up-to-date :unset) ;; status
                                     false                                              ;; last-run-propagated-value
                                     (mut-set/make-mutable-object-set)                  ;; maybe-signal-sources
                                     (mut-set/make-mutable-object-set signal-sources)   ;; signal-sources
                                     (mut-set/make-mutable-object-set)                  ;; signal-watchers
                                     nil                                                ;; on-clean-up-callback
                                     on-dispose-callback
                                     (mut-set/make-mutable-object-set)                  ;; higher-priority-nodes
                                     metadata
                                     ,)]
    (doseq [^ISignalSource signal-source signal-sources]
      (add-signal-watcher signal-source reactive-node))
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
  (when-some [^IRunObserver current-observer (get-current-observer)]
    (add-clean-up-callback current-observer callback)))

;; ----------------------------------------------
;; Factories for commonly used reactive nodes

(defn create-signal
  ([value]
   (create-signal value nil))
  ([value options]
   (make-reactive-node (into {:value value
                              :dispose-on-zero-signal-watchers true}
                             options))))

(defn create-derived
  ([run-fn]
   (create-derived run-fn nil))
  ([run-fn options]
   (make-reactive-node (into {:run-fn run-fn
                              :dispose-on-zero-signal-watchers true}
                             options))))

(defn- not-identical? [x y]
  (not (identical? x y)))

(defn create-state
  ([value]
   (create-state value nil))
  ([value options]
   (make-reactive-node (into {:value value
                              :propagation-filter-fn not-identical?
                              :dispose-on-zero-signal-watchers true}
                             options))))

(defn create-memo
  ([run-fn]
   (create-memo run-fn nil))
  ([run-fn options]
   (make-reactive-node (into {:run-fn run-fn
                              :propagation-filter-fn not-identical?
                              :dispose-on-zero-signal-watchers true}
                             options))))

(defn create-effect
  ([run-fn]
   (create-effect run-fn nil))
  ([run-fn options]
   (make-reactive-node (into {:run-fn run-fn
                              :has-side-effect true}
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
