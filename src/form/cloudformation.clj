(ns form.cloudformation
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [form.codec :as codec]
            [form.terminal :as terminal]
            [form.util :as util]
            [tick.core :as t]
            [tick.locale-en-us]))

(def aws-region (or (System/getenv "AWS_REGION") "eu-west-1"))
(def polling-interval-ms 5000)

(def ^:dynamic *cloudformation-client*
  (aws/client {:api :cloudformation
               :region (keyword aws-region)}))

(defn- stack-params
  [provision-params]
  (mapv (fn [[k v]]
          {:ParameterKey (->> k name csk/->PascalCaseString)
           :ParameterValue v})
        (dissoc provision-params :technology :interactive)))

(defn- stack-name
  [{:keys [environment application technology version]}]
  (str/join "-" [environment application technology (str "v" version)]))

(defn- template-for-stack [technology]
  (if-let [template-resource (io/resource (format "cloudformation/template-%s.yml" technology))]
    (slurp template-resource)
    (throw (ex-info (format "Cannot find required cloudformation/template-%s.yml" technology)
                    {:technology technology}))))

(defn- get-git-commit-hash []
  (let [git-commit-hash (str/trim (:out (sh "git" "rev-parse" "--short" "HEAD")))]
    git-commit-hash))

(defn- console-link-for-stack-events [stack-name]
  (format "https://%s.console.aws.amazon.com/cloudformation/home?region=%s#/stacks/events?stackId=%s"
          aws-region aws-region stack-name))

(defn- console-link-for-change-set [stack-name change-set-id]
  (format (str "https://%s.console.aws.amazon.com/cloudformation/home"
               "?region=%s#/stacks/changesets/changes?stackId=%s&changeSetId=%s")
          aws-region aws-region stack-name (codec/url-encode change-set-id)))

(defn- stack-exists? [stack-name]
  (let [response (aws/invoke *cloudformation-client*
                             {:op :DescribeStacks
                              :request {:StackName stack-name}})]
    (not (:cognitect.anomalies/category response))))

(defn- generate-change-set-name [stack-name]
  (let [request-timestamp (t/format (t/formatter "hh-mm-ss-SSSz") (t/zoned-date-time))
        commit-hash (get-git-commit-hash)]
    (str stack-name "-changeset-" commit-hash "-" request-timestamp)))

(defn- get-stack-status [stack-name]
  (let [response (aws/invoke *cloudformation-client*
                             {:op :DescribeStacks
                              :request {:StackName stack-name}})
        stack (first (:Stacks response))]
    (:StackStatus stack)))

(defn- wait-for-stack-operation-to-complete! [stack-name]
  (let [successful-statuses #{"CREATE_COMPLETE"
                              "UPDATE_COMPLETE"}
        failed-statuses #{"ROLLBACK_COMPLETE"
                          "UPDATE_ROLLBACK_COMPLETE"
                          "ROLLBACK_FAILED"
                          "UPDATE_ROLLBACK_FAILED"}]
    (loop [status (get-stack-status stack-name)]
      (cond
        (successful-statuses status)
        (do
          (terminal/success! (format "Stack status [%s]" status))
          status)

        (failed-statuses status)
        (do
          (terminal/error! (format "Stack status [%s]" status))
          status)

        :else
        (do
          (terminal/info! (format "Stack status [%s]" status))
          (Thread/sleep polling-interval-ms)
          (recur (get-stack-status stack-name)))))))

;; The change-set-identifier must be the callee-assigned change-set-name prior during
;; creation and prior to execution, but must be the AWS assigned arn when checking
;; the execution status 🤷🏻‍♂️
(defn- get-change-set-status! [stack-name change-set-identifier]
  (let [response (aws/invoke *cloudformation-client*
                             {:op :DescribeChangeSet
                              :request {:StackName stack-name
                                        :ChangeSetName change-set-identifier}})]
    (select-keys response [:Status :StatusReason :Changes :ChangeSetId :ExecutionStatus])))

(defn- wait-for-change-set-to-exist! [stack-name change-set-name]
  (let [successful-statuses #{"CREATE_COMPLETE"}
        failed-statuses #{"FAILED"}]
    (loop [{:keys [Status StatusReason Changes ChangeSetId]} (get-change-set-status! stack-name change-set-name)]
      (cond
        (successful-statuses Status)
        (do
          (terminal/success! (format "Change set status [%s]" Status))
          ChangeSetId)

        (failed-statuses Status)
        (do
          (if (seq Changes)
            (do
              (terminal/error! (format "Change set status [%s]" Status))
              (terminal/error! StatusReason))
            (terminal/info! (format "Skipping as change set requires no changes")))
          ChangeSetId)

        :else
        (do
          (terminal/info! (format "Change set status [%s]" Status))
          (Thread/sleep polling-interval-ms)
          (recur (get-change-set-status! stack-name change-set-name)))))))

(defn- wait-for-change-set-execution-to-complete! [stack-name change-set-id]
  (let [successful-statuses #{"EXECUTE_COMPLETE"}
        failed-statuses #{"EXECUTE_FAILED"}]
    (loop [{:keys [ExecutionStatus]} (get-change-set-status! stack-name change-set-id)]
      (cond
        (successful-statuses ExecutionStatus)
        (do
          (terminal/success! (format "Change set execution status [%s]" ExecutionStatus))
          ExecutionStatus)

        (failed-statuses ExecutionStatus)
        (do
          (terminal/error! (format "Change set execution status [%s]" ExecutionStatus))
          ExecutionStatus)

        :else
        (do
          (terminal/info! (format "Change set execution status [%s]" ExecutionStatus))
          (Thread/sleep polling-interval-ms)
          (recur (get-change-set-status! stack-name change-set-id)))))))

(defn- create-change-set! [stack-name change-set-name template-body stack-params]
  (let [response (aws/invoke *cloudformation-client*
                             {:op :CreateChangeSet
                              :request (cond-> {:StackName stack-name
                                                :ChangeSetName change-set-name
                                                :TemplateBody template-body
                                                :Parameters stack-params
                                                :Capabilities ["CAPABILITY_IAM"]}
                                         (or (not (stack-exists? stack-name))
                                             (= "REVIEW_IN_PROGRESS" (get-stack-status stack-name)))
                                         (assoc :ChangeSetType "CREATE"))})]
    (if (:cognitect.anomalies/category response)
      (do
        (terminal/error! (format "failed to create change set [%s]" change-set-name))
        (when-let [anomaly (:cognitect.anomalies/message response)]
          (terminal/error! anomaly))
        (when-let [message (get-in response [:ErrorResponse :Error :Message])]
          (terminal/error! message)))
      (do
        (terminal/info! (format "Creating change set [%s]" change-set-name))
        (wait-for-change-set-to-exist! stack-name change-set-name)))))

(defn- execute-change-set! [stack-name change-set-name change-set-id]
  (let [response (aws/invoke *cloudformation-client*
                             {:op :ExecuteChangeSet
                              :request {:StackName stack-name
                                        :ChangeSetName change-set-id}})
        console-link (console-link-for-stack-events stack-name)]
    (if (:cognitect.anomalies/category response)
      (do
        (terminal/error! (format "failed to execute change set [%s]" change-set-name))
        (when-let [message (get-in response [:ErrorResponse :Error :Message])]
          (terminal/error! message)
          (terminal/link! console-link)))
      (do
        (terminal/info! (format "Executing change set [%s]" change-set-name))
        (terminal/link! console-link)
        (wait-for-change-set-execution-to-complete! stack-name change-set-id)
        (wait-for-stack-operation-to-complete! stack-name)))))

(defn ask!
  ([question yes-fn] (ask! question yes-fn #(terminal/info! "Skipping")))
  ([question yes-fn no-fn]
   (terminal/question! (str question " (y/n)"))
   (let [answer (read-line)]
     (if (= answer "y")
       (yes-fn)
       (no-fn)))))

(defn- create-stack! [{:keys [technology interactive] :as provision-params}]
  (let [template (template-for-stack technology)
        stack-name (stack-name provision-params)
        change-set-name (generate-change-set-name stack-name)
        stack-params (stack-params provision-params)
        change-set-id (create-change-set! stack-name change-set-name template stack-params)]
    (when (= "CREATE_COMPLETE" (:Status (get-change-set-status! stack-name change-set-name)))
      (if interactive
        (let [console-link (console-link-for-change-set stack-name change-set-id)]
          (terminal/link! console-link)
          (ask! "Do you want to execute the change set?"
                #(execute-change-set! stack-name change-set-name change-set-id)))
        (execute-change-set! stack-name change-set-name change-set-id)))))

(defn- update-stack! [{:keys [technology interactive] :as provision-params}]
  (let [template (template-for-stack technology)
        stack-name (stack-name provision-params)
        change-set-name (generate-change-set-name stack-name)
        stack-params (stack-params provision-params)
        change-set-id (create-change-set! stack-name change-set-name template stack-params)]
    (when (= "CREATE_COMPLETE" (:Status (get-change-set-status! stack-name change-set-name)))
      (if interactive
        (let [console-link (console-link-for-change-set stack-name change-set-id)]
          (terminal/link! console-link)
          (ask! "Do you want to execute the change set?"
                #(execute-change-set! stack-name change-set-name change-set-id)))
        (execute-change-set! stack-name change-set-name change-set-id)))))

(defn- protected? [environment]
  (let [protected-environments #{"prod" "production"}]
    (protected-environments environment)))

(defn- update-termination-protection! [{:keys [environment] :as provision-params}]
  (let [stack-name (stack-name provision-params)]
    (aws/invoke *cloudformation-client*
                {:op :UpdateTerminationProtection
                 :request {:StackName stack-name
                           :EnableTerminationProtection (protected? environment)}})))

(defn provision! [provision-params]
  (terminal/info! (format "Provisioning with: [%s]" provision-params))
  (try
    (let [stack-name (stack-name provision-params)]
      (if (stack-exists? stack-name)
        (update-stack! provision-params)
        (create-stack! provision-params))
      (update-termination-protection! provision-params))
    (catch Exception e
      (terminal/error! (ex-message e)))))

(comment
  ;; Validate requests using spec
  (aws/validate-requests *cloudformation-client* true)

  ;; What operations can a client perform?
  (aws/ops *cloudformation-client*)

  ;; Docs for an operation - appears in REPL
  (aws/doc *cloudformation-client* :CreateChangeSet)
  (aws/doc *cloudformation-client* :ExecuteChangeSet)
  (aws/doc *cloudformation-client* :UpdateTerminationProtection)

  ;; Example usage which will error due to missing UniqueId parameter
  (provision! {:environment "staging"
               :application "testing"
               :technology "s3"
               :version 1
               :interactive true})

  ;; And a working alternative
  (provision! {:environment "staging"
               :application "testing"
               :technology "s3"
               :version 1
               :unique-id (util/uuid)
               :interactive true})
  ;;
  )
