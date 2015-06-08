; dpt1010f15
(ns dpt.lazy-evaluation
  (:use clojure.pprint clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :once dosync-fixture)

; Tests if "le" fails outside dosync
(is (thrown? java.lang.IllegalStateException
             (le (+ 2 2))))

; Test if "le" can be derefed and is returned correctly
(deftest le-deref-dosync-returning-thunk
  (is (== 4
          @(dosync
             (le (+ 2 2))))))

(deftest le-error-deref-le-before-commit
  (is (thrown? java.lang.Exception
               (dosync
                 @(le (+ 2 2))))))

(deftest le-deref-le-inside-le
  (is (== 5
          @(dosync
             (let [result (le (+ 2 2))]
               (le (+ 1 @result)))))))

(deftest le-error-deref-le-symbol-before-commit
  (is (thrown? java.lang.Exception
               (dosync
                 (let [result (le (+ 2 2))]
                   (+ 1 @result))))))

; Test if "ler" can use alter/commute identity on refs and only refs
(deftest ler-error-not-ref
  (is (thrown? java.lang.ClassCastException
               (dosync
                 (ler ["I-am-not-a-ref"] (+ 2 2))))))

(deftest ler-not-retry-deref-alter
  (is (assert-not-retry
        (dosync
          (ler [dpt-deref-ref] (+ 2 2))))))

(deftest ler-retry-alter-alter
  (is (assert-retry
        (dosync
          (ler [dpt-alter-ref] (+ 2 2))))))

(deftest ler-not-retry-commute-alter
  (is (assert-not-retry
        (dosync
          (ler {dpt-commute-ref alter} (+ 2 2))))))

; Test if "le"and "ler" retries before changes on both alter and commute
(def al (java.util.ArrayList.))

(deftest retries-before-if-altered
  (is (assert-retry
        (dosync
          (ler [dpt-alter-ref] (.add al 2)))))
  (is (.isEmpty al) "The side effect was not prevented before retry")
  (.clear al))


(deftest retries-before-if-commute
  (is (assert-retry
        (dosync
          (ler {dpt-alter-ref commute} (.add al 2)))))
  (is (.isEmpty al) "The side effect was not prevented before retry")
  (.clear al))

(deftest retries-before-if-alter-ensure
  (is (assert-retry
        (dosync
          (ler [dpt-alter-ref] (.add al 2)))))
  (is (.isEmpty al) "The side effect was not prevented before retry")
  (.clear al))


(deftest retries-before-if-commute-ensure
  (is (assert-retry
        (dosync
          (ler {dpt-ensure-ref commute} (.add al 2)))))
  (is (.isEmpty al) "The side effect was not prevented before retry")
  (.clear al))

; Tests if special cases in dosync still hold inside "le"
(def test-agent (agent 0))

(deftest await-causes-exception
  (is (thrown? java.lang.IllegalStateException
               (dosync
                 (ler [] (await test-agent)))))
  "Await inside a ler transcation did not cause an exception")

(deftest send-is-delyaed
  ; Send but terminate trancscation
  (try
    (dosync
      (ler [] (send test-agent (fn [cv] "send-is-delyaed")))
      (throw (Exception.)))
    (catch Exception e))
  ; Agent should not be changed
  (await test-agent)
  (is (= 0 @test-agent)
      "Send was not delayed insde the dosync so retry did not stop it"))
