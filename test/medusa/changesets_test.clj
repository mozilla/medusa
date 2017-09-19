(ns medusa.changesets-test
  (:require [clojure.test :refer :all]
            [clj.medusa.changesets :refer [find-date-buildid]]))

(deftest find-date-buildid-smoke
  (testing "Unremarkable invocation of find-date-buildid"
    (is (= (find-date-buildid "2017-09-18" "mozilla-central") "20170918100059"))))
