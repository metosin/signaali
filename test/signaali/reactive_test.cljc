(ns signaali.reactive-test
  #?(:clj (:import [clojure.lang IDeref]))
  (:require [clojure.test :refer [deftest testing is are]]
            [signaali.reactive :as sr]))

(deftest reactive-node-test
  (testing "a reactive node is an instance of IDeref"
    (let [x (sr/create-signal 1)]
      #?(:cljs (is (satisfies? IDeref x))
         :clj (is (instance? IDeref x)))))

  (testing "a reactive node behaves similarly to an atom."
    (let [x (sr/create-signal 1)]
      (is (= 1 @x))

      (let [a (reset! x 5)]
        (is (= a 5)))
      (is (= 5 @x))

      (let [a (swap! x inc)]
        (is (= a 6)))
      (is (= 6 @x)))))

(deftest signal-test
  (testing "setting a signal to the same value makes its subscriptions dirty"
    (let [log (atom [])
          x (sr/create-signal 1)
          y (sr/create-derived (fn []
                                 (let [x-value @x]
                                   (swap! log conj [:y x-value])
                                   (* x-value 100))))]

      ;; Log is empty when no value is pulled out of y or z.
      (is (= 1 @x))
      (is (= [] @log))

      ;; y runs once
      (reset! x 1)
      (is (= 100 @y))
      (is (= [[:y 1]] @log))

      ;; setting x to the same value makes y run once more when evaluated.
      (reset! x 1)
      (is (= 100 @y))
      (is (= [[:y 1] [:y 1]] @log))

      ,)))

(deftest derived-test
  (testing "which nodes run and when"
    (let [log (atom [])
          x (sr/create-signal 1)
          y (sr/create-derived (fn []
                                 (let [x-value @x]
                                   (swap! log conj [:y x-value])
                                   (* x-value 2))))
          z (sr/create-derived (fn []
                                 (let [x-value @x
                                       y-value @y]
                                   (swap! log conj [:z x-value y-value])
                                   (+ (* x-value 100) y-value))))
          no-run (sr/create-derived (fn []
                                      (let [z-value @z]
                                        (swap! log conj [:no-run z-value])
                                        (str z-value))))]

      ;; Log is empty when no value is pulled out of y or z.
      (is (= 1 @x))
      (is (= [] @log))

      ;; When y is evaluated, only y is run.
      (is (= 2 @y))
      (is (= [[:y 1]] @log))

      ;; When z is evaluated, only z is run.
      (is (= 102 @z))
      (is (= [[:y 1] [:z 1 2]] @log))

      ;; Now let's set a new value in the signal x.
      (reset! log [])
      (swap! x inc)
      (is (= 2 @x))
      (is (= [] @log))

      ;; When y is evaluated, only y is run.
      (is (= 4 @y))
      (is (= [[:y 2]] @log))

      ;; When z is evaluated, only z is run.
      (is (= 204 @z))
      (is (= [[:y 2] [:z 2 4]] @log))

      ;; Now let's set a new value in the signal x.
      (reset! log [])
      (swap! x inc)
      (is (= 3 @x))
      (is (= [] @log))

      ;;;; Here we don't evaluate y.
      ;;(is (= 4 @y))

      ;; When z is evaluated, y and z both run.
      (is (= 306 @z))
      (is (= [[:y 3] [:z 3 6]] @log))

      ;; no-run never runs.
      ,)))

(deftest state-test
  (testing "setting a state to the same value does not make its subscriptions dirty"
    (let [log (atom [])
          x (sr/create-state 1)
          y (sr/create-derived (fn []
                                 (let [x-value @x]
                                   (swap! log conj [:y x-value])
                                   (* x-value 100))))]

      ;; Log is empty when no value is pulled out of y or z.
      (is (= 1 @x))
      (is (= [] @log))

      ;; y runs once
      (reset! x 1)
      (is (= 100 @y))
      (is (= [[:y 1]] @log))

      ;; setting x to the same value does not make y run once more when evaluated.
      (reset! x 1)
      (is (= 100 @y))
      (is (= [[:y 1]] @log))

      ,)))

(deftest memo-test
  (testing "memo doesn't not mark its subscriptions as dirty when it's result is the same."
    (let [log (atom [])
          x (sr/create-signal 1)
          y (sr/create-memo (fn []
                              (let [x-value @x]
                                (swap! log conj [:y x-value])
                                (* x-value 2))))
          z (sr/create-derived (fn []
                                 (let [y-value @y]
                                   (swap! log conj [:z y-value])
                                   (* y-value 100))))]

      ;; Log is empty when no value is pulled out of y or z.
      (is (= 1 @x))
      (is (= [] @log))

      ;; Normal execution order
      (is (= 200 @z))
      (is (= [[:y 1] [:z 2]] @log))

      ;; Set x to the same value 1
      (reset! log [])
      (reset! x 1)
      (is (= 200 @z))
      (is (= [[:y 1]] @log)) ;; z was not marked as dirty, so it did not re-run

      ,)))

(deftest effect-test
  (testing "the effects are re-run when a dependency changes."
    (let [log (atom #{})
          x (sr/create-signal 1)
          y (sr/create-effect (fn []
                                (let [x-value @x]
                                  (swap! log conj [:y x-value]))))
          z (sr/create-effect (fn []
                                (let [x-value @x]
                                  (swap! log conj [:z x-value]))))]
      ;; No effect is run at the beginning
      (sr/re-run-stale-effectful-nodes)
      (is (= 1 @x))
      (is (= #{} @log))

      ;; Need to run the effects once, so that they know about their dependencies.
      @y
      @z
      (is (= #{[:y 1] [:z 1]} @log))

      ;; The effects are run when we reset the signal.
      (reset! log #{})
      (reset! x 1)
      (sr/re-run-stale-effectful-nodes)
      (is (= #{[:y 1] [:z 1]} @log))

      ,))

  (testing "the ordering between the effects"
    (let [log (atom [])
          x (sr/create-signal 1)
          y (sr/create-effect (fn []
                                (let [x-value @x]
                                  (swap! log conj [:y x-value]))))
          z (sr/create-effect (fn []
                                (let [x-value @x]
                                  (swap! log conj [:z x-value]))))]

      (sr/run-after y z) ;; When both are scheduled for re-run, y runs after z.

      ;; Need to run the effects once, so that they know about their dependencies.
      ;; y runs after z, but here z is not scheduled for re-run yet.
      @y
      (is (= [[:y 1]] @log))

      ;; Now z is scheduled for re-run, but y is not.
      @z
      (is (= [[:y 1] [:z 1]] @log))

      ;; The 2 effects are run altogether when we reset the signal, in the order requested.
      (reset! log [])
      (reset! x 2)
      (sr/re-run-stale-effectful-nodes)
      (is (= [[:z 2] [:y 2]] @log))

      ,))

  (testing "the cleanup of the effect"
    (let [log (atom [])
          x (sr/create-signal 1)
          y (sr/create-effect (fn []
                                (let [x-value @x]
                                  (swap! log conj [:y+ x-value])
                                  (sr/on-clean-up (fn []
                                                    (swap! log conj [:y- x-value]))))))
          z (sr/create-effect (fn []
                                (let [x-value @x]
                                  (swap! log conj [:z+ x-value])
                                  (sr/on-clean-up (fn []
                                                    (swap! log conj [:z- x-value]))))))]

      (sr/run-after y z) ;; When both are scheduled for re-run, y runs after z.

      ;; Need to run the effects once, so that they know about their dependencies.
      @y
      @z
      (is (= [[:y+ 1] [:z+ 1]] @log))

      ;; The 2 effects are run altogether when we reset the signal,
      ;; in the order requested, but their on-clean-up callback is called before they are re-run.
      (reset! log [])
      (reset! x 2)
      (sr/re-run-stale-effectful-nodes)
      (is (= [[:z- 1] [:z+ 2] [:y- 1] [:y+ 2]] @log))

      ,))

  (testing "an effect doesn't re-run after a memo is updated with the same value"
    (let [log (atom [])
          a (sr/create-signal 2)
          b (sr/create-signal 2)
          c (sr/create-memo (fn []
                              (let [sum (+ @a @b)]
                                (swap! log conj [:c sum])
                                sum)))
          z (sr/create-effect (fn []
                                (let [c-value @c]
                                  (swap! log conj [:z+ c-value])
                                  (sr/on-clean-up (fn []
                                                    (swap! log conj [:z- c-value]))))))]
      ;; First run
      @z
      (is (= [[:c 4] [:z+ 4]] @log))

      ;; Then, when the memo node is updated with the same value ...
      (reset! log [])
      (swap! a dec)
      (swap! b inc)
      (sr/re-run-stale-effectful-nodes)
      ;; ... the effect is not re-run
      (is (= [[:c 4]] @log))

      ,))

  (testing "manually adding signal sources when creating a reactive node"
    (let [log (atom [])
          a (sr/create-signal 2)
          b (sr/create-memo (fn []
                              (let [b-value (+ @a @a)]
                                (swap! log conj [:b b-value])
                                b-value))
                            {:signal-sources [a]})
          c (sr/create-effect (fn []
                                (let [c-value @b]
                                  (swap! log conj [:c+ c-value])
                                  (sr/on-clean-up (fn []
                                                    (swap! log conj [:c- c-value])))
                                  c-value))
                              {:signal-sources [b]})]
      (is (= [] @log))
      (reset! a 3)
      (is (= [] @log))
      (sr/re-run-stale-effectful-nodes)
      (is (= [[:b 6] [:c+ 6]] @log))

      ,))

  ,)
