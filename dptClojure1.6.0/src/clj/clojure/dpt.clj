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
  (:import clojure.lang.Thunk)
  (:import clojure.lang.LockingTransaction))

; Ensures that reflection is shown so we can add type hints
(set! *warn-on-reflection* true)

;;; =================
;;; AFTER-COMMIT-FUNC
;;; =================
;; Helper function to update refs based on a ref-set-map
(defn- update-arg-by-ref-map
  "Sets the value of reference based on the (ref-set-map) arguments"
  [ref-set-map arg]
  (if (and (instance? clojure.lang.Ref arg) (contains? ref-set-map arg))
    (let [arg-val @arg]
      (ref-set arg (ref-set-map arg))
     arg-val)
    arg))

;; Helper function for adding a function and its arguments to be run when a transaction commits
(defn dosync-ac-helper
  "Adds the function (func) and arguments (args) to be run when the transaction
  commits, each argument is checked against the hash-map (ref-set-map), if
  passed, and any ref it contains is set to the key's accompanied value, after
  it's dereferenced value is sent to after-commit"
  [^java.util.ArrayList array ref-set-map func & args]
  (when-not (fn? func)
    (throw (IllegalArgumentException. "argument (fun) must be fn")))
  (if (nil? ref-set-map)
    (.add array [func args])
    (let [updated-args (map #(update-arg-by-ref-map ref-set-map %) args)]
      (doall updated-args)
      (.add array [func updated-args])))
  nil)

;; Macros for setting the after-commit-func environment and adding functions or expressions to be executed
(defmacro ac
  "Adds a list of expressions to be execute the transcation after-commit"
  [& body]
  `(dosync-ac-helper ~'&ac-funcs-and-args nil (fn [_#] ~@body) nil))

(defmacro ac-fn
  "Adds a after-commit function with arguments that should not be changed"
  [func & args]
  `(dosync-ac-helper ~'&ac-funcs-and-args nil ~func ~@args))

(defmacro ac-fn-set
  "Adds a after-commit function with arguments that can be changed through ref-set"
  [ref-set-map func & args]
  `(dosync-ac-helper ~'&ac-funcs-and-args ~ref-set-map ~func ~@args))

(defmacro dosync-ac
  "Dosync with the possibility of adding multi functions for execution after the transaction commits"
  [& body]
  `(let [~'&ac-funcs-and-args (java.util.ArrayList.)
         dosync-return# (dosync
                          (.clear ~'&ac-funcs-and-args)
                          ~@body)
         after-commit-return# (map #(apply (first %) (second %)) ~'&ac-funcs-and-args)]
     ; Vector is used to for returning to allow for efficient indexing and to force evaluation of lazyseq
     (vec (conj after-commit-return# dosync-return#))))

;;; ====
;;; UNDO
;;; ====
(defmacro undo
  "Registers a list of expressions to be run if the transaction terminates"
  [& body]
  `(.add  ~'&undo-funcs [#(do ~@body) nil]))

(defmacro undo-fn
  "Registers an function to be run if the transaction terminates"
  [func & args]
  `(.add  ~'&undo-funcs [~func '~args]))

(defmacro dosync-undo
  "Dosync extended with the possibility of running function on retry"
  [& body]
  `(let [~'&undo-funcs (java.util.ArrayList.)]
     (dosync
       (try
         ~@body
         (catch Error e#
           (doall (map #(apply (first %) (second %)) ~'&undo-funcs))
           (.clear ~'&undo-funcs)
           (throw e#))))))

;;; ===============
;;; LAZY EVALUATION
;;; ===============
;; Helper function for extracting Refs to be used in a lazy evaluation block
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

;; Macros for creating a Lazy Expression
(defmacro ler
  "Creation of lazy expresion with body of exrepssions and a map for setting the value of a ref"
  [refs & body]
  `(do
     (cond
       (map? ~refs) (doseq [[le-ref# func#] ~refs]
                      ; The function should be either commute or alter to set locks correctly
                      (if (or (= func# commute) (= func# alter))
                        (func# le-ref# identity)
                        (throw (IllegalArgumentException. "map value must be alter or commute"))))
       (vector? ~refs) (doseq [r# ~refs] (alter r# identity))
       :else (throw (IllegalArgumentException. "(refs) is neither a vector or a map")))
     (Thunk. (fn [] ~@body))))

(defmacro le
  "Creation of lazy expression taking only a body of expressions"
  [& body]
  ; Extracts the lexical scoped symbols from the environment
  (let [lexically-scoped-bindings (keys &env)]
    `(do
       (doseq [r# (extract-refs '() '~body ~@lexically-scoped-bindings)]
         (alter r# identity))
       (Thunk. (fn [] ~@body)))))

;;; =========
;;; Check-Run
;;; =========
;; Macros for check-run
(defmacro dosync-checked
  "A version of dosync that ensures it can commit before executing"
  [& body]
  `@(dosync
      (le ~@body)))

(defmacro dosync-checked-ref
  "A version of dosync that ensures it can commit before executing and excepts overwriting refs"
  [refs & body]
  `@(dosync
      (ler ~refs ~@body)))

;;; =====
;;; Retry
;;; =====
(defn retry
  "Aborts a transaction and waits until any of the specified refs have changed"
  ([] (.doBlocking (LockingTransaction/getEx) nil false))
  ([refs] (.doBlocking (LockingTransaction/getEx) refs false)))

(defn retry-all
  "Aborts a transaction and waits until all of the specified refs have changed"
  ([] (.doBlocking (LockingTransaction/getEx) nil true))
  ([refs] (.doBlocking (LockingTransaction/getEx) refs true)))

(defn or-else
  "Execute the first expressions that do not result in a transaction retry in a list of expressions"
  [& body]
  (.doOrElse (LockingTransaction/getEx) (java.util.ArrayList. ^java.util.ArrayList body)))

(defn terminate
  "Returns the context for both types of events, returns nil if no context exists"
  []
  (.abort (LockingTransaction/getEx)))
