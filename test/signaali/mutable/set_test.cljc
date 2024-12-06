(ns signaali.mutable.set-test
  (:require #?(:clj  [clojure.test :refer [deftest testing is are]]
               :cljs [cljs.test :refer [deftest testing is are]])
            [signaali.mutable.set :as mut-set]))

(deftest stack-test
  (let [s (doto (mut-set/make-mutable-object-set)
            (mut-set/conj! 10)
            (mut-set/conj! 20)
            (mut-set/conj! 30))]

    (testing "contains?"
      (is (true? (mut-set/contains? s 30)))
      (is (= 3 (mut-set/count s))))

    (testing "disj!"
      (mut-set/disj! s 30)
      (is (false? (mut-set/contains? s 30)))
      (is (= 2 (mut-set/count s))))

    (testing "clear!"
      (mut-set/clear! s)
      (is (false? (mut-set/contains? s 10)))
      (is (= 0 (mut-set/count s))))

    ,))
