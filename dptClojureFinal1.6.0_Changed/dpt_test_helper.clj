; dpt1010f15
(ns dpt.dpt-test-helper
  (:use clojure.test clojure.dpt))

;; Small fixture that just exposes a ref
(def dpt-test-ref (ref 0))
(defn test-ref-fixture
  "Fixture for running a test with a single ref rest each run"
  [func]
  (dosync
    (ref-set dpt-test-ref 0))
    (func))

;; Refs needed for dosync-fixture, global vars is simpler then local vars
(def dpt-deref-ref (ref 0))
(def dpt-commute-ref (ref 0))
(def dpt-alter-ref (ref 0))
(def dpt-ref-set-ref (ref 0))
(def dpt-ensure-ref (ref 0))

(defn dosync-fixture
  "Fixture for running a test with a set of refs changed"
  [func]
  (let [lockbject (Object.)
        thread (Thread.
                #(dosync
                   (deref dpt-deref-ref)
                   (commute dpt-commute-ref identity)
                   (alter dpt-alter-ref identity)
                   (ref-set dpt-alter-ref 0)
                   (ensure dpt-ensure-ref)
                   ; Sub thread waits for main thread to sleep
                   (locking lockbject
                     (.notify lockbject)
                     (.wait lockbject))))]
              ; Main thread waits for sub thread to alter
              (locking lockbject
                (.start thread)
                (.wait lockbject))
      ; Main thread exectutes the body passed to it
      (func)
      (locking lockbject
       (.notify lockbject))))

;; Var needed for the retry fixture, var prevets retry on parallel test execution as they are thread local
(def retry-ref (ref true))
(def retry-lockbject (Object.))

(defn alter-retry-ref []
  (locking retry-lockbject
    (.notify retry-lockbject)))

(defn retry-fixture
  "Fixture for running a test with a set of refs blocked by retry"
  [func]
  (let [thread (Thread.
                 #(dosync
                    (locking retry-lockbject
                      (.notify retry-lockbject)
                      (.wait retry-lockbject))
                    (alter retry-ref not)))]
    ; Main thread waits for sub thread to alter
    (locking retry-lockbject
      (.start thread)
      (.wait retry-lockbject))
    ; Main thread exectutes the body passed to it
    (func)
    (dosync
      (ref-set retry-ref true))))

;; Asserts for checking retrying in a dosync bloc k
(defmacro assert-retry
  "Asserts if the function body retries if executed in a dosync block"
  [& body]
  `(with-local-vars [retried# true]
    (try ; Breaks execution through exeception on retry
      (dosync
        ; retried# should be "false" on a succesfull run for the "is" check
        (var-set retried# (not @retried#))
        (when @retried#
          (throw (Exception.)))
        ~@body)
      ; The exeception is used to exit dosync, but other exceptions should not
      (catch Exception e#
        (when-not @retried#
          (throw e#))))
    (is @retried# "The dosync block did not retry at least once")))

(defmacro assert-not-retry
  "Asserts if the function body does not retry if executed in a dosync block"
  [& body]
  `(with-local-vars [retried# true]
    (try ; Breaks execution through exeception on retry
      (dosync
        ; retried# shoulc be "false" on a succesfull run for the "is" check
        (var-set retried# (not @retried#))
        (when @retried#
          (throw (Exception.)))
        ~@body)
      ; The exeception is used to exit dosync, but other exceptions should not
      (catch Exception e#
        (when-not @retried#
          (throw e#))))
    (is (not @retried#) "The dosync block retried at least once")))
