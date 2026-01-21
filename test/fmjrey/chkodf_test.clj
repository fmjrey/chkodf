;; ---------------------------------------------------------
;; fmjrey.chkodf.-test
;;
;; Example unit tests for fmjrey.chkodf
;;
;; - `deftest` - test a specific function
;; - `testing` logically group assertions within a function test
;; - `is` assertion:  expected value then function call
;; ---------------------------------------------------------


(ns fmjrey.chkodf-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fmjrey.chkodf :as chkodf]))


(deftest application-test
  (testing "TODO: Start with a failing test, make it pass, then refactor"

    ;; TODO: fix greet function to pass test
    (is (= "fmjrey application developed by the secret engineering team"
           (chkodf/greet)))

    ;; TODO: fix test by calling greet with {:team-name "Practicalli Engineering"}
    (is (= (chkodf/greet "Practicalli Engineering")
           "fmjrey service developed by the Practicalli Engineering team"))))
