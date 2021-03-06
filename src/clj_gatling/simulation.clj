(ns clj-gatling.simulation
  (:require [clj-gatling.httpkit :as http]
            [clj-gatling.simulation-runners :refer :all]
            [clj-gatling.schema :as schema]
            [clj-gatling.simulation-util :refer [weighted-scenarios
                                                 choose-runner
                                                 log-exception]]
            [schema.core :refer [check validate]]
            [clj-time.local :as local-time]
            [clojure.set :refer [rename-keys]]
            [clojure.core.async :as async :refer [go go-loop close! put! <!! alts! <! >!]]))

(set! *warn-on-reflection* true)

(defn- now [] (System/currentTimeMillis))

(defn asynchronize [f ctx]
  (let [parse-response (fn [result]
                         (if (vector? result)
                           {:result (first result) :end-time (now) :context (second result)}
                           {:result result :end-time (now) :context ctx}))]
    (go
      (try
        (let [result (f ctx)]
          (if (instance? clojure.core.async.impl.channels.ManyToManyChannel result)
            (parse-response (<! result))
            (parse-response result)))
        (catch Exception e
          {:result false :end-time (now) :context ctx :exception e})))))

(defn async-function-with-timeout [step timeout sent-requests user-id original-context]
  (swap! sent-requests inc)
  (go
    (when-let [sleep-before (:sleep-before step)]
      (<! (async/timeout (sleep-before original-context))))
    (let [original-context-with-user (assoc original-context :user-id user-id)
          start (now)
          return {:name (:name step)
                  :id user-id
                  :start start
                  :context-before original-context-with-user}
          response (asynchronize (:request step) original-context-with-user)
          [{:keys [result end-time context exception]} c] (alts! [response (async/timeout timeout)])]
      (if (= c response)
        [(assoc return :end end-time
                       :exception exception
                       :result result
                       :context-after context) context]
        [(assoc return :end (now)
                       :exception exception
                       :return false
                       :context-after original-context-with-user)
         original-context-with-user]))))

(defn- response->result [scenario result]
  {:name (:name scenario)
   :id (:id (first result))
   :start (:start (first result))
   :end (:end (last result))
   :requests result})

(defn- run-scenario-once [{:keys [runner simulation-start] :as options} scenario user-id]
  (let [timeout (:timeout-in-ms options)
        sent-requests (:sent-requests options)
        result-channel (async/chan)
        skip-next-after-failure? (if (nil? (:skip-next-after-failure? scenario))
                                   true
                                   (:skip-next-after-failure? scenario))
        should-terminate? #(and (:allow-early-termination? scenario)
                                (not (continue-run? runner @sent-requests simulation-start)))
        request-failed? #(not (:result %))]
    (go-loop [steps (:steps scenario)
              context (or (merge (:context options) (:context scenario)) {})
              results []]
             (let [[result new-ctx] (<! (async-function-with-timeout (first steps)
                                                                     timeout
                                                                     sent-requests
                                                                     user-id
                                                                     context))]
               (when-let [e (:exception result)]
                 (log-exception (:error-file options) e))
               (if (or (should-terminate?)
                       (empty? (rest steps))
                       (and skip-next-after-failure?
                            (request-failed? result)))
                 (>! result-channel (->> (dissoc result :exception)
                                         (conj results)))
                 (recur (rest steps) new-ctx (conj results result)))))
    result-channel))


(defn- run-scenario-constantly
  [{:keys [concurrent-scenarios
           runner
           sent-requests
           simulation-start
           concurrency
           concurrency-distribution
           context] :as options}
   scenario
   user-id]
  (let [c (async/chan)
        should-run-now? (if concurrency-distribution
                          #(let [progress (calculate-progress runner @sent-requests simulation-start)
                                 target-concurrency (* concurrency
                                                       (concurrency-distribution progress context))]
                             (> target-concurrency @concurrent-scenarios))
                          (constantly true))]
    (go-loop []
             (if (should-run-now?)
               (do
                 (swap! concurrent-scenarios inc)
                 (let [result (<! (run-scenario-once options scenario user-id))]
                   (swap! concurrent-scenarios dec)
                   (>! c result)))
               (<! (async/timeout 200)))
             (if (continue-run? runner @sent-requests simulation-start)
               (recur)
               (close! c)))
    c))

(defn- print-scenario-info [scenario]
  (println "Running scenario" (:name scenario)
           "with concurrency" (count (:users scenario))))

(defn- run-scenario [options scenario]
  (print-scenario-info scenario)
  (let [concurrent-scenarios (atom 0)
        responses (async/merge (map #(run-scenario-constantly (assoc options :concurrent-scenarios concurrent-scenarios) scenario %)
                                    (:users scenario)))
        results (async/chan)]
    (go-loop []
             (if-let [result (<! responses)]
               (do
                 (>! results (response->result scenario result))
                 (recur))
               (close! results)))
    results))

(defn run-scenarios [{:keys [post-hook context runner concurrency-distribution] :as options} scenarios]
  (println "Running simulation with"
           (runner-info runner)
           (if concurrency-distribution
             "using concurrency distribution function"
             ""))
  (validate [schema/RunnableScenario] scenarios)
  (let [simulation-start (local-time/local-now)
        sent-requests (atom 0)
        run-scenario-with-opts (partial run-scenario
                                        (assoc options
                                               :simulation-start simulation-start
                                               :sent-requests sent-requests))
        responses (async/merge (map run-scenario-with-opts scenarios))
        results (async/chan)]
    (go-loop []
             (if-let [result (<! responses)]
               (do
                 (>! results result)
                 (recur))
               (do
                (close! results)
                (when post-hook (post-hook context)))))
    results))

(defn run [{:keys [scenarios pre-hook post-hook] :as simulation}
           {:keys [concurrency users context] :as options}]
  (validate schema/Simulation simulation)
  (let [user-ids (or users (range concurrency))
        final-ctx (merge context (when pre-hook (pre-hook context)))]
    (run-scenarios (assoc options
                          :context final-ctx
                          :post-hook post-hook
                          :runner (choose-runner scenarios
                                                 (count user-ids)
                                                 options))
                   (weighted-scenarios user-ids scenarios))))
