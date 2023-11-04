(ns form.cloudformation-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [form.cloudformation :as sut]
            [form.terminal :as terminal]
            [form.util :as util]))

(def running-in-repl? (bound? #'*1))

(defmacro with-localstack
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

(deftest form-provision-test
  (testing "provisioning template-s3.yml"
    (testing "when the cloudformation template is well formed"
      (testing "and a complete set of provisioning parameters are supplied"
        (with-localstack
          (let [captured-output (with-out-str (sut/provision! {:environment "integration"
                                                               :application "testing"
                                                               :technology "s3"
                                                               :version 1
                                                               :unique-id (util/uuid)
                                                               :interactive false}))
                first-run-success-indicator
                (terminal/ansi-coded-string "Success" :green "Stack status [CREATE_COMPLETE]")
                subsequent-run-success-indicator
                (terminal/ansi-coded-string "Success" :green "Stack status [UPDATE_COMPLETE]")]
            (testing "is successsful"
              (is (or (str/includes? captured-output first-run-success-indicator)
                      (str/includes? captured-output subsequent-run-success-indicator))))))))))
