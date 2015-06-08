; dpt1010f15
(ns dpt.extract-refs
  (:use clojure.pprint clojure.test clojure.dpt dpt.dpt-test-helper))

;; Test static defined Var Ref, IS-SUPPORTED
(def static-var-ref (ref 0))
(deftest test-static-var-ref
  (let [body `(commute static-var-ref inc)]
    (is (== 1 (count (extract-refs '() body))))))

;; Test dynamic defined Var Ref, IS-SUPPORTED
(def ^:dynamic dynamic-var-ref (ref 1))
(deftest test-dynamic-var-ref
  (let [body `(commute dynamic-var-ref inc)]
    (is (== 1 (count (extract-refs '() body))))))

(deftest test-binding-dynamic-var-ref
  (let [body `(binding [dynamic-var-ref (ref 10)]
                (commute dynamic-var-ref inc))]
    (is (== 1 (count (extract-refs '() body))))))

(deftest test-with-bindings-dynamic-var-ref
  (let [body `(with-bindings {#'dynamic-var-ref (ref 10)}
                (commute dynamic-var-ref inc))]
    (is (== 1 (count (extract-refs '() body))))))

;; Test with multiple ref both dynamic and static
(def combi-static-var-ref (ref 1))
(def ^:dynamic combi-dynamic-var-ref (ref 2))
(def ref-list [static-var-ref dynamic-var-ref])
(deftest test-combi-bindings
  (let [body `(binding [combi-dynamic-var-ref (ref 10)]
                (identity ref-list)
                (alter combi-static-var-ref dec)
                (alter combi-dynamic-var-ref identity)
                (commute combi-dynamic-var-ref inc))]
    (is (== 4 (count (extract-refs '() body))))))
