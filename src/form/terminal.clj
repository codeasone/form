(ns form.terminal
  (:import [java.time LocalTime]
           [java.time.format DateTimeFormatter])
  (:require [clojure.string :as str]))

(def ansi-color-codes
  {:black "\033[30m"
   :red "\033[31m"
   :green "\033[32m"
   :yellow "\033[33m"
   :blue "\033[34m"
   :magenta "\033[35m"
   :cyan "\033[36m"
   :white "\033[37m"})

(def ansi-bold "\033[1m")
(def ansi-reset "\033[0m")

(defn- ansi-bold-brackets [input]
  (clojure.string/replace input #"\[(.*?)\]" (fn [[_ content]]
                                               (str "[" ansi-bold content ansi-reset "]"))))

(defn ansi-coded-string [prefix-text prefix-color text]
  (str ansi-bold (ansi-color-codes prefix-color) prefix-text ": " ansi-reset
       (ansi-bold-brackets text)))

;; Any sub-strings within [...] will be bolded
(defn- timestamped-message! [prefix-text prefix-color text]
  (let [timestamp-str (str ansi-bold (ansi-color-codes :white)
                           (.format (DateTimeFormatter/ofPattern "HH:mm:ss") (LocalTime/now))
                           ansi-reset " ")]
    (println (str timestamp-str (ansi-coded-string prefix-text prefix-color text)))))

(def error! (partial timestamped-message! "Error" :red))
(def warning! (partial timestamped-message! "Warning" :yellow))
(def success! (partial timestamped-message! "Success" :green))
(def info! (partial timestamped-message! "Info" :blue))
(def link! (partial timestamped-message! "Link" :magenta))
(def debug! (partial timestamped-message! "Debug" :cyan))
(def question! (partial timestamped-message! "Question" :blue))

(comment
  (do
    (error! "This [resource] caused an error")
    (info! "Computing")
    (warning! "Unusual")
    (success! "All done!")
    (link! "https://www.kagi.com")
    (debug! "Here!")
    (question! "Are you ready?"))
  ;;
  )
