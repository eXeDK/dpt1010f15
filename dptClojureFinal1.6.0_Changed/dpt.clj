; dpt1010f15
;   Copyright (c) dp1010f15. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "STM/IO Clojure extensions."
      :author "dpt1010f15"}
  clojure.dpt
  (:import clojure.lang.EventManager)
  (:import clojure.lang.LockingTransaction)
  (:import clojure.lang.RT))


;;; Environment setup
(set! *warn-on-reflection* true)


;;; Event Handling Helper Functions
(defn- ref? [sym]
  "Tests if a symbol is a reference"
  (instance? clojure.lang.IRef sym))

(defn extract-refs
  "Extract refs from a list of expressions and accumulate them in the acc list,
  namespace is determined at runtime so the function have the scope of caller"
  [acc & body]
  (distinct (reduce
    ; Reduce Function
    (fn [acc elem]
      (cond
        ; Element var containing a ref
        (and (ref? elem) (var? elem)) (conj acc @elem)
        ; Element is a raw ref
        (ref? elem) (conj acc elem)
        ; Element is a symbol that could be a ref
        (symbol? elem) (do
                         (let [elem-var (some-> elem resolve var-get)]
                           ; Symbol is a var containing a ref
                           (if (ref? elem-var)
                             (conj acc elem-var)
                             ; Symbol is a sequences so we need to check it
                             (if (or (seq? elem-var) (vector? elem-var))
                               (extract-refs acc elem-var)
                               ; Symbol was neither a ref or sequence
                               acc))))))
    ; Removes nesting to only require recursion for vars with sequences
    acc (flatten body))))


;;; Transactional Event Handling
(defn stm-listen
  "Registers a thread local transactional event identified by event-key"
  [event-key event-fn & event-args]
  (EventManager/stmListen event-key event-fn event-args false))

(defn stm-listen-once
  "Registers a single run transactional event identified by event-key"
  [event-key event-fn & event-args]
  (EventManager/stmListen event-key event-fn event-args true))

(defn stm-notify
  "Notifies the transactional events identified by the event-key keyword, and
  gives each event accesses to data given as context"
  ([event-key] (EventManager/stmNotify event-key nil))
  ([event-key context] (EventManager/stmNotify event-key context)))

(defmacro lock-refs
  "Takes the appropriate locks on all extractable refs in body of code"
  [func & body]
  ; Extracts the lexical scoped symbols from the environment
  (let [lexically-scoped-bindings (keys &env)
    locking-fn (case func
                 ensure #(ensure %)
                 commute #(commute % identity)
                 alter #(alter % identity)
                 (throw (IllegalArgumentException. "func must be ensure, commute, or alter")))]
    `(do
       (doseq [r# (extract-refs '() '~body ~@lexically-scoped-bindings)]
         (~locking-fn r#))
       ~@body)))


;;; Special Transactional Events
(defmacro on-abort
  "Registers a list of expressions to be run if the transaction aborts"
  [& body]
  `(EventManager/stmListen LockingTransaction/ONABORTKEYWORD (fn [] ~@body) nil false))

(defn on-abort-fn
  "Registers a function to be run if the transaction aborts"
  [event-fn & event-args]
  (EventManager/stmListen LockingTransaction/ONABORTKEYWORD event-fn event-args false))

(defmacro on-commit
  "Registers a list of expression to be run when the transaction commits"
  [& body]
  `(EventManager/stmListen LockingTransaction/ONCOMMITKEYWORD (fn [] ~@body) nil false))

(defn on-commit-fn
  "Registers a function to be run when the transaction commits"
  [event-fn & event-args]
  (EventManager/stmListen LockingTransaction/ONCOMMITKEYWORD  event-fn event-args false))

(defmacro after-commit
  "Registers a list of expressions to be run after the transaction commit"
  [& body]
  `(EventManager/stmListen LockingTransaction/AFTERCOMMITKEYWORD (fn [] ~@body) nil false))

(defn after-commit-fn
  "Registers a function to be run after the transaction commit"
  [event-fn & event-args]
  (EventManager/stmListen LockingTransaction/AFTERCOMMITKEYWORD event-fn event-args false))


;;; Generic Event Handling
(defn listen
  "Registers a thread local event for the event identified by event-key"
  [event-key event-fn & event-args]
  (EventManager/listen event-key event-fn event-args true false))

(defn listen-with-params
  "Registers a event for the event identified by event-key, arguments can by
  given to configure if the listener should be thread local and if it is to be
  deleted after listener have been executed"
  [event-key thread-local delete-after-run event-fn & event-args]
  (EventManager/listen event-key event-fn event-args thread-local delete-after-run))

(defn notify
  "Notifies the events identified by the event-key keyword, and gives each
  event accesses to data given as context"
  ([event-key] (EventManager/notify event-key nil))
  ([event-key context] (EventManager/notify event-key context)))

(defn dismiss
  "Dismisses an event identified by the combination of event-key and event-fn, "
  [event-key event-fn dismiss-from]
  (EventManager/dismiss event-key event-fn dismiss-from))

(defn context
  "Returns the context for both types of events, returns nil if no context exists"
  []
  (EventManager/getContext))

;;; Transactional Control
(defn retry
  "Aborts a transaction and waits until any of the specified refs have changed"
  ([] (RT/stmBlocking nil nil nil false))
  ([refs] (RT/stmBlocking refs nil nil false))
  ([refs func & args] (RT/stmBlocking refs func args false)))

(defn retry-all
  "Aborts a transaction and waits until all of the specified refs have changed"
  ([] (RT/stmBlocking nil nil nil true))
  ([refs] (RT/stmBlocking refs nil nil true))
  ([refs func & args] (RT/stmBlocking refs func args true)))

(defn or-else
  "Execute the first expressions that do not result in a transaction retry in a list of expressions"
  [& body]
  (RT/stmOrElse body))

(defn terminate
  "Returns the context for both types of events, returns nil if no context exists"
  []
  (RT/stmAbort))
