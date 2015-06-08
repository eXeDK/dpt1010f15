; dpt1010f15
(ns dpt.undo
  (:use clojure.pprint clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :once dosync-fixture)
(use-fixtures :each test-ref-fixture)

(deftest executes-transcation
  (is (== 4
          (dosync-undo
            (ref-set dpt-test-ref 4))
          (deref dpt-test-ref))
      "dosync-undo does not execute a transaction"))

(deftest executes-undo-function
  (try
    (dosync
      (ref-set dpt-test-ref "NaN"))
    ; The refs is set in its own dosync as undo otherwise would throw set away
    (dosync-undo
      (undo-fn #(throw (Exception.)))
      (alter dpt-alter-ref inc))
    (catch Exception e
      (dosync
        (ref-set dpt-test-ref 0))))
  (is (number? @dpt-test-ref) "dosync-undo does not execute the after-commit function"))

(deftest executes-undo-body
  (try
    (dosync
      (ref-set dpt-test-ref "NaN"))
    ; The refs is set in its own dosync as undo otherwise would throw set away
    (dosync-undo
      (undo (throw (Exception.)))
      (alter dpt-alter-ref inc))
    (catch Exception e
      (dosync
        (ref-set dpt-test-ref 0))))
  (is (number? @dpt-test-ref) "dosync-undo does not execute the after-commit function"))
