(ns signaali.mutable.stack-test
  (:require #?(:clj  [clojure.test :refer [deftest testing is are]]
               :cljs [cljs.test :refer [deftest testing is are]])
            [signaali.mutable.stack :as mut-stack]))

(deftest stack-test
  (let [s (doto (mut-stack/make-mutable-object-stack)
            (mut-stack/conj! 10)
            (mut-stack/conj! 20)
            (mut-stack/conj! 30))]

    (testing "count"
      (is (= 3 (mut-stack/count s))))

    (testing "peek"
      (is (= 30 (mut-stack/peek s)))
      (is (= 3 (mut-stack/count s))))

    (testing "pop!"
      (mut-stack/pop! s)
      (is (= 20 (mut-stack/peek s)))
      (is (= 2 (mut-stack/count s))))))
