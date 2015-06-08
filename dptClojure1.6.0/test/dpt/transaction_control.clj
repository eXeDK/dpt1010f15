; dpt1010f15
(ns dpt.transaction-control
  (:use clojure.pprint clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :each retry-fixture)
(def lockbject (Object.))
(def retried-counter 0)

(deftest retry-retries
  (let [result (future
                 (dosync
                   (alter-var-root (var retried-counter) inc)
                   (when @retry-ref
                     (retry))))]
    (Thread/sleep 500) ; Sleep is best sync
    (alter-retry-ref)
    @result)
  (is (== retried-counter 2) "Retry did not force the transcation to retry")
  (alter-var-root (var retried-counter) (fn [x] 0)))

(deftest retry-all-retries
  (let [result (future
                 (dosync
                   (alter-var-root (var retried-counter) inc)
                   (when @retry-ref
                     (retry-all))))]
    (Thread/sleep 500) ; Sleep is best sync
    (alter-retry-ref)
    @result)
  (is (== retried-counter 2) "Retry-all did not force the transcation to retry")
  (alter-var-root (var retried-counter) (fn [x] 0)))


(deftest retry-unblocks-with-refs
  (let [result (future
                 (dosync
                   (deref retry-ref)
                   (when @retry-ref
                     (retry))))]
    (Thread/sleep 500) ; Sleep is best sync
    (alter-retry-ref)
    @result)
  (is true "The test did not block, but true is not true"))

(deftest retry-all-unblocks-with-refs
  (let [result (future
                 (dosync
                   (deref retry-ref)
                   (when @retry-ref
                     (retry-all))))]
    (Thread/sleep 500) ; Sleep is best sync
    (alter-retry-ref)
    @result)
  (is true "The test did not block, but true is not true"))

(deftest retry-unblocks-without-refs
  (let [result (future
                 (dosync
                   (when @retry-ref
                     (retry))))]
    (Thread/sleep 500) ; Sleep is best sync
    (alter-retry-ref)
    @result)
  (is true "The test did not block, but true is not true"))

(deftest retry-all-unblocks-without-refs
  (let [result (future
                 (dosync
                   (when @retry-ref
                     (retry-all))))]
    (Thread/sleep 500) ; Sleep is best sync
    (alter-retry-ref)
    @result)
  (is true "The test did not block, but true is not true"))
