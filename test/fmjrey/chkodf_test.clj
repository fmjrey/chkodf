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
  (:require [app :refer [app-name version-string]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fmjrey.chkodf :as chkodf]))

(defn includes-all? [text & substrings]
  (every? #(str/includes? text %) substrings))

(deftest application-test
  (testing "TODO: Start with a failing test, make it pass, then refactor"

    (let [options {:println? false}]
      (is (includes-all? (chkodf/greet options)
                         app-name version-string)))

    (let [username "Test User"
          options {:println? false :username username}]
      (is (includes-all? (chkodf/greet options)
                         app-name version-string username)))))
