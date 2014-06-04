(ns clj-odfdom.core
  (:import [org.odftoolkit.odfdom.doc OdfTextDocument]))

;; Based on http://stackoverflow.com/a/13107088/483566
(defn -main [& args]
  (let [document (OdfTextDocument/loadDocument "test.odt")
        texts    (.getTextContent (.getContentRoot document))]
    (println texts)))
