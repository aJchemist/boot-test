(ns adzerk.boot-test
  {:boot/export-tasks true}
  (:refer-clojure :exclude [test])
  (:require [boot.pod  :as pod]
            [boot.core :as core]))

(defn next-pod! [pool]
  (pool :refresh)
  (doto (pool)
    (pod/eval-in
     (require '[clojure.test :as t])
     (defn test-ns* [pred ns]
       (binding [t/*report-counters* (ref t/*initial-report-counters*)]
         (let [ns-obj (the-ns ns)]
           (t/do-report {:type :begin-test-ns :ns ns-obj})
           (t/test-vars (filter pred (vals (ns-publics ns))))
           (t/do-report {:type :end-test-ns :ns ns-obj}))
         @t/*report-counters*)))))

(core/deftask test
  "Run clojure.test tests in a pod."
  [n namespaces NAMESPACE #{sym} "Symbols of the namespaces to run tests in."
   f filters EXPR #{any} "Clojure expressions that are evaluated with % bound to a Var in a namespace under test. All must evaluate to true for a Var to be considered for testing by clojure.test/test-vars."]
  (let [worker-pods (pod/pod-pool 2 (core/get-env))]
    (core/cleanup (worker-pods :shutdown))
    (core/with-pre-wrap
      (if (seq namespaces)
        (let [pod (next-pod! worker-pods)
              filterf `(~'fn [~'%] (and ~@filters))
              summary (pod/eval-in pod
                                   (doseq [ns '~namespaces] (require ns))
                                   (let [ns-results (map (partial test-ns* ~filterf) '~namespaces)]
                                     (-> (reduce (partial merge-with +) ns-results)
                                         (assoc :type :summary)
                                         (doto t/do-report))))]
          (when (> (apply + (map summary [:fail :error])) 0)
            (throw (ex-info "Some tests failed or errored" summary))))
        (println "No namespaces were tested.")))))
