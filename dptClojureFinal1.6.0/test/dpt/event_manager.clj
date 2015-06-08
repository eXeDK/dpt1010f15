; dpt1010f15
(ns dpt.event-manager
  (:use clojure.test clojure.dpt dpt.dpt-test-helper))

(use-fixtures :once dosync-fixture)

; Events exception test
(deftest stm-listen-exception
  (is (thrown? java.lang.Exception
               (stm-listen :test #(identity 0)))))

(deftest stm-notify-exception
  (is (thrown? java.lang.Exception
               (stm-notify :test))))

(deftest listen-exception
  (is (thrown? java.lang.Exception
               (dosync
                 (listen :test #(identity 0))))))

(deftest notify-exception
  (is (thrown? java.lang.Exception
               (dosync
                 (notify :test)))))

; Basic STM events
(deftest stm-listen-multiple
  (let [event-ref (ref 0)]
    (dosync
      (stm-listen :test #(alter event-ref inc))
      (stm-notify :test)
      (stm-notify :test))
    (is (== @event-ref 2))))

(deftest stm-listen-once-multiple
  (let [event-ref (ref 0)]
    (dosync
      (stm-listen-once :test #(alter event-ref inc))
      (stm-notify :test)
      (stm-notify :test))
    (is (== @event-ref 1))))

; Macro STM events
(deftest on-abort-event
  ; The transcation aborts so a ref cannot be changed
  (with-local-vars [event-var 0]
    (is (assert-retry
          (dosync
            (on-abort
              (var-set event-var 5))
            (alter dpt-alter-ref inc))))
    (is (== @event-var 5))))

(deftest on-abort-commit-context
  (let [event-ref (ref 0)]
    (dosync
      (on-commit
        (ref-set ((context) event-ref) 5))
      ; The context contain "sets" any ref we wrote to
    (alter event-ref identity))
    (is (== @event-ref 5))))

(deftest on-abort-event
  ; The transcation aborts so a ref cannot be changed
  (with-local-vars [event-var 0]
    (dosync
      (after-commit
        (var-set event-var 5)))
    (is (== @event-var 5))))

; Normal events
(deftest listen-dismiss
  (with-local-vars [event-var 0 dismiss-key (listen :test #(var-set event-var (inc @event-var)))]
    (notify :test)
    (dismiss :test @dismiss-key :all)
    (notify :test)
    (is (== @event-var 1))))

(deftest listen-global
  (with-local-vars [event-var 0 dismiss-key (listen-with-params :test false false #(var-set event-var (inc @event-var)))]
    (notify :test)
    (dismiss :test @dismiss-key :local)
    (notify :test)
    (dismiss :test @dismiss-key :all)
    (is (== @event-var 2))))

(deftest listen-once
  (with-local-vars [event-var 0 dismiss-key (listen-with-params :test true true #(var-set event-var (inc @event-var)))]
    (notify :test)
    (notify :test)
    (dismiss :test @dismiss-key :all)
    (is (== @event-var 1))))

(deftest listen-contex
  (let [dismiss-key (listen :test #(is (== 5 (context))))]
    (notify :test 5)
    (dismiss :test dismiss-key :all)))

(deftest listen-global-context-threads
    (let [dismiss-key (listen-with-params :test false false #(is (== 7 (context))))]
      (future (notify :test 7))
      (dismiss :test dismiss-key :all)))
