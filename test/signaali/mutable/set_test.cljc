(ns signaali.mutable.set-test
  (:require #?(:clj  [clojure.test :refer [deftest testing is are]]
               :cljs [cljs.test :refer [deftest testing is are]])
            [signaali.mutable.set :as mut-set]))

(deftest set-test
  (testing "mutable set made from a Clojure collection"
    (let [set (mut-set/make-mutable-object-set [10 20 #_30 40])]
      (is (true? (mut-set/contains? set 10)))
      (is (true? (mut-set/contains? set 20)))
      (is (false? (mut-set/contains? set 30)))
      (is (true? (mut-set/contains? set 40)))))

  (let [set (doto (mut-set/make-mutable-object-set)
              (mut-set/conj! 10)
              (mut-set/conj! 20)
              (mut-set/conj! 30))]

    (testing "contains?"
      (is (true? (mut-set/contains? set 30)))
      (is (= 3 (mut-set/count set))))

    (testing "disj!"
      (mut-set/disj! set 30)
      (is (false? (mut-set/contains? set 30)))
      (is (= 2 (mut-set/count set))))

    (testing "clear!"
      (mut-set/clear! set)
      (is (false? (mut-set/contains? set 10)))
      (is (= 0 (mut-set/count set))))

    ,))
