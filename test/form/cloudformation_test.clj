(ns form.cloudformation-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [form.cloudformation :as sut]
            [form.terminal :as terminal]))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(def running-in-repl? (bound? #'*1))

(defmacro with-localstack
  {:added "1.0"}
  [& body]
  `(let [client# (aws/client {:api :cloudformation
                              :region sut/aws-region
                              :endpoint-override {:protocol :http
                                                  :hostname (if running-in-repl? "localhost" "localstack")
                                                  :port 4566}
                              :credentials-provider (credentials/basic-credentials-provider
                                                     {:access-key-id "placeholder"
                                                      :secret-access-key "placeholder"})})]
     (binding [sut/*cloudformation-client* client#]
       ~@body)))

(deftest form-provision
  (testing "creating a cloudformation stack"
    (testing "when the cloudformation template is well formed"
      (with-localstack
        (let [captured-output (with-out-str (sut/provision! {:organisation "form"
                                                             :environment (uuid)
                                                             :application "testing"
                                                             :technology "s3"
                                                             :version 1
                                                             :interactive false}))]
          (testing "is successsful"
            (is (str/includes?
                 captured-output
                 (terminal/ansi-coded-string "Success" :green "Stack status [CREATE_COMPLETE]")))))))))
