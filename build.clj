;; ---------------------------------------------------------
;; Build Script
;;
;; Build project and package for deployment
;; - `uberjar` - packaged application for deployment
;; - `clean` remove all build assets and jar files
;;
;; All functions are passed command line arguments
;; - `nil` is passed if there are no arguments
;;
;;
;; tools.build API commands
;; - `create-basis` create a project basis
;; - `copy-dir` copy Clojure source and resources into a working dir
;; - `compile-clj` compile Clojure source code to classes
;; - `delete` - remove path from file space
;; - `write-pom` - write pom.xml and pom.properties files
;; - `jar` - to jar up the working dir into a jar file
;;
;; ---------------------------------------------------------

(ns build
  (:import [java.time LocalDateTime ZoneOffset]
           [java.time.format DateTimeFormatter])
  (:require [app]
            [clojure.tools.build.api :as build-api]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

;; ---------------------------------------------------------
;; Project configuration

;; Versioning
(def major-version 0)
(def minor-version 3)
(def timestamp-formatter
  (-> "yyyyMMddHHmmss"
      DateTimeFormatter/ofPattern
      (.withZone ZoneOffset/UTC)))
(def version-string
  (format "%d.%d.%s-%s"
          major-version minor-version
          (build-api/git-count-revs nil)
          (.format timestamp-formatter (LocalDateTime/now))))

;; Project/app name
(def app 'fmjrey/chkodf) ;; build-api/write-pom expects a symbol
(def app-name "ChkODF")
(def app-home "https://github.com/fmjrey/chkodf")
(def app-cli "chkodf")
(def target-dir "target")

;; Config map that may be merged with tools.build cli options
(def project-config
  "Project configuration to support all tasks"
  {:main-namespace  'fmjrey/chkodf
   :project-basis   (build-api/create-basis)
   :class-directory "target/classes"
   :uberjar-file (str (io/file target-dir
                               (format "%s-%s.jar" app version-string)))})

(defn config
  "Display build configuration"
  [config]
  (pprint/pprint (or config project-config)))

;; End of Build configuration
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Build tasks

(defn write-app-info
  "Write app info and version to a file."
  [_]
  (app/write-app-info {:app-name app-name
                       :app-home app-home
                       :app-cli app-cli
                       :major-version major-version
                       :minor-version minor-version
                       :version-string version-string}))

(defn clean
  "Remove a directory
  - `:path '\"directory-name\"'` for a specific directory
  - `nil` (or no command line arguments) to delete `target` directory
  `target` is the default directory for build artefacts
  Checks that `.` and `/` directories are not deleted"
  [directory]
  (when (not (contains? #{"." "/"} directory))
    (build-api/delete {:path (or (:path directory) target-dir)})))


(defn uberjar
  "Create an archive containing Clojure and the build of the project
  Merge command line configuration with the default project config"
  [options]
  (let [config (merge project-config options)
        {:keys [class-directory main-namespace
                project-basis uberjar-file]} config]

    (clean target-dir)

    (write-app-info nil)

    (build-api/copy-dir {:src-dirs   ["src" "resources"]
                         :target-dir class-directory})

    (build-api/compile-clj {:basis     project-basis
                            :class-dir class-directory
                            :src-dirs  ["src"]})

    (build-api/uber {:basis     project-basis
                     :class-dir class-directory
                     :main      main-namespace
                     :uber-file uberjar-file})))

;; End of Build tasks
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Deployment tasks
;; - optional deployment tasks for services or libraries

(defn deploy-bin
  "Build a uberjar and deploy it to the given path and permission.
  Expected keys and defaults values are
  `{:uberjar-file uberjar-file`
  ` :dest-dir \"~/.local/bin\"`
  ` :filename app-cli`
  ` :chmod-permissions \"u=rwx,g=rx,o=rx\"}`
  Merge command line configuration with the default project config."
  [options]
  (let [config (merge project-config options)
        {:keys [uberjar-file dest-dir filename chmod-permissions]
         :or {dest-dir (str (io/file (System/getProperty "user.home")
                                     ".local" "bin"))
              filename app-cli
              chmod-permissions "u=rwx,g=rx,o=rx"}} config
        dest-filepath (str (io/file dest-dir filename))]
    (uberjar options)
    (build-api/copy-file {:src uberjar-file :target dest-filepath})
    (build-api/process {:command-args ["chmod" chmod-permissions dest-filepath]})
    (println "Deployed executable to" dest-filepath)))

;; End of Deployment tasks
;; ---------------------------------------------------------
