;; ---------------------------------------------------------
;; App info file handling
;;
;; Functions to generate, write, and read the app.edn file
;; which contains a map of application information such assoc
;; name and version.
;;
;; ---------------------------------------------------------

(ns app
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def app-filename "app.edn")
(def app-file (io/file "resources" app-filename))
(when-not (-> app-file .exists)
  (println (str "WARNING: " app-file " not found")))

(defn write-app-info
  "Write content to the app file."
  [content]
  (io/make-parents app-file)
  (spit app-file (pr-str content)))

(defn gen-app-info-with [s]
  {:app-name s
   :app-home s
   :app-cli s
   :major-version s
   :minor-version s
   :version-string s})

(def app-info
  (try
    (-> app-file slurp edn/read-string)
    (catch Throwable _
      (gen-app-info-with (str "Failed to read " app-file)))))
(def app-name (:app-name app-info))
(def app-home (:app-home app-info))
(def app-cli (:app-cli app-info))
(def major-version (:major-version app-info))
(def minor-version (:minor-version app-info))
(def version-string (:version-string app-info))
