(ns form.core
  (:require [clojure.set :as set]
            [form.cloudformation :as cf]))

(defn preprocess-args
  [args]
  (let [required-args #{:environment :application :technology}
        passed-args (set (keys args))]
    (if (set/subset? required-args passed-args)
      (cond-> args
        (nil? (:version args))
        (assoc :version 1)
        (or (nil? (:interactive args)) (true? (:interactive args)))
        (assoc :interactive true))
      (let [missing-arguments (set/difference required-args passed-args)]
        (throw (ex-info (format "Missing required command-line arguments [%s]" missing-arguments)
                        args))))))

(defn provision
  "Exec function to cloudform infrastructure.
  `:environment` -- e.g. staging or prod (required)
  `:application` -- name of the application or service whose resources are being provisioned (required),
  `:technology` -- technology name that groups the stack resources e.g. s3 or rds (required),
  `:version` -- major version number (defaults to 1)"
  [args]
  (let [preprocessed-args (preprocess-args args)]
    (cf/provision! preprocessed-args)))
