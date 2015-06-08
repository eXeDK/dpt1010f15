; dpt1010f15
(ns dpt.transaction_control
  (:use clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :once dosync-fixture)

; Retry
(deftest retry-by-gets
  (let [retry-ref (ref 0)]
    ; Start unlock thread
    (future
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (dosync
        (alter retry-ref inc)))
    ; Blocks main thread
    (dosync
      (when (== 0 @retry-ref)
        (retry)))))

(deftest retry-by-ref
  (let [retry-ref (ref 0)]
    ; Start unlock thread
    (future
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (dosync
        (alter retry-ref inc)))
    ; Blocks main thread
    (dosync
      (when (== 0 @retry-ref)
        (retry retry-ref)))))

(deftest retry-by-ref-and-func
  (let [retry-ref (ref 0)]
    ; Start unlock thread
    (future
      (dosync
        (alter retry-ref inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (dosync
        (alter retry-ref inc)))
    ; Blocks main thread
    (dosync
      (when (not (== 2 @retry-ref))
        (retry retry-ref #(== @retry-ref 2)))
      (is (== @retry-ref 2)))))

; Retry-All
(deftest retry-all-by-gets
  (let [retry-ref-one (ref 0) retry-ref-two (ref 0)]
    ; Start unlock thread
    (future
      ; First ref should not unlock
      (dosync
        (alter retry-ref-one inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry is awake
      (dosync
        (alter retry-ref-two inc)))
    ; Blocks main thread
    (dosync
      (deref retry-ref-one)
      (when (== 0 @retry-ref-two)
        (retry))
      (alter retry-ref-one inc))))

(deftest retry-all-by-refs
  (let [retry-ref-one (ref 0) retry-ref-two (ref 0)]
    ; Start unlock thread
    (future
      ; First ref should not unlock
      (dosync
        (alter retry-ref-one inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry is awake
      (dosync
        (alter retry-ref-two inc)))
    ; Blocks main thread
    (dosync
      (deref retry-ref-one)
      (when (== 0 @retry-ref-two)
        (retry [retry-ref-one retry-ref-two]))
      (alter retry-ref-one inc))))

(deftest retry-all-by-refs-and-func
  (let [retry-ref-one (ref 0) retry-ref-two (ref 0)]
    ; Start unlock thread
    (future
      ; First ref should not unlock
      (dosync
        (alter retry-ref-one inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry-all is awake
      ; Second ref should not unlock due to func
      (dosync
        (alter retry-ref-two inc))
      (Thread/sleep 1000) ; Test sync with time, deadlocks if missed
      (is (not (== @retry-ref-one 2))) ; Checks if retry-all is awake
      (dosync
        (alter retry-ref-one inc)
        (alter retry-ref-two inc)))
    ; Blocks main thread
    (dosync
      (deref retry-ref-one)
      (when (== 0 @retry-ref-two)
        (retry [retry-ref-one retry-ref-two] #(== @retry-ref-one 2)))
      (alter retry-ref-one inc))))

; Or-Else
(deftest or-else-test
  (let [or-else-ref (ref 0)]
    (dosync
      (or-else
        #(alter dpt-alter-ref inc)
        #(ref-set or-else-ref 5)
        #(ref-set or-else-ref 7)))
    (is (== @or-else-ref 5))))

; Terminate
(deftest terminate-test
  (let [terminate-ref (ref 0)]
    (dosync
      (terminate)
      (ref-set terminate-ref 7))
    (dosync
      (ref-set terminate-ref 5))
    (is (== @terminate-ref 5))))
