(ns form.codec
  (:require [clojure.string :as str]))

;; Taken verbatim from https://github.com/ring-clojure/ring to avoid having to bring
;; in entire dependency
(defn- double-escape [^String x]
  (.replace (.replace x "\\" "\\\\") "$" "\\$"))

(defn percent-encode
  "Percent-encode every character in the given string using either the specified
  encoding, or UTF-8 by default."
  ([unencoded]
   (percent-encode unencoded "UTF-8"))
  ([^String unencoded ^String encoding]
   (->> (.getBytes unencoded encoding)
        (map (partial format "%%%02X"))
        (str/join))))

(defn url-encode
  "Returns the url-encoded version of the given string, using either a specified
  encoding or UTF-8 by default."
  ([unencoded]
   (url-encode unencoded "UTF-8"))
  ([unencoded encoding]
   (str/replace
    unencoded
    #"[^A-Za-z0-9_~.+-]+"
    #(double-escape (percent-encode % encoding)))))
