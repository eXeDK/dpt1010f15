; dpt1010f15
(ns dpt.check-run
  (:use clojure.pprint clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :once dosync-fixture)

; NOTE: check-run is built on-top of "le" and "ler" so many of those tests overlap
(deftest check-run
  (is (== 4 (dosync-checked (+ 2 2)))))

(deftest check-run-refs
  (is (assert-retry
        (dosync-checked-ref
          [dpt-alter-ref]
          (+ 2 2)))))
