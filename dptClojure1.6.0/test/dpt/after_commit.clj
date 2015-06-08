; dpt1010f15
(ns dpt.after-commit
  (:use clojure.pprint clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :once dosync-fixture)

(deftest executes-transcation
  (is (== 4
          (let [test-ref (ref 0)]
            (dosync-ac
              (ref-set test-ref 4))
            (deref test-ref)))
      "dosync-ac does not execute a transaction"))

(deftest executes-after-commit-function
  (is (== 4
          (second
            (dosync-ac
              (+ 2 5)
              (ac-fn #(+ 2 %) 2)
              (+ 2 5)
              (ac-fn #(+ 10 %) 2))))
      "dosync-ac does not execute the after-commit function"))

(deftest executes-after-commit-body
  (is (== 4
          (second
            (dosync-ac
              (+ 2 5)
              (ac (+ 2 2) (+ 2 2))
              (+ 2 5)
              (ac (+ 10 2)))))
      "dosync-ac does not execute the after-commit function"))

(deftest executes-after-commit-func-and-body
  (let [returns (dosync-ac
                  (+ 2 5)
                  (ac (+ 2 2))
                  (ac-fn #(+ 10 5))
                  (* 3 2))] ; Tests that dosync-ac returns the last value, nill is not trivial to sum
    (is (== 25 (reduce + returns)) "dosync-ac does not execute the after-commit function, body, or does not returns")))

(deftest executes-ref-set
  (is (== 4
          (second
            (dosync-ac
              (ref-set dpt-deref-ref 2) ; It is set to zero again by ac-fn-set
              (ac-fn-set {dpt-deref-ref 0} #(+ 2 %) dpt-deref-ref))))
      "ac-fn-set does not extract the value of the ref")
  (is (== 0 @dpt-deref-ref) "ac-fn-set does not set the new value of the ref"))
