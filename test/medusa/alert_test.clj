(ns medusa.alert-test
  (:require [clojure.test :refer :all]
            [clj.medusa.alert]))

(deftest test-build-range
  (testing "single build in a day"
    (is (= (#'clj.medusa.alert/build-range "20170102030405" "20170102030405") "20170102030405")))
  (testing "multiple builds in a day"
    (is (= (#'clj.medusa.alert/build-range "10170102030405" "20170102030405") "10170102030405...20170102030405"))))
